package org.vault.exporter;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Phase 1: checkpoint every completed page into Keystore-derived encrypted private staging.
 * Phase 2: finalize verified encrypted .tva from staging; interrupted finalization can restart.
 * All cloud files are downloaded on demand using downloadFile(synchronous=true, limit=0).
 */
public final class ExportEngine {
  public interface Progress { void show(String message); }
  /** Injectable read-only Telegram transport. Production uses TdGateway; a local
   * test fixture supplies the same request/response shapes with NO network. */
  public interface Transport { JSONObject await(JSONObject request, long timeoutSeconds) throws Exception; }
  public static final class Summary {
    public final int messages, media, missing;
    public final boolean historyFinished;
    public Summary(int m,int f,int e,boolean done){messages=m;media=f;missing=e;historyFinished=done;}
  }
  /** Privacy failure: the plaintext TDLib cache may still exist. Stop exporting. */
  public static final class CachePrivacyException extends IOException {
    public CachePrivacyException(String message) { super(message); }
    public CachePrivacyException(String message, Throwable cause) { super(message,cause); }
  }
  /** Stop BEFORE a huge cloud download consumes all app-private storage. */
  public static final class StorageCapacityException extends IOException {
    public final long requiredBytes,availableBytes;
    StorageCapacityException(long required,long available){
      super("Недостаточно памяти телефона для безопасного скачивания файла");
      requiredBytes=required;availableBytes=available;
    }
    public String userMessage(){
      long mib=1024L*1024L;
      return "Недостаточно свободной памяти. Требуется примерно "
        +((requiredBytes+mib-1)/mib)+" МиБ, доступно "+(availableBytes/mib)
        +" МиБ. Освободи место или выбери меньший чат. Прогресс экспорта сохранён.";
    }
  }
  private void checkCapacityBeforeDownload(JSONObject tdFile)throws StorageCapacityException {
    final long mib=1024L*1024L, reserve=64L*mib;
    long declared=Math.max(0L,tdFile.optLong("size",0L));
    declared=Math.max(declared,Math.max(0L,tdFile.optLong("expected_size",0L)));
    long available=new File(app.getFilesDir(),"tdlib/files").getUsableSpace();
    if(available<=0)available=app.getFilesDir().getUsableSpace();
    long doubled=declared> (Long.MAX_VALUE-reserve)/2 ? Long.MAX_VALUE : declared*2+reserve;
    long required=Math.max(reserve,doubled);
    if(available<required)throw new StorageCapacityException(required,available);
  }
  private static final int FILE_RETRIES=3;
  private final Context app;
  private final Transport td;
  private final Progress progress;
  private final String permittedRoot;
  public ExportEngine(Context ctx,TdGateway td,Progress progress) throws Exception {
    this(ctx,td::await,progress);
  }
  public ExportEngine(Context ctx,Transport transport,Progress progress) throws Exception {
    app=ctx.getApplicationContext();this.td=transport;this.progress=progress;
    permittedRoot=new File(app.getFilesDir(),"tdlib/files").getCanonicalPath()+File.separator;
  }
  private static JSONObject req(String name,String key,Object value) throws JSONException {return TdGateway.cmd(name).put(key,value);}
  private static JSONObject object(JSONObject root,String key){return root==null?null:root.optJSONObject(key);}
  private static JSONObject deepest(JSONObject root,String... keys){
    JSONObject obj=root; for(String k:keys){obj=object(obj,k);if(obj==null)return null;}return obj;
  }
  private static JSONObject largestPhoto(JSONObject message){
    JSONObject photo=deepest(message,"content","photo"); if(photo==null)return null;
    JSONArray sizes=photo.optJSONArray("sizes");if(sizes==null)return null;
    JSONObject best=null;long area=-1;
    for(int i=0;i<sizes.length();i++){
      JSONObject item=sizes.optJSONObject(i);if(item==null)continue;
      JSONObject f=item.optJSONObject("photo");if(f==null || f.optInt("id")<=0)continue;
      long a=(long)item.optInt("width")*item.optInt("height");
      if(a>=area){best=f;area=a;}
    }
    return best;
  }
  /** Select the main media only. Thumbnail files do not replace full-resolution files. */
  private static JSONObject mainFile(JSONObject message){
    JSONObject content=message.optJSONObject("content");if(content==null)return null;
    switch(content.optString("@type")) {
      case "messagePhoto":return largestPhoto(message);
      case "messageVideo":return deepest(message,"content","video","video");
      case "messageVideoNote":return deepest(message,"content","video_note","video");
      case "messageVoiceNote":return deepest(message,"content","voice_note","voice");
      case "messageAudio":return deepest(message,"content","audio","audio");
      case "messageDocument":return deepest(message,"content","document","document");
      case "messageAnimation":return deepest(message,"content","animation","animation");
      case "messageSticker":return deepest(message,"content","sticker","sticker");
      default:return null;
    }
  }
  /** Content variants audited as having no standalone TDLib File to download.
   * Other variants must be explicitly audited before the exporter may call an
   * archive complete. This conservative list intentionally excludes messageText
   * with a rich link preview: the preview can carry an embedded photo/video. */
  private static boolean isVerifiedNonMediaContent(String contentType){
    switch(contentType){
      case "messageContact":
      case "messageLocation":
      case "messageVenue":
      case "messagePoll":
      case "messageDice":
      case "messageCall":
      case "messageChatChangeTitle":
      case "messageChatDeletePhoto":
      case "messageChatAddMembers":
      case "messageChatDeleteMember":
      case "messageChatJoinByLink":
      case "messageChatJoinByRequest":
      case "messageChatSetMessageAutoDeleteTime":
      case "messagePinMessage":
      case "messageScreenshotTaken":
        return true;
      default: return false;
    }
  }

