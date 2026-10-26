package org.vault.exporter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.*;

/** Offline synthetic-only acceptance check. Re-decrypts the ACTUAL output
 * and verifies real media bytes and all pages without buffering chat history. */
final class MegaArchiveVerifier {
  private MegaArchiveVerifier(){}
  static void verify(InputStream encrypted,char[] pass)throws Exception{
    long messages=0,attachments=0,mediaFiles=0,words=0,previewItems=0;
    Set<String> contents=new HashSet<>(),formats=new HashSet<>(),seen=new HashSet<>();
    byte[] scratch=new byte[65536];
    try(ZipInputStream archive=new ZipInputStream(new ChunkedVault.Decrypt(encrypted,pass))){
      ZipEntry entry;
      while((entry=archive.getNextEntry())!=null){
        String path=entry.getName();
        ArchiveWriter.validate(path);
        if(!seen.add(path))throw new IOException("Duplicate ZIP entry");
        if(path.startsWith("messages/")&&path.endsWith(".jsonl")){
          BufferedReader reader=new BufferedReader(new InputStreamReader(archive,StandardCharsets.UTF_8));
          String line;
          while((line=reader.readLine())!=null){
            if(line.isEmpty())continue;
            JSONObject m=new JSONObject(line);
            JSONObject c=m.optJSONObject("content");
            if(c==null)throw new IOException("Message without content");
            String kind=c.optString("@type");
            contents.add(kind);
            JSONObject text=c.optJSONObject("text");
            if(text==null)text=c.optJSONObject("caption");
            if(text!=null){
              String wordsText=text.optString("text","").trim();
              if(!wordsText.isEmpty())words+=wordsText.split("\\s+").length;
            }
            if("messageSticker".equals(kind)){
              JSONObject f=c.optJSONObject("sticker");
              JSONObject format=f==null?null:f.optJSONObject("format");
              if(format!=null)formats.add(format.optString("@type"));
            }
            if(!m.has("id")||m.optLong("id")==0)throw new IOException("No message ID");
            messages++;
          }
        }else if(path.startsWith("media-index/")&&path.endsWith(".jsonl")){
          BufferedReader reader=new BufferedReader(new InputStreamReader(archive,StandardCharsets.UTF_8));
          String line;
          while((line=reader.readLine())!=null){
            if(line.isEmpty())continue;
            JSONObject ref=new JSONObject(line);
            if(ref.optLong("bytes",-1)<=0 || ref.optInt("file_id",0)==0)
              throw new IOException("Media record missing byte count");
            if(ref.optString("role").startsWith("preview_"))previewItems++;
            attachments++;
          }
        }else{
          byte[] prefix=new byte[12];int n=0,k;
          while(n<prefix.length&&(k=archive.read(prefix,n,prefix.length-n))!=-1)n+=k;
          if(path.startsWith("media/")){
            String ext=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
            if("jpg".equals(ext)&&!(n>=2&&(prefix[0]&255)==255&&(prefix[1]&255)==216))
              throw new IOException("Not a real JPEG: "+path);
            if("ogg".equals(ext)&&!ascii(prefix,0,"OggS"))
              throw new IOException("Not a real OGG: "+path);
            if("tgs".equals(ext)&&!(n>=2&&(prefix[0]&255)==31&&(prefix[1]&255)==139))
              throw new IOException("Not a gzipped TGS: "+path);
            if("webp".equals(ext)&&!(ascii(prefix,0,"RIFF")&&ascii(prefix,8,"WEBP")))
              throw new IOException("Not a WEBP sticker: "+path);
            if("wav".equals(ext)&&!(ascii(prefix,0,"RIFF")&&ascii(prefix,8,"WAVE")))
              throw new IOException("Not a WAV: "+path);
            if("mp4".equals(ext)&&!ascii(prefix,4,"ftyp"))
              throw new IOException("Not an MP4: "+path);
            mediaFiles++;
          }
          while(archive.read(scratch)!=-1){}
        }
        archive.closeEntry();
      }
    }
    String[] required={"messagePhoto","messageVideoNote","messageVoiceNote",
      "messageSticker","messageAudio","messageVideo","messageAnimation",
      "messageDocument","messageText"};
    for(String k:required)if(!contents.contains(k))throw new IOException("Missing Telegram media subtype: "+k);
    if(!formats.contains("stickerFormatTgs")||!formats.contains("stickerFormatWebp"))
      throw new IOException("Missing sticker formats");
    if(messages!=MegaMockTdlib.MESSAGE_COUNT || attachments!=MegaMockTdlib.MEDIA_COUNT ||
        mediaFiles!=MegaMockTdlib.MEDIA_COUNT ||previewItems!=2 ||words!=MegaMockTdlib.ARCHIVED_WORDS)
      throw new IOException("Incomplete mega export: messages="+messages+" words="+words+
        " attachments="+attachments+" files="+mediaFiles+" preview="+previewItems);
  }
  private static boolean ascii(byte[] b,int offset,String expected){
    if(b.length<offset+expected.length())return false;
    for(int i=0;i<expected.length();i++)
      if((b[offset+i]&255)!=expected.charAt(i))return false;
    return true;
  }
}
