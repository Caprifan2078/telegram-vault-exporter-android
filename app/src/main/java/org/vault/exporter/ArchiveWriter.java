package org.vault.exporter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.*;

/** Export structure: manifest.json, messages/page-NNNNN.jsonl, media-index/page-NNNNN.jsonl, media/** */
public final class ArchiveWriter implements Closeable {
  private final ZipOutputStream zip;
  public ArchiveWriter(OutputStream target, char[] passphrase) throws Exception {
    zip = new ZipOutputStream(new ChunkedVault.Encrypt(target, passphrase), StandardCharsets.UTF_8);
    zip.setLevel(Deflater.DEFAULT_COMPRESSION);
  }
  public void writeBytes(String name, byte[] content) throws IOException {
    validate(name);
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }
  /** Stream validated plaintext directly from encrypted private staging into the password archive. */
  public String writeStream(String name, InputStream input) throws Exception {
    validate(name);
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    zip.putNextEntry(new ZipEntry(name));
    try (InputStream in = input) {
      byte[] buf=new byte[65536]; int n;
      while((n=in.read(buf))!=-1) {hash.update(buf,0,n);zip.write(buf,0,n);}
    }
    zip.closeEntry();
    StringBuilder hex=new StringBuilder();
    for(byte b: hash.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
    return hex.toString();
  }
  /** Videos, OGG, JPEG, WEBP and most documents are already compressed.
   * ZIP level 0 preserves streaming/ZIP64 support without recompressing gigabytes
   * of incompressible data and burning phone CPU/battery. Text still uses level 6. */
  public String writeMediaStream(String name, InputStream encryptedStagedInput)throws Exception {
    zip.setLevel(Deflater.NO_COMPRESSION);
    try {return writeStream(name,encryptedStagedInput);}
    finally {zip.setLevel(Deflater.DEFAULT_COMPRESSION);}
  }
  public String writeMedia(String name, File input) throws Exception {
    validate(name);
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    zip.putNextEntry(new ZipEntry(name));
    try (InputStream in = new BufferedInputStream(new FileInputStream(input))) {
      byte[] buf = new byte[65536]; int n;
      while ((n = in.read(buf)) != -1) {
        hash.update(buf, 0, n);
        zip.write(buf, 0, n);
      }
    }
    zip.closeEntry();
    StringBuilder hex = new StringBuilder();
    for (byte b : hash.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return hex.toString();
  }
  public static void validate(String name) throws IOException {
    if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains("..") || name.contains(":") || name.indexOf('\0') >= 0)
      throw new IOException("Недопустимый путь в архиве");
  }
  @Override public void close() throws IOException { zip.close(); }
}
