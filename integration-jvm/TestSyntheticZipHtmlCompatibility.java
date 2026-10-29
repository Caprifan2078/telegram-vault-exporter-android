import org.vault.exporter.ArchiveWriter;
import org.vault.exporter.SyntheticZipWriter;
import org.vault.exporter.LocalMockTdlib;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Offline end-to-end contract for the actual synthetic ZIP generator and the
 * Telegram Desktop HTML class/structure selectors consumed by Archive Viewer.
 * No account, API credentials, internet, Android runtime or personal data. */
public final class TestSyntheticZipHtmlCompatibility {
  private static void check(boolean passed,String reason) {
    if(!passed)throw new AssertionError(reason);
  }
  private static Map<String,byte[]> unpack(byte[] contents)throws IOException {
    Map<String,byte[]> found=new LinkedHashMap<>();
    try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(contents),StandardCharsets.UTF_8)){
      ZipEntry entry;
      while((entry=zip.getNextEntry())!=null){
        ByteArrayOutputStream item=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];int n;
        while((n=zip.read(buffer))!=-1)item.write(buffer,0,n);
        check(found.put(entry.getName(),item.toByteArray())==null,"duplicate output entry");
        zip.closeEntry();
      }
    }
    return found;
  }
  private static final class HtmlCount extends HTMLEditorKit.ParserCallback {
    int history,messages,authors,dates,texts,mediaLinks;
    @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos){
      String classes=Objects.toString(attrs.getAttribute(HTML.Attribute.CLASS),"");
      if(tag==HTML.Tag.DIV){
        if(classes.equals("history"))history++;
        if(Arrays.asList(classes.split("\\s+")).contains("message"))messages++;
        if(classes.equals("from_name"))authors++;
        if(classes.contains("date")&&classes.contains("details"))dates++;
        if(classes.equals("text"))texts++;
      }
      if(tag==HTML.Tag.A){
        String ref=Objects.toString(attrs.getAttribute(HTML.Attribute.HREF),"");
        if(ref.startsWith("media/"))mediaLinks++;
      }
    }
  }
  public static void main(String[] args)throws Exception {
    check(args.length==0,"No arguments allowed");
    char[] password="fictional-jvm-passphrase-only".toCharArray();
    StringBuilder messages=new StringBuilder(),mediaIndex=new StringBuilder();
    int[] positions={3,9,17,25,33,41,49};
    String[] suffix={".jpg",".mp4",".webp",".txt",".ogg",".pdf",".png"};
    String[] kinds={"photo","video","sticker","document","voice","document","photo"};
    Map<String,byte[]> originals=new LinkedHashMap<>();
    for(int i=0;i<LocalMockTdlib.MESSAGE_COUNT;i++) {
      JSONObject content=new JSONObject().put("text",
        new JSONObject().put("text",i==7?"<script>alert('fictional')</script> & text"
          :"Учебное сообщение "+(i+1)+" ✅"));
      JSONObject msg=new JSONObject().put("id",50000+i)
        .put("date",1726000000L+i*60L).put("is_outgoing",i%2==0)
        .put("content",content);
      messages.append(msg.toString()).append('\n');
    }
    for(int i=0;i<LocalMockTdlib.MEDIA_COUNT;i++) {
      String name="media/fake-"+i+suffix[i];
      originals.put(name,("OFFLINE-FICTIONAL-MEDIA-"+i).getBytes(StandardCharsets.UTF_8));
      mediaIndex.append(new JSONObject().put("message_id",50000+positions[i])
        .put("path",name).put("category",kinds[i]).toString()).append('\n');
    }
    ByteArrayOutputStream protectedZip=new ByteArrayOutputStream();
    try(ArchiveWriter writer=new ArchiveWriter(protectedZip,password)){
      writer.writeBytes("manifest.json",new JSONObject().put("messages",60)
        .put("media_downloaded",7).toString().getBytes(StandardCharsets.UTF_8));
      writer.writeBytes("messages/page-00001.jsonl",messages.toString().getBytes(StandardCharsets.UTF_8));
      writer.writeBytes("media-index/page-00001.jsonl",mediaIndex.toString().getBytes(StandardCharsets.UTF_8));
      for(var e:originals.entrySet())writer.writeBytes(e.getKey(),e.getValue());
    }
    ByteArrayOutputStream published=new ByteArrayOutputStream();
    SyntheticZipWriter.write(published,protectedZip.toByteArray(),password);
    SyntheticZipWriter.verify(new ByteArrayInputStream(published.toByteArray()));
    Map<String,byte[]> zip=unpack(published.toByteArray());
    check(zip.containsKey("messages.html"),"HTML file absent");
    check(Arrays.equals(zip.get("messages/page-00001.jsonl"),
      messages.toString().getBytes(StandardCharsets.UTF_8)),"JSONL data modified");
    for(var e:originals.entrySet())
      check(Arrays.equals(e.getValue(),zip.get(e.getKey())),"Media changed: "+e.getKey());
    String html=new String(zip.get("messages.html"),StandardCharsets.UTF_8);
    check(html.contains("FICTIONAL")||html.contains("НЕ Telegram"),"missing fictional banner");
    check(!html.contains("<script>alert"),"unescaped HTML injection");
    check(html.contains("&lt;script&gt;"),"escaped text lost");
    HtmlCount actual=new HtmlCount();
    new ParserDelegator().parse(new StringReader(html),actual,true);
    check(actual.history==1,"missing Telegram-style history container");
    check(actual.messages==60,"expected 60 Telegram-style message DIVs, got "+actual.messages);
    check(actual.authors==60,"missing from_name for some messages: "+actual.authors);
    check(actual.dates==60,"missing date.details for some messages: "+actual.dates);
    check(actual.texts==60,"missing text DIV for some messages: "+actual.texts);
    check(actual.mediaLinks==7,"expected 7 media hrefs, got "+actual.mediaLinks);
    Arrays.fill(password,'\0');
    System.out.printf(Locale.ROOT,
      "PASS compatible synthetic ZIP: %d messages, %d media, JSONL intact, HTML escaped, Desktop-style selectors; %d bytes%n",
      actual.messages,actual.mediaLinks,published.size());
  }
}
