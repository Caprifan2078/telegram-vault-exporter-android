package org.vault.exporter;

/** JNI wrapper around the official TDLib JSON C interface. Does not contain an MTProto implementation. */
public final class TdNative {
  static { System.loadLibrary("vaultbridge"); }
  public static native long createClient();
  public static native void send(long client, String json);
  public static native String receive(double seconds);
  private TdNative() {}
}
