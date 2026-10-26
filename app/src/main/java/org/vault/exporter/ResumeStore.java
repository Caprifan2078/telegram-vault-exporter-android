package org.vault.exporter;

import java.io.*;
import java.nio.file.Files;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * App-private, encrypted, crash-recoverable staging area. No plaintext messages or media are
 * written to this store. Existing completed pages and media files are reusable after a restart.
 * The store's key is domain-separated from TDLib's database secret using HMAC-SHA256.
 * This code uses standard JVM APIs and can be tested without Android or a Telegram account.
 */
public final class ResumeStore implements Closeable {
  private static final byte[] MAGIC = "TVS1".getBytes(StandardCharsets.US_ASCII);
  private static final int MAX_PAGE_BYTES = 16 * 1024 * 1024;
  private static final long MAX_LEGACY_TVS1_BYTES = 8L * 1024 * 1024;
  private final File root;
  private final byte[] key;
  private final long chatId;
  // An OS-backed lock prevents two Android Activity/process instances from
  // writing the same per-chat checkpoint concurrently. Keep it OUTSIDE the
  // erasable chat directory so erase() cannot bypass an active lock.
  private static final Set<String> JVM_LOCKS=ConcurrentHashMap.newKeySet();
  private final String localLockKey;
  private boolean closed;
  private final RandomAccessFile lockFile;
  private final FileLock exclusiveLock;
  public static final class State {
    public final long chatId, cursor;
    public final int pages, messages, media;
    public final boolean historyFinished;
    public State(long chatId, long cursor, int pages, int messages, int media, boolean done) {
      this.chatId=chatId;this.cursor=cursor;this.pages=pages;this.messages=messages;this.media=media;this.historyFinished=done;
    }
  }
  public static final class Media {
    public final String sha256;
    public final long bytes;
    public Media(String sha, long bytes) { sha256=sha;this.bytes=bytes; }
  }
  public ResumeStore(File privateDir, long chatId, byte[] sessionSecret) throws Exception {
    if (chatId == 0 || sessionSecret == null || sessionSecret.length != 32) throw new IllegalArgumentException("Неправильный ключ или чат");
    this.chatId=chatId;
    File privateRoot=privateDir.getCanonicalFile();
    if(!privateRoot.isDirectory() && !privateRoot.mkdirs())
      throw new IOException("Не удалось создать закрытый каталог экспорта");
    // The lock file is stable across app restarts. A leftover file does not
    // block the next process: the kernel releases FileLock on process death.
    File locks=new File(privateRoot,".locks");
    if(!locks.isDirectory() && !locks.mkdirs())throw new IOException("Нет каталога блокировок");
    File lockPath=new File(locks,"chat-"+chatId+".lock").getCanonicalFile();
    String lockKey=lockPath.getPath();
    // POSIX fcntl() locks may be released when *any* FD for the same inode is
    // closed by this process. Reject duplicate in-process writers BEFORE
    // opening a second handle, otherwise even a failed tryLock() can weaken
    // the original process's cross-process exclusion on some systems.
    if(!JVM_LOCKS.add(lockKey))throw new IOException("Экспорт этого чата уже выполняется");
    RandomAccessFile handle=null;
    FileLock held=null;
    try {
      handle=new RandomAccessFile(lockPath,"rw");
      try {held=handle.getChannel().tryLock();}
      catch(OverlappingFileLockException busy){throw new IOException("Экспорт этого чата уже выполняется",busy);}
      if(held==null)throw new IOException("Экспорт этого чата уже выполняется");
      this.root = new File(privateRoot,"chat-"+chatId).getCanonicalFile();
      if (!root.isDirectory() && !root.mkdirs())
        throw new IOException("Не удалось создать закрытый каталог");
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(sessionSecret,"HmacSHA256"));
      this.key=mac.doFinal(("TelegramVault resume store v1|"+chatId).getBytes(StandardCharsets.UTF_8));
    } catch(Exception e) {
      if(held!=null)try{held.release();}catch(IOException ignored){}
      if(handle!=null)try{handle.close();}catch(IOException ignored){}
      JVM_LOCKS.remove(lockKey);
      throw e;
    }
    this.localLockKey=lockKey;
    this.lockFile=handle;
    this.exclusiveLock=held;
  }
  public State state() throws Exception {
    File state = path("checkpoint.enc");
    if (!state.exists()) return new State(chatId,0,0,0,0,false);
    try(DataInputStream in=new DataInputStream(open("checkpoint.enc"))) {
      if (in.readInt()!=0x52535431) throw new IOException("Неизвестный checkpoint");
      long cid=in.readLong(), cursor=in.readLong();
      int p=in.readInt(), m=in.readInt(), f=in.readInt(); boolean done=in.readBoolean();
      if(cid!=chatId || p<0 || m<0 || f<0 || (p>0 && cursor<=0)) throw new IOException("Повреждён checkpoint");
      if(in.read()!=-1) throw new IOException("Лишние байты checkpoint");
      return new State(cid,cursor,p,m,f,done);
    }
  }
  public void checkpoint(State state) throws Exception {
    if (state.chatId != chatId) throw new IllegalArgumentException("Другой чат");
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    DataOutputStream data=new DataOutputStream(out);
    data.writeInt(0x52535431);data.writeLong(state.chatId);data.writeLong(state.cursor);
    data.writeInt(state.pages);data.writeInt(state.messages);data.writeInt(state.media);
    data.writeBoolean(state.historyFinished);data.close();
    put("checkpoint.enc",new ByteArrayInputStream(out.toByteArray()));
  }
  public void putPage(int page, byte[] messages, byte[] mediaIndex) throws Exception {
    if (page<1 || messages.length>MAX_PAGE_BYTES || mediaIndex.length>MAX_PAGE_BYTES)
      throw new IOException("Слишком большая страница сообщений");
    put(String.format(Locale.ROOT,"pages/%06d.jsonl.enc",page),new ByteArrayInputStream(messages));
    put(String.format(Locale.ROOT,"indexes/%06d.jsonl.enc",page),new ByteArrayInputStream(mediaIndex));
  }
  public Media saveMedia(long messageId,int fileId,File plaintext) throws Exception {
    String name=mediaName(messageId,fileId);
    if(exists(name)) return fingerprint(name); // authenticates the staged file, avoids re-download
    if(!plaintext.isFile()) throw new FileNotFoundException("Медиафайл отсутствует");
    if(root.getUsableSpace()<plaintext.length()+8L*1024*1024)
      throw new IOException("Недостаточно места для безопасного сохранения медиа");
    return put(name,new BufferedInputStream(new FileInputStream(plaintext)));
  }
  /** Never resume with a media checkpoint whose plaintext cache cleanup failed. */
  public void dropMedia(long messageId,int fileId) throws IOException {
    File saved=path(mediaName(messageId,fileId));
    if(saved.exists() && !saved.delete())
      throw new IOException("Unable to discard unsafe encrypted media checkpoint");
  }
  public boolean hasMedia(long messageId,int fileId) { return exists(mediaName(messageId,fileId)); }
  public Media mediaFingerprint(long messageId,int fileId) throws Exception { return fingerprint(mediaName(messageId,fileId)); }
  public InputStream openMedia(long messageId,int fileId) throws Exception { return open(mediaName(messageId,fileId)); }
  public InputStream openPage(int page) throws Exception { return open(String.format(Locale.ROOT,"pages/%06d.jsonl.enc",page)); }
  public InputStream openIndex(int page) throws Exception { return open(String.format(Locale.ROOT,"indexes/%06d.jsonl.enc",page)); }
  public boolean hasPage(int page) { return exists(String.format(Locale.ROOT,"pages/%06d.jsonl.enc",page)) && exists(String.format(Locale.ROOT,"indexes/%06d.jsonl.enc",page)); }
  private String mediaName(long messageId,int fileId) {
    if(messageId<=0 || fileId<=0) throw new IllegalArgumentException("Invalid media ID");
    return "media/"+messageId+"-"+fileId+".enc";
  }
  private boolean exists(String name) { return path(name).isFile(); }
  private File path(String name) {
    if(name.startsWith("/") || name.contains("..") || name.contains("\\") || name.indexOf('\0')!=-1)
      throw new IllegalArgumentException("Unsafe staging path");
    return new File(root,name);
  }
  private byte[] aad(String name) { return ("TVS1|"+chatId+"|"+name).getBytes(StandardCharsets.UTF_8); }
  private Media put(String name,InputStream input) throws Exception {
    File dest=path(name), parent=dest.getParentFile();
    if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Не удалось создать папку checkpoint");
    File temp=File.createTempFile(".pending-",".tmp",parent);

    MessageDigest md=MessageDigest.getInstance("SHA-256"); long size=0;
    try(InputStream src=input; FileOutputStream file=new FileOutputStream(temp)) {
      try(OutputStream enc=StagingCipher.encrypt(file,key,chatId,name)) {
        byte[] buf=new byte[65536];int n;
        while((n=src.read(buf))!=-1) {enc.write(buf,0,n);md.update(buf,0,n);size+=n;}
      }
      try(FileOutputStream fsync=new FileOutputStream(temp,true)) {fsync.getFD().sync();}
      Files.move(temp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    } catch(Exception e) {temp.delete(); throw e;}
    return new Media(hex(md.digest()),size);
  }
  private InputStream open(String name) throws Exception {
    File source=path(name);
    FileInputStream file=new FileInputStream(source);
    try {
      byte[] magic=new byte[4];
      if(file.read(magic)!=4)throw new EOFException("Обрезанный staging-файл");
      file.close();
      if(Arrays.equals(magic,"TVS2".getBytes(StandardCharsets.US_ASCII)))
        return StagingCipher.decrypt(new FileInputStream(source),key,chatId,name);
      if(!Arrays.equals(magic,MAGIC))throw new IOException("Неизвестный формат staging");
      // Legacy TVS1 used whole-file GCM with CipherInputStream, which can allocate
      // memory proportional to media size. Fail closed rather than OOM on large old files.
      if(source.length()>MAX_LEGACY_TVS1_BYTES)
        throw new IOException("Старый staging TVS1 слишком велик для безопасного чтения; сбрось прогресс и загрузи заново");
      FileInputStream legacy=new FileInputStream(source);
      try {
        byte[] ignored=new byte[4];legacy.read(ignored);
        byte[] nonce=new byte[12];new DataInputStream(legacy).readFully(nonce);
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        aes.updateAAD(aad(name));
        return new CipherInputStream(legacy,aes);
      } catch(Exception e) {legacy.close();throw e;}
    } catch(Exception e) {try{file.close();}catch(IOException ignored){}throw e;}
  }
  private Media fingerprint(String name) throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;
    try(InputStream input=open(name)) {
      byte[] buf=new byte[65536];int n;
      while((n=input.read(buf))!=-1) {digest.update(buf,0,n);size+=n;}
    }
    return new Media(hex(digest.digest()),size);
  }
  private static String hex(byte[] b) {
    StringBuilder s=new StringBuilder(b.length*2);
    for(byte n:b) s.append(String.format(Locale.ROOT,"%02x",n&255));
    return s.toString();
  }
  public void erase() throws IOException { if (!deleteChildren(root)) throw new IOException("Не удалось удалить временные данные экспорта"); }
  private static boolean deleteChildren(File dir) {
    File[] list=dir.listFiles(); boolean ok=true;
    if(list!=null) for(File f:list) {if(f.isDirectory())ok &=deleteChildren(f); else ok &=f.delete();}
    return dir.delete() && ok;
  }
  @Override public synchronized void close() {
    if(closed)return;
    closed=true;
    Arrays.fill(key,(byte)0);
    try{exclusiveLock.release();}catch(IOException ignored){}
    try{lockFile.close();}catch(IOException ignored){}
    JVM_LOCKS.remove(localLockKey);
  }
}
