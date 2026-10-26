package org.vault.exporter;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;

/**
 * A real encrypted .tva fixture, created on Android without Telegram, networking,
 * TDLib credentials or any user data. Debug-only feature, not a Telegram export.
 */
public final class OfflineArchiveFixture {
  private OfflineArchiveFixture() {}
  public static final String PASSWORD = "Vault-demo-archive-only";
  private static final long CHAT = 99001L;

  private static JSONObject message(long id, int sender, String text) throws Exception {
    return new JSONObject().put("@type","message").put("id",id)
      .put("chat_id",CHAT).put("date",1720000000)
      .put("sender_id",new JSONObject().put("@type","messageSenderUser").put("user_id",sender))
      .put("is_outgoing",sender==1)
      .put("content",new JSONObject().put("@type","messageText").put("text",
        new JSONObject().put("@type","formattedText").put("text",text)
          .put("entities",new JSONArray())));
  }
  private static String sha256(byte[] data) throws Exception {
    byte[] digest=MessageDigest.getInstance("SHA-256").digest(data);
    StringBuilder hex=new StringBuilder();
    for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));
    return hex.toString();
  }
  public static void write(OutputStream output,char[] password) throws Exception {
    // The fixture contains fictional text and a tiny fabricated text attachment,
    // NEVER a real Telegram chat or its credentials.
    byte[] attachment="Vault sample file: no account was accessed.\n".getBytes(StandardCharsets.UTF_8);
    String filePath="media/300/11.txt";
    JSONObject document=message(300,1,"");
    document.put("content",new JSONObject().put("@type","messageDocument").put("caption",
      new JSONObject().put("text","Fictional attachment").put("entities",new JSONArray()))
      .put("document",new JSONObject().put("file_name","vault-demo.txt")
        .put("mime_type","text/plain")
        .put("document",new JSONObject().put("@type","file").put("id",11)
          .put("size",attachment.length))));
    JSONObject second=message(200,2,"Привет! Это учебный архив, не экспорт Telegram.");
    JSONObject third=message(100,1,"Работает без аккаунта и без интернета.");
    String messages=document.toString()+"\n"+second.toString()+"\n"+third.toString()+"\n";
    JSONObject index=new JSONObject().put("message_id",300).put("file_id",11)
      .put("category","document").put("mime_type","text/plain")
      .put("original_name","vault-demo.txt").put("path",filePath)
      .put("sha256",sha256(attachment)).put("bytes",attachment.length);
    JSONArray users=new JSONArray()
      .put(new JSONObject().put("kind","user").put("id",1).put("first_name","Тест"))
      .put(new JSONObject().put("kind","user").put("id",2).put("first_name","Друг"));
    JSONObject manifest=new JSONObject()
      .put("schema","tg-vault-v1").put("source","Vault Offline Fixture (NOT TELEGRAM)")
      .put("chat_id",CHAT).put("chat_title","Учебная переписка")
      .put("exported_at_utc",Instant.now().toString())
      .put("messages",3).put("media_downloaded",1)
      .put("media_missing",0).put("history_end_reached",true)
      .put("resume_supported",false).put("participants",2)
      .put("verification","FICTIONAL OFFLINE TEST DATA ONLY");
    try(ArchiveWriter archive=new ArchiveWriter(output,password)){
      archive.writeBytes("messages/page-000001.jsonl",messages.getBytes(StandardCharsets.UTF_8));
      archive.writeBytes("media-index/page-000001.jsonl",
        (index.toString()+"\n").getBytes(StandardCharsets.UTF_8));
      archive.writeBytes(filePath,attachment);
      archive.writeBytes("participants.json",users.toString().getBytes(StandardCharsets.UTF_8));
      archive.writeBytes("manifest.json",manifest.toString(2).getBytes(StandardCharsets.UTF_8));
    }
  }
}
