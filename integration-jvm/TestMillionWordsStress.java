import org.vault.exporter.*;
import org.json.*;
import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** 1.08m-word, high-entropy 48-MiB, resumable REAL ExportEngine pipeline.
 * Host JVM and synthetic transport, not Android E2E or real Telegram.
 */
public final class TestMillionWordsStress {
  private static final long CHAT=4321L;
  private static final int N=54_000, WORDS=20, MEDIA=8;
  private static final long FIRST_ID=900_000_000L, STEP=19;
  private static final char[] PASS="never-real-stress-passphrase".toCharArray();
  private static void check(boolean pass,String msg){if(!pass)throw new AssertionError(msg);}
  private static long[] makeIds(){long[] ids=new long[N];for(int i=0;i<N;i++)ids[i]=FIRST_ID-i*STEP;return ids;}
  private static TdGateway gateway(long[] ids,File root){TdGateway fake=new TdGateway(ids,root);fake.configureMega(WORDS);return fake;}
  private static void export(File root,TdGateway fake,File dest)throws Exception{
    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
      ExportEngine.Summary summary=new ExportEngine(new Context(root),fake,x->{}).export(CHAT,out,PASS);
      check(summary.historyFinished&&summary.messages==N&&summary.media==MEDIA&&summary.missing==0,
        "Incomplete real ExportEngine output");
    }
  }
  private static void verify(File archive)throws Exception{
    check(archive.length()>=48L*1024*1024,"Final TVA unexpectedly small; fake media was compressible or lost");
    try(InputStream in=new BufferedInputStream(new FileInputStream(archive))){
      int entries=ArchiveVerifier.verify(in,PASS);
      check(entries>N/100*2,"Missing archive pages");
    }
    long messages=0,words=0;int media=0;long previous=Long.MAX_VALUE;
    try(InputStream in=new BufferedInputStream(new FileInputStream(archive));
        ZipInputStream zip=new ZipInputStream(new ChunkedVault.Decrypt(in,PASS))){
      ZipEntry e;
      while((e=zip.getNextEntry())!=null){
        if(e.getName().startsWith("messages/")) {
          BufferedReader reader=new BufferedReader(new InputStreamReader(zip,java.nio.charset.StandardCharsets.UTF_8));
          String line;
          while((line=reader.readLine())!=null){
            if(line.isEmpty())continue;
            JSONObject obj=new JSONObject(line); long id=obj.getLong("id");
            check(id==FIRST_ID-messages*STEP && id<previous,"Lost, duplicated or shuffled message "+messages);
            previous=id; messages++;
            JSONObject text=obj.optJSONObject("content").optJSONObject("text");
            if(text!=null){
              String textLine=text.optString("text","");
              words+=textLine.split(" ").length;
            }
          }
        } else if(e.getName().startsWith("media/")){
          byte[] b=new byte[65536];while(zip.read(b)!=-1){} media++;
        } else {byte[] b=new byte[65536];while(zip.read(b)!=-1){}}
        zip.closeEntry();
      }
    }
    check(messages==N && media==MEDIA,"Wrong message/media counts");
    check(words==(N-MEDIA)*WORDS,"Missing synthetic million-word corpus: "+words);
    System.out.printf("PASS MEGA: %,d messages, %,d real words, %d media, %,d encrypted bytes, all IDs monotonic, no loss%n",
      messages,words,media,archive.length());
  }
  public static void main(String[] args)throws Exception {
    File root=Files.createTempDirectory("vault-1m-stress-").toFile();
    long[] ids=makeIds();
    TdGateway interrupted=gateway(ids,root);interrupted.failOnHistoryCall(5);
    boolean failed=false;
    try{export(root,interrupted,new File(root,"partial-output.tva"));}
    catch(IOException expected){failed=expected.getMessage().contains("SIMULATED_NETWORK_DROP");}
    check(failed,"Network interruption was not triggered");
    TdGateway recovered=gateway(ids,root);
    File archive=new File(root,"complete.tva");
    long start=System.nanoTime();export(root,recovered,archive);
    verify(archive);
    check(!new File(root,"tdlib/files/file-503.bin").exists(),"Large plaintext TDLib file was not purged");
    System.out.printf("PASS encrypted resume after dropout; verified %.1f seconds under -Xmx96m%n",
      (System.nanoTime()-start)/1e9);

    // Provider/disk failure AFTER all pages were saved. No repeat Telegram history fetches.
    TdGateway finalizeFail=gateway(ids,root);
    boolean diskFull=false;
    final int[] seen={0};
    try {
      new ExportEngine(new Context(root),finalizeFail,x->{}).export(CHAT,new OutputStream(){
        @Override public void write(int b)throws IOException{ if(++seen[0]>500000)throw new IOException("SIMULATED_DISK_FULL"); }
        @Override public void write(byte[] b,int off,int len)throws IOException{
          if(seen[0]+len>500000)throw new IOException("SIMULATED_DISK_FULL");
          seen[0]+=len;
        }
      },PASS);
    }catch(IOException expected){diskFull=expected.getMessage().contains("SIMULATED_DISK_FULL");}
    check(diskFull,"Expected external storage failure during finalization");
    check(finalizeFail.historyCalls()==0,"Finalization asked Telegram for history again");
    TdGateway reconstruct=gateway(ids,root);
    File reconstructed=new File(root,"rebuilt-after-disk-full.tva");
    export(root,reconstruct,reconstructed);
    check(reconstruct.historyCalls()==0,"Rebuild refetched cloud messages");
    verify(reconstructed);
    System.out.println("PASS 48-MiB archive rebuilt after disk-full with zero chat-history RPCs");
  }
}
