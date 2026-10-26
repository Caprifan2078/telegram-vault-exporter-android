package org.vault.exporter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Fictional TDLib-style server for exercising the REAL ExportEngine entirely
 * offline. It cannot authorize, call Telegram, or see real account data.
 * This is a synthetic backend, not a Telegram client and not proof of API login.
 */
public final class LocalMockTdlib implements ExportEngine.Transport {
  public static final long CHAT_ID = -98987654321L;
  public static final int MESSAGE_COUNT = 60;
  public static final int MEDIA_COUNT = 7;
  private final File privateRoot;

  public LocalMockTdlib(Context context) {
    privateRoot = context.getApplicationContext().getFilesDir();
  }
  private static JSONObject obj(String type) throws Exception {
    return new JSONObject().put("@type",type);
  }
  private static JSONObject file(int id) throws Exception {
    return new JSONObject().put("id",id).put("@type","file");
  }
  private static JSONObject caption(String text) throws Exception {
    return obj("formattedText").put("text",text).put("entities",new JSONArray());
  }
  private static JSONObject photo(int id) throws Exception {
    return obj("photo").put("sizes",new JSONArray().put(
      obj("photoSize").put("width",96).put("height",72).put("photo",file(id))));
  }
  private static JSONObject message(int index) throws Exception {
    long id=100000L-index*17L;
    int sender=(index%3==0)?1002:1001;
    String text;
    switch(index%5) {
      case 0:text="Сообщение №"+(index+1)+". Это НЕ настоящий Telegram: все участники и медиа вымышлены.";break;
      case 1:text="Тест переноса строк:\nПривет, как дела?\nФото и файлы должны сохраняться полностью.";break;
      case 2:text="Ссылка: https://example.invalid — никаких запросов по ней не делаем.";break;
      case 3:text="HTML-безопасность: <script>alert('не исполнять')</script> & кавычки.";break;
      default:text="Проверяем кириллицу, emoji 📦✨ и экспорт длинной истории.";break;
    }
    JSONObject c=obj("messageText").put("text",caption(text));
    switch(index) {
      case 3: case 37: case 52: {
        int idFile=index==3?9001:index==37?9005:9007;
        c=obj("messagePhoto").put("photo",photo(idFile)).put("caption",caption("Учебная фотография "+idFile));
        break;
      }
      case 9: case 45: {
        int idFile=index==9?9002:9006;
        c=obj("messageDocument").put("document",obj("document")
          .put("file_name",index==9?"instructions.txt":"export-notes.txt")
          .put("mime_type","text/plain").put("document",file(idFile)))
          .put("caption",caption("Учебный документ"));
        break;
      }
      case 16:
        c=obj("messageVideo").put("video",obj("video")
          .put("video",file(9003)).put("mime_type","video/mp4"))
          .put("caption",caption("Очень короткое учебное видео"));
        break;
      case 24:
        c=obj("messageSticker").put("sticker",obj("sticker")
          .put("sticker",file(9004)).put("format",obj("stickerFormatWebp")));
        break;
      default: break;
    }
    JSONObject msg=obj("message").put("id",id).put("chat_id",CHAT_ID)
      .put("date",1726000000-index*135).put("sender_id",
        obj("messageSenderUser").put("user_id",sender))
      .put("is_outgoing",sender==1001).put("can_be_saved",true).put("content",c);
    if(index>0 && index%11==0)msg.put("reply_to",obj("messageReplyToMessage")
      .put("message_id",id+17));
    if(index>0 && index%13==0)msg.put("forward_info",new JSONObject()
      .put("origin",obj("messageOriginHiddenUser").put("sender_name","Пример")));
    return msg;
  }

  private File mediaFile(int id) throws Exception {
    if(id<9001||id>9007)throw new java.io.IOException("Invalid mock file ID");
    File dir=new File(privateRoot,"tdlib/files");
    if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException("Cannot create local mock files");
    File path=new File(dir,"mock-"+id+".bin").getCanonicalFile();
    if(!path.getPath().startsWith(dir.getCanonicalPath()+File.separator))
      throw new SecurityException("Mock file escaped private directory");
    return path;
  }
  private static byte[] bitmap(int id,Bitmap.CompressFormat format) throws Exception {
    Bitmap image=Bitmap.createBitmap(96,72,Bitmap.Config.ARGB_8888);
    try {
      for(int y=0;y<72;y++)for(int x=0;x<96;x++){
        int r=(x*2+id)%255,g=(y*3+id/3)%255,b=(x+y+id/5)%255;
        image.setPixel(x,y,Color.rgb(r,g,b));
      }
      ByteArrayOutputStream out=new ByteArrayOutputStream();
      if(!image.compress(format,85,out))throw new java.io.IOException("Image compression failed");
      return out.toByteArray();
    } finally { image.recycle(); }
  }
  private static final String VIDEO64 =
    "AAAAHGZ0eXBpc29tAAACAGlzb21pc28ybXA0MQAAA1xtb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAD6AABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAACh3RyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAAD6AAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAUAAAAEAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAAA+gAAAAAAAEAAAAAAf9tZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAEAAAABAAFXEAAAAAAAtaGRscgAAAAAAAAAAdmlkZQAAAAAAAAAAAAAAAFZpZGVvSGFuZGxlcgAAAAGqbWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAABanN0YmwAAADqc3RzZAAAAAAAAAABAAAA2m1wNHYAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAUABAAEgAAABIAAAAAAAAAAETTGF2YzYxLjE5LjEwMSBtcGVnNAAAAAAAAAAAAAAAAAAY//8AAABgZXNkcwAAAAADgICATwABAASAgIBBIBEAAAAAAw1AAAADiAWAgIAvAAABsAEAAAG1iRMAAAEAAAABIADEjYgAFQKECBRDAAABskxhdmM2MS4xOS4xMDEGgICAAQIAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAMNQAAAA4gAAAAYc3R0cwAAAAAAAAABAAAAAgAAIAAAAAAUc3RzcwAAAAAAAAABAAAAAQAAABxzdHNjAAAAAAAAAAEAAAABAAAAAgAAAAEAAAAcc3RzegAAAAAAAAAAAAAAAgAAAFoAAAAXAAAAFHN0Y28AAAAAAAAAAQAAA4gAAABhdWR0YQAAAFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYxLjcuMTAzAAAACGZyZWUAAAB5bWRhdAAAAbMAEAcAAAG2FhkabbDUK422/jbb+Ntv422/fwAAlYjTbYahXG238bbfxtt/G237AACpiNNthqFcbbfxtt/G238bbfsAAL2I022GoVxtt/G238bbfxtt+wAAAbZfDD9/AACVj78AAKmPvwAAvY+/";