  private static String type(JSONObject msg){
    JSONObject content=msg.optJSONObject("content");String name=content==null?"":content.optString("@type");
    switch(name){
      case "messagePhoto":return "photo";
      case "messageVideo":return "video";
      case "messageVideoNote":return "round_video";
      case "messageVoiceNote":return "voice";
      case "messageAudio":return "audio";
      case "messageDocument":return "document";
      case "messageAnimation":return "animation";
      case "messageSticker":return "sticker";
      default:return "other";
    }
  }
  private static String extension(String category,JSONObject message){
    JSONObject content=message.optJSONObject("content");
    JSONObject doc=object(content,"document");
    String original=doc==null?"":doc.optString("file_name");
    int dot=original.lastIndexOf('.');
    if(dot>0){String candidate=original.substring(dot).toLowerCase(Locale.ROOT);
      if(candidate.matches("\\.[a-z0-9]{1,8}"))return candidate;}
    if("audio".equals(category)) {
      JSONObject track=object(content,"audio");
      String musicName=track==null?"":track.optString("file_name","");
      int extAt=musicName.lastIndexOf('.');
      if(extAt>0){
        String ext=musicName.substring(extAt).toLowerCase(Locale.ROOT);
        if(ext.matches("\\.[a-z0-9]{1,8}"))return ext;
      }
      String mediaType=track==null?"":track.optString("mime_type","");
      if("audio/ogg".equals(mediaType))return ".ogg";
      if("audio/wav".equals(mediaType)||"audio/x-wav".equals(mediaType))return ".wav";
      if("audio/flac".equals(mediaType))return ".flac";
      if("audio/mp4".equals(mediaType))return ".m4a";
      return ".mp3";
    }
    switch(category){
      case "photo":return ".jpg";
      case "round_video":case "video":case "animation":return ".mp4";
      case "voice":return ".ogg";
      case "sticker": {
        JSONObject sticker=deepest(message,"content","sticker","format");
        String stickerType=sticker==null?"stickerFormatWebp":sticker.optString("@type","");
        if("stickerFormatWebp".equals(stickerType))return ".webp";
        if("stickerFormatTgs".equals(stickerType))return ".tgs";
        if("stickerFormatWebm".equals(stickerType))return ".webm";
        throw new IllegalArgumentException("Неизвестный формат стикера: "+stickerType);
      }
      default:return ".bin";
    }
  }
  private static String mime(JSONObject message,String category){
    JSONObject content=message.optJSONObject("content"); JSONObject metadata=object(content, category.equals("document")?"document":category.equals("voice")?"voice_note":category.equals("round_video")?"video_note":category);
    String val=metadata==null?"":metadata.optString("mime_type","");
    if(!val.isEmpty())return val;
    switch(category){case "photo":return "image/jpeg";case "round_video":case "video":return "video/mp4";case "voice":return "audio/ogg";default:return "application/octet-stream";}
  }
  private static String fileName(JSONObject message){
    JSONObject doc=deepest(message,"content","document");return doc==null?"":doc.optString("file_name","");
  }
  /** Only read/download calls may be repeated; never retries login, logout or modifications. */
  private JSONObject safeRead(JSONObject request, long timeoutSeconds) throws Exception {
    for (int attempt = 0; attempt < 2; attempt++) {
      try { return td.await(request, timeoutSeconds); }
      catch (TelegramRateLimit.Delay limit) {
        // Wait exactly as Telegram requests, plus a small margin; never bypass or rotate identities.
        if (limit.waitSeconds > 120 || attempt != 0)
          throw new IOException("Telegram просит подождать " + limit.waitSeconds + " сек. Экспорт приостановлен, прогресс сохранён.", limit);
        progress.show("Telegram ограничил скорость. Ожидаем " + limit.waitSeconds + " сек. без новых запросов.");
        Thread.sleep((limit.waitSeconds + 2L) * 1000L);
      }
    }
    throw new IOException("Не удалось получить ответ после ограничения Telegram");
  }

