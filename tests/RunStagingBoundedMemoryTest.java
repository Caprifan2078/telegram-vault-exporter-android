import org.vault.exporter.ResumeStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Must pass with -Xmx32m even when staged media is 48 MiB. */
public final class RunStagingBoundedMemoryTest {
  public static void main(String[] args) throws Exception {
    File root=Files.createTempDirectory("vault-memory-test").toFile();
    File file=new File(root,"large-synthetic-media.bin");
    try(FileOutputStream f=new FileOutputStream(file)) {
      byte[] block=new byte[65536];Arrays.fill(block,(byte)42);
      for(int i=0;i<768;i++)f.write(block);
    }
    try(ResumeStore store=new ResumeStore(new File(root,"staging"),1,new byte[32])) {
      if(store.saveMedia(100,501,file).bytes!=48L*1024*1024)throw new AssertionError("write lost bytes");
      if(store.mediaFingerprint(100,501).bytes!=48L*1024*1024)throw new AssertionError("read lost bytes");
    }
    System.out.println("PASS: streaming 48MiB media staging under JVM 32MiB heap");
  }
}
