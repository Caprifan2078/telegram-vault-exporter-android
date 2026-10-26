package org.vault.exporter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
/** In-process synthetic TDLib responses; does not load JNI or contact Telegram. */
public final class TdNative {
  static final BlockingQueue<String> inbound=new LinkedBlockingQueue<>();
  static final BlockingQueue<String> outbound=new LinkedBlockingQueue<>();
  static final AtomicInteger nextClient=new AtomicInteger(91);
  static boolean disableSensitiveLogging(){return true;}
  static int createClient(){return nextClient.getAndIncrement();}
  static void send(long client,String request){outbound.add(request);}
  static String receive(double seconds){
    try{return inbound.poll(Math.max(1,(long)(seconds*1000)), TimeUnit.MILLISECONDS);}
    catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
  }
  static void reply(long client,String id,String type){
    inbound.add(new JSONObject().put("@client_id",client).put("@extra",id).put("@type",type).toString());
  }
  static void flush(){inbound.clear();outbound.clear();}
}
