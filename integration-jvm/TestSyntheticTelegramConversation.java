import org.vault.exporter.*;
import org.json.*;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real ExportEngine/Verifier vs invented but TDLib-shaped conversation.
 * This test must NEVER use a Telegram account, phone, network or private media.
 */
public final class TestSyntheticTelegramConversation {
  static final long CHAT=7654321L;
  static final char[] PASS="reproducible-test-passphrase".toCharArray();
  static final String MARK="LAB_ONLY_SYNTHETIC_NOT_REAL_TELEGRAM";
  static final int COUNT=320,MEDIA=10;
  static final int[] MEDIA_POSITIONS={3,9,17,25,33,41,49,57,87};
  static final long[] IDS=TestHardenedExportEngine.ids(COUNT);
  static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
  static JSONObject rawContent(int position){
    if(position==87){
      JSONObject photo=new JSONObject().put("sizes",new JSONArray().put(new JSONObject()
        .put("width",1500).put("height",1200).put("photo",new JSONObject().put("id",502))));
      JSONObject vid=new JSONObject().put("video",new JSONObject().put("id",503))
        .put("mime_type","video/mp4");
      return new JSONObject().put("@type","messagePaidMedia").put("media",new JSONArray()
        .put(new JSONObject().put("@type","paidMediaPhoto").put("photo",photo))
        .put(new JSONObject().put("@type","paidMediaVideo").put("video",vid)));
    }
    if(position%31==0)return new JSONObject().put("@type","messageChatChangeTitle")
        .put("title","Тестовая лаборатория 🧪");
    if(position%29==0)return new JSONObject().put("@type","messagePoll")
        .put("poll",new JSONObject().put("question","Тест шифрования архива?")
          .put("options",new JSONArray().put("да").put("обязательно")));
    if(position%17==0)return new JSONObject().put("@type","messageLocation")
        .put("location",new JSONObject().put("latitude",48.00001).put("longitude",11.00002));
    if(position%13==0)return new JSONObject().put("@type","messageContact")
        .put("contact",new JSONObject().put("first_name","Фиктивный")
          .put("last_name","Контакт").put("phone_number","0000000000"));
    JSONObject text=new JSONObject().put("@type","messageText")
      .put("text",new JSONObject().put("@type","formattedText")
        .put("text",position==1?"Здравствуйте!\nТест: кавычки \"и слэш\\\" ☃️🚀"
          :position==59?"Большое тестовое сообщение: "+"текст🚀".repeat(1000)
          :"Сообщение из лаборатории №"+position+" — резервное копирование и история."));
    if(position%23==0)text.put("link_preview",new JSONObject()
      .put("@type","linkPreviewTypeArticle").put("url","https://example.invalid/synthetic-only"));
    return text;
  }
  static JSONObject message(int position){
    JSONObject m=new JSONObject().put("id",IDS[position]).put("chat_id",CHAT)
      .put("date",1_700_000_000+position*61)
      .put("sender_id",new JSONObject().put("@type","messageSenderUser")
        .put("user_id",1001+position%3))
      .put("is_outgoing",position%3==0)
      .put("can_be_saved",true)
      .put("content",rawContent(position));
    if(position%9==0 && position+2<COUNT)
      m.put("reply_to",new JSONObject().put("@type","messageReplyToMessage")
        .put("message_id",IDS[position+2]));
    if(position%11==0)m.put("edit_date",1_700_000_099+position*61);
    if(position%19==0)m.put("forward_info",new JSONObject()
      .put("origin",new JSONObject().put("@type","messageOriginHiddenUser")
        .put("sender_name","Вымышленный собеседник")));
    return m;
  }
  static TdGateway gateway(File root){
    TdGateway fake=new TdGateway(IDS,root);
    for(int i=0;i<COUNT;i++){
      boolean defaultMedia=false;
      for(int pos:MEDIA_POSITIONS)if(i==pos){defaultMedia=true;break;}
      if(!defaultMedia)fake.messageOverride(i,message(i));
    }
    fake.messageOverride(87,message(87));
    return fake;
  }
  static void send(File root,TdGateway td,OutputStream out)throws Exception {
    new ExportEngine(new Context(root),td,ignored->{}).export(CHAT,out,PASS);
  }
  static Map<Long,JSONObject> messages(Map<String,byte[]> archive){
    Map<Long,JSONObject> found=new LinkedHashMap<>();
    for(var e:archive.entrySet())if(e.getKey().startsWith("messages/"))
      for(String line:new String(e.getValue(),StandardCharsets.UTF_8).split("\n")){
        if(line.isEmpty())continue;
        JSONObject m=new JSONObject(line);
        check(found.put(m.getLong("id"),m)==null,"duplicate message");
      }
    return found;
  }
  public static void main(String[] args)throws Exception {
    File root=Files.createTempDirectory("synthetic-tdlib-conversation-").toFile();
    TdGateway flaky=gateway(root);flaky.failOnHistoryCall(12);
    boolean interrupted=false;
    try{send(root,flaky,new ByteArrayOutputStream());}
    catch(IOException ex){interrupted=ex.getMessage().contains("SIMULATED_NETWORK_DROP");}
    check(interrupted,"history outage not triggered");
    ByteArrayOutputStream resumed=new ByteArrayOutputStream();
    TdGateway resume=gateway(root);send(root,resume,resumed);
    TestHardenedExportEngine.checkArchive(resumed.toByteArray(),IDS,MEDIA);
    Map<String,byte[]> unpacked=TestHardenedExportEngine.unpack(resumed.toByteArray());
    Map<Long,JSONObject> got=messages(unpacked);
    check(got.size()==COUNT,"wrong message count");
    int replies=0,forwards=0,edits=0,unicode=0,contacts=0;
    for(int i=0;i<COUNT;i++){
      JSONObject m=got.get(IDS[i]);check(m!=null,"missing message "+i);
      if(i==87){check("messagePaidMedia".equals(m.optJSONObject("content").optString("@type")),"album dropped");continue;}
      boolean defaultMedia=false;
      for(int pos:MEDIA_POSITIONS)if(i==pos){defaultMedia=true;break;}
      if(defaultMedia)continue;
      JSONObject expected=message(i);
      check(expected.toString().equals(m.toString()),"raw TDLib fields changed at position "+i);
      if(m.has("reply_to"))replies++;
      if(m.has("forward_info"))forwards++;
      if(m.has("edit_date"))edits++;
      if(m.toString().contains("🚀"))unicode++;
      if("messageContact".equals(m.optJSONObject("content").optString("@type")))contacts++;
    }
    check(replies>20 && forwards>10 && edits>20 && unicode>=2 && contacts>10,
      "fixture missing diversity "+replies+"/"+forwards+"/"+edits+"/"+unicode+"/"+contacts);
    byte[] p=unpacked.get("participants.json");
    check(p!=null,"participants missing");
    JSONArray senders=new JSONObject("{\"data\":"+new String(p,StandardCharsets.UTF_8)+"}").optJSONArray("data");
    Set<Long> users=new HashSet<>();
    for(int i=0;i<senders.length();i++){
      JSONObject person=senders.getJSONObject(i);
      if("user".equals(person.optString("kind")))users.add(person.getLong("id"));
    }
    check(users.containsAll(Arrays.asList(1001L,1002L,1003L,55L)),"participants missing "+users);
    JSONObject manifest=new JSONObject(new String(unpacked.get("manifest.json"),StandardCharsets.UTF_8));
    check(manifest.getInt("messages")==COUNT && manifest.getInt("media_downloaded")==MEDIA,"bad manifest");
    // Important privacy property: temporary TDLib plaintext cache is fully purged.
    File cache=new File(root,"tdlib/files");
    File[] leftovers=cache.listFiles();check(leftovers==null||leftovers.length==0,"plaintext cache not purged");
    // A real Telegram protection flag must trump an otherwise valid message.
    File denied=Files.createTempDirectory("synthetic-protected-message-").toFile();
    TdGateway locked=gateway(denied);
    JSONObject protectedMessage=message(1).put("can_be_saved",false);
    locked.messageOverride(1,protectedMessage);
    boolean refused=false;
    try{send(denied,locked,new ByteArrayOutputStream());}
    catch(SecurityException ex){refused=ex.getMessage().contains("запрещено");}
    check(refused,"Telegram content-saving restriction ignored");
    // Optional developer artifact: synthetic only, test-password protected, never for production.
    if(args.length>1)throw new IllegalArgumentException("At most one optional TVA output path");
    if(args.length==1)Files.write(Path.of(args[0]),resumed.toByteArray());
    System.out.printf(Locale.ROOT,
      "PASS Synthetic TDLib conversation: %d messages, %d attachments including a two-file album, %d replies, %d forwards, %d edits, Unicode, participants, interruption/resume, plaintext purge, fail-closed restrictions; TVA=%d bytes%n",
      COUNT,MEDIA,replies,forwards,edits,resumed.size());
  }
}
