package android.content;
import java.io.File;
public class Context {
  private final File root;
  public Context(File root){this.root=root;}
  public Context getApplicationContext(){return this;}
  public File getFilesDir(){return root;}
}
