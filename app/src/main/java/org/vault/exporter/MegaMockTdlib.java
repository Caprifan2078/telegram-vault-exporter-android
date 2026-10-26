package org.vault.exporter;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/** Purely fictional, lazy, OFFLINE load simulator for the SAME ExportEngine.
 * 54,000 messages generate 1,080,000 words, but 8 TDLib media-only
 * types cannot carry captions: exactly 1,079,840 text words are archived.
 * 18 mixed real-format media plus one extra link-preview album item. Two 12-MiB binary documents.
 * No Telegram account, network, SDK keys or background server.
 */
public final class MegaMockTdlib implements ExportEngine.Transport {
  public static final long CHAT_ID=-98800002026L;
  public static final int MESSAGE_COUNT=54000;
  public static final int WORDS_PER_MESSAGE=20;
  public static final int TOTAL_WORDS=MESSAGE_COUNT*WORDS_PER_MESSAGE;
  /** Eight media-only messages cannot contain captions (two each of four types). */
  public static final int ARCHIVED_WORDS=TOTAL_WORDS-8*WORDS_PER_MESSAGE;
  public static final int MEDIA_COUNT=19;
  public static final long TOTAL_MEDIA_BYTES=24L*1024*1024; // excludes tiny encoded image/audio/video fixtures
  private static final long START_ID=1900000000L;
  private static final long STEP=17L;
  private final File privateRoot;

  public MegaMockTdlib(Context context) {
    this.privateRoot=context.getApplicationContext().getFilesDir();
  }
  private static JSONObject type(String t) throws Exception {
    return new JSONObject().put("@type",t);
  }
  private static JSONObject caption(String s) throws Exception {
    return type("formattedText").put("text",s).put("entities",new JSONArray());
  }
  private static String words(int index){
    StringBuilder b=new StringBuilder(20*18);
    for(int i=0;i<WORDS_PER_MESSAGE;i++){
      if(i>0)b.append(' ');
      b.append("сообщение").append(index%173).append("_слово").append(i);
    }
    return b.toString();
  }
  private static int mediaId(int index) {
    return index%3000==0 ? 29001+(index/3000) : 0;
  }

