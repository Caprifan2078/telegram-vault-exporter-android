package org.vault.exporter;

/** JVM-only constants for the offline synthetic ZIP bridge test.
 * The Android build uses the real LocalMockTdlib implementation instead. */
public final class LocalMockTdlib {
  private LocalMockTdlib() {}
  public static final int MESSAGE_COUNT = 60;
  public static final int MEDIA_COUNT = 7;
}
