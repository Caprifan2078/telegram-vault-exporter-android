import org.vault.exporter.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Separate VM execution with -Xmx32m; neither encoder nor verifier may read all media into heap. */
public final class RunVerifierBoundedMemoryTest {
  static String hex(byte[] bytes){StringBuilder sb=new StringBuilder();for(byte b:bytes)sb.append(String.format(Locale.ROOT,"%02x",b&255));return sb.toString();}
  public static void main(String[] args)throws Exception{
    Path dir=Files.createTempDirectory("vault-verifier-limited-heap-");
    Path media=dir.resolve("synthetic.bin"),archiveFile=dir.resolve("synthetic.tva");
    final long size=64L*1024*1024;
    byte[] buf=new byte[65536];Random rnd=new Random(1777);
    MessageDigest hash=MessageDigest.getInstance("SHA-256");
    try(OutputStream out=Files.newOutputStream(media)){
      for(long written=0;written<size;written+=buf.length){rnd.nextBytes(buf);out.write(buf);hash.update(buf);}
    }
    String sha=hex(hash.digest());char[] pass="synthetic-verifier-low-heap-2026".toCharArray();
    try(OutputStream target=Files.newOutputStream(archiveFile); ArchiveWriter writer=new ArchiveWriter(target,pass)){
      writer.writeBytes("messages/page-000001.jsonl","{\"id\":7654321}\n".getBytes(StandardCharsets.UTF_8));
      String index="{\"message_id\":7654321,\"file_id\":17,\"path\":\"media/7654321/17.bin\",\"bytes\":"+size+",\"sha256\":\""+sha+"\"}\n";
      writer.writeBytes("media-index/page-000001.jsonl",index.getBytes(StandardCharsets.UTF_8));
      String savedSha=writer.writeMedia("media/7654321/17.bin",media.toFile());
      if(!sha.equals(savedSha))throw new AssertionError("Writer fingerprint incorrect");
      writer.writeBytes("participants.json","[]".getBytes(StandardCharsets.UTF_8));
      writer.writeBytes("manifest.json","{\"schema\":\"tg-vault-v1\",\"messages\":1,\"media_downloaded\":1,\"media_missing\":0,\"history_end_reached\":true}".getBytes(StandardCharsets.UTF_8));
    }
    try(InputStream encrypted=Files.newInputStream(archiveFile)){
      int n=ArchiveVerifier.verify(encrypted,pass);
      if(n!=5)throw new AssertionError("Verified incorrect number of files: "+n);
    }
    System.out.println("PASS: independent SHA-256/length verification for 64 MiB media under JVM 32 MiB heap");
    Files.delete(media);Files.delete(archiveFile);Files.delete(dir);
  }
}
