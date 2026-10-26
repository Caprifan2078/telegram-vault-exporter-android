import org.vault.exporter.ResumeStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;

public final class RunResumeTests {
  static void ok(boolean v, String reason) {if(!v)throw new AssertionError(reason);}
  static byte[] all(InputStream input) throws Exception {
    try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();
    }
  }
  public static void main(String[] args) throws Exception {
    File home=Files.createTempDirectory("tg-vault-resume-test").toFile();
    byte[] key=new byte[32];new SecureRandom().nextBytes(key);
    byte[] page="{\"id\":900,\"text\":\"hello\"}\n".getBytes(StandardCharsets.UTF_8);
    byte[] index="{\"media\":1}\n".getBytes(StandardCharsets.UTF_8);
    File media=new File(home,"original.mp4");byte[] content=new byte[2_000_000];new SecureRandom().nextBytes(content);
    Files.write(media.toPath(),content);
    try(ResumeStore st=new ResumeStore(new File(home,"private"),123L,key)){
      ok(st.state().pages==0,"initial state");
      st.putPage(1,page,index);
      ResumeStore.Media m=st.saveMedia(900,42,media);
      ok(st.hasMedia(900,42),"saved media");
      ok(Arrays.equals(content,all(st.openMedia(900,42))),"media roundtrip");
      ok(Arrays.equals(page,all(st.openPage(1))),"page roundtrip");
      ok(m.bytes==content.length,"media size");
      st.checkpoint(new ResumeStore.State(123,900,1,1,1,false));
      ok(st.state().messages==1,"checkpoint stored");
      // Staging must not contain the literal plaintext of messages.
      byte[] disk=Files.readAllBytes(new File(home,"private/chat-123/pages/000001.jsonl.enc").toPath());
      ok(!new String(disk,StandardCharsets.ISO_8859_1).contains("hello"),"sealed content");
    }
    try(ResumeStore recovered=new ResumeStore(new File(home,"private"),123L,key)){
      ok(recovered.state().cursor==900,"crash resume cursor");
      ResumeStore.Media reused=recovered.saveMedia(900,42,media);
      ok(reused.bytes==content.length,"reuse encrypted media");
      ok(recovered.mediaFingerprint(900,42).sha256.equals(reused.sha256),"reuse hash");
      recovered.checkpoint(new ResumeStore.State(123,900,1,1,1,true));
    }
    try(ResumeStore wrong=new ResumeStore(new File(home,"private"),124L,key)){
      ok(wrong.state().pages==0,"chat isolated");
    }
    byte[] wrongKey=new byte[32];new SecureRandom().nextBytes(wrongKey);
    boolean bad=false;
    try(ResumeStore wrong=new ResumeStore(new File(home,"private"),123L,wrongKey)){
      try{wrong.state();}catch(IOException e){bad=true;}
    }
    ok(bad,"wrong key rejected");
    File sealed=new File(home,"private/chat-123/media/900-42.enc");
    try(RandomAccessFile data=new RandomAccessFile(sealed,"rw")){
      data.seek(data.length()-16);int b=data.read();data.seek(data.length()-16);data.write(b^0x40);
    }
    bad=false;
    try(ResumeStore st=new ResumeStore(new File(home,"private"),123L,key)){
      try{st.mediaFingerprint(900,42);}catch(IOException e){bad=true;}
    }
    ok(bad,"tampered ciphertext rejected");
    System.out.println("PASS resume: restart, reuse, confidentiality, isolation, wrong key, tamper");
  }
}