  /** On any incomplete download, cancel first, delete local TDLib cache and
   * confirm that there is no active writer and no file left behind.
   * Never retry when plaintext cleanup cannot be established. */
  private void purgeFailedDownload(int fileId, File observedSource) throws CachePrivacyException {
    try {
      JSONObject cancelled=td.await(req("cancelDownloadFile","file_id",fileId)
        .put("only_if_pending",false),30);
      if(!"ok".equals(cancelled.optString("@type")))
        throw new IOException("TDLib did not confirm cancellation");
      JSONObject deleted=td.await(req("deleteFile","file_id",fileId),30);
      if(!"ok".equals(deleted.optString("@type")))
        throw new IOException("TDLib did not confirm deletion");
      JSONObject check=td.await(req("getFile","file_id",fileId),30);
      if(!check.has("id") || check.optInt("id",-1)!=fileId)
        throw new IOException("TDLib returned a different file during cleanup check");
      JSONObject local=check.optJSONObject("local");
      if(local==null || local.optBoolean("is_downloading_active",false))
        throw new IOException("TDLib may still be writing plaintext");
      String reported=local.optString("path","");
      if(!reported.isEmpty()) {
        File current=new File(reported).getCanonicalFile();
        if(!current.getPath().startsWith(permittedRoot))
          throw new IOException("Cleanup path is outside private TDLib cache");
        if(current.isFile())throw new IOException("Plaintext cache remains after deletion");
      }
      if(observedSource!=null && observedSource.isFile())
        throw new IOException("Observed plaintext cache remains after deletion");
    } catch(Exception e) {
      throw new CachePrivacyException(
        "Не удалось подтвердить очистку временного файла после ошибки загрузки: "+fileId,e);
    }
  }

  /** TDLib returns originals to unencrypted, app-private storage. The export
   * may proceed only when both TDLib and the local filesystem confirm removal. */
  private void purgeDownloadedSource(int fileId,File source) throws CachePrivacyException {
    try {
      JSONObject result=td.await(req("deleteFile","file_id",fileId),30);
      if(!"ok".equals(result.optString("@type")))
        throw new IOException("TDLib did not acknowledge deleteFile");
      if(source.isFile())throw new IOException("Plaintext source still exists");
    } catch(Exception e) {
      throw new CachePrivacyException("Незашифрованная копия медиа не удалена из кэша: "+fileId,e);
    }
  }

