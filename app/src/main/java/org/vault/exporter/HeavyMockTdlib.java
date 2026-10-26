package org.vault.exporter;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/** Purely fictional, lazy, OFFLINE load simulator for the SAME ExportEngine.
 * Exactly 24,000 messages x 20 generated words = 480,000 words.
 * Twelve binary files with 36 MiB combined size are generated on request.
 * No Telegram account, network, SDK keys or background server.
 */
public final class HeavyMockTdlib implements ExportEngine.Transport {
  public static final long CHAT_ID=-98800002026L;
  public static final int MESSAGE_COUNT=24000;
  public static final int WORDS_PER_MESSAGE=20;
  public static final int TOTAL_WORDS=MESSAGE_COUNT*WORDS_PER_MESSAGE;
  public static final int MEDIA_COUNT=12;
  public static final long TOTAL_MEDIA_BYTES=(10L*2+2L*8)*1024*1024;
  private static final long START_ID=1900000000L;
  private static final long STEP=17L;
  private final File privateRoot;

  public HeavyMockTdlib(Context context) {
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
    return index%2000==0 ? 19001+(index/2000) : 0;
  }
  private static int mediaLength(int id) {
    if(id<19001||id>=19001+MEDIA_COUNT)throw new IllegalArgumentException("Invalid synthetic file");
    return (id==19001||id==19006 ? 8:2)*1024*1024;
  }
  private static JSONObject message(int index) throws Exception {
    long id=START_ID-(long)index*STEP;
    String body=words(index);
    JSONObject content;
    int f=mediaId(index);
    if(f!=0){
      JSONObject file=type("file").put("id",f);
      content=type("messageDocument").put("document",type("document")
        .put("file_name","synthetic-"+f+".bin")
        .put("mime_type","application/octet-stream").put("document",file))
        .put("caption",caption(body));
    } else {
      content=type("messageText").put("text",caption(body));
    }
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
    File file=new File(folder,"heavy-"+fileId+".bin").getCanonicalFile();
    if(!file.getPath().startsWith(folder.getPath()+File.separator))
      throw new SecurityException("Synthetic download escaped private directory");
    return file;
  }
  private static JSONObject fileInfo(int id,File file) throws Exception{
    return type("file").put("id",id).put("size",mediaLength(id))
      .put("local",new JSONObject()
        .put("path",file.isFile()?file.getAbsolutePath():"")
        .put("is_downloading_completed",file.isFile())
        .put("is_downloading_active",false));
  }
  private static void writeDeterministic(File file,int id)throws Exception{
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
          .put("title","СТРЕСС-ТЕСТ · 480 000 вымышленных слов")
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
