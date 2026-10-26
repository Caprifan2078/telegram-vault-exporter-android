import org.vault.exporter.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.*;

/** The same fixture the Android offline APK saves through the system document picker. */
public final class TestOfflineArchiveFixture {
  public static void main(String[] unused) throws Exception {
    char[] password=OfflineArchiveFixture.PASSWORD.toCharArray();
    ByteArrayOutputStream result=new ByteArrayOutputStream();
    OfflineArchiveFixture.write(result,password);
    byte[] archive=result.toByteArray();
    if(archive.length<100||ArchiveVerifier.verify(new ByteArrayInputStream(archive),password)!=5)
      throw new AssertionError("offline archive fixture failed independent verification");
    boolean wrongPasswordRejected=false;
    try {
      ArchiveVerifier.verify(new ByteArrayInputStream(archive),"not-the-demo-pass".toCharArray());
    }catch(Exception expected){wrongPasswordRejected=true;}
    if(!wrongPasswordRejected)throw new AssertionError("Incorrect password accepted");
    int messageCount=0,mediaCount=0;
    try(ZipInputStream zip=new ZipInputStream(
        new ChunkedVault.Decrypt(new ByteArrayInputStream(archive),password))){
      ZipEntry entry;byte[] buf=new byte[512];
      while((entry=zip.getNextEntry())!=null){
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        int n;while((n=zip.read(buf))!=-1)bytes.write(buf,0,n);
        if(entry.getName().equals("messages/page-000001.jsonl")){
          String text=bytes.toString(StandardCharsets.UTF_8);
          for(String row:text.split("\\n"))if(!row.isEmpty())messageCount++;
          if(!text.contains("Это учебный архив"))
            throw new AssertionError("Fixture must be visibly fictional");
        }
        if(entry.getName().equals("media/300/11.txt")){
          mediaCount++;
          if(!bytes.toString(StandardCharsets.UTF_8).contains("no account"))
            throw new AssertionError("Fixture contains unexpected media");
        }
        zip.closeEntry();
      }
    }
    if(messageCount!=3||mediaCount!=1)throw new AssertionError("Fixture content mismatch");
    byte[] corrupted=archive.clone();corrupted[corrupted.length/2]^=1;
    boolean corruptionRejected=false;
    try{ArchiveVerifier.verify(new ByteArrayInputStream(corrupted),password);}
    catch(Exception expected){corruptionRejected=true;}
    if(!corruptionRejected)throw new AssertionError("Corrupt fixture accepted");
    Arrays.fill(password,'\0');
    System.out.println("PASS: Android offline fixture: 3 synthetic messages, 1 media, 5 entries, correct password, tamper rejection");
  }
}
