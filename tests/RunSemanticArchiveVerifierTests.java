import org.vault.exporter.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Adversarial, independent audit of internally inconsistent encrypted TVA fixtures. */
public final class RunSemanticArchiveVerifierTests {
  static final char[] PASS="offline-semantic-audit-2026".toCharArray();
  static final byte[] PHOTO={1,2,3,4,5,6,7};
  static final byte[] CIRCLE={0,0,0,16,102,116,121,112,1,2,3,4};
  static int cases=0;
  static String sha(byte[] input)throws Exception {
    byte[] digest=MessageDigest.getInstance("SHA-256").digest(input);
    StringBuilder result=new StringBuilder();
    for(byte b:digest)result.append(String.format(Locale.ROOT,"%02x",b&255));
    return result.toString();
  }
  static String index(long message,int file,String ext,byte[] bytes)throws Exception{
    return "{\"message_id\":"+message+",\"file_id\":"+file+",\"category\":\"video\",\"path\":\"media/"+message+"/"+file+ext+"\",\"sha256\":\""+sha(bytes)+"\",\"bytes\":"+bytes.length+"}\n";
  }
  static String manifest(int messages,int media){return "{\"schema\":\"tg-vault-v1\",\"messages\":"+messages+",\"media_downloaded\":"+media+",\"media_missing\":0,\"history_end_reached\":true}";}
  static final class Entry {String name;byte[] data;Entry(String n,byte[] d){name=n;data=d;}}
  static Entry e(String name,String body){return new Entry(name,body.getBytes(StandardCharsets.UTF_8));}
  static Entry e(String name,byte[] body){return new Entry(name,body);}
  static ArrayList<Entry> fixture()throws Exception {
    ArrayList<Entry> data=new ArrayList<>();
    data.add(e("messages/page-000001.jsonl","{\"id\":100,\"text\":\"escapes: \\\" quote and nested\",\"content\":{\"text\":{\"text\":\"Hi\"}}}\n{\"id\":90}\n"));
    data.add(e("media-index/page-000001.jsonl",index(100,501,".jpg",PHOTO)));
    data.add(e("media/100/501.jpg",PHOTO));
    data.add(e("messages/page-000002.jsonl","{\"id\":70}\n"));
    data.add(e("media-index/page-000002.jsonl",index(70,502,".mp4",CIRCLE)));
    data.add(e("media/70/502.mp4",CIRCLE));
    data.add(e("participants.json","[]"));
    data.add(e("manifest.json",manifest(3,2)));
    return data;
  }
  static byte[] build(List<Entry> entries)throws Exception{
    ByteArrayOutputStream output=new ByteArrayOutputStream();
    try(ArchiveWriter archive=new ArchiveWriter(output,PASS)){
      for(Entry entry:entries)archive.writeBytes(entry.name,entry.data);
    }
    return output.toByteArray();
  }
  static void pass(List<Entry> fixture,int expected)throws Exception{
    int count=ArchiveVerifier.verify(new ByteArrayInputStream(build(fixture)),PASS);
    if(count!=expected)throw new AssertionError("Entry count mismatch "+count);
    cases++;
  }
  static void reject(List<Entry> entries,String why)throws Exception {
    try {ArchiveVerifier.verify(new ByteArrayInputStream(build(entries)),PASS);}
    catch(IOException expected){cases++;return;}
    throw new AssertionError("SEMANTIC CORRUPTION ACCEPTED: "+why);
  }
  static void change(List<Entry> fixture,int pos,String body){fixture.set(pos,e(fixture.get(pos).name,body));}
  static void testCorruption()throws Exception {
    ArrayList<Entry> good=fixture();pass(good,8);
    ArrayList<Entry> a;
    a=fixture();change(a,7,manifest(300,2));reject(a,"inflated message count");
    a=fixture();change(a,7,manifest(3,200));reject(a,"inflated media count");
    a=fixture();change(a,7,manifest(2,2));reject(a,"missing message count");
    a=fixture();change(a,7,manifest(3,1));reject(a,"missing media count");
    a=fixture();change(a,7,manifest(3,2).replace("\"media_missing\":0","\"media_missing\":1"));reject(a,"missing media");
    a=fixture();change(a,7,manifest(3,2).replace("true","false"));reject(a,"unfinished history");
    a=fixture();change(a,7,manifest(3,2).replace("\"messages\":3","\"messages\":\"3\""));reject(a,"numeric string counts");
    a=fixture();change(a,7,manifest(3,2).replace("true","\"true\""));reject(a,"boolean string");
    a=fixture();change(a,7,manifest(3,2).replace("tg-vault-v1","another-schema"));reject(a,"unknown schema");
    a=fixture();change(a,7,manifest(3,2).replace("\"messages\":3","\"messages\":3,\"messages\":3"));reject(a,"duplicate JSON manifest key");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace(sha(PHOTO),"0".repeat(64)));reject(a,"false media hash");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace("\"bytes\":7","\"bytes\":999999"));reject(a,"false size");
    a=fixture();a.set(2,e("media/100/501.jpg",new byte[]{9,9,9,9,9,9,9}));reject(a,"modified media bytes");
    a=fixture();a.remove(2);reject(a,"missing media payload");
    a=fixture();a.add(3,e("media/100/999.mp4",PHOTO));reject(a,"unreferenced media payload");
    a=fixture();change(a,1,index(123456,501,".jpg",PHOTO));reject(a,"orphan message ref");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO)+index(100,501,".jpg",PHOTO));reject(a,"duplicate media index row");
    a=fixture();change(a,0,"{\"id\":100}\n{\"id\":100}\n");reject(a,"duplicate message ID on page");
    a=fixture();change(a,3,"{\"id\":90}\n");reject(a,"duplicate message ID across pages");
    a=fixture();change(a,3,"{\"id\":110}\n");reject(a,"ascending message ID across pages");
    a=fixture();change(a,0,"{\"id\":\"100\"}\n{\"id\":90}\n");reject(a,"string message IDs");
    a=fixture();change(a,0,"{\"id\":100,\"id\":100}\n{\"id\":90}\n");reject(a,"duplicate JSON message id key");
    a=fixture();change(a,0,"{\"id\":100,}\n{\"id\":90}\n");reject(a,"bad JSON");
    a=fixture();change(a,0,"{\"id\":100}\n".repeat(2001));reject(a,"unbounded page count");
    a=fixture();a.set(3,e("messages/page-000003.jsonl","{\"id\":70}\n"));reject(a,"missing page numbering");
    a=fixture();a.remove(6);reject(a,"missing participants");
    a=fixture();a.add(e("extra.txt","hello"));reject(a,"unexpected file after manifest");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace("media/100/501.jpg","media/../../evil.jpg"));reject(a,"path traversal");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace("\"sha256\"","\"digest\""));reject(a,"missing SHA256");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace("\"bytes\":7","\"bytes\":\"7\""));reject(a,"string length field");
    a=fixture();change(a,0,"{\"id\":100}\n{\"id\":90,\"text\":\""+"x".repeat(2*1024*1024)+"\"}\n");reject(a,"oversized JSONL row");
    a=fixture();change(a,6,"not-json");reject(a,"malformed participants JSON");
    a=fixture();change(a,6,"[{\"id\":1},]");reject(a,"participants trailing comma");
    a=fixture();change(a,6,"[42]");reject(a,"nonobject participants");
    a=fixture();change(a,7,manifest(3,2).replace("\"schema\"", "\"participants\":2,\"schema\""));reject(a,"participant count mismatch");
  }
  public static void main(String[] args)throws Exception {
    testCorruption();
    System.out.println("PASS "+cases+" verifier fixtures: complete archive + adversarial counts, ids, malformed JSON, tamper, path and missing file rejection");
  }
}