  private File cloudDownload(int id) throws Exception {
    Exception last=null;
    for(int attempt=1;attempt<=FILE_RETRIES;attempt++) {
      File observedSource=null;
      try {
        JSONObject result=safeRead(req("downloadFile","file_id",id).put("priority",16)
          .put("offset",0).put("limit",0).put("synchronous",true),3600);
        JSONObject local=result.optJSONObject("local");
        if(local!=null&&!local.optString("path","").isEmpty())
          observedSource=new File(local.optString("path")).getCanonicalFile();
        if(!result.has("id") || result.optInt("id",-1)!=id)
          throw new SecurityException("TDLib вернула неверный file_id для вложения "+id);
        if(local==null || !local.optBoolean("is_downloading_completed",false))
          throw new IOException("TDLib не завершила загрузку файла "+id);
        if(observedSource==null || !observedSource.isFile() ||
            !observedSource.getPath().startsWith(permittedRoot))
          throw new SecurityException("Медиа вне закрытого каталога приложения");
        long declared=result.optLong("size",0);
        if(declared<0 || (declared>0 && observedSource.length()!=declared))
          throw new IOException("Загруженный файл имеет неверный размер: "+id);
        return observedSource;
      } catch(Exception failure) {
        try { purgeFailedDownload(id,observedSource); }
        catch(CachePrivacyException cleanup) {
          cleanup.addSuppressed(failure);
          throw cleanup;
        }
        if(failure instanceof SecurityException || failure instanceof InterruptedException ||
            failure instanceof TelegramRateLimit.Delay ||
            (failure instanceof IOException && failure.getCause() instanceof TelegramRateLimit.Delay))
          throw failure;
        last=failure;
        if(attempt<FILE_RETRIES) {
          progress.show("Повтор загрузки файла "+id+" ("+(attempt+1)+"/"+FILE_RETRIES+")");
          Thread.sleep(1000L*attempt);
        }
      }
    }
    throw new IOException("Не удалось безопасно скачать файл "+id+"; можно продолжить позже",last);
  }