  private static boolean allowed(int id){
    return (id>=29001&&id<=29018)||id==29020;
  }
  private static int mediaLength(int id) {
    if(!allowed(id))throw new IllegalArgumentException("Invalid synthetic file");
    return (id==29009||id==29018)?12*1024*1024:0;
  }
  private static JSONObject file(int id) throws Exception{
    return type("file").put("id",id);
  }
  private static JSONObject photo(int id)throws Exception {
    return type("photo").put("sizes",new JSONArray().put(
      type("photoSize").put("width",96).put("height",72).put("photo",file(id))));
  }
  private static JSONObject contentFor(int id,String body)throws Exception {
    int kind=(id-29001)%9;
    JSONObject c;
    switch(kind){
      case 0:
        c=type("messagePhoto").put("photo",photo(id)).put("caption",caption(body));break;
      case 1:
        c=type("messageVideoNote").put("video_note",type("videoNote")
          .put("video",file(id)).put("duration",1).put("length",96));break;
      case 2:
        c=type("messageVoiceNote").put("voice_note",type("voiceNote")
          .put("voice",file(id)).put("mime_type","audio/ogg").put("duration",1));break;
      case 3:
      case 4:
        c=type("messageSticker").put("sticker",type("sticker")
          .put("sticker",file(id)).put("format",type(kind==3?"stickerFormatWebp":"stickerFormatTgs")));break;
      case 5:
        c=type("messageAudio").put("audio",type("audio").put("audio",file(id))
          .put("file_name","study-audio.wav").put("mime_type","audio/wav"))
          .put("caption",caption(body));break;
      case 6:
        c=type("messageVideo").put("video",type("video").put("video",file(id))
          .put("mime_type","video/mp4")).put("caption",caption(body));break;
      case 7:
        c=type("messageAnimation").put("animation",type("animation")
          .put("animation",file(id)).put("mime_type","video/mp4"))
          .put("caption",caption(body));break;
      case 8:
        c=type("messageDocument").put("document",type("document")
          .put("file_name","huge-synthetic-"+id+".bin")
          .put("mime_type","application/octet-stream").put("document",file(id)))
          .put("caption",caption(body));break;
      default:throw new IOException("Unknown synthetic media kind");
    }
    return c;
  }
  private static JSONObject message(int index) throws Exception {
    long id=START_ID-(long)index*STEP;
    String body=words(index);
    JSONObject content;
    int fileId=mediaId(index);
    if(fileId==29010) {
      JSONObject link=type("linkPreview").put("type","linkPreviewTypeAlbum")
        .put("album",new JSONArray()
          .put(type("linkPreviewAlbumMediaPhoto").put("photo",photo(29010)))
          .put(type("linkPreviewAlbumMediaVideo").put("video",
            type("video").put("video",file(29020)).put("mime_type","video/mp4"))));
      content=type("messageText").put("text",caption(body)).put("link_preview",link);
    }else if(fileId!=0)content=contentFor(fileId,body);
    else content=type("messageText").put("text",caption(body));
    return type("message").put("id",id).put("chat_id",CHAT_ID)
      .put("date",1700000000L-(long)index*51)
      .put("sender_id",type("messageSenderUser").put("user_id",index%2==0?1001:1002))
      .put("is_outgoing",index%2==0).put("can_be_saved",true).put("content",content);
  }
  private File path(int fileId) throws Exception{
    mediaLength(fileId);
    File folder=new File(privateRoot,"tdlib/files").getCanonicalFile();
    if(!folder.isDirectory()&&!folder.mkdirs())
      throw new IOException("Cannot create private synthetic download directory");
    File file=new File(folder,"mega-"+fileId+".bin").getCanonicalFile();
    if(!file.getPath().startsWith(folder.getPath()+File.separator))
      throw new SecurityException("Synthetic download escaped private directory");
    return file;
  }
  private static JSONObject fileInfo(int id,File file) throws Exception{
    return type("file").put("id",id).put("size",file.isFile()?file.length():0)
      .put("local",new JSONObject()
        .put("path",file.isFile()?file.getAbsolutePath():"")
        .put("is_downloading_completed",file.isFile())
        .put("is_downloading_active",false));
  }

