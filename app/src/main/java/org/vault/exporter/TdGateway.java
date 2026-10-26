package org.vault.exporter;

import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class TdGateway {
  // Read-only export API surface: auth and local cache cleanup are the only
  // intentional side-effecting exceptions. No message/chat mutations.
  private static final Set<String> ALLOWED_REQUESTS = java.util.Collections.unmodifiableSet(
      new java.util.HashSet<>(java.util.Arrays.asList(
    "getAuthorizationState", "setTdlibParameters", "setAuthenticationPhoneNumber",
    "setAuthenticationEmailAddress", "checkAuthenticationEmailCode",
    "checkAuthenticationCode", "checkAuthenticationPassword", "logOut",
    "getChats", "loadChats", "getChat", "getChatHistory", "getUser",
    "downloadFile", "cancelDownloadFile", "deleteFile", "getFile", "registerUser"
      )));
  public interface Listener { void onUpdate(JSONObject event); }
  private final long client;
  private final ConcurrentHashMap<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();
  private final Listener listener;
  // Registration is deliberately unavailable in production and offline clients.
  private final boolean allowTestRegistration;
  private final Thread reader;
  private volatile boolean running = true;

  public TdGateway(Listener callback) { this(callback, false); }
  public TdGateway(Listener callback, boolean allowTestRegistration) {
    this.listener = callback;
    this.allowTestRegistration = allowTestRegistration;
    if (!TdNative.disableSensitiveLogging())
      throw new IllegalStateException("Не удалось отключить журналы TDLib: сетевое подключение запрещено");
    client = TdNative.createClient();
    if (client == 0) throw new IllegalStateException("TDLib не включена в APK: нет официальной libtdjson.so");
    reader = new Thread(() -> {
      while (running) {
        String response = TdNative.receive(0.5);
        if (response == null) continue;
        try {
          JSONObject obj = new JSONObject(response);
          if (obj.optLong("@client_id", client) != client) continue;
          String extra = obj.optString("@extra", "");
          if (!extra.isEmpty()) {
            CompletableFuture<JSONObject> promise = pending.remove(extra);
            if (promise != null) { promise.complete(obj); continue; }
          }
          if (obj.optString("@type").startsWith("update")) listener.onUpdate(obj);
        } catch (Exception ignored) { /* Don't log private Telegram content. */ }
      }
    }, "tdlib-event-pump");
    reader.setDaemon(true);
    reader.start();
  }
  public CompletableFuture<JSONObject> call(JSONObject request) {
    if(!running)throw new IllegalStateException("TDLib client is closed");
    // Never log private request data. Reject unknown/write commands before JNI.
    String method = request == null ? "" : request.optString("@type", "");
    if (!ALLOWED_REQUESTS.contains(method) || ("registerUser".equals(method) && !allowTestRegistration))
      throw new SecurityException("Unauthorized Telegram RPC blocked by read-only export policy");
    // Fail-closed: this QA APK must NEVER submit the owner's real phone number,
    // even to a test DC. Official shared test numbers are the only permitted ones.
    if (allowTestRegistration && "setAuthenticationPhoneNumber".equals(method)) {
      String phone=request.optString("phone_number","");
      if (!phone.matches("\\+?99966[1-3][0-9]{4}"))
        throw new SecurityException("Test DC accepts only official 99966XYYYY test numbers");
    }
    String id = Long.toString(sequence.incrementAndGet());
    CompletableFuture<JSONObject> result = new CompletableFuture<>();
    pending.put(id, result);
    // Do not retain timed-out, cancelled or otherwise completed calls indefinitely.
    // Remove only OUR future: late replies must never resolve a different request.
    result.whenComplete((value, error) -> pending.remove(id, result));
    try { request.put("@extra", id); TdNative.send(client, request.toString()); }
    catch (Exception e) { result.completeExceptionally(e); }
    return result;
  }
  public JSONObject await(JSONObject request, long timeoutSeconds) throws Exception {
    CompletableFuture<JSONObject> future = call(request);
    JSONObject response;
    try {
      response = future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException | InterruptedException ex) {
      // No retry here: the caller owns resume/retry policy. Late responses are ignored.
      future.cancel(false);
      throw ex;
    }
    if ("error".equals(response.optString("@type"))) {
      int code = response.optInt("code");
      String message = response.optString("message");
      long seconds = TelegramRateLimit.waitSeconds(code, message);
      if (seconds >= 0) throw new TelegramRateLimit.Delay(seconds);
      // Never include the serialized request or message contents in the exception.
      throw new IllegalStateException("Telegram отклонил запрос, код " + code + ". При повторной ошибке останови экспорт.");
    }
    return response;
  }
  public static JSONObject cmd(String name) {
    JSONObject req = new JSONObject();
    try { req.put("@type", name); } catch (Exception e) { throw new IllegalStateException(e); }
    return req;
  }
  public void stop() {
    if(!running)return;
    // Close the native client while preserving the separate TDLib authorization for restart.
    // This is NOT logOut: only the explicit button ends the Telegram account session.
    try{TdNative.send(client,"{\"@type\":\"close\"}");}catch(Throwable ignored){}
    running=false;
    for(CompletableFuture<JSONObject> v:pending.values())v.cancel(true);
    pending.clear();
    if(Thread.currentThread()!=reader){
      try{reader.join(1100);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
  }
}
