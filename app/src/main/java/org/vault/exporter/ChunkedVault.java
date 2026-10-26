package org.vault.exporter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** TVA1: streaming, authenticated, bounded-memory, password-encrypted container. */
public final class ChunkedVault {
  private ChunkedVault() {}
  public static final int BLOCK = 256 * 1024;
  public static final int ITERATIONS = 400000;
  private static final byte[] MAGIC = "TVA1".getBytes(StandardCharsets.US_ASCII);
  public static byte[] key(char[] password, byte[] salt, int iterations) throws Exception {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
    try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
    finally { spec.clearPassword(); }
  }
  private static byte[] nonce(byte[] prefix, int seq) {
    return ByteBuffer.allocate(12).put(prefix).putInt(seq).array();
  }
  private static byte[] aad(byte[] header, int seq, boolean last) {
    return ByteBuffer.allocate(header.length + 5).put(header).putInt(seq).put((byte)(last ? 1 : 0)).array();
  }
  private static byte[] crypt(int mode, byte[] input, byte[] k, byte[] nonce, byte[] aad) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(mode, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, nonce));
    c.updateAAD(aad);
    return c.doFinal(input);
  }
  public static final class Encrypt extends OutputStream {
    private final DataOutputStream out;
    private final byte[] key, prefix, header, block = new byte[BLOCK];
    private int filled, seq;
    private boolean closed;
    public Encrypt(OutputStream output, char[] passphrase) throws Exception {
      out = new DataOutputStream(output);
      byte[] salt = new byte[16]; prefix = new byte[8];
      SecureRandom rng = new SecureRandom(); rng.nextBytes(salt); rng.nextBytes(prefix);
      header = ByteBuffer.allocate(4 + 16 + 4 + 4 + 8)
          .put(MAGIC).put(salt).putInt(ITERATIONS).putInt(BLOCK).put(prefix).array();
      key = key(passphrase, salt, ITERATIONS);
      out.write(header);
    }
    @Override public void write(int b) throws IOException {
      block[filled++] = (byte)b;
      if (filled == BLOCK) flushBlock(false);
    }
    @Override public void write(byte[] b, int off, int len) throws IOException {
      if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
      while (len > 0) {
        int n = Math.min(len, BLOCK - filled);
        System.arraycopy(b, off, block, filled, n);
        filled += n; off += n; len -= n;
        if (filled == BLOCK) flushBlock(false);
      }
    }
    private void flushBlock(boolean last) throws IOException {
      try {
        if (seq == Integer.MAX_VALUE) throw new IOException("Слишком большой архив");
        int n = last ? 0 : filled;
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, Arrays.copyOf(block, n), key, nonce(prefix, seq), aad(header, seq, last));
        out.writeInt(n);
        out.write(ciphertext);
        seq++; filled = 0;
      } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Ошибка шифрования", e); }
    }
    @Override public void flush() throws IOException { out.flush(); }
    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      try {
        if (filled > 0) flushBlock(false);
        flushBlock(true);
        out.flush();
      } finally {
        Arrays.fill(key, (byte)0);
        Arrays.fill(block, (byte)0);
        out.close();
      }
    }
  }
  public static final class Decrypt extends InputStream {
    private final DataInputStream in;
    private final byte[] key, prefix, header;
    private byte[] plain = new byte[0];
    private int cursor, seq;
    private boolean finalSeen, closed;
    public Decrypt(InputStream input, char[] password) throws Exception {
      in = new DataInputStream(input);
      header = new byte[36]; in.readFully(header);
      ByteBuffer b = ByteBuffer.wrap(header);
      byte[] magic = new byte[4]; b.get(magic);
      if (!Arrays.equals(magic, MAGIC)) throw new IOException("Неверный формат TVA1");
      byte[] salt = new byte[16]; b.get(salt);
      int iter = b.getInt(), size = b.getInt();
      if (iter != ITERATIONS || size != BLOCK) throw new IOException("Неподдерживаемая версия шифрования");
      prefix = new byte[8]; b.get(prefix);
      key = key(password, salt, iter);
    }
    private boolean next() throws IOException {
      if (finalSeen) return false;
      try {
        int n = in.readInt();
        if (n < 0 || n > BLOCK || seq == Integer.MAX_VALUE) throw new IOException("Некорректный блок архива");
        byte[] data = new byte[n + 16]; in.readFully(data);
        boolean last = (n == 0);
        plain = crypt(Cipher.DECRYPT_MODE, data, key, nonce(prefix, seq), aad(header, seq, last));
        seq++; cursor = 0;
        if (last) {
          finalSeen = true;
          if (in.read() != -1) throw new IOException("Лишние данные после конца архива");
          return false;
        }
        return true;
      } catch (IOException e) { throw e; }
      catch (Exception e) { throw new IOException("Неверный пароль или архив повреждён", e); }
    }
    @Override public int read() throws IOException {
      if (cursor >= plain.length && !next()) return -1;
      return plain[cursor++] & 255;
    }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
      if (len == 0) return 0;
      if (cursor >= plain.length && !next()) return -1;
      int n = Math.min(len, plain.length - cursor);
      System.arraycopy(plain, cursor, b, off, n); cursor += n;
      return n;
    }
    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      Arrays.fill(key, (byte)0);
      Arrays.fill(plain, (byte)0);
      in.close();
    }
  }
}
