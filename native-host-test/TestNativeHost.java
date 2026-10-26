import org.vault.exporter.TdNative;
public final class TestNativeHost {
  private static void verify(String value) {
    TdNative.send(17, value);
    String actual=TdNative.receive(0.01);
    if(!value.equals(actual))
      throw new AssertionError("JNI UTF-8 roundtrip corrupted: expected="+value+" actual="+actual);
  }
  private static void invalid(String request,String expected){
    TdNative.send(17,request);
    String actual=TdNative.receive(0.01);
    if(!expected.equals(actual))throw new AssertionError(
      "Malformed UTF-8 was not safely decoded: "+request+" result="+actual);
  }
  public static void main(String[] args) {
    if (TdNative.createClient()!=17)throw new AssertionError("Incorrect client ID");
    verify("{\"@type\":\"getChat\",\"chat_id\":1234}");
    verify("{\"name\":\"Привет, мир\"}");
    verify("{\"title\":\"Чат 🐈💙🚀\"}");
    verify("{\"text\":\"\uD83D\uDE01\uD83E\uDDD1\u200D\uD83D\uDCBB\"}");
    invalid("__truncated_2__","\uFFFD\uFFFD");
    invalid("__truncated_3__","\uFFFD\uFFFD\uFFFD");
    invalid("__overlong__","\uFFFD\uFFFD");
    invalid("__surrogate__","\uFFFD\uFFFD\uFFFD");
    invalid("__above_max__","\uFFFD\uFFFD\uFFFD\uFFFD");
    System.out.println("PASS JNI native UTF-8: Cyrillic, emoji, truncated and invalid sequences");
  }
}
