import org.vault.exporter.ResumeStore;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Functional regression: two exports must not mutate one chat at once. */
public final class RunExclusiveResumeLockTests {
  static void ok(boolean x,String message){if(!x)throw new AssertionError(message);}
  static int child(File dir)throws Exception {
    String javaExecutable=System.getProperty("java.home")+File.separator+"bin"+File.separator+"java";
    Process p=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("java.class.path"),
      "TryResumeLockChild",dir.getAbsolutePath()).redirectErrorStream(true).start();
    if(!p.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){
      p.destroyForcibly();throw new AssertionError("Cross-process lock check hung");
    }
    String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
    if(p.exitValue()!=0 && p.exitValue()!=42)
      throw new AssertionError("Unexpected child failure: "+output);
    return p.exitValue();
  }
  public static void main(String[] args)throws Exception {
    File base=Files.createTempDirectory("vault-exclusive-export-").toFile();
    byte[] secret=new byte[32];
    // Two different chat IDs may be independently staged.
    try(ResumeStore a=new ResumeStore(base,123,secret);
        ResumeStore b=new ResumeStore(base,456,secret)){
      a.putPage(1,"hello\n".getBytes("UTF-8"),new byte[0]);
      boolean rejected=false;
      try(ResumeStore impossible=new ResumeStore(base,123,secret)){}
      catch(IOException expected){rejected=expected.getMessage().contains("уже выполняется");}
      ok(rejected,"Second writer must be rejected for the same chat");
      // Deleting the chat staging directory must not release or bypass the lock.
      a.erase();
      rejected=false;
      try(ResumeStore impossible=new ResumeStore(base,123,secret)){}
      catch(IOException expected){rejected=true;}
      ok(rejected,"Erase must not bypass exclusive lock");
      ok(child(base)==42,"Second process must be denied while lock held");
      ok(b.state().chatId==456,"Other chat must remain independent");
    }
    // Stale lock files after process death/restart must not block new attempts.
    ok(child(base)==0,"Independent process must acquire after prior owner exits");
    try(ResumeStore recovered=new ResumeStore(base,123,secret)) {
      ok(recovered.state().chatId==123,"Released lock must permit resume");
      recovered.checkpoint(new ResumeStore.State(123,0,0,0,0,false));
    }
    // A late duplicate close of an already released store must never remove a
    // newer session's local reservation for the same lock file.
    ResumeStore old=new ResumeStore(base,123,secret);
    old.close();
    try(ResumeStore current=new ResumeStore(base,123,secret)) {
      old.close();
      boolean blocked=false;
      try(ResumeStore duplicate=new ResumeStore(base,123,secret)){}
      catch(IOException e){blocked=true;}
      ok(blocked,"Double close must not remove a newer owner's reservation");
      ok(child(base)==42,"New owner's cross-process lock must remain valid");
    }
    // Simulate a force-killed Android-style process: kernel must release the
    // native lock and the last completed encrypted checkpoint must survive.
    String javaExe=System.getProperty("java.home")+File.separator+"bin"+File.separator+"java";
    Process victim=new ProcessBuilder(javaExe,"-cp",System.getProperty("java.class.path"),
      "HoldResumeLockChild",base.getAbsolutePath()).redirectErrorStream(true).start();
    File flag=new File(base,"child-ready.flag");
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while(!flag.exists() && victim.isAlive() && System.nanoTime()<deadline)
      Thread.sleep(25);
    boolean acquired=flag.exists();
    if(!acquired) { victim.destroyForcibly();throw new AssertionError("Child never acquired crash-test lock"); }
    // Reject a second exporter while victim is alive.
    boolean denied=false;
    try(ResumeStore wrong=new ResumeStore(base,888,secret)){}
    catch(IOException expected){denied=true;}
    ok(denied,"Crash-test lock must exclude the parent before process death");
    victim.destroyForcibly();
    ok(victim.waitFor(10,java.util.concurrent.TimeUnit.SECONDS),"Timed out killing child");
    try(ResumeStore recovery=new ResumeStore(base,888,secret)){
      ok(recovery.state().chatId==888,"Checkpoint unreadable after force-kill");
      ok(!recovery.state().historyFinished,"Incomplete checkpoint must remain incomplete");
    }
    flag.delete();
    System.out.println("PASS: per-chat lock, cross-process exclusion, crash recovery after SIGKILL, reopening and double-close safety");
  }
}
