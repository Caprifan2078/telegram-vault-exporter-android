import org.vault.exporter.ResumeStore;
import java.io.*;
import java.nio.file.*;

/** Independent process: hold a resumable checkpoint, then await forced termination. */
public final class HoldResumeLockChild {
  public static void main(String[] args) throws Exception {
    File directory=new File(args[0]);
    try(ResumeStore store=new ResumeStore(directory,888,new byte[32])) {
      store.checkpoint(new ResumeStore.State(888,0,0,0,0,false));
      Files.write(new File(directory,"child-ready.flag").toPath(),new byte[]{1});
      Thread.sleep(60000L);
    }
  }
}
