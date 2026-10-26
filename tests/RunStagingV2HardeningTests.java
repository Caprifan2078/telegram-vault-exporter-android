import org.vault.exporter.ResumeStore;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Offline, dependency-free JVM regression tests. Uses no Telegram credentials or traffic. */
public final class RunStagingV2HardeningTests {
  static int checks=0;
  static void ok(boolean cond,String reason){checks++;if(!cond)throw new AssertionError(reason);}
  interface Failure {void run() throws Exception;}
  static void reject(Failure run, String why)throws Exception {
    boolean rejected=false;
    try {run.run();}catch(Exception x){rejected=true;}
    ok(rejected,why);
  }
  static byte[] read(InputStream in)throws Exception {
    try(InputStream a=in;ByteArrayOutputStream b=new ByteArrayOutputStream()){
      byte[] buf=new byte[8192];int n;while((n=a.read(buf))!=-1)b.write(buf,0,n);
      return b.toByteArray();
    }
  }
  static byte[] legacy(long chatId, String path, byte[] secret, byte[] text)throws Exception {
    Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
    byte[] key=mac.doFinal(("TelegramVault resume store v1|"+chatId).getBytes(StandardCharsets.UTF_8));
    byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
    cipher.updateAAD(("TVS1|"+chatId+"|"+path).getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream out=new ByteArrayOutputStream();out.write("TVS1".getBytes(StandardCharsets.US_ASCII));out.write(nonce);out.write(cipher.doFinal(text));
    return out.toByteArray();
  }
  public static void main(String[] args)throws Exception {
    byte[] secret=new byte[32];Arrays.fill(secret,(byte)21);
    File root=Files.createTempDirectory("vault-tvs2-regress-").toFile();
    File staging=new File(root,"stage");
    File plain=new File(root,"input.dat");
    long chatId=113;
    int[] sizes={0,1,15,16,17,262143,262144,262145,786432};
    for(int size:sizes){
      byte[] bytes=new byte[size];new Random(1212+size).nextBytes(bytes);Files.write(plain.toPath(),bytes);
      try(ResumeStore store=new ResumeStore(staging,chatId,secret)){
        long id=size+2L;
        ResumeStore.Media fingerprint=store.saveMedia(id,501,plain);
        byte[] recovered=read(store.openMedia(id,501));
        ok(Arrays.equals(bytes,recovered),"TVS2 corrupted size "+size);
        ok(fingerprint.bytes==size,"Wrong media size "+size);
        ok(store.mediaFingerprint(id,501).sha256.equals(fingerprint.sha256),"Wrong hash "+size);
      }
    }
    File target=new File(staging,"chat-113/media/262147-501.enc"); // payload size 262145
    ok(target.exists(),"Test fixture missing");
    byte[] original=Files.readAllBytes(target.toPath());
    ok(original[0]=='T'&&original[3]=='2',"New stage not TVS2");
    // Dropped authenticated terminator, corruption, wrong chat/path, trailing garbage.
    for(int cut:new int[]{1,12,20,original.length-1,original.length-16}){
      byte[] shortened=Arrays.copyOf(original,cut);
      Files.write(target.toPath(),shortened);
      reject(()-> {try(ResumeStore s=new ResumeStore(staging,chatId,secret)){
        read(s.openMedia(262147,501));}},"Truncated TVS2 accepted (cut="+cut+")");
    }
    Files.write(target.toPath(),original);
    byte[] modified=original.clone();modified[16]^=0x55;
    Files.write(target.toPath(),modified);
    reject(()->{try(ResumeStore s=new ResumeStore(staging,chatId,secret)){
      read(s.openMedia(262147,501));}},"Authenticated media tampering accepted");
    byte[] extended=Arrays.copyOf(original,original.length+1);extended[extended.length-1]=3;
    Files.write(target.toPath(),extended);
    reject(()->{try(ResumeStore s=new ResumeStore(staging,chatId,secret)){
      read(s.openMedia(262147,501));}},"Trailing bytes accepted");
    Files.write(target.toPath(),original);
    File cross=new File(staging,"chat-113/media/999-501.enc");
    Files.copy(target.toPath(),cross.toPath());
    reject(()->{try(ResumeStore s=new ResumeStore(staging,chatId,secret)){
      read(s.openMedia(999,501));}},"Replayed ciphertext accepted with different AAD path");
    try(ResumeStore s=new ResumeStore(staging,chatId,secret)){
      reject(()->read(s.openMedia(262147,502)),"Wrong file identity accepted");
      s.checkpoint(new ResumeStore.State(chatId,1000,1,40,8,false));
    }
    try(ResumeStore s=new ResumeStore(staging,chatId,secret)){
      ResumeStore.State checkpoint=s.state();
      ok(checkpoint.cursor==1000&&checkpoint.messages==40&&!checkpoint.historyFinished,"Checkpoint failed across process reopen");
      s.checkpoint(new ResumeStore.State(chatId,1000,1,40,8,true));
    }
    try(ResumeStore s=new ResumeStore(staging,chatId,secret)){
      ok(s.state().historyFinished,"Checkpoint terminal state not durable");
    }
    // TVS1 must remain readable for small pre-existing checkpoints (backward compatibility).
    File legacyPath=new File(staging,"chat-113/checkpoint.enc");
    ByteArrayOutputStream meta=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(meta);
    data.writeInt(0x52535431);data.writeLong(chatId);data.writeLong(777L);
    data.writeInt(3);data.writeInt(12);data.writeInt(4);data.writeBoolean(false);data.close();
    Files.write(legacyPath.toPath(),legacy(chatId,"checkpoint.enc",secret,meta.toByteArray()));
    try(ResumeStore s=new ResumeStore(staging,chatId,secret)){
      ok(s.state().cursor==777,"Legacy TVS1 checkpoint migration read failed");
    }
    // TVS1 large files fail explicitly, no accidental provider heap exhaustion.
    byte[] legacyHuge=new byte[9*1024*1024];
    File oversized=new File(staging,"chat-113/media/10000000-501.enc");
    Files.write(oversized.toPath(),legacyHuge); // replace marker with legacy magic
    try(RandomAccessFile f=new RandomAccessFile(oversized,"rw")){f.writeBytes("TVS1");}
    reject(()->{try(ResumeStore s=new ResumeStore(staging,chatId,secret)){
      read(s.openMedia(10000000,501));}},"Oversized legacy staging was not rejected");
    System.out.println("PASS "+checks+" TVS2/JVM checks (boundaries, GCM authentication, replay, truncation, exact terminator, checkpoint, legacy)");
  }
}
