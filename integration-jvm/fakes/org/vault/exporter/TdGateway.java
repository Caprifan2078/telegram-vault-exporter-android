package org.vault.exporter;
import org.json.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Fake transport, no Telegram connection. Mimics short pages with cursor overlap. */
public final class TdGateway {
  final long[] ids;final File root;
  int calls,failHistoryAt,failFileId,failChatAt;
  boolean inconsistentEmpty,protectedChat,secretChat,largeVideo;
  int misroutedPosition=-1;
  boolean misroutedChatMetadata,misrouteStalledProbe;
  public void misrouteHistoryMessageAt(int position){misroutedPosition=position;}
  public void misrouteChatMetadata(){misroutedChatMetadata=true;}
  public void misrouteStalledProbe(){misrouteStalledProbe=true;}
  final Map<Integer,JSONObject> overrides=new HashMap<>();
  public void contentOverride(int position, JSONObject replacement){overrides.put(position,replacement);}
  public TdGateway(long[] ids,File root){this.ids=ids;this.root=root;}
  public void failOnHistoryCall(int n){failHistoryAt=n;}
  public void failDownloadFor(int id){failFileId=id;}
  public void failChatCall(int n){failChatAt=n;}
  public int historyCalls(){return calls;}
  public void useLargeSyntheticVideo(){largeVideo=true;}
  public static JSONObject cmd(String type){return new JSONObject().put("@type",type);}
  public static byte[] contentFor(int id){
    return switch(id){
      case 501->"OggS synthetic voice data".getBytes(StandardCharsets.UTF_8);
      case 502->new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xe0,1,2,3,4};
      case 503,504,507->new byte[]{0,0,0,16,102,116,121,112,105,115,111,109,1,2,3,4};
      case 505->"ID3 synthetic audio".getBytes(StandardCharsets.UTF_8);
      case 506->"%PDF-1.4\nsynthetic doc".getBytes(StandardCharsets.UTF_8);
      case 508->"RIFF1234WEBPsynthetic".getBytes(StandardCharsets.UTF_8);
      default->throw new IllegalArgumentException("No such media "+id);
    };
  }
  private static JSONObject file(int id){return new JSONObject().put("id",id);}
  private JSONObject content(int position,long id){
    switch(position){
      case 3:return new JSONObject().put("@type","messageVoiceNote").put("voice_note",new JSONObject().put("voice",file(501)).put("mime_type","audio/ogg"));
      case 9:return new JSONObject().put("@type","messagePhoto").put("photo",new JSONObject().put("sizes",new JSONArray().put(new JSONObject().put("width",64).put("height",64).put("photo",file(599))).put(new JSONObject().put("width",1920).put("height",1080).put("photo",file(502)))));
      case 17:return new JSONObject().put("@type","messageVideo").put("video",new JSONObject().put("video",file(503)).put("mime_type","video/mp4"));
      case 25:return new JSONObject().put("@type","messageVideoNote").put("video_note",new JSONObject().put("video",file(504)).put("mime_type","video/mp4"));
      case 33:return new JSONObject().put("@type","messageAudio").put("audio",new JSONObject().put("audio",file(505)).put("mime_type","audio/mpeg"));
      case 41:return new JSONObject().put("@type","messageDocument").put("document",new JSONObject().put("document",file(506)).put("file_name","notes.pdf").put("mime_type","application/pdf"));
      case 49:return new JSONObject().put("@type","messageAnimation").put("animation",new JSONObject().put("animation",file(507)).put("mime_type","video/mp4"));
      case 57:return new JSONObject().put("@type","messageSticker").put("sticker",new JSONObject().put("sticker",file(508)).put("mime_type","image/webp"));
      default:return new JSONObject().put("@type","messageText").put("text",new JSONObject().put("text","Synthetic #"+id));
    }
  }
  private File cached(int id) { return new File(root,"tdlib/files/file-"+id+".bin"); }
  public JSONObject await(JSONObject req,long timeout)throws Exception {
    String type=req.optString("@type");
    if(type.equals("getChat")){
      if(protectedChat)return new JSONObject().put("id",req.getLong("chat_id")).put("has_protected_content",true);
      return new JSONObject().put("id",misroutedChatMetadata?9999L:req.getLong("chat_id")).put("title","Synthetic").put("has_protected_content",false).put("type",new JSONObject().put("@type",secretChat?"chatTypeSecret":"chatTypePrivate"));
    }
    if(type.equals("getUser"))return new JSONObject().put("first_name","Test");
    if(type.equals("getChatHistory")){
      calls++;if(calls==failHistoryAt)throw new IOException("SIMULATED_NETWORK_DROP");
      long from=req.optLong("from_message_id");int limit=req.optInt("limit",100);
      JSONArray arr=new JSONArray();int start=0;
      if(from>0)while(start<ids.length&&ids[start]>from)start++;
      if(start>=ids.length)return new JSONObject().put("messages",arr);
      if(misrouteStalledProbe && from==ids[6]){
        arr.put(new JSONObject().put("id",ids[6]).put("chat_id",req.getLong("chat_id"))
          .put("content",content(6,ids[6])));
        return new JSONObject().put("messages",arr);
      }
      for(int i=start;i<ids.length&&i<start+Math.min(7,limit);i++)
        arr.put(new JSONObject().put("id",ids[i])
          .put("chat_id", (i==misroutedPosition ||
              (misrouteStalledProbe && from==ids[6]-1 && i==7))?9999L:req.getLong("chat_id"))
          .put("sender_id",new JSONObject().put("@type","messageSenderUser").put("user_id",55))
          .put("content",overrides.getOrDefault(i,content(i,ids[i]))));
      return new JSONObject().put("messages",arr);
    }
    if(type.equals("downloadFile")){
      int id=req.getInt("file_id");if(id==failFileId)throw new IOException("SIMULATED_MEDIA_DOWNLOAD_ERROR");
      File f=cached(id);f.getParentFile().mkdirs();
      try(OutputStream out=new FileOutputStream(f)){
        if(largeVideo && id==503){
          byte[] chunk=new byte[65536];Arrays.fill(chunk,(byte)0x6a);
          for(int i=0;i<48*1024*1024/chunk.length;i++)out.write(chunk);
        }else out.write(contentFor(id));
      }
      return new JSONObject().put("id",id).put("size",f.length())
        .put("local",new JSONObject().put("is_downloading_completed",true)
          .put("is_downloading_active",false).put("path",f.getCanonicalPath()));
    }
    if(type.equals("cancelDownloadFile"))return new JSONObject().put("@type","ok");
    if(type.equals("deleteFile")){
      File f=cached(req.getInt("file_id"));
      if(f.exists()&&!f.delete())throw new IOException("Mock failed to remove cached plaintext");
      return new JSONObject().put("@type","ok");
    }
    if(type.equals("getFile")){
      int id=req.getInt("file_id");File f=cached(id);
      return new JSONObject().put("id",id)
        .put("local",new JSONObject().put("is_downloading_active",false)
          .put("is_downloading_completed",f.isFile())
          .put("path",f.isFile()?f.getCanonicalPath():""));
    }
    throw new IOException("Unsupported fake call: "+type);
  }
}
