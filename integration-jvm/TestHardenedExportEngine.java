import org.vault.exporter.*;
import org.json.*;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.util.zip.*;
/** Real patched ExportEngine+ResumeStore(TVS2)+strict ArchiveVerifier vs fake TDLib. */
public final class TestHardenedExportEngine {
  static final char[] PASS="reproducible-test-passphrase".toCharArray();
  static void yes(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
  static void send(File root,TdGateway td,long chat,OutputStream output)throws Exception{
    new ExportEngine(new Context(root),td,message->{}).export(chat,output,PASS);
  }
  static long[] ids(int n) {long[] a=new long[n];for(int i=0;i<n;i++)a[i]=200_000-i*31L;return a;}
  static Map<String,byte[]> unpack(byte[] blob)throws Exception{
    Map<String,byte[]> ret=new LinkedHashMap<>();
    try(ZipInputStream zip=new ZipInputStream(new ChunkedVault.Decrypt(new ByteArrayInputStream(blob),PASS))){
      ZipEntry entry;byte[] buf=new byte[65536];
      while((entry=zip.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();int read;
        while((read=zip.read(buf))!=-1)out.write(buf,0,read);
        yes(ret.put(entry.getName(),out.toByteArray())==null,"duplicate archive entry");zip.closeEntry();
      }
    }
    return ret;
  }
  static String hash(byte[] data)throws Exception {
    byte[] digest=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
  }
  static void checkArchive(byte[] blob,long[] expected,int media)throws Exception{
    int n=ArchiveVerifier.verify(new ByteArrayInputStream(blob),PASS);
    Map<String,byte[]> files=unpack(blob);yes(n==files.size(),"archive entry count");
    HashSet<Long> actual=new HashSet<>();int countMedia=0;
    for(var file:files.entrySet()){
      if(file.getKey().startsWith("messages/")){
        for(String line:new String(file.getValue(),StandardCharsets.UTF_8).split("\\n")){
          if(!line.isEmpty())yes(actual.add(new JSONObject(line).getLong("id")),"duplicate ID");
        }
      }
      if(file.getKey().startsWith("media-index/")){
        for(String line:new String(file.getValue(),StandardCharsets.UTF_8).split("\\n")){
          if(line.isEmpty())continue;JSONObject row=new JSONObject(line);
          byte[] raw=files.get(row.getString("path"));yes(raw!=null,"missing indexed media");
          yes(raw.length==row.getLong("bytes"),"media size mismatch");
          yes(hash(raw).equals(row.getString("sha256")),"media SHA mismatch");
          yes(Arrays.equals(raw,TdGateway.contentFor(row.getInt("file_id"))),"wrong media bytes");
          countMedia++;
        }
      }
    }
    yes(actual.size()==expected.length,"missing messages "+(expected.length-actual.size()));
    for(long id:expected)yes(actual.contains(id),"missing ID "+id);
    yes(countMedia==media,"wrong media count "+countMedia);
    JSONObject manifest=new JSONObject(new String(files.get("manifest.json"),StandardCharsets.UTF_8));
    yes(manifest.getInt("messages")==expected.length&&manifest.getInt("media_downloaded")==media,"manifest count mismatch");
  }
  public static void main(String[] args)throws Exception{
    long chat=4321;long[] shortIds=ids(250);File root=Files.createTempDirectory("hardened-engine-short-").toFile();
    TdGateway fails=new TdGateway(shortIds,root);fails.failOnHistoryCall(9);boolean failed=false;
    try{send(root,fails,chat,new ByteArrayOutputStream());}
    catch(IOException ex){failed=ex.getMessage().contains("SIMULATED_NETWORK_DROP");}
    yes(failed,"expected history outage");
    ByteArrayOutputStream done=new ByteArrayOutputStream();send(root,new TdGateway(shortIds,root),chat,done);
    checkArchive(done.toByteArray(),shortIds,8);
    boolean wrongPassword=false;try{ArchiveVerifier.verify(new ByteArrayInputStream(done.toByteArray()),"bad password".toCharArray());}catch(Exception expected){wrongPassword=true;}
    yes(wrongPassword,"wrong password allowed");
    // Restart when one full-resolution file is not retrievable. Page may not be committed.
    File mediaRoot=Files.createTempDirectory("hardened-engine-media-").toFile();
    TdGateway broken=new TdGateway(shortIds,mediaRoot);broken.failDownloadFor(503);boolean mediaFail=false;
    try{send(mediaRoot,broken,chat,new ByteArrayOutputStream());}
    catch(IOException ex){mediaFail=ex.getMessage().contains("503");}
    yes(mediaFail,"missing download accepted");
    ByteArrayOutputStream recovered=new ByteArrayOutputStream();send(mediaRoot,new TdGateway(shortIds,mediaRoot),chat,recovered);checkArchive(recovered.toByteArray(),shortIds,8);
    // Crash in final archive output must not invalidate committed encrypted staging.
    File finalRoot=Files.createTempDirectory("hardened-engine-finalize-").toFile();
    final long[] created={0};boolean failedFinalize=false;
    try{send(finalRoot,new TdGateway(shortIds,finalRoot),chat,new OutputStream(){
      @Override public void write(int value)throws IOException {if(++created[0]>1300)throw new IOException("SIMULATED_DISK_FULL");}
      @Override public void write(byte[] b,int off,int len)throws IOException {
        for(int i=0;i<len;i++)write(b[off+i]);
      }
    });}catch(IOException expected){failedFinalize=expected.getMessage().contains("SIMULATED_DISK_FULL");}
    yes(failedFinalize,"finalization interruption not triggered");
    ByteArrayOutputStream rebuilt=new ByteArrayOutputStream();
    TdGateway finalGateway=new TdGateway(shortIds,finalRoot);
    send(finalRoot,finalGateway,chat,rebuilt);
    yes(finalGateway.historyCalls()==0,"finalization retry fetched history again");
    checkArchive(rebuilt.toByteArray(),shortIds,8);
    // Bit flip in authenticated TVS2 staging must never be silently accepted.
    Path staged=Files.walk(new File(root,"export-progress/chat-"+chat+"/media").toPath())
      .filter(Files::isRegularFile).findFirst().orElseThrow();
    byte[] ciphertext=Files.readAllBytes(staged);
    yes(ciphertext.length>40,"short staging cipher fixture");
    ciphertext[35]^=1;
    Files.write(staged,ciphertext);
    boolean tamperFailed=false;
    try{send(root,new TdGateway(shortIds,root),chat,new ByteArrayOutputStream());}
    catch(IOException ex){tamperFailed=true;}
    yes(tamperFailed,"tampered staging passed finalization");
    // Three failures across the SAME resume store; latest exporter must recover exact 2k message archive.
    long[] longIds=ids(2000);File longRoot=Files.createTempDirectory("hardened-engine-many-").toFile();
    for(int hit:new int[]{6,15,31}){
      TdGateway flaky=new TdGateway(longIds,longRoot);flaky.failOnHistoryCall(hit);boolean stopped=false;
      try{send(longRoot,flaky,chat,new ByteArrayOutputStream());}catch(IOException ex){stopped=ex.getMessage().contains("SIMULATED_NETWORK_DROP");}
      yes(stopped,"expected outage at history call "+hit);
    }
    ByteArrayOutputStream large=new ByteArrayOutputStream();send(longRoot,new TdGateway(longIds,longRoot),chat,large);
    checkArchive(large.toByteArray(),longIds,8);
    // An unfamiliar Telegram media-bearing content type cannot silently pass as
    // an ordinary message. A failed page must remain resumable after fixing it.
    for(String unsupported:new String[]{"messagePaidMedia", "messageChatChangePhoto", "messageImaginaryAttachment"}) {
      File typeRoot=Files.createTempDirectory("hardened-unknown-type-").toFile();
      TdGateway unsupportedGateway=new TdGateway(shortIds,typeRoot);
      unsupportedGateway.contentOverride(10,new JSONObject().put("@type",unsupported)
          .put("media",new JSONArray().put(new JSONObject().put("file",new JSONObject().put("id",4242)))));
      boolean stopped=false;
      try{send(typeRoot,unsupportedGateway,chat,new ByteArrayOutputStream());}
      catch(IOException expected){stopped=expected.getMessage().contains(unsupported);}
      yes(stopped,"Unsupported content was silently ignored: "+unsupported);
      // A subsequent run of the same exporter with newly supported content is
      // allowed to resume the already committed pages and finish accurately.
      ByteArrayOutputStream afterUpdate=new ByteArrayOutputStream();
      send(typeRoot,new TdGateway(shortIds,typeRoot),chat,afterUpdate);
      checkArchive(afterUpdate.toByteArray(),shortIds,8);
    }
    File previewRoot=Files.createTempDirectory("hardened-link-preview-").toFile();
    TdGateway preview=new TdGateway(shortIds,previewRoot);
    preview.contentOverride(1,new JSONObject().put("@type","messageText")
        .put("text",new JSONObject().put("text","Example link"))
        .put("link_preview",new JSONObject().put("@type","linkPreviewPhoto")));
    boolean previewRejected=false;
    try{send(previewRoot,preview,chat,new ByteArrayOutputStream());}
    catch(IOException expected){previewRejected=expected.getMessage().contains("messageText");}
    yes(previewRejected,"Rich preview silently skipped");
    File ordinaryRoot=Files.createTempDirectory("hardened-non-media-").toFile();
    TdGateway ordinary=new TdGateway(shortIds,ordinaryRoot);
    ordinary.contentOverride(1,new JSONObject().put("@type","messageContact")
        .put("contact",new JSONObject().put("first_name","Test")));
    ByteArrayOutputStream ordinaryArchive=new ByteArrayOutputStream();
    send(ordinaryRoot,ordinary,chat,ordinaryArchive);
    checkArchive(ordinaryArchive.toByteArray(),shortIds,8);
    // All three TDLib history paths must refuse data belonging to another chat.
    File metadataRoot=Files.createTempDirectory("hardened-wrong-chat-meta-").toFile();
    TdGateway wrongMetadata=new TdGateway(shortIds,metadataRoot);
    wrongMetadata.misrouteChatMetadata();
    boolean rejectedMetadata=false;
    try{send(metadataRoot,wrongMetadata,chat,new ByteArrayOutputStream());}
    catch(IOException expected){rejectedMetadata=expected.getMessage().contains("другой чат");}
    yes(rejectedMetadata,"Wrong getChat metadata accepted");
    File misrouteRoot=Files.createTempDirectory("hardened-wrong-chat-history-").toFile();
    TdGateway wrongMessage=new TdGateway(shortIds,misrouteRoot);
    wrongMessage.misrouteHistoryMessageAt(11);
    boolean rejectedMessage=false;
    try{send(misrouteRoot,wrongMessage,chat,new ByteArrayOutputStream());}
    catch(IOException expected){rejectedMessage=expected.getMessage().contains("другого чата");}
    yes(rejectedMessage,"Cross-chat history accepted");
    ByteArrayOutputStream afterMisroute=new ByteArrayOutputStream();
    send(misrouteRoot,new TdGateway(shortIds,misrouteRoot),chat,afterMisroute);
    checkArchive(afterMisroute.toByteArray(),shortIds,8);
    File probeRoot=Files.createTempDirectory("hardened-wrong-chat-probe-").toFile();
    TdGateway wrongProbe=new TdGateway(shortIds,probeRoot);
    wrongProbe.misrouteStalledProbe();
    boolean rejectedProbe=false;
    try{send(probeRoot,wrongProbe,chat,new ByteArrayOutputStream());}
    catch(IOException expected){rejectedProbe=expected.getMessage().contains("другого чата");}
    yes(rejectedProbe,"Cross-chat stalled probe accepted");
    ByteArrayOutputStream afterProbe=new ByteArrayOutputStream();
    send(probeRoot,new TdGateway(shortIds,probeRoot),chat,afterProbe);
    checkArchive(afterProbe.toByteArray(),shortIds,8);
    System.out.println("PASS: getChat, history, stalled probe wrong-chat rejection and safe resume");
    System.out.println("PASS strict content coverage: three unknown media variants and rich previews fail closed; progress resumes; known contact succeeds");
    System.out.printf(Locale.ROOT,"PASS Hardened ExportEngine+TVS2+strict verifier: 250 and 2000 messages, eight media kinds, three outages, media retry, interrupted finalization, SHA-256, wrong password; bytes=%d%n",large.size());
  }
}
