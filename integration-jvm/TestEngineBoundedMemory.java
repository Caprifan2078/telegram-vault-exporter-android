import org.vault.exporter.*;
import android.content.Context;
import java.io.*;
import java.nio.file.*;
/** Actual ExportEngine and verified TVA output with a streaming 48-MiB media blob under 32 MiB heap. */
public final class TestEngineBoundedMemory {
  public static void main(String[] args)throws Exception{
    File root=Files.createTempDirectory("hardened-engine-memory-").toFile();
    File result=new File(root,"out.tva");
    long[] ids=new long[80];for(int i=0;i<ids.length;i++)ids[i]=80_000-i*19L;
    TdGateway fake=new TdGateway(ids,root);fake.useLargeSyntheticVideo();
    char[] pass="bounded-memory-demo".toCharArray();
    try(OutputStream output=new BufferedOutputStream(new FileOutputStream(result))){
      ExportEngine.Summary s=new ExportEngine(new Context(root),fake,x->{}).export(4321,output,pass);
      if(s.messages!=80||s.media!=8||!s.historyFinished)throw new AssertionError("wrong export summary");
    }
    try(InputStream input=new FileInputStream(result)){
      if(ArchiveVerifier.verify(input,pass)<10)throw new AssertionError("wrong entry count");
    }
    System.out.printf("PASS bounded-memory real ExportEngine+TVS2+strict ArchiveVerifier: 48 MiB video @ -Xmx32m, TVA bytes=%d%n",result.length());
  }
}
