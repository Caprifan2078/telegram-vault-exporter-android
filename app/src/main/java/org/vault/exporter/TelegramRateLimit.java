package org.vault.exporter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative treatment of Telegram's mandatory FLOOD_WAIT delays. */
public final class TelegramRateLimit {
  private static final Pattern FLOOD = Pattern.compile("^(?:FLOOD_WAIT|FLOOD_PREMIUM_WAIT)_([0-9]{1,10})$");
  private TelegramRateLimit() {}

  /** Returns required wait seconds, or -1 for unrecognized/non-rate-limit errors. */
  public static long waitSeconds(int code, String message) {
    if (code != 420 || message == null) return -1;
    Matcher matcher = FLOOD.matcher(message.trim());
    if (!matcher.matches()) return -1;
    try {
      long wait = Long.parseLong(matcher.group(1));
      return wait >= 0 && wait <= 2147483647L ? wait : -1;
    } catch (NumberFormatException ex) { return -1; }
  }
  public static final class Delay extends Exception {
    public final long waitSeconds;
    public Delay(long seconds) {
      super("Telegram установил ограничение запросов. Ожидание: " + seconds + " сек. Экспорт можно продолжить позже.");
      waitSeconds = seconds;
    }
  }
}