  /** Embedded MP4 for offline round-video fixture; never fetched from Telegram. */
  static byte[] sampleMp4(){return Base64.decode(VIDEO64,Base64.DEFAULT);}
  private static byte[] mediaBytes(int id) throws Exception {
    switch(id) {
      case 9001:case 9005:case 9007:return bitmap(id,Bitmap.CompressFormat.JPEG);
      case 9004:return bitmap(id,Bitmap.CompressFormat.WEBP);
      case 9003:return Base64.decode(VIDEO64,Base64.DEFAULT);
      case 9002:return "Local training document.\nNo real Telegram data or account is used.\n"
        .getBytes(StandardCharsets.UTF_8);
      case 9006:return "This file tests downloading a second document and cleanup.\n"
        .getBytes(StandardCharsets.UTF_8);
      default:throw new java.io.IOException("No synthetic media for "+id);
    }
  }

  @Override public synchronized JSONObject await(JSONObject request,long timeoutSeconds) throws Exception {
    String name=request.optString("@type","");
    switch(name) {
      case "getChat":
        if(request.getLong("chat_id")!=CHAT_ID)throw new SecurityException("Unknown synthetic chat");
        return obj("chat").put("id",CHAT_ID).put("title","УЧЕБНЫЙ ЧАТ — НЕ TELEGRAM")
          .put("has_protected_content",false).put("type",obj("chatTypePrivate"));
      case "getUser": {
        int id=request.getInt("user_id");
        if(id!=1001&&id!=1002)throw new SecurityException("Unknown synthetic user");
        return obj("user").put("id",id).put("first_name",id==1001?"Алиса":"Борис")
          .put("last_name","(учебный)");
      }
      case "getChatHistory": {
        if(request.getLong("chat_id")!=CHAT_ID)throw new SecurityException("Unknown synthetic history");
        // Return newest-first short pages with one overlapping cursor item.
        long from=request.optLong("from_message_id",0);
        int start=0;
        if(from!=0){
          while(start<MESSAGE_COUNT && (100000L-start*17L)>from)start++;
          if(start==MESSAGE_COUNT-1)start=MESSAGE_COUNT;
        }
        JSONArray result=new JSONArray();
        int limit=Math.min(11,Math.max(1,request.optInt("limit",100)));
        for(int i=start;i<MESSAGE_COUNT&&i<start+limit;i++)result.put(message(i));
        return obj("messages").put("messages",result).put("total_count",MESSAGE_COUNT);
      }
      case "downloadFile": {
        int id=request.getInt("file_id");
        if(!request.optBoolean("synchronous",false)||request.optInt("offset",-1)!=0||request.optInt("limit",-1)!=0)
          throw new SecurityException("Mock cloud download requires full synchronous file");
        File f=mediaFile(id);
        byte[] content=mediaBytes(id);
        try(FileOutputStream out=new FileOutputStream(f)){out.write(content);}
        return obj("file").put("id",id).put("size",content.length)
          .put("local",new JSONObject().put("path",f.getAbsolutePath())
            .put("is_downloading_completed",true).put("is_downloading_active",false));
      }
      case "deleteFile": {
        File f=mediaFile(request.getInt("file_id"));
        if(f.exists()&&!f.delete())throw new java.io.IOException("Failed to erase mock source");
        return obj("ok");
      }
      case "cancelDownloadFile": return obj("ok");
      case "getFile": {
        File f=mediaFile(request.getInt("file_id"));
        return obj("file").put("id",request.getInt("file_id")).put("local",
          new JSONObject().put("path",f.isFile()?f.getAbsolutePath():"")
            .put("is_downloading_completed",f.isFile()).put("is_downloading_active",false));
      }
      default: throw new java.io.IOException("Synthetic transport rejects "+name);
    }
  }
}
