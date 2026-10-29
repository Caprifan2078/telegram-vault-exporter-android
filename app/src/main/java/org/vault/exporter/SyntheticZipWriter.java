package org.vault.exporter;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** TEST-ONLY conversion of a verified synthetic encrypted TVA to a readable ZIP.
 * Production user data NEVER flows through this class. No network APIs. */
public final class SyntheticZipWriter {
  private SyntheticZipWriter(){}
  private static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
  private static String escape(String value){
    if(value==null)return "";
    return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
      .replace("\"","&quot;").replace("'","&#39;");
  }
  private static byte[] readSmall(InputStream in,int limit)throws IOException{
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    byte[] buf=new byte[8192];int count;
    while((count=in.read(buf))!=-1){
      if(out.size()+count>limit)throw new IOException("Oversized synthetic metadata");
      out.write(buf,0,count);
    }
    return out.toByteArray();
  }
  private static void collectLines(String text,List<JSONObject> into)throws Exception{
    for(String line:text.split("\n"))if(!line.isEmpty())into.add(new JSONObject(line));
  }
  public static void write(OutputStream destination,byte[] encrypted,char[] passphrase)throws Exception{
    ArrayList<JSONObject> history=new ArrayList<>(),refs=new ArrayList<>();
    Set<String> seen=new HashSet<>();
    boolean hadManifest=false;
    try(ZipInputStream input=new ZipInputStream(new ChunkedVault.Decrypt(
          new ByteArrayInputStream(encrypted),passphrase));
        ZipOutputStream zip=new ZipOutputStream(destination,StandardCharsets.UTF_8)){
      ZipEntry entry;
      byte[] block=new byte[65536];
      while((entry=input.getNextEntry())!=null){
        String name=entry.getName();
        ArchiveWriter.validate(name);
        if(!seen.add(name))throw new IOException("Duplicate zip entry");
        zip.putNextEntry(new ZipEntry(name));
        if(name.equals("manifest.json")){
          JSONObject manifest=new JSONObject(new String(readSmall(input,1048576),StandardCharsets.UTF_8));
          manifest.put("source","FICTIONAL LOCAL TEST (NOT TELEGRAM)");
          manifest.put("verification","Synthetic TDLib-shaped fixture, no Telegram server or account contacted");
          zip.write(utf8(manifest.toString(2)));
          hadManifest=true;
        }else if(name.startsWith("messages/")&&name.endsWith(".jsonl")){
          byte[] lines=readSmall(input,2*1024*1024);
          collectLines(new String(lines,StandardCharsets.UTF_8),history);
          zip.write(lines);
        }else if(name.startsWith("media-index/")&&name.endsWith(".jsonl")){
          byte[] lines=readSmall(input,2*1024*1024);
          collectLines(new String(lines,StandardCharsets.UTF_8),refs);
          zip.write(lines);
        }else{
          int n;while((n=input.read(block))!=-1)zip.write(block,0,n);
        }
        zip.closeEntry();input.closeEntry();
      }
      if(!hadManifest || history.size()!=LocalMockTdlib.MESSAGE_COUNT
          || refs.size()!=LocalMockTdlib.MEDIA_COUNT)
        throw new IOException("Wrong size of synthetic history");
      Collections.sort(history,(a,b)->Long.compare(a.optLong("id"),b.optLong("id")));
      Map<Long,List<JSONObject>> attachments=new HashMap<>();
      for(JSONObject ref:refs){
        String path=ref.getString("path");
        ArchiveWriter.validate(path);
        if(!path.startsWith("media/")||!seen.contains(path))
          throw new IOException("Missing synthetic media");
        attachments.computeIfAbsent(ref.getLong("message_id"),k->new ArrayList<>()).add(ref);
      }
      StringBuilder html=new StringBuilder();
      html.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self' file:; media-src 'self' file:; style-src 'unsafe-inline'\">")
        .append("<title>Учебный чат — НЕ Telegram</title><style>")
        .append("body{background:#101a28;color:#f4f7fa;font:16px/1.5 sans-serif;max-width:820px;margin:auto;padding:20px}")
        .append(".message{background:#213147;border-radius:15px;padding:15px;margin:10px 0}.body{display:block}")
        .append(".from_name{color:#a4c6ff;font-weight:700}.date.details{font-size:12px;color:#bacbdc}")
        .append(".text{white-space:pre-wrap;margin:8px 0}.media_wrap{margin-top:9px}")
        .append("small{color:#bacbdc}img,video{max-width:90%;display:block;border-radius:8px}a{color:#a7deff}")
        .append("</style></head><body><h1>Учебный чат — НЕ Telegram</h1>")
        .append("<p>60 вымышленных сообщений. Никакой реальный аккаунт и сервер Telegram не использовались.</p>")
        .append("<div class=\"history\">");
      // Match Telegram Desktop HTML selectors used by Telegram Archive Viewer:
      // div.history > div.message, .body .from_name, .body .date.details and
      // .body .text. Preserve the verified JSONL entries for other readers.
      for(JSONObject msg:history){
        JSONObject content=msg.optJSONObject("content");
        JSONObject text=content==null?null:content.optJSONObject("text");
        if(text==null&&content!=null)text=content.optJSONObject("caption");
        long id=msg.getLong("id");
        String sender=(msg.optBoolean("is_outgoing",false)?"Алиса":"Борис");
        String when=new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss",java.util.Locale.ROOT)
          .format(new java.util.Date(msg.optLong("date",0L)*1000L));
        html.append("<div class=\"message default\" id=\"message").append(id).append("\"><div class=\"body\">")
          .append("<div class=\"from_name\">").append(escape(sender)).append("</div>")
          .append("<div class=\"date details\" title=\"").append(escape(when)).append("\">")
          .append(escape(when)).append("</div><div class=\"text\">")
          .append(escape(text==null?"[вложение]":text.optString("text","")))
          .append("</div>");
        for(JSONObject ref:attachments.getOrDefault(id,Collections.emptyList())){
          String name=ref.getString("path"), category=ref.optString("category","");
          html.append("<div class=\"media_wrap\">");
          if("photo".equals(category)||"sticker".equals(category))
            html.append("<img loading=\"lazy\" src=\"").append(escape(name)).append("\" alt=\"медиа\">");
          else if("video".equals(category))
            html.append("<video controls preload=\"none\" src=\"").append(escape(name)).append("\"></video>");
          html.append("<a href=\"").append(escape(name)).append("\">Файл: ")
            .append(escape(name)).append("</a></div>");
        }
        html.append("</div></div>");
      }
      html.append("</div></body></html>");
      zip.putNextEntry(new ZipEntry("messages.html"));zip.write(utf8(html.toString()));zip.closeEntry();
      zip.putNextEntry(new ZipEntry("README_TEST_ONLY.txt"));
      zip.write(utf8("This is a fictional OFFLINE test of Vault Exporter.\n"
        +"No Telegram account, chat, credential or network session was accessed.\n"
        +"Open messages.html after extracting this ZIP. Media files are in media/.\n"));
      zip.closeEntry();
      zip.finish();
    }
  }
  public static void verify(InputStream in)throws Exception{
    int messages=0,media=0;boolean html=false,marker=false,manifest=false;
    HashSet<String> seen=new HashSet<>();
    byte[] buf=new byte[65536];
    try(ZipInputStream zip=new ZipInputStream(in,StandardCharsets.UTF_8)){
      ZipEntry entry;
      while((entry=zip.getNextEntry())!=null){
        String name=entry.getName();ArchiveWriter.validate(name);
        if(!seen.add(name))throw new IOException("Duplicate synthetic ZIP entry");
        if(name.equals("messages.html")){html=true;readSmall(zip,1024*1024);}
        else if(name.equals("README_TEST_ONLY.txt")){marker=true;readSmall(zip,1024*1024);}
        else if(name.equals("manifest.json")){
          String text=new String(readSmall(zip,1024*1024),StandardCharsets.UTF_8);
          JSONObject meta=new JSONObject(text);
          manifest=meta.optString("source").equals("FICTIONAL LOCAL TEST (NOT TELEGRAM)");
        }else if(name.startsWith("messages/")&&name.endsWith(".jsonl")){
          for(String line:new String(readSmall(zip,2*1024*1024),StandardCharsets.UTF_8).split("\n"))
            if(!line.isEmpty()){new JSONObject(line);messages++;}
        }else{
          if(name.startsWith("media/"))media++;
          while(zip.read(buf)!=-1){}
        }
        zip.closeEntry();
      }
    }
    if(!html||!marker||!manifest||messages!=LocalMockTdlib.MESSAGE_COUNT
        ||media!=LocalMockTdlib.MEDIA_COUNT)
      throw new IOException("Synthetic ZIP verification failed");
  }
}
