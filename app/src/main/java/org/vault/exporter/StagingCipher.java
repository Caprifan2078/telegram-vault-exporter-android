package org.vault.exporter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** TVS2: authenticated fixed-size streaming staging with a mandatory authenticated end marker.
 * Do not use CipherInputStream(AES/GCM) over an entire media file: some providers buffer it all.
 */
final class StagingCipher {
  private static final byte[] MAGIC="TVS2".getBytes(StandardCharsets.US_ASCII);
  private static final int BLOCK=256*1024;
  private StagingCipher() {}

  private static byte[] nonce(byte[] prefix, int seq) {
    return ByteBuffer.allocate(12).put(prefix).putInt(seq).array();
  }
  private static byte[] aad(byte[] name, int seq, boolean terminal) {
    return ByteBuffer.allocate(name.length+5).put(name).putInt(seq).put((byte)(terminal?1:0)).array();
  }
  private static byte[] crypt(int mode,byte[] data,byte[] key,byte[] nonce,byte[] aad) throws Exception {
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
    cipher.updateAAD(aad);
    return cipher.doFinal(data);
  }
  private static byte[] context(long chatId,String path) {
    return ("TVS2|"+chatId+"|"+path).getBytes(StandardCharsets.UTF_8);
  }
  static OutputStream encrypt(OutputStream target,byte[] key,long chatId,String path) throws IOException {
    return new Writer(target,key,context(chatId,path));
  }
  static InputStream decrypt(InputStream source,byte[] key,long chatId,String path) throws IOException {
    return new Reader(source,key,context(chatId,path));
  }
  private static final class Writer extends OutputStream {
    private final DataOutputStream out;
    private final byte[] key, context, prefix=new byte[8], block=new byte[BLOCK];
    private int filled,seq;
    private boolean closed;
    Writer(OutputStream stream,byte[] key,byte[] context) throws IOException {
      this.out=new DataOutputStream(stream);this.key=key.clone();this.context=context;
      new SecureRandom().nextBytes(prefix);out.write(MAGIC);out.write(prefix);
    }
    private void checkOpen() throws IOException {if(closed)throw new IOException("Staging writer closed");}
    @Override public void write(int n) throws IOException {
      checkOpen();block[filled++]=(byte)n;if(filled==BLOCK)send(false);
    }
    @Override public void write(byte[] b,int off,int len) throws IOException {
      checkOpen();if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();
      while(len>0){int n=Math.min(len,BLOCK-filled);System.arraycopy(b,off,block,filled,n);
        off+=n;len-=n;filled+=n;if(filled==BLOCK)send(false);}
    }
    private void send(boolean terminal) throws IOException {
      if(seq==Integer.MAX_VALUE)throw new IOException("Staging exceeds sequence limit");
      int n=terminal?0:filled;
      try {
        byte[] crypted=crypt(Cipher.ENCRYPT_MODE,Arrays.copyOf(block,n),key,
           nonce(prefix,seq),aad(context,seq,terminal));
        out.writeInt(n);out.write(crypted);seq++;filled=0;
      }catch(Exception ex){throw new IOException("Staging encryption failed",ex);}
    }
    @Override public void flush() throws IOException {out.flush();}
    @Override public void close() throws IOException {
      if(closed)return;closed=true;
      try {if(filled>0)send(false);send(true);out.flush();}
      finally {Arrays.fill(key,(byte)0);Arrays.fill(block,(byte)0);out.close();}
    }
  }
  private static final class Reader extends InputStream {
    private final DataInputStream in;
    private final byte[] key,context,prefix=new byte[8];
    private byte[] plain=new byte[0];
    private int offset,seq;
    private boolean ended,closed;
    Reader(InputStream source,byte[] key,byte[] context) throws IOException {
      this.in=new DataInputStream(source);this.key=key.clone();this.context=context;
      try {byte[] header=new byte[4];in.readFully(header);
        if(!Arrays.equals(header,MAGIC))throw new IOException("Wrong TVS2 marker");
        in.readFully(prefix);
      }catch(IOException ex){in.close();throw ex;}
    }
    private boolean next() throws IOException {
      if(ended)return false;
      if(seq==Integer.MAX_VALUE)throw new IOException("Staging sequence overflow");
      int n;
      try{n=in.readInt();}catch(EOFException ex){throw new EOFException("Missing authenticated TVS2 end marker");}
      if(n<0||n>BLOCK)throw new IOException("Invalid TVS2 block length");
      byte[] ciphertext=new byte[n+16];in.readFully(ciphertext);
      boolean terminal=n==0;
      try {plain=crypt(Cipher.DECRYPT_MODE,ciphertext,key,nonce(prefix,seq),aad(context,seq,terminal));}
      catch(Exception ex){throw new IOException("TVS2 authentication failed",ex);}
      seq++;offset=0;
      if(terminal){ended=true;if(in.read()!=-1)throw new IOException("Extra bytes after TVS2 end marker");return false;}
      if(plain.length!=n)throw new IOException("TVS2 payload length mismatch");
      return true;
    }
    @Override public int read() throws IOException {
      if(closed)throw new IOException("Staging reader closed");
      if(offset>=plain.length&&!next())return -1;
      return plain[offset++]&255;
    }
    @Override public int read(byte[] b,int off,int len) throws IOException {
      if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();
      if(closed)throw new IOException("Staging reader closed");
      if(len==0)return 0;
      if(offset>=plain.length&&!next())return -1;
      int take=Math.min(len,plain.length-offset);System.arraycopy(plain,offset,b,off,take);offset+=take;
      return take;
    }
    @Override public void close() throws IOException {
      if(closed)return;closed=true;
      Arrays.fill(key,(byte)0);Arrays.fill(plain,(byte)0);in.close();
    }
  }
}
