package org.vault.exporter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Independent streaming audit of the TVA1 archive's authenticated plaintext.
 * Memory use is bounded by one JSON line, one TDLib message page, and a hash buffer;
 * media files are never materialized in memory. In addition to AEAD/ZIP integrity,
 * checks exact message counts, strictly decreasing IDs, media references and bytes.
 * This checks INTERNAL consistency, not completeness of Telegram's cloud history.
 */
public final class ArchiveVerifier {
  private ArchiveVerifier() {}
  private static final int MAX_MESSAGE_LINE=2*1024*1024;
  private static final int MAX_INDEX_LINE=128*1024;
  private static final int MAX_PAGE_ROWS=1000;
  private static final int MAX_MANIFEST_BYTES=1024*1024;
  private static final int MAX_PARTICIPANTS_BYTES=16*1024*1024;

  /** Strict, small JSON parser: exposes typed top-level scalars, validates nested content. */
  private static final class Scalar {
    final String text; final char type;
    Scalar(String text,char type){this.text=text;this.type=type;}
  }
  private static final class Json {
    final String s; int pos;
    Json(String raw){s=raw;}
    void ws(){while(pos<s.length() && (s.charAt(pos)==' '||s.charAt(pos)=='\n'||s.charAt(pos)=='\t'||s.charAt(pos)=='\r'))pos++;}
    IOException fail(){return new IOException("Некорректная JSON-строка в архиве");}
    void want(char c)throws IOException {ws();if(pos==s.length()||s.charAt(pos++)!=c)throw fail();}
    String string()throws IOException {
      ws();if(pos==s.length()||s.charAt(pos++)!='"')throw fail();
      StringBuilder b=new StringBuilder();
      while(pos<s.length()){
        char c=s.charAt(pos++);
        if(c=='"')return b.toString();
        if(c<' ')throw fail();
        if(c!='\\'){b.append(c);continue;}
        if(pos>=s.length())throw fail();
        char escape=s.charAt(pos++);
        switch(escape){
          case '"':case '\\':case '/':b.append(escape);break;
          case 'b':b.append('\b');break;case 'f':b.append('\f');break;
          case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;
          case 'u':
            if(pos+4>s.length())throw fail();
            try {b.append((char)Integer.parseInt(s.substring(pos,pos+4),16));}
            catch(NumberFormatException e){throw fail();}
            pos+=4;break;
          default:throw fail();
        }
      }
      throw fail();
    }
    Scalar value(int depth)throws IOException {
      if(depth>64)throw fail();
      ws();if(pos>=s.length())throw fail();
      char c=s.charAt(pos);
      if(c=='"')return new Scalar(string(),'s');
      if(c=='{'||c=='['){
        pos++;char end=c=='{'?'}':']';ws();
        if(pos<s.length()&&s.charAt(pos)==end){pos++;return new Scalar(null,'o');}
        do {
          if(c=='{'){string();want(':');}
          value(depth+1);ws();
          if(pos>=s.length())throw fail();
          char sep=s.charAt(pos++);
          if(sep==end)return new Scalar(null,'o');
          if(sep!=',')throw fail();
        }while(true);
      }
      int begin=pos;
      if(s.startsWith("true",pos)){pos+=4;return new Scalar("true",'b');}
      if(s.startsWith("false",pos)){pos+=5;return new Scalar("false",'b');}
      if(s.startsWith("null",pos)){pos+=4;return new Scalar(null,'0');}
      if(c=='-')pos++;
      if(pos>=s.length())throw fail();
      if(s.charAt(pos)=='0')pos++;
      else {if(s.charAt(pos)<'1'||s.charAt(pos)>'9')throw fail();
        while(pos<s.length()&&s.charAt(pos)>='0'&&s.charAt(pos)<='9')pos++;}
      if(pos<s.length()&&s.charAt(pos)=='.'){
        pos++;int at=pos;
        while(pos<s.length()&&s.charAt(pos)>='0'&&s.charAt(pos)<='9')pos++;
        if(at==pos)throw fail();
      }
      if(pos<s.length()&&(s.charAt(pos)=='e'||s.charAt(pos)=='E')){
        pos++;if(pos<s.length()&&(s.charAt(pos)=='+'||s.charAt(pos)=='-'))pos++;
        int at=pos;while(pos<s.length()&&s.charAt(pos)>='0'&&s.charAt(pos)<='9')pos++;
        if(at==pos)throw fail();
      }
      return new Scalar(s.substring(begin,pos),'n');
    }
    Map<String,Scalar> fields()throws IOException {
      LinkedHashMap<String,Scalar> values=new LinkedHashMap<>();
      want('{');ws();
      if(pos<s.length()&&s.charAt(pos)=='}'){pos++;ws();if(pos!=s.length())throw fail();return values;}
      do {
        String key=string();want(':');Scalar val=value(0);
        if(values.containsKey(key))throw new IOException("Повторное JSON-поле: "+key);
        values.put(key,val);ws();
        if(pos>=s.length())throw fail();
        char sep=s.charAt(pos++);
        if(sep=='}'){ws();if(pos!=s.length())throw fail();return values;}
        if(sep!=',')throw fail();
      }while(true);
    }
  }
  private static Scalar required(Map<String,Scalar> obj,String key,char type)throws IOException {
    Scalar v=obj.get(key);
    if(v==null || v.type!=type)throw new IOException("Отсутствует или имеет неверный тип поле: "+key);
    return v;
  }
  private static String str(Map<String,Scalar> obj,String key)throws IOException {
    return required(obj,key,'s').text;
  }
  private static long number(Map<String,Scalar> obj,String key,long min,long max)throws IOException {
    String v=required(obj,key,'n').text;
    if(!v.matches("-?(0|[1-9][0-9]*)"))throw new IOException("Некорректное число: "+key);
    try {long n=Long.parseLong(v);if(n>=min&&n<=max)return n;}
    catch(NumberFormatException ignored){}
    throw new IOException("Число вне допустимого диапазона: "+key);
  }
  private static String decode(byte[] bytes)throws IOException {
    try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
    catch(CharacterCodingException e){throw new IOException("Неверная кодировка UTF-8",e);}
  }
  private static final class Lines {
    final InputStream in;final int max;
    final byte[] buffer=new byte[8192];int pos,limit;
    Lines(InputStream in,int max){this.in=in;this.max=max;}
    String next()throws IOException {
      ByteArrayOutputStream out=new ByteArrayOutputStream();
      while(true){
        if(pos==limit){limit=in.read(buffer);pos=0;
          if(limit<0)return out.size()==0?null:decode(out.toByteArray());}
        int begin=pos;
        while(pos<limit&&buffer[pos]!='\n')pos++;
        int length=pos-begin;
        if(out.size()+length>max)throw new IOException("Слишком длинная JSONL-строка");
        out.write(buffer,begin,length);
        if(pos<limit){pos++;return decode(out.toByteArray());}
      }
    }
  }
  private static final class Media {
    final long id;final String path,sha;final long bytes;
    Media(long id,String path,String sha,long bytes){this.id=id;this.path=path;this.sha=sha;this.bytes=bytes;}
  }
  private static long[] stats(ZipInputStream zip,MessageDigest digest)throws IOException {
    byte[] buf=new byte[65536];long nbytes=0;int n;
    digest.reset();
    while((n=zip.read(buf))!=-1){nbytes+=n;digest.update(buf,0,n);}
    return new long[]{nbytes};
  }
  private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);
    for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
    return out.toString();}
  /** Participants are metadata, but malformed arrays must not pass an integrity report. */
  private static int readParticipants(ZipInputStream zip)throws IOException {
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
    while((n=zip.read(buf))!=-1){
      if(out.size()+n>MAX_PARTICIPANTS_BYTES)throw new IOException("Слишком много данных участников");
      out.write(buf,0,n);
    }
    Json json=new Json(decode(out.toByteArray()));json.want('[');json.ws();
    int count=0;
    if(json.pos<json.s.length() && json.s.charAt(json.pos)==']')json.pos++;
    else while(true){
      json.ws();
      if(json.pos>=json.s.length()||json.s.charAt(json.pos)!='{')throw new IOException("Неверный участник архива");
      json.value(0);count++;
      json.ws();if(json.pos>=json.s.length())throw new IOException("Обрезан массив участников");
      char sep=json.s.charAt(json.pos++);
      if(sep==']')break;
      if(sep!=',')throw new IOException("Некорректный список участников");
    }
    json.ws();if(json.pos!=json.s.length())throw new IOException("Лишние байты в participants.json");
    return count;
  }
  private static Map<String,Scalar> readManifest(ZipInputStream zip)throws IOException {
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
    while((n=zip.read(buf))!=-1){if(out.size()+n>MAX_MANIFEST_BYTES)throw new IOException("Манифест слишком большой");out.write(buf,0,n);}
    return new Json(decode(out.toByteArray())).fields();
  }
  private static String page(String dir,int p){return String.format(Locale.ROOT,dir+"/page-%06d.jsonl",p);}
  public static int verify(InputStream input,char[] password)throws Exception {
    ChunkedVault.Decrypt decrypted=new ChunkedVault.Decrypt(input,password);
    int entries=0,pages=0;long messages=0,media=0,previousId=Long.MAX_VALUE;
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(ZipInputStream zip=new ZipInputStream(decrypted)){
      ZipEntry e=zip.getNextEntry();
      while(e!=null&&e.getName().startsWith("messages/")){
        if(pages==999999)throw new IOException("Слишком много страниц");
        pages++;
        if(!e.getName().equals(page("messages",pages)))throw new IOException("Пропущена или неверно названа страница сообщений");
        HashSet<Long> pageIds=new HashSet<>();
        Lines rows=new Lines(zip,MAX_MESSAGE_LINE);String line;
        while((line=rows.next())!=null){
          Map<String,Scalar> fields=new Json(line).fields();
          long id=number(fields,"id",1,Long.MAX_VALUE);
          if(id>=previousId||!pageIds.add(id))throw new IOException("Повтор или неправильный порядок ID сообщения: "+id);
          if(pageIds.size()>MAX_PAGE_ROWS)throw new IOException("Слишком много сообщений на странице");
          previousId=id;messages++;
        }
        if(pageIds.isEmpty())throw new IOException("Пустая страница сообщений");
        entries++;zip.closeEntry();
        e=zip.getNextEntry();
        if(e==null||!e.getName().equals(page("media-index",pages)))throw new IOException("Нет индекса медиа для страницы "+pages);
        LinkedHashMap<String,Media> referenced=new LinkedHashMap<>();HashSet<String> usedFileRefs=new HashSet<>();
        rows=new Lines(zip,MAX_INDEX_LINE);
        while((line=rows.next())!=null){
          Map<String,Scalar> fields=new Json(line).fields();
          long msgId=number(fields,"message_id",1,Long.MAX_VALUE);
          long fileId=number(fields,"file_id",1,Integer.MAX_VALUE);
          long length=number(fields,"bytes",0,Long.MAX_VALUE);
          String path=str(fields,"path"),sha=str(fields,"sha256");
          if(!pageIds.contains(msgId))throw new IOException("Медиа ссылается на чужое сообщение: "+msgId);
          // One Telegram message can contain multiple full-resolution files
          // (paid-media carousel, animated chat photo or link-preview album).
          // Reject duplicate message/file pairs even if their extensions differ.
          if(!usedFileRefs.add(msgId+":"+fileId))
            throw new IOException("Повторное вложение в сообщении: "+msgId+"/"+fileId);
          if(!path.matches("media/"+msgId+"/"+fileId+"\\.[a-z0-9]{1,8}"))throw new IOException("Неверный путь медиа: "+path);
          if(!sha.matches("[a-f0-9]{64}"))throw new IOException("Некорректный SHA-256 медиа");
          if(referenced.put(path,new Media(msgId,path,sha,length))!=null)throw new IOException("Повторное имя медиа: "+path);
        }
        entries++;zip.closeEntry();
        for(Media expected:referenced.values()){
          e=zip.getNextEntry();
          if(e==null||!e.getName().equals(expected.path))throw new IOException("Отсутствует медиа: "+expected.path);
          long size=stats(zip,digest)[0];
          if(size!=expected.bytes||!hex(digest.digest()).equals(expected.sha))throw new IOException("Медиа не совпадает с индексом: "+expected.path);
          media++;entries++;zip.closeEntry();
        }
        e=zip.getNextEntry();
      }
      if(e==null||!"participants.json".equals(e.getName()))throw new IOException("Нет participants.json либо неожиданные записи");
      int participants=readParticipants(zip);entries++;zip.closeEntry();
      e=zip.getNextEntry();
      if(e==null||!"manifest.json".equals(e.getName()))throw new IOException("Манифест отсутствует или не последняя запись");
      Map<String,Scalar> manifest=readManifest(zip);entries++;zip.closeEntry();
      if(!"tg-vault-v1".equals(str(manifest,"schema")))throw new IOException("Неизвестная схема архива");
      if(!"true".equals(required(manifest,"history_end_reached",'b').text))throw new IOException("История не завершена");
      if(number(manifest,"media_missing",0,Long.MAX_VALUE)!=0)throw new IOException("В архиве пропущены медиа");
      if(number(manifest,"messages",0,Long.MAX_VALUE)!=messages)throw new IOException("Число сообщений не совпадает с манифестом");
      if(number(manifest,"media_downloaded",0,Long.MAX_VALUE)!=media)throw new IOException("Число медиа не совпадает с манифестом");
      if(manifest.containsKey("participants") &&
         number(manifest,"participants",0,Long.MAX_VALUE)!=participants)
        throw new IOException("Число участников не совпадает с манифестом");
      if(zip.getNextEntry()!=null)throw new IOException("Лишние записи после манифеста");
      byte[] buf=new byte[65536];
      while(decrypted.read(buf)!=-1) {/* authenticate final encrypted block */}
    }
    return entries;
  }
}
