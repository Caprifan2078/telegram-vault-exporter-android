package org.vault.exporter;
import android.content.Context;
import java.util.Arrays;
/** Test-only 32-byte secret; no Android Keystore or production credentials. */
public final class SessionSecrets{
  public SessionSecrets(Context context){}
  public byte[] getOrCreate(){byte[] secret=new byte[32];Arrays.fill(secret,(byte)0x5c);return secret;}
}
