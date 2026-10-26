import org.vault.exporter.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.*;

/** Offline integration test: fake history -> encrypted resumable stage -> password TVA -> verified ZIP. */
public final class RunPipelineTests {
  static byte[] all(InputStream input) throws Exception {
    ByteArrayOutputStream output=new ByteArrayOutputStream();
    byte[] data=new byte[65536];int n;
    while((n=input.read(data))!=-1)output.write(data,0,n);
    return output.toByteArray();
  }
  static void yes(boolean check,String message){if(!check)throw new AssertionError(message);}
  public static void main(String[] args) throws Exception {
    File home=Files.createTempDirectory("vault-pipeline").toFile();
    byte[] key=new byte[32];new SecureRandom().nextBytes(key);
    byte[] jpeg=new byte[256_000];new SecureRandom().nextBytes(jpeg);
    File mediaFile=new File(home,"fake_photo.jpg");Files.write(mediaFile.toPath(),jpeg);
    String message="{\"id\":123,\"date\":1770000000,\"content\":{\"@type\":\"messagePhoto\"}}\n";
    String mediaIndex="";
    try(ResumeStore stage=new ResumeStore(new File(home,"private"),700,key)){
      ResumeStore.Media md=stage.saveMedia(123,8,mediaFile);
      mediaIndex="{\"message_id\":123,\"file_id\":8,\"path\":\"media/123/8.jpg\",\"sha256\":\""+md.sha256+"\",\"bytes\":"+md.bytes+"}\n";
      stage.putPage(1,message.getBytes(StandardCharsets.UTF_8),mediaIndex.getBytes(StandardCharsets.UTF_8));
      stage.checkpoint(new ResumeStore.State(700,123,1,1,1,true));
    }
    File outputFile=new File(home,"sample.tva");char[] pass="DemoVaultPassword2026".toCharArray();
    try(ResumeStore resumed=new ResumeStore(new File(home,"private"),700,key);
        ArchiveWriter archive=new ArchiveWriter(new FileOutputStream(outputFile),pass)){
      yes(resumed.state().historyFinished,"resumed complete");
      archive.writeStream("messages/page-000001.jsonl",resumed.openPage(1));
      archive.writeStream("media-index/page-000001.jsonl",resumed.openIndex(1));
      archive.writeStream("media/123/8.jpg",resumed.openMedia(123,8));
      archive.writeBytes("participants.json","[]".getBytes(StandardCharsets.UTF_8));
      archive.writeBytes("manifest.json","{\"schema\":\"tg-vault-v1\",\"messages\":1,\"media_downloaded\":1,\"media_missing\":0,\"history_end_reached\":true}".getBytes(StandardCharsets.UTF_8));
    }
    try(InputStream in=new FileInputStream(outputFile)){yes(ArchiveVerifier.verify(in,pass)==5,"archive integrity");}
    boolean seenMessage=false,seenImage=false;
    try(ZipInputStream z=new ZipInputStream(new ChunkedVault.Decrypt(new FileInputStream(outputFile),pass))){
      ZipEntry entry;
      while((entry=z.getNextEntry())!=null){
        byte[] data=all(z);
        if(entry.getName().startsWith("messages/"))seenMessage=new String(data,StandardCharsets.UTF_8).contains("123");
        if(entry.getName().endsWith("8.jpg"))seenImage=Arrays.equals(jpeg,data);
      }
    }
    yes(seenImage && seenMessage,"media and messages match exactly");
    if(args.length>0) Files.copy(outputFile.toPath(),Paths.get(args[0]),StandardCopyOption.REPLACE_EXISTING);
    System.out.println("PASS pipeline: encrypted staging -> password archive -> verified media + messages");
  }
}