  private static final String OPUS64=
    "T2dnUwACAAAAAAAAAAD8HSNPAAAAAJsseowBE09wdXNIZWFkAQE4AYC7AAAAAABPZ2dTAAAAAAAAAAAAAPwdI08BAAAASk4yjQE9T3B1c1RhZ3MMAAAATGF2ZjYxLjcuMTAzAQAAAB0AAABlbmNvZGVyPUxhdmM2MS4xOS4xMDEgbGlib3B1c09nZ1MABNhCAAAAAAAA/B0jTwIAAADOVFqHEkUyOjM2MS42OTApLSosLygtJniCAbdsfkDmAAAGS7TjumYR3p00RmwBHPB+I1m2zIXrmd7aBIGjduC2A1wWfuKopx7fzlrQS4bGLc+BnYqkIkXcxwldWXijP/c8SYkgRE44NBK88qYP0nO7RCMB0GfpMVbaH53LGZpqR+yl6UjVKJXzDI8vDtnYeJujEbQcv91Uzmc7uTUBYR3O7ktUqNRHJprqmW+OYFoNFtoC66pzyiYmMmfoR3xsPNI9Z4d81A/JRniboxJWzh/rHuLPNM8xN6O+TVReu5Msyuneb6RRgj9JM9iqxBTTebVKSlbg7p00lgHti3ibo191nPxFnln8dl0dm0LWLdbt2rT+M0qiiPHb0QDtdNLR97QvXC/MhfkO48SdkloCocZWFniboxdL3bZFFYvbBhpCrFc6kQFxMIRtTcgLt1IFwlAkY3GSEZ1jHjQzYEDUVw+Nf9d4m6NfdZz8Re9N0PGoMzDJInT8lphqcZO9QaKb4y5mIFDyPbUeI5Q9GVCjO92UeJujElOqSE58dqVXbhGsFan+L3uK0O2Gh9Ys7lBcSQjl1VNZ0tddTPYilPZJYoavF7XKlwDFeJujElbOIipE2rqOH645l6lNTORVtEpM4p2qbdYfVBCkU6HSwC7QiCpmaxtWnQqPbfLRdAnitV+WSJsrH3Wc/EkOx0PyU9Ql+auOF3stKcwxPEDj2WJURkDqh+396u41fIQh99VMVaSpSJsq0lOqSHqbxqaiSomEcqXBf4rLnBPLoyompOv3StrMf/2BF+oLhKhImysfdZz8Re3IDcuJapc8oVPRhw5FPCd04GnqFsMcNLDDp7/IBKP7DWxBBrFImysfdZz8SzpFo8jBl3j6862c89fKRRpABwVPsspOThH9nRfGLmct3axImyrSU6pE4iz0VB4XsYFsvyPw8w1L4fXxvOCVslWj+sp8mu/rpo1Ch+RIiEibKx91nPxF0LSJJhFkvqG4J8Lr7eawrHb+Q/qTn6/4hIFy516f/+Z8VNnkZGBASJsq0lFFAM/QRp+elvFv/4uwMhq5H0OXAayU+PC7OKhfqx0uuJQz0EibKx91nPxF7a+RhL9tyfe9vF1EFBratwJMUJxAVXxmAAjwqhSbFCyavGZyoEibzKWrlufswI0lTxpcekdfyBo24BK71IG2HQuRDVclP4XSyhos";
  private static byte[] jpegOrWebp(int id,boolean sticker)throws Exception {
    android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(96,72,
      android.graphics.Bitmap.Config.ARGB_8888);
    try{
      for(int y=0;y<72;y++)for(int x=0;x<96;x++)
        b.setPixel(x,y,android.graphics.Color.rgb((x*2+id)%255,(y*3+id/3)%255,(x+y)%255));
      java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
      if(!b.compress(sticker?android.graphics.Bitmap.CompressFormat.WEBP:
          android.graphics.Bitmap.CompressFormat.JPEG,85,out))
        throw new IOException("Failed to encode synthetic image");
      return out.toByteArray();
    }finally{b.recycle();}
  }
  private static byte[] tgs()throws Exception{
    byte[] json=("{\"v\":\"5.7.4\",\"fr\":30,\"ip\":0,\"op\":30,\"w\":128,\"h\":128,\"nm\":\"study\","
      +"\"ddd\":0,\"assets\":[],\"layers\":[]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
    try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(out)){gzip.write(json);}
    return out.toByteArray();
  }
  private static byte[] wav()throws Exception {
    int sampleRate=8000,samples=sampleRate/3;
    java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(44+samples*2);
    java.io.DataOutputStream d=new java.io.DataOutputStream(bytes);
    // RIFF headers use little endian, emit bytes explicitly.
    writeAscii(d,"RIFF");write32(d,36+samples*2);writeAscii(d,"WAVEfmt ");
    write32(d,16);write16(d,1);write16(d,1);write32(d,sampleRate);
    write32(d,sampleRate*2);write16(d,2);write16(d,16);
    writeAscii(d,"data");write32(d,samples*2);
    for(int i=0;i<samples;i++)write16(d,(int)(Math.sin(i*2*Math.PI*440/sampleRate)*4000));
    d.flush();return bytes.toByteArray();
  }
  private static void writeAscii(java.io.DataOutputStream d,String s)throws Exception{
    d.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }
  private static void write16(java.io.DataOutputStream d,int v)throws Exception {
    d.write(v&255);d.write((v>>>8)&255);
  }
  private static void write32(java.io.DataOutputStream d,int v)throws Exception {
    write16(d,v);write16(d,v>>>16);
  }
  private static byte[] encodedSample(int id)throws Exception{
    if(id==29020)return LocalMockTdlib.sampleMp4();
    switch((id-29001)%9){
      case 0:return jpegOrWebp(id,false);
      case 1:case 6:case 7:return LocalMockTdlib.sampleMp4();
      case 2:return android.util.Base64.decode(OPUS64,android.util.Base64.DEFAULT);
      case 3:return jpegOrWebp(id,true);
      case 4:return tgs();
      case 5:return wav();
      default:throw new IOException("Expected a binary document, got "+id);
    }
  }
  private static void writeDeterministic(File file,int id)throws Exception {
    if(id!=29009&&id!=29018){
      byte[] payload=encodedSample(id);
      try(FileOutputStream out=new FileOutputStream(file)){out.write(payload);out.getFD().sync();}
      return;
    }
    Random random=new Random(id*17L+0x3568ac9L);
    byte[] block=new byte[64*1024];
    int remaining=mediaLength(id);
    try(FileOutputStream out=new FileOutputStream(file)){
      while(remaining>0){
        int n=Math.min(block.length,remaining);
        random.nextBytes(block);
        out.write(block,0,n);
        remaining-=n;
      }
      out.getFD().sync();
    }
  }
  @Override public synchronized JSONObject await(JSONObject request,long timeoutSeconds)throws Exception {
    String method=request.optString("@type","");
    switch(method){
      case "getChat":
        if(request.getLong("chat_id")!=CHAT_ID)throw new SecurityException("Invalid heavy test chat");
        return type("chat").put("id",CHAT_ID)
          .put("title","СТРЕСС-ТЕСТ · 1 079 840 слов · голосовые, кружки, стикеры")
          .put("has_protected_content",false).put("type",type("chatTypePrivate"));
      case "getUser": {
        int id=request.getInt("user_id");
        if(id!=1001&&id!=1002)throw new SecurityException("Invalid synthetic user");
        return type("user").put("id",id).put("first_name",id==1001?"Тест-А":"Тест-Б")
          .put("last_name","Вымышленный");
      }
      case "getChatHistory": {
        if(request.getLong("chat_id")!=CHAT_ID)
          throw new SecurityException("Unknown synthetic chat ID");
        long from=request.optLong("from_message_id",0);
        int start=from==0?0:(int)Math.max(0,
          Math.min(MESSAGE_COUNT,(START_ID-from+STEP-1)/STEP));
        JSONArray data=new JSONArray();
        int limit=Math.min(100,Math.max(1,request.optInt("limit",100)));
        for(int i=start;i<MESSAGE_COUNT && i<start+limit;i++)
          data.put(message(i));
        return type("messages").put("messages",data).put("total_count",MESSAGE_COUNT);
      }
      case "downloadFile": {
        int id=request.getInt("file_id");
        if(!request.optBoolean("synchronous",false)||
            request.optInt("offset",-1)!=0||request.optInt("limit",-1)!=0)
          throw new SecurityException("Full synchronous synthetic downloads only");
        File file=path(id);
        if(!file.isFile())writeDeterministic(file,id);
        return fileInfo(id,file);
      }
      case "deleteFile": {
        File file=path(request.getInt("file_id"));
        if(file.exists()&&!file.delete())throw new IOException("Synthetic cache cannot be deleted");
        return type("ok");
      }
      case "cancelDownloadFile":return type("ok");
      case "getFile": {
        int id=request.getInt("file_id");
        return fileInfo(id,path(id));
      }
      default:throw new IOException("Synthetic fixture rejects TDLib command "+method);
    }
  }
}
