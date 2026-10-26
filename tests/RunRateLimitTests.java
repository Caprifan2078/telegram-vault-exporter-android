import org.vault.exporter.TelegramRateLimit;
public class RunRateLimitTests {
  private static void eq(long x,long y){ if(x!=y) throw new AssertionError(x+" != "+y); }
  public static void main(String[] args) {
    eq(TelegramRateLimit.waitSeconds(420,"FLOOD_WAIT_23"),23);
    eq(TelegramRateLimit.waitSeconds(420,"FLOOD_PREMIUM_WAIT_61"),61);
    eq(TelegramRateLimit.waitSeconds(400,"FLOOD_WAIT_30"),-1);
    eq(TelegramRateLimit.waitSeconds(420,"FLOOD_WAIT_foo"),-1);
    eq(TelegramRateLimit.waitSeconds(420,"FLOOD_WAIT_9999999999999"),-1);
    eq(TelegramRateLimit.waitSeconds(420,"SLOWMODE_WAIT_1"),-1);
    eq(TelegramRateLimit.waitSeconds(420,null),-1);
    System.out.println("PASS: Telegram rate-limit parser");
  }
}
