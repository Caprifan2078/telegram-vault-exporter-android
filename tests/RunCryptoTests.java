import org.vault.exporter.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class RunCryptoTests {
  static void yes(boolean check, String why) { if(!check) throw new AssertionError(why); }
  static void rejects(byte[] data, String password) throws Exception {
    try { ArchiveVerifier.verify(new ByteArrayInputStream(data), password.toCharArray());
      throw new AssertionError("CORRUPTION ACCEPTED");
    } catch (IOException expected) { /* cryptographic failure, parser failure, or invalid format */ }
  }
  public static void main(String[] args) throws Exception {
    byte[] big = new byte[3 * ChunkedVault.BLOCK + 777];
    new java.security.SecureRandom().nextBytes(big);
    File sample=File.createTempFile("vault-med", ".bin"); Files.write(sample.toPath(),big);
    ByteArrayOutputStream buffer=new ByteArrayOutputStream();
    char[] pw="test long development password 9".toCharArray();
    try(ArchiveWriter archive=new ArchiveWriter(buffer,pw)) {
      archive.writeBytes("messages/page-000001.jsonl","{\"id\":1,\"text\":\"мяу\"}\n".getBytes(StandardCharsets.UTF_8));
      java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
      byte[] fingerprint=sha.digest(big);
      StringBuilder expected=new StringBuilder();
      for(byte b:fingerprint)expected.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
      String index="{\"message_id\":1,\"file_id\":100,\"path\":\"media/1/100.bin\",\"sha256\":\""+expected+"\",\"bytes\":"+big.length+"}\n";
      archive.writeBytes("media-index/page-000001.jsonl",index.getBytes(StandardCharsets.UTF_8));
      String digest=archive.writeMedia("media/1/100.bin",sample);
      yes(digest.matches("[a-f0-9]{64}"),"SHA-256 hex");
      archive.writeBytes("participants.json","[]".getBytes(StandardCharsets.UTF_8));
      archive.writeBytes("manifest.json", "{\"schema\":\"tg-vault-v1\",\"messages\":1,\"media_downloaded\":1,\"media_missing\":0,\"history_end_reached\":true}".getBytes(StandardCharsets.UTF_8));
    }
    byte[] original=buffer.toByteArray();
    int n=ArchiveVerifier.verify(new ByteArrayInputStream(original), pw);
    yes(n==5,"Expected 5 zip entries, got "+n);
    rejects(original,"not the password");
    for(int pos : new int[]{0,18,35,40,original.length/2,original.length-17,original.length-1}) {
      byte[] corrupt=original.clone(); corrupt[pos]^=(byte)0x32;
      rejects(corrupt,new String(pw));
    }
    rejects(Arrays.copyOf(original, original.length-18),new String(pw));
    rejects(Arrays.copyOf(original, original.length-1),new String(pw));
    for(String name : new String[]{"../escape", "/media/photo", "folder\\evil", "a..b", "C:unsafe"}) {
      try{ArchiveWriter.validate(name);throw new AssertionError("Path accepted: "+name);}
      catch(IOException expected) {}
    }
    sample.delete();
    System.out.println("PASS: authenticated streaming, >3 blocks, password, tampering, truncation, ZIP entries, path filtering; bytes="+original.length);
  }
}