  private static final class Attachment {
    final JSONObject file;
    final String category,mime,originalName,role;
    final int ordinal;
    Attachment(JSONObject f,String c,String mimeType,String name,String role,int ordinal){
      this.file=f;this.category=c;this.mime=mimeType;this.originalName=name;
      this.role=role;this.ordinal=ordinal;
    }
    int id(){return file.optInt("id",0);}
  }
  /** Avoid thumbnails becoming the original photo; one original per attachment. */
  private static JSONObject originalPhoto(JSONObject photo){
    JSONArray sizes=photo==null?null:photo.optJSONArray("sizes");
    if(sizes==null)return null;
    JSONObject chosen=null;long max=-1;
    for(int i=0;i<sizes.length();i++){
      JSONObject item=sizes.optJSONObject(i);
      if(item==null)continue;
      JSONObject file=item.optJSONObject("photo");
      if(file==null||file.optInt("id",0)<=0)continue;
      long w=Math.max(0,item.optLong("width")),h=Math.max(0,item.optLong("height"));
      long area=w*h;
      if(area>=max){max=area;chosen=file;}
    }
    return chosen;
  }
  private static void attach(List<Attachment> out,JSONObject file,String category,String mime,
      String name,String role,int ordinal,long messageId)throws IOException {
    if(file==null||file.optInt("id",0)<=0)
      throw new IOException("В сообщении "+messageId+" нет исходного файла "+role);
    for(Attachment a:out)if(a.id()==file.optInt("id",0))
      throw new IOException("Повторный идентификатор вложения: "+messageId);
    out.add(new Attachment(file,category,mime,name,role,ordinal));
  }
  private static List<Attachment> attachments(JSONObject msg)throws IOException {
    long mid=msg.optLong("id");
    JSONObject c=msg.optJSONObject("content");
    String kind=c==null?"":c.optString("@type","");
    if(msg.has("can_be_saved")&&!msg.optBoolean("can_be_saved",true))
      throw new SecurityException("Сохранение сообщения запрещено Telegram: "+mid);
    if(c!=null&&c.optBoolean("is_secret",false))
      throw new SecurityException("Нельзя экспортировать самоисчезающее медиа: "+mid);
    ArrayList<Attachment> result=new ArrayList<>();
    String category=type(msg);
    if(!"other".equals(category)){
      attach(result,mainFile(msg),category,mime(msg,category),fileName(msg),"main",0,mid);
      return result;
    }
    if("messagePaidMedia".equals(kind)){
      JSONArray media=c.optJSONArray("media");
      if(media==null||media.length()==0)
        throw new IOException("Пустой список платных медиа "+mid);
      for(int i=0;i<media.length();i++){
        JSONObject item=media.optJSONObject(i);
        String mediaKind=item==null?"":item.optString("@type","");
        if("paidMediaPhoto".equals(mediaKind))
          attach(result,originalPhoto(item.optJSONObject("photo")),"photo","image/jpeg",
            "","paid_photo",i,mid);
        else if("paidMediaVideo".equals(mediaKind)){
          JSONObject v=item.optJSONObject("video");
          attach(result,object(v,"video"),"video",
            v==null?"video/mp4":v.optString("mime_type","video/mp4"),
            "","paid_video",i,mid);
        }else throw new IOException("Медиа скрыто или тип не поддержан: "+mediaKind+" в "+mid);
      }
      return result;
    }
    if("messageChatChangePhoto".equals(kind)){
      JSONObject p=c.optJSONObject("photo");
      attach(result,originalPhoto(p),"photo","image/jpeg","","chat_photo",0,mid);
      JSONObject animation=p==null?null:p.optJSONObject("animation");
      if(animation!=null){
        JSONObject animatedFile=animation.optJSONObject("file");
        if(animatedFile==null)animatedFile=animation.optJSONObject("animation");
        attach(result,animatedFile,"video","video/mp4","","chat_photo_animation",1,mid);
      }
      if(p!=null&&p.optJSONObject("sticker")!=null)
        throw new IOException("Анимированное фото со стикером пока не поддержано: "+mid);
      return result;
    }
    if("messageText".equals(kind)){
      JSONObject preview=c.optJSONObject("link_preview");
      if(preview==null)return result;
      // TDLib link-preview variants may encode the discriminator as @type.
      // A missing/unknown discriminator must never silently hide rich media.
      String previewType=preview.optString("type","");
      if(previewType.isEmpty())previewType=preview.optString("@type","");
      JSONObject p=preview.optJSONObject("photo"),v=preview.optJSONObject("video");
      JSONObject doc=preview.optJSONObject("document"),anim=preview.optJSONObject("animation");
      JSONObject audio=preview.optJSONObject("audio");
      JSONArray album=preview.optJSONArray("album");
      if(album!=null&&!preview.optBoolean("has_large_media",true)){
        // A thumbnail-only album is not a safely restorable original.
        throw new IOException("Предпросмотр без полных исходных файлов: "+mid);
      }
      if(album!=null){
        for(int i=0;i<album.length();i++){
          JSONObject element=album.optJSONObject(i);
          String itemType=element==null?"":element.optString("@type","");
          if("linkPreviewAlbumMediaPhoto".equals(itemType))
            attach(result,originalPhoto(element.optJSONObject("photo")),"photo","image/jpeg",
              "","preview_photo",i,mid);
          else if("linkPreviewAlbumMediaVideo".equals(itemType)){
            JSONObject item=element.optJSONObject("video");
            attach(result,object(item,"video"),"video","video/mp4",
              "","preview_video",i,mid);
          }else throw new IOException("Неподдержанный элемент альбома ссылок: "+itemType);
        }
        return result;
      }
      if(p!=null)attach(result,originalPhoto(p),"photo","image/jpeg","","preview_photo",0,mid);
      if(v!=null)attach(result,object(v,"video"),"video","video/mp4","","preview_video",1,mid);
      if(doc!=null)attach(result,object(doc,"document"),"document",
        doc.optString("mime_type","application/octet-stream"),doc.optString("file_name",""),
        "preview_document",2,mid);
      if(anim!=null)attach(result,object(anim,"animation"),"animation",
        anim.optString("mime_type","video/mp4"),"","preview_animation",3,mid);
      if(audio!=null)attach(result,object(audio,"audio"),"audio",
        audio.optString("mime_type","audio/mpeg"),"","preview_audio",4,mid);
      if(result.isEmpty() && !previewType.isEmpty()
          && !("linkPreviewTypeArticle".equals(previewType)||"linkPreviewTypeWebApp".equals(previewType)))
        throw new IOException("Непроверенный предпросмотр ссылки "+previewType+" в "+mid);
      return result;
    }
    if(!isVerifiedNonMediaContent(kind))
      throw new IOException("Тип сообщения "+kind+" ещё не проверен на медиа: "+mid);
    return result;
  }
  private static String attachmentExtension(Attachment a,JSONObject msg){
    if("photo".equals(a.category))return ".jpg";
    if("preview_video".equals(a.role)||"paid_video".equals(a.role)||
       "chat_photo_animation".equals(a.role))return ".mp4";
    if("preview_animation".equals(a.role))return ".mp4";
    if(a.originalName!=null){
      int dot=a.originalName.lastIndexOf('.');
      if(dot>0){
        String ext=a.originalName.substring(dot).toLowerCase(Locale.ROOT);
        if(ext.matches("\\.[a-z0-9]{1,8}"))return ext;
      }
    }
    return extension(a.category,msg);
  }
  private static byte[] bytes(StringBuilder builder){return builder.toString().getBytes(StandardCharsets.UTF_8);}
  public Summary export(long chatId,OutputStream output,char[] password) throws Exception {
    JSONObject chat=safeRead(req("getChat","chat_id",chatId),30);
    if (!chat.has("id") || chat.optLong("id") != chatId)
      throw new IOException("TDLib вернула другой чат при проверке выбранной переписки");
    if(chat.optBoolean("has_protected_content",false))throw new SecurityException("Сохранение содержимого этого чата ограничено");
    if("chatTypeSecret".equals(object(chat,"type")==null?"":object(chat,"type").optString("@type")))
      throw new SecurityException("Облачный API не экспортирует секретные чаты");
    byte[] sessionSecret=new SessionSecrets(app).getOrCreate();
    try(ResumeStore stage=new ResumeStore(new File(app.getFilesDir(),"export-progress"),chatId,sessionSecret)){
      Arrays.fill(sessionSecret,(byte)0);
      ResumeStore.State s=stage.state();
      if(s.pages>0)progress.show("Продолжаем: уже сохранено сообщений: "+s.messages+", файлов: "+s.media);
      while(!s.historyFinished) {
        // TDLib requires the last received ID, not id-1 (message IDs can be sparse).
        long nextFrom=s.cursor;
        JSONObject history=safeRead(req("getChatHistory","chat_id",chatId)
          .put("from_message_id",nextFrom).put("offset",0).put("limit",100).put("only_local",false),120);
        JSONArray messages=history.optJSONArray("messages");
        if(messages==null)throw new IOException("TDLib вернула историю в неизвестном формате");
        ArrayList<JSONObject> page=new ArrayList<>();long oldest=Long.MAX_VALUE;
        long[] rawIds=new long[messages.length()];
        for(int i=0;i<messages.length();i++){
          JSONObject msg=messages.optJSONObject(i);
          if(msg==null)throw new IOException("TDLib вернула неполное сообщение");
          if (!msg.has("chat_id") || msg.optLong("chat_id") != chatId)
            throw new IOException("TDLib вернула сообщение другого чата: экспорт остановлен без потери прогресса");
          rawIds[i]=msg.optLong("id");
        }
        HistoryCursor.Selection selection=HistoryCursor.select(s.cursor,rawIds);
        for(int i:selection.indices)page.add(messages.getJSONObject(i));
        if(!page.isEmpty())oldest=selection.nextCursor;
        if(messages.length()==0){
          // A second network-enabled query is required before declaring history complete.
          // Never equate a page of already-seen IDs with the end of Telegram history.
          JSONObject confirmation=safeRead(req("getChatHistory","chat_id",chatId)
            .put("from_message_id",nextFrom).put("offset",0).put("limit",100)
            .put("only_local",false),120);
          JSONArray check=confirmation.optJSONArray("messages");
          if(check==null || check.length()!=0)
            throw new IOException("История не подтверждена: Telegram дал разные ответы. Продолжи экспорт позднее.");
          s=new ResumeStore.State(chatId,s.cursor,s.pages,s.messages,s.media,true);
          stage.checkpoint(s);
          break;
        }
        if(selection.stalled) {
          // Retry a strict lower-bound query instead of incorrectly declaring EOF.
          if(s.cursor<=1)throw new IOException("Невозможно подтвердить конец истории: некорректный курсор");
          long probe=s.cursor-1;
          JSONObject lower=safeRead(req("getChatHistory","chat_id",chatId)
              .put("from_message_id",probe).put("offset",0).put("limit",100)
              .put("only_local",false),120);
          JSONArray candidates=lower.optJSONArray("messages");
          if(candidates==null)throw new IOException("Неверный ответ TDLib при проверке нижней границы");
          if(candidates.length()==0) {
            JSONObject check=safeRead(req("getChatHistory","chat_id",chatId)
                .put("from_message_id",probe).put("offset",0).put("limit",100)
                .put("only_local",false),120);
            JSONArray second=check.optJSONArray("messages");
            if(second==null||second.length()!=0)
              throw new IOException("Конец истории не подтверждён повторным запросом");
            s=new ResumeStore.State(chatId,s.cursor,s.pages,s.messages,s.media,true);
            stage.checkpoint(s);
            break;
          }
          long[] candidateIds=new long[candidates.length()];
          for(int i=0;i<candidates.length();i++){
            JSONObject candidate=candidates.optJSONObject(i);
            if(candidate==null)throw new IOException("Недопустимый элемент истории");
            if (!candidate.has("chat_id") || candidate.optLong("chat_id") != chatId)
              throw new IOException("TDLib вернула сообщение другого чата при восстановлении курсора");
            candidateIds[i]=candidate.optLong("id");
          }
          HistoryCursor.Selection lowerSelection=HistoryCursor.select(s.cursor,candidateIds);
          if(lowerSelection.indices.length==0)
            throw new IOException("История не продвигается: TDLib возвращает только сохранённые сообщения");
          for(int i:lowerSelection.indices)page.add(candidates.getJSONObject(i));
          oldest=lowerSelection.nextCursor;
        }
        StringBuilder line=new StringBuilder(),index=new StringBuilder();
        int downloadedOnPage=0;
        for(JSONObject msg:page) {
          long msgId=msg.getLong("id");
          // Validate before recording this message as a completed checkpoint.
          List<Attachment> attached=attachments(msg);
          line.append(msg.toString()).append('\n');
          for(Attachment attachment:attached){
            int fileId=attachment.id();
            ResumeStore.Media saved;
            if(stage.hasMedia(msgId,fileId)){
              saved=stage.mediaFingerprint(msgId,fileId);
            }else{
              checkCapacityBeforeDownload(attachment.file);
            File source=cloudDownload(fileId);
              Exception saveFailure=null;
              try{saved=stage.saveMedia(msgId,fileId,source);}
              catch(Exception e){saveFailure=e;throw e;}
              finally{
                try{purgeDownloadedSource(fileId,source);}
                catch(CachePrivacyException cleanup){
                  try{stage.dropMedia(msgId,fileId);}
                  catch(Exception eraseFailure){cleanup.addSuppressed(eraseFailure);}
                  if(saveFailure!=null)cleanup.addSuppressed(saveFailure);
                  throw cleanup;
                }
              }
            }
            String target="media/"+msgId+"/"+fileId+attachmentExtension(attachment,msg);
            index.append(new JSONObject()
              .put("message_id",msgId).put("file_id",fileId)
              .put("category",attachment.category).put("mime_type",attachment.mime)
              .put("original_name",attachment.originalName).put("role",attachment.role)
              .put("position",attachment.ordinal).put("path",target)
              .put("sha256",saved.sha256).put("bytes",saved.bytes)
              .toString()).append('\n');
            downloadedOnPage++;
          }
        }
        int next=s.pages+1;
        stage.putPage(next,bytes(line),bytes(index));
        s=new ResumeStore.State(chatId,oldest,next,s.messages+page.size(),s.media+downloadedOnPage,false);
        // Checkpoint only after EVERY message and attached full-resolution file on page is stored.
        stage.checkpoint(s);
        progress.show("Сохранено: "+s.messages+" сообщений · "+s.media+" медиа. Можно продолжить после перерыва.");
      }
      // No downloads required during finalization; partial final .tva may be recreated from staging.
      progress.show("Все доступные данные получены. Собираем защищённый архив…");
      int files=0;
      LinkedHashSet<Long> users=new LinkedHashSet<>(), senderChats=new LinkedHashSet<>();
      try(ArchiveWriter archive=new ArchiveWriter(output,password)){
        for(int page=1;page<=s.pages;page++) {
          if(!stage.hasPage(page))throw new IOException("Контрольная точка повреждена: страница "+page);
          String pageName=String.format(Locale.ROOT,"messages/page-%06d.jsonl",page);
          // Extract sender identities as metadata without changing the raw TDLib messages.
          try(BufferedReader messageReader=new BufferedReader(new InputStreamReader(stage.openPage(page),StandardCharsets.UTF_8))) {
            String msgLine;
            while((msgLine=messageReader.readLine())!=null) {
              if(msgLine.isEmpty())continue;
              JSONObject sender=new JSONObject(msgLine).optJSONObject("sender_id");
              if(sender==null)continue;
              String ty=sender.optString("@type");
              if("messageSenderUser".equals(ty))users.add(sender.optLong("user_id"));
              if("messageSenderChat".equals(ty))senderChats.add(sender.optLong("chat_id"));
            }
          }
          archive.writeStream(pageName,stage.openPage(page));
          String indexName=String.format(Locale.ROOT,"media-index/page-%06d.jsonl",page);
          // Read one page of encrypted media references and emit authenticated staging files.
          // Avoid materializing the entire per-page media index in RAM.
          // Streaming a verified encrypted index twice costs I/O but keeps
          // memory bounded even for unusually large multi-attachment pages.
          archive.writeStream(indexName,stage.openIndex(page));
          try(BufferedReader reader=new BufferedReader(new InputStreamReader(
              stage.openIndex(page),StandardCharsets.UTF_8))){
            String row;
            while((row=reader.readLine())!=null){if(row.isEmpty())continue;
              JSONObject media=new JSONObject(row);
              String name=media.getString("path");
              String hash=archive.writeMediaStream(name,stage.openMedia(media.getLong("message_id"),media.getInt("file_id")));
              if(!hash.equals(media.getString("sha256")))throw new SecurityException("Медиа изменилось: "+name);
              files++;
            }
          }
          progress.show("Упаковка защищённого архива: "+page+"/"+s.pages+" страниц");
        }
        JSONArray participants=new JSONArray();
        for(long userId:users) {
          try {
            JSONObject user=safeRead(req("getUser","user_id",userId),30);
            participants.put(new JSONObject().put("kind","user").put("id",userId)
              .put("first_name",user.optString("first_name"))
              .put("last_name",user.optString("last_name"))
              .put("username",user.optString("username")));
          } catch(Exception e) {participants.put(new JSONObject().put("kind","user").put("id",userId));}
        }
        for(long senderChat:senderChats) {
          try {
            JSONObject identity=safeRead(req("getChat","chat_id",senderChat),30);
            participants.put(new JSONObject().put("kind","chat").put("id",senderChat)
              .put("title",identity.optString("title")));
          } catch(Exception e) {participants.put(new JSONObject().put("kind","chat").put("id",senderChat));}
        }
        archive.writeBytes("participants.json",participants.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject manifest=new JSONObject()
          .put("schema","tg-vault-v1").put("source","TDLib Android")
          .put("chat_id",chatId).put("chat_title",chat.optString("title","Чат"))
          .put("exported_at_utc",Instant.now().toString())
          .put("messages",s.messages).put("media_downloaded",files)
          .put("media_missing",0).put("history_end_reached",s.historyFinished)
          .put("resume_supported",true).put("participants",participants.length())
          .put("verification","Available cloud history; deleted or restricted content cannot be recovered");
        archive.writeBytes("manifest.json",manifest.toString(2).getBytes(StandardCharsets.UTF_8));
      }
      return new Summary(s.messages,files,0,s.historyFinished);
    } finally {Arrays.fill(sessionSecret,(byte)0);}
  }
}
