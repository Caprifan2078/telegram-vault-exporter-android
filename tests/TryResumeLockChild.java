import org.vault.exporter.ResumeStore;
import java.io.*;

/** Separate process fixture to verify that locks work across JVM processes. */
public final class TryResumeLockChild {
  public static void main(String[] args) throws Exception {
    try(ResumeStore ignored=new ResumeStore(new File(args[0]),123,new byte[32])) {
      System.out.println("LOCK_ACQUIRED");
    } catch(IOException expected) {
      System.out.println("LOCK_DENIED");
      System.exit(42);
    }
  }
}
