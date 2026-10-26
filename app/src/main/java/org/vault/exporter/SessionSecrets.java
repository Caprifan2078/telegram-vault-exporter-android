package org.vault.exporter;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Wraps TDLib database secret using a nonexportable Android Keystore key. */
public final class SessionSecrets {
  private static final String ALIAS = "tg_vault_database_wrap_v1";
  private static final String PREF = "tdlib_session";
  private final Context ctx;
  public SessionSecrets(Context context) { ctx = context.getApplicationContext(); }
  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
    if (!store.containsAlias(ALIAS)) {
      KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
      gen.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS, null);
  }
  public byte[] getOrCreate() throws Exception {
    SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    String saved = prefs.getString("wrapped", null);
    if (saved != null) {
      byte[] ciphertext = Base64.decode(saved, Base64.NO_WRAP);
      byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      return cipher.doFinal(ciphertext);
    }
    byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
    boolean ok = prefs.edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
        .putString("wrapped", Base64.encodeToString(cipher.doFinal(secret), Base64.NO_WRAP)).commit();
    if (!ok) throw new IllegalStateException("Не удалось сохранить ключ сессии");
    return secret;
  }
  public void discard() {
    if(!ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().clear().commit())
      throw new IllegalStateException("Не удалось удалить секрет сеанса");
    try {
      KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
      if(store.containsAlias(ALIAS))store.deleteEntry(ALIAS);
    } catch(Exception e){throw new IllegalStateException("Не удалось удалить ключ из Android Keystore",e);}
  }
}
