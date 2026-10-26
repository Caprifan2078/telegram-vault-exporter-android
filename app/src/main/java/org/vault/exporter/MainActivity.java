package org.vault.exporter;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** A minimal native Android UI. No browser server, Termux, commands or analytics. */
public final class MainActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private TdGateway td;
  private LinearLayout root, interactive;
  private TextView status, selected;
  private long selectedChatId = 0;
  private String selectedChatTitle = "Чат";
  private boolean exporting = false;
  private volatile boolean logoutRequested = false;
  private final java.util.concurrent.atomic.AtomicBoolean initializing = new java.util.concurrent.atomic.AtomicBoolean(false);
  private final java.util.concurrent.atomic.AtomicBoolean nativeProbeRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
  private final int CREATE_ARCHIVE = 718;
  private final int CREATE_OFFLINE_FIXTURE = 719;
  private final int CREATE_SYNTHETIC_EXPORT = 720;
  private final int CREATE_HEAVY_EXPORT = 721;
  private final int CREATE_MEGA_EXPORT = 722;
  private char[] pendingPassphrase;
  // The document picker is an asynchronous operation; preserve the original chat.
  private long pendingChatId = 0;
  private final int BACKGROUND=Color.rgb(10, 18, 32), WHITE=0xffeef4ff, MUTED=0xff97a9c4;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    // Screenshot/recording is allowed ONLY for isolated test-DC debug demo.
    // Keep privacy protection for production and all release APKs, including debug production.
    // Debug APK has INTERNET permission removed via app/src/debug/AndroidManifest.xml.
    if (!(BuildConfig.DEBUG && BuildConfig.TELEGRAM_TEST_DC))
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    getWindow().setStatusBarColor(BACKGROUND); getWindow().setNavigationBarColor(BACKGROUND);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND);
    root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 36, 24, 36);
    scroll.addView(root); setContentView(scroll);
    title("Vault Exporter", 28, WHITE);
    title("ЭКСПОРТЁР • ЛОКАЛЬНО И БЕЗ ПОСРЕДНИКОВ", 11, MUTED);
    title("Независимое приложение на базе Telegram API. Не является официальным приложением Telegram и не связано с его разработчиками.", 12, MUTED);
    title("Первый вход создаст отдельный сеанс Telegram. Применяются правила Telegram для сторонних клиентов; нулевой риск ограничений не гарантируется.", 12, MUTED);
    status = title("Не подключено к Telegram. Данные аккаунта не запрашиваются.", 15, WHITE);
    interactive = new LinearLayout(this); interactive.setOrientation(LinearLayout.VERTICAL);
    root.addView(interactive);
    title("Переписка скачивается напрямую через TDLib. Архив шифруется локально. Никаких серверов разработчика.", 13, MUTED);
    showWelcome();
  }
  /** No network connection is made by merely launching this Activity. */
  private void showWelcome() {
    clearUi();
    if (BuildConfig.TELEGRAM_TEST_DC) {
      message("РЕЖИМ ПРОВЕРКИ: тестовые серверы Telegram. Здесь нет переписок основного аккаунта.");
      message("Доступность тестовой регистрации зависит от Telegram. Не вводите личные данные в общедоступных тестовых аккаунтах.");
    } else {
      message("ВНИМАНИЕ: эта сборка подключается к настоящему Telegram. Риск ограничений стороннего API-клиента нельзя исключить.");
    }
    if(BuildConfig.TELEGRAM_TEST_DC)
      button("Попробовать без аккаунта: скачать учебный чат",this::requestSyntheticExport);
    if(BuildConfig.TELEGRAM_TEST_DC)
      button("Тяжёлый тест: 480 000 слов и большие файлы",this::requestHeavyExport);
    if(BuildConfig.TELEGRAM_TEST_DC)
      button("Миллион слов + кружки, голосовые и стикеры",this::requestMegaExport);
    button("Посмотреть, как работает экспорт (офлайн)", this::showOfflineDemo);
    if (BuildConfig.DEBUG && BuildConfig.TELEGRAM_TEST_DC)
      button("Создать проверочный архив .tva (офлайн)", this::requestOfflineFixture);
    if (BuildConfig.DEBUG && BuildConfig.TELEGRAM_TEST_DC)
      button("Проверить нативную TDLib (офлайн)", this::probeNativeTdlibOffline);
    if (BuildConfig.TELEGRAM_TEST_DC && BuildConfig.ALLOW_TEST_DC_NETWORK) {
      message("Только тестовые серверы Telegram. Потребуется отдельная тестовая учётная запись. Обычные чаты недоступны.");
      button("Подключить тестовый Telegram (отдельный аккаунт)", this::askBeforeConnect);
    } else if (BuildConfig.TELEGRAM_TEST_DC) {
      message("В учебной версии вход в Telegram отключён. Интернет не требуется: нажми кнопку учебного чата.");
    } else if (!BuildConfig.DEBUG) {
      button("Подключить настоящий Telegram", this::askBeforeConnect);
    } else {
      message("СТРОГО ОФЛАЙН-ДЕМО: доступ к Интернету заблокирован разрешениями Android. Вход в Telegram отключён.");
    }
    message("Экспорт не запускается автоматически. Не требуется удалять или отключать основной Telegram.");
  }

  /** Runs the actual ExportEngine against a fictional TDLib-shaped local backend.
   * This control exists ONLY in test-data-center builds; it NEVER calls Telegram. */

  /** Explicitly opted-in, entirely local large-volume scenario. Unlike the small
   * demonstration, the protected final archive streams directly to the selected
   * Android document; it is never accumulated in a giant byte[]. */
  private void requestHeavyExport() {
    if(!BuildConfig.TELEGRAM_TEST_DC || td!=null || exporting) {
      showStatus("Тяжёлый учебный тест доступен только без Telegram.");
      return;
    }
    long free=getFilesDir().getUsableSpace();
    if(free<256L*1024*1024) {
      new AlertDialog.Builder(this).setTitle("Недостаточно свободной памяти")
        .setMessage("Для безопасного тяжёлого теста нужно минимум 256 МБ свободного места во внутреннем хранилище.")
        .setPositiveButton("Понятно",null).show();
      return;
    }
    new AlertDialog.Builder(this).setTitle("Тяжёлый учебный экспорт")
      .setMessage("24 000 вымышленных сообщений, ровно 480 000 слов и 12 документов общей величиной 36 МиБ.\n\n"
        +"Экспорт происходит пакетами, файлы шифруются на телефоне. Итог — защищённый .tva, а не ZIP.\n\n"
        +"Только вымышленные данные: вход в Telegram и интернет не используются. "
        +"Потребуется несколько минут, дополнительное свободное место и заряд аккумулятора. "
        +"Держи приложение открытым.\n\nПароль учебного файла: Vault-stress-test-only-2026")
      .setNegativeButton("Отмена",null)
      .setPositiveButton("Начать тест",(d,w)->{
        exporting=true;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,"Vault_STRESS_480000_WORDS.tva");
        try {startActivityForResult(intent,CREATE_HEAVY_EXPORT);}
        catch(RuntimeException e){exporting=false;showStatus("Сохранение недоступно. Повтори попытку.");}
      }).show();
  }

  private void saveHeavyExport(Uri destination){
    if(!BuildConfig.TELEGRAM_TEST_DC || !exporting)return;
    String provider=destination.getAuthority();
    if(!"com.android.externalstorage.documents".equals(provider) &&
       !"com.android.providers.downloads.documents".equals(provider) &&
       !"com.android.providers.media.documents".equals(provider)){
      exporting=false;
      boolean deleted=false;
      try{deleted=getContentResolver().delete(destination,null,null)>0;}catch(Exception ignored){}
      showStatus("Выбери локальную память телефона, не облачный диск."
        +(deleted?"":" Проверь, не остался ли пустой файл."));
      return;
    }
    clearUi();
    message("Создаём 480 000 вымышленных слов и 36 МиБ файлов. Данные шифруются пакетами. "
      +"Вход в Telegram не выполняется. Держи приложение открытым.");
    showStatus("Тяжёлый тест: начинаем…");
    worker.execute(()->{
      char[] pass="Vault-stress-test-only-2026".toCharArray();
      boolean progressErased=false;
      try {
        ExportEngine.Summary summary;
        try(OutputStream stream=getContentResolver().openOutputStream(destination,"w")){
          if(stream==null)throw new IOException("Не удалось создать локальный файл");
          summary=new ExportEngine(this,(ExportEngine.Transport)new HeavyMockTdlib(this),
            this::showStatus).export(HeavyMockTdlib.CHAT_ID,stream,pass);
        }
        if(!summary.historyFinished || summary.messages!=HeavyMockTdlib.MESSAGE_COUNT ||
            summary.media!=HeavyMockTdlib.MEDIA_COUNT ||summary.missing!=0)
          throw new IOException("Тяжёлый экспорт не содержит все учебные данные");
        int count;
        try(InputStream check=getContentResolver().openInputStream(destination)){
          if(check==null)throw new IOException("Невозможно открыть архив для проверки");
          count=ArchiveVerifier.verify(check,pass);
        }
        if(count<HeavyMockTdlib.MEDIA_COUNT)
          throw new IOException("В защищённом архиве не хватает учебных файлов");
        byte[] secret=null;
        try{
          secret=new SessionSecrets(this).getOrCreate();
          try(ResumeStore staging=new ResumeStore(new File(getFilesDir(),"export-progress"),
              HeavyMockTdlib.CHAT_ID,secret)) {
            if(staging.state().historyFinished){
              staging.erase();
              progressErased=true;
            }
          }
        }finally{if(secret!=null)Arrays.fill(secret,(byte)0);}
        final boolean clean=progressErased;
        showStatus("Успех: 480 000 слов и 12 учебных файлов сохранены и проверены.");
        runOnUiThread(()->{
          if(isFinishing() || isDestroyed())return;
          clearUi();
          message("Готово! Защищённый .tva записан в выбранную папку. "
            +"24 000 сообщений, 480 000 слов, 12 файлов (36 МиБ)."
            +(clean?"":" Внимание: временный зашифрованный прогресс ещё не очищен."));
          message("Пароль: Vault-stress-test-only-2026. В архиве только вымышленные данные.");
          button("На главный экран",this::showWelcome);
          new AlertDialog.Builder(this).setTitle("Тяжёлый тест завершён")
            .setMessage("История полностью выгружена, архив проверен повторным чтением. "
              +"Никаких данных Telegram не использовалось.")
            .setPositiveButton("Понятно",null).show();
        });
      } catch(Exception failure){
        boolean deleted=false;
        try{deleted=getContentResolver().delete(destination,null,null)>0;}catch(Exception ignored){}
        final boolean removed=deleted;
        showStatus("Тяжёлый тест прерван: "+failure.getClass().getSimpleName()
          +(removed?"":"; проверь и удали неполный файл вручную."));
        runOnUiThread(()->{
          clearUi();
          message("Тест остановлен без подключения Telegram. "
            +"Зашифрованный прогресс внутри приложения может сохраниться для повторной попытки."
            +(removed?"":" Не удалось подтвердить удаление неполного выходного файла."));
          button("На главный экран",this::showWelcome);
        });
      }finally{
        Arrays.fill(pass,'\0');
        exporting=false;
      }
    });
  }
  private void requestMegaExport() {
    if(!BuildConfig.TELEGRAM_TEST_DC || td!=null || exporting) {
      showStatus("Миллионный офлайн-тест доступен только без Telegram.");
      return;
    }
    long free=getFilesDir().getUsableSpace();
    if(free<256L*1024*1024) {
      new AlertDialog.Builder(this).setTitle("Недостаточно свободной памяти")
        .setMessage("Для безопасного тяжёлого теста нужно минимум 256 МБ свободного места во внутреннем хранилище.")
        .setPositiveButton("Понятно",null).show();
      return;
    }
    new AlertDialog.Builder(this).setTitle("Миллион слов и разные вложения")
      .setMessage("54 000 вымышленных сообщений, ровно 1 080 000 слов и 19 вложений разных типов общей величиной 24 МиБ.\n\n"
        +"Экспорт происходит пакетами, файлы шифруются на телефоне. Итог — защищённый .tva, а не ZIP.\n\n"
        +"Учебные кружки, Opus-голосовые, WebP и TGS-стикеры, видео, фото и файлы. Все данные вымышленные; интернет не нужен. "
        +"Потребуется несколько минут, дополнительное свободное место и заряд аккумулятора. "
        +"Держи приложение открытым.\n\nПароль учебного файла: Vault-mega-offline-2026")
      .setNegativeButton("Отмена",null)
      .setPositiveButton("Начать тест",(d,w)->{
        exporting=true;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,"Vault_MEGA_1080000_WORDS.tva");
        try {startActivityForResult(intent,CREATE_MEGA_EXPORT);}
        catch(RuntimeException e){exporting=false;showStatus("Сохранение недоступно. Повтори попытку.");}
      }).show();
  }

  private void saveMegaExport(Uri destination){
    if(!BuildConfig.TELEGRAM_TEST_DC || !exporting)return;
    String provider=destination.getAuthority();
    if(!"com.android.externalstorage.documents".equals(provider) &&
       !"com.android.providers.downloads.documents".equals(provider) &&
       !"com.android.providers.media.documents".equals(provider)){
      exporting=false;
      boolean deleted=false;
      try{deleted=getContentResolver().delete(destination,null,null)>0;}catch(Exception ignored){}
      showStatus("Выбери локальную память телефона, не облачный диск."
        +(deleted?"":" Проверь, не остался ли пустой файл."));
      return;
    }
    clearUi();
    message("Создаём 1 080 000 вымышленных слов и 24 МиБ файлов. Данные шифруются пакетами. "
      +"Вход в Telegram не выполняется. Держи приложение открытым.");
    showStatus("Миллион слов: начинаем…");
    worker.execute(()->{
      char[] pass="Vault-mega-offline-2026".toCharArray();
      boolean progressErased=false;
      String phase="создание архива";
      try {
        ExportEngine.Summary summary;
        try(OutputStream stream=getContentResolver().openOutputStream(destination,"w")){
          if(stream==null)throw new IOException("Не удалось создать локальный файл");
          summary=new ExportEngine(this,(ExportEngine.Transport)new MegaMockTdlib(this),
            this::showStatus).export(MegaMockTdlib.CHAT_ID,stream,pass);
        }
        phase="проверка количества сообщений";
        if(!summary.historyFinished || summary.messages!=MegaMockTdlib.MESSAGE_COUNT ||
            summary.media!=MegaMockTdlib.MEDIA_COUNT ||summary.missing!=0)
          throw new IOException("Тяжёлый экспорт не содержит все учебные данные");
        phase="проверка структуры TVA";
        int count;
        try(InputStream check=getContentResolver().openInputStream(destination)){
          if(check==null)throw new IOException("Невозможно открыть архив для проверки");
          count=ArchiveVerifier.verify(check,pass);
        }
        if(count<MegaMockTdlib.MEDIA_COUNT)
          throw new IOException("В защищённом архиве не хватает учебных файлов");
        phase="проверка всех сообщений и форматов медиа";
        // Independent streaming acceptance: verify all 54,000 message bodies,
        // >1M actual words, every file signature and multi-media preview.
        try(InputStream check=getContentResolver().openInputStream(destination)){
          if(check==null)throw new IOException("Cannot reopen finished mega archive");
          MegaArchiveVerifier.verify(check,pass);
        }
        phase="очистка промежуточных файлов";
        byte[] secret=null;
        try{
          secret=new SessionSecrets(this).getOrCreate();
          try(ResumeStore staging=new ResumeStore(new File(getFilesDir(),"export-progress"),
              MegaMockTdlib.CHAT_ID,secret)) {
            if(staging.state().historyFinished){
              staging.erase();
              progressErased=true;
            }
          }
        }finally{if(secret!=null)Arrays.fill(secret,(byte)0);}
        final boolean clean=progressErased;
        showStatus("Успех: 1 080 000 слов и 19 учебных медиафайлов сохранены и проверены.");
        runOnUiThread(()->{
          if(isFinishing() || isDestroyed())return;
          clearUi();
          message("Готово! Защищённый .tva записан в выбранную папку. "
            +"54 000 сообщений, 1 080 000 слов, 19 медиафайлов (24 МиБ)."
            +(clean?"":" Внимание: временный зашифрованный прогресс ещё не очищен."));
          message("Пароль: Vault-mega-offline-2026. В архиве только вымышленные данные.");
          button("На главный экран",this::showWelcome);
          new AlertDialog.Builder(this).setTitle("Миллион слов завершён")
            .setMessage("История полностью выгружена, архив проверен повторным чтением. "
              +"Никаких данных Telegram не использовалось.")
            .setPositiveButton("Понятно",null).show();
        });
      } catch(Exception failure){
        boolean deleted=false;
        try{deleted=getContentResolver().delete(destination,null,null)>0;}catch(Exception ignored){}
        final boolean removed=deleted;
        // Diagnostics are rendered ONLY for completely fictional offline data.
        // Never show filenames, file-system paths, API values or user data.
        Throwable deepest=failure;
        for(int d=0;d<5&&deepest.getCause()!=null;d++)deepest=deepest.getCause();
        String detail=deepest.getMessage();
        if(detail==null||detail.isEmpty())detail="причина не указана";
        if(detail.contains("/")||detail.contains("\\\\")||detail.length()>180)
          detail="подробности скрыты для защиты данных";
        final String explanation="Миллион слов прерван на этапе: "+phase+
          ". "+deepest.getClass().getSimpleName()+": "+detail+
          (removed?"":"; проверь и удали неполный файл вручную.");
        showStatus(explanation);
        runOnUiThread(()->{
          clearUi();
          message(explanation);
          message("Тест остановлен без подключения Telegram. "
            +"Зашифрованный прогресс внутри приложения может сохраниться для повторной попытки."
            +(removed?"":" Не удалось подтвердить удаление неполного выходного файла."));
          button("На главный экран",this::showWelcome);
        });
      }finally{
        Arrays.fill(pass,'\0');
        exporting=false;
      }
    });
  }
  private void requestSyntheticExport() {
    if(!BuildConfig.TELEGRAM_TEST_DC||td!=null||exporting){
      showStatus("Учебная проверка доступна только без подключённого Telegram.");
      return;
    }
    new AlertDialog.Builder(this).setTitle("Безопасная учебная переписка")
      .setMessage("60 вымышленных сообщений, 7 настоящих учебных медиафайлов: фотографии, видео, документ и стикер.\n\nЭкспорт проходит через ТОТ ЖЕ движок, что и обычные чаты. Ни аккаунт, ни интернет не нужны.\n\nСохранённый ZIP не зашифрован, но содержит только вымышленные данные.")
      .setNegativeButton("Отмена",null).setPositiveButton("Скачать учебный чат",(d,w)->{
        exporting=true;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE,"Vault_УЧЕБНАЯ_ПЕРЕПИСКА.zip");
        try{startActivityForResult(intent,CREATE_SYNTHETIC_EXPORT);}
        catch(Exception e){exporting=false;showStatus("Выбор папки недоступен. Повтори попытку.");}
      }).show();
  }
  private void saveSyntheticExport(Uri destination) {
    if(!BuildConfig.TELEGRAM_TEST_DC || !exporting)return;
    String authority=destination.getAuthority();
    if(!"com.android.externalstorage.documents".equals(authority)
        &&!"com.android.providers.downloads.documents".equals(authority)
        &&!"com.android.providers.media.documents".equals(authority)){
      exporting=false;
      boolean removed=false;
      try{removed=getContentResolver().delete(destination,null,null)>0;}catch(Exception ignored){}
      showStatus("Для учебного ZIP выбери память самого телефона, не облако."
        +(removed?"":" Проверь и удали созданный пустой файл вручную."));
      return;
    }
    showStatus("Учебный чат: загружаем 60 сообщений и медиа в закрытый кэш…");
    clearUi();message("Идёт настоящая проверка механизма экспорта — без Telegram. Держи приложение открытым.");
    worker.execute(()->{
      boolean clean=false;
      try{
        // The production engine ALWAYS creates a password-encrypted .tva first.
        // Only FICTIONAL data may be decrypted into this explicitly marked demo ZIP.
        char[] demoPassword="Vault-fictional-offline-chat-2026".toCharArray();
        try {
          ByteArrayOutputStream encrypted=new ByteArrayOutputStream();
          ExportEngine.Transport simulation=new LocalMockTdlib(this);
          ExportEngine.Summary summary=new ExportEngine(this,simulation,this::showStatus)
            .export(LocalMockTdlib.CHAT_ID,encrypted,demoPassword);
          if(summary.messages!=LocalMockTdlib.MESSAGE_COUNT
              ||summary.media!=LocalMockTdlib.MEDIA_COUNT||!summary.historyFinished)
            throw new IOException("Учебная переписка выгружена не полностью");
          byte[] protectedArchive=encrypted.toByteArray();
          if(protectedArchive.length>12*1024*1024)
            throw new IOException("Слишком большой учебный архив");
          int entries=ArchiveVerifier.verify(new ByteArrayInputStream(protectedArchive),demoPassword);
          if(entries<LocalMockTdlib.MEDIA_COUNT)
            throw new IOException("Не все учебные медиа упакованы");
          try(OutputStream out=getContentResolver().openOutputStream(destination,"w")){
            if(out==null)throw new IOException("Не удалось открыть файл");
            SyntheticZipWriter.write(out,protectedArchive,demoPassword);
          }
          try(InputStream check=getContentResolver().openInputStream(destination)){
            if(check==null)throw new IOException("Не удалось проверить учебный ZIP");
            SyntheticZipWriter.verify(check);
          }
        }finally{Arrays.fill(demoPassword,'\0');}
        byte[] key=null;
        try{
          key=new SessionSecrets(this).getOrCreate();
          try(ResumeStore staged=new ResumeStore(new File(getFilesDir(),"export-progress"),
              LocalMockTdlib.CHAT_ID,key)){staged.erase();clean=true;}
        }finally{if(key!=null)Arrays.fill(key,(byte)0);}
        final boolean cleanDone=clean;
        showStatus("Готово! 60 сообщений и 7 файлов в учебном ZIP. Это НЕ выгрузка Telegram.");
        runOnUiThread(()->{
          if(!isFinishing()&&!isDestroyed()){
            clearUi();
            message("Готово! ZIP сохранён в выбранную папку.\n60 вымышленных сообщений и 7 медиафайлов.\n"
              +(cleanDone?"Временные данные удалены.":"Внимание: закрытый временный прогресс не удалён."));
            button("На главный экран",this::showWelcome);
            new AlertDialog.Builder(this).setTitle("Учебный экспорт завершён")
              .setMessage("Получился настоящий ZIP со страницами истории и медиа.\n\n"
                +"Вы проверили движок на вымышленных данных — без сети и аккаунта Telegram."
                +(cleanDone?"":"\n\nНе удалось подтвердить удаление зашифрованного прогресса."))
              .setPositiveButton("Понятно",null).show();
          }
        });
      }catch(Exception ex){
        boolean removed=false;
        try{removed=getContentResolver().delete(destination,null,null)>0;}catch(Exception ignored){}
        String warning=removed?"":" ВНИМАНИЕ: удали незавершённый ZIP из выбранной папки вручную.";
        showStatus("Учебный экспорт не удался ("+ex.getClass().getSimpleName()+")."+warning);
        runOnUiThread(()->{
          clearUi();button("Повторить учебную проверку",this::requestSyntheticExport);
          button("На главный экран",this::showWelcome);
        });
      }finally{exporting=false;}
    });
  }

  private void showOfflineDemo() {
    new AlertDialog.Builder(this)
      .setTitle("Офлайн-демонстрация")
      .setMessage("1. Авторизация в отдельном сеансе\n2. Выбор облачной переписки\n3. Скачивание поддерживаемых вложений, даже без кэша\n4. Шифрование локального архива\n5. Проверка целостности и предложение выхода\n\nЭто только демонстрация шагов. Соединение с Telegram не выполняется.")
      .setPositiveButton("Понятно", null).show();
  }
  // One real end-to-end local Android archive without Telegram login or real messages.
  // The passphrase is publicly known because the archive contains ONLY fictional data.
  private void requestOfflineFixture() {
    if (!(BuildConfig.DEBUG && BuildConfig.TELEGRAM_TEST_DC)) return;
    new AlertDialog.Builder(this).setTitle("Создать настоящий учебный архив")
      .setMessage("Сохранит зашифрованный .tva с 3 вымышленными сообщениями и 1 текстовым файлом.\n\nНикаких данных Telegram или сети. Пароль: Vault-demo-archive-only")
      .setNegativeButton("Отмена",null)
      .setPositiveButton("Выбрать папку",(dialog,which)->{
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,"Vault_OFFLINE_TEST.tva");
        try {startActivityForResult(intent,CREATE_OFFLINE_FIXTURE);}
        catch(RuntimeException e){showStatus("Не удалось открыть выбор папки для учебного архива.");}
      }).show();
  }
  private void saveOfflineFixture(Uri destination) {
    if (!(BuildConfig.DEBUG && BuildConfig.TELEGRAM_TEST_DC)) return;
    String provider=destination.getAuthority();
    if (!"com.android.externalstorage.documents".equals(provider) &&
        !"com.android.providers.downloads.documents".equals(provider) &&
        !"com.android.providers.media.documents".equals(provider)) {
      showStatus("Для учебного архива выбери локальную память телефона, не облачный диск.");
      return;
    }
    showStatus("Создаём и проверяем учебный .tva на телефоне…");
    worker.execute(()->{
      char[] password=OfflineArchiveFixture.PASSWORD.toCharArray();
      try {
        try(OutputStream out=getContentResolver().openOutputStream(destination,"w")){
          if(out==null)throw new IOException("Не открылся локальный файл");
          OfflineArchiveFixture.write(out,password);
        }
        int entries;
        try(InputStream in=getContentResolver().openInputStream(destination)){
          if(in==null)throw new IOException("Не удалось открыть архив для проверки");
          entries=ArchiveVerifier.verify(in,password);
        }
        if(entries!=5)throw new IOException("Неверное число проверенных записей");
        showStatus("Готово: настоящий зашифрованный учебный архив создан и проверен (5 записей).");
        runOnUiThread(()->{
          if(!isFinishing()&&!isDestroyed())
            new AlertDialog.Builder(this).setTitle("Учебный архив готов")
              .setMessage("Создан реальный зашифрованный файл .tva из вымышленных сообщений. Проверка целостности пройдена.\n\nПароль: Vault-demo-archive-only\n\nЭто НЕ экспорт Telegram.")
              .setPositiveButton("Понятно",null).show();
        });
      } catch(Exception e) {
        try{getContentResolver().delete(destination,null,null);}catch(Exception ignored){}
        showStatus("Не получилось создать или проверить учебный архив. Проверь свободное место.");
      } finally {Arrays.fill(password,'\0');}
    });
  }
  /**
   * Debug/test-DC only: verify JNI dynamic linking and basic official TDLib C API.
   * Deliberately never sends setTdlibParameters, credentials or any network request.
   * The emulator CI also disables all radios before using this control.
   */
  private void probeNativeTdlibOffline() {
    if (!BuildConfig.DEBUG || !BuildConfig.TELEGRAM_TEST_DC || td != null || exporting) {
      new AlertDialog.Builder(this).setTitle("Диагностика TDLib")
        .setMessage("Эта проверка доступна только в безопасном офлайн-режиме.")
        .setPositiveButton("Понятно", null).show();
      return;
    }
    // The previous version updated a status label near the very top of the screen:
    // when the user tapped the middle button, it looked as if nothing happened.
    // A modal makes the action and its result immediately visible at the tap location.
    if (!nativeProbeRunning.compareAndSet(false, true)) {
      new AlertDialog.Builder(this).setTitle("Диагностика TDLib")
        .setMessage("Проверка уже выполняется. Повторно запускать её не нужно.")
        .setPositiveButton("Понятно", null).show();
      return;
    }
    final AlertDialog dialog = new AlertDialog.Builder(this)
      .setTitle("Диагностика TDLib")
      .setMessage("Идёт офлайн-проверка движка (до 10 секунд).\nАккаунт Telegram не подключается.")
      .setPositiveButton("Закрыть", null).create();
    dialog.show();
    showStatus("Проверяем двусторонний JNI/TDLib без входа…");
    try {
      worker.execute(() -> {
        long clientId = 0;
        String result = "TDLIB_OFFLINE_PROBE_FAIL: нет корректного ответа TDLib.";
        try {
          clientId = TdNative.createClient();
          if (clientId <= 0) throw new IllegalStateException("TDLib client creation returned zero");
          // getAuthorizationState is local before TDLib parameters; no account or network.
          String token = "vault-offline-roundtrip-" + clientId;
          JSONObject request = TdGateway.cmd("getAuthorizationState").put("@extra", token);
          TdNative.send(clientId, request.toString());
          boolean gotReply = false;
          for (int tries = 0; tries < 20; tries++) {
            String wire = TdNative.receive(0.5);
            if (wire == null) continue;
            JSONObject response = new JSONObject(wire);
            if (!token.equals(response.optString("@extra"))) continue;
            if (response.optLong("@client_id", 0) != clientId)
              throw new IOException("Invalid TDLib response client identity");
            if (!"authorizationStateWaitTdlibParameters".equals(response.optString("@type")))
              throw new IOException("Unexpected unauthenticated state");
            gotReply = true;
            break;
          }
          if (!gotReply) throw new IOException("TDLib roundtrip timed out");
          result = "TDLIB_OFFLINE_PROBE_PASS: движок работает. Telegram не подключён.";
        } catch (Throwable ignored) {
          // Do not include native failure details or private payloads in the UI.
        } finally {
          if (clientId > 0) {
            try { TdNative.send(clientId, "{\"@type\":\"close\"}"); }
            catch (Throwable ignored) { /* diagnostic cleanup only */ }
          }
          nativeProbeRunning.set(false);
          final String message = result;
          showStatus(message);
          runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && dialog.isShowing()) {
              dialog.setMessage(message.startsWith("TDLIB_OFFLINE_PROBE_PASS")
                ? "Готово! Движок TDLib отвечает.\n\nЭто только офлайн-диагностика — переписки не загружаются.\n\n" + message
                : "Проверка не прошла. Никаких данных Telegram не запрашивали.\n\n" + message);
            }
          });
        }
      });
    } catch (RuntimeException unavailable) {
      nativeProbeRunning.set(false);
      dialog.setMessage("Не удалось начать офлайн-проверку. Перезапусти приложение.");
    }
  }
  private void askBeforeConnect() {
    if(BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_TEST_DC_NETWORK){
      showStatus("Офлайн-сборка не подключается к Telegram. Используй вымышленный чат.");
      return;
    }
    if (BuildConfig.DEBUG) { showStatus("В демонстрационной сборке доступ к Telegram отключён."); return; }
    if (td != null) { showStatus("Подключение уже началось."); return; }
    if (!BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_PRODUCTION) {
      showStatus("Подключение к основному Telegram запрещено конфигурацией этой сборки.");
      return;
    }
    if (BuildConfig.TELEGRAM_API_ID <= 0 || BuildConfig.TELEGRAM_API_HASH.isEmpty()) {
      showStatus("Для сетевого теста разработчик должен настроить Telegram API. Офлайн-демонстрация доступна без ключей.");
      return;
    }
    String notice = BuildConfig.TELEGRAM_TEST_DC
      ? "Будут использованы только тестовые серверы Telegram. Не вводите здесь информацию о настоящих переписках. Тестовый вход может быть недоступен."
      : "Это НЕОФИЦИАЛЬНЫЙ клиент. Telegram предупреждает о дополнительной проверке таких клиентов. Нулевой риск для аккаунта гарантировать нельзя. Не подключайте важный аккаунт, если не готовы принять этот риск.";
    new AlertDialog.Builder(this).setTitle("Подтвердить подключение")
      .setMessage(notice).setNegativeButton("Отмена",null)
      .setPositiveButton(BuildConfig.TELEGRAM_TEST_DC ? "Только тестовый сервер" : "Понимаю риск, подключить",
        (d, which) -> beginConnect()).show();
  }
  private void beginConnect() {
    if (BuildConfig.DEBUG || (BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_TEST_DC_NETWORK)
        || (!BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_PRODUCTION)) {
      showStatus("Сетевое подключение заблокировано конфигурацией сборки.");
      return;
    }
    clearUi(); message("Соединяемся с " + (BuildConfig.TELEGRAM_TEST_DC ? "тестовым Telegram" : "основным Telegram") + "…");
    showStatus("Инициализация TDLib по вашему запросу…");
    worker.execute(() -> {
      try {
        // Do not materialize the Keystore-wrapped TDLib secret as a long-lived
        // immutable String before TDLib actually requests its parameters.
        td = new TdGateway(this::onTdEvent);
        JSONObject stateObj = td.await(TdGateway.cmd("getAuthorizationState"),30);
        onAuthorizationState(stateObj);
      } catch(Throwable e) {
        showStatus("Ошибка TDLib: " + e.getClass().getSimpleName() + ". Сеанс не подтверждён.");
        runOnUiThread(() -> {clearUi(); button("Вернуться", this::showWelcome);});
      }
    });
  }
  private int dp(int value) { return (int)(getResources().getDisplayMetrics().density * value + .5f); }
  private TextView title(String text, int size, int color) {
    TextView v = new TextView(this); v.setText(text); v.setTextColor(color); v.setTextSize(size);
    v.setPadding(0, dp(4), 0, dp(13)); root.addView(v); return v;
  }
  private TextView message(String text) {
    TextView v=new TextView(this); v.setText(text); v.setTextSize(15); v.setTextColor(WHITE);
    v.setPadding(0, dp(12), 0, dp(12)); interactive.addView(v); return v;
  }
  private Button button(String text, Runnable callback) {
    Button btn=new Button(this); btn.setAllCaps(false); btn.setText(text); btn.setTextSize(16);
    btn.setTextColor(WHITE);
    GradientDrawable shape=new GradientDrawable(); shape.setColor(0xff2865ec); shape.setCornerRadius(dp(18));
    btn.setBackground(shape);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(56)); p.setMargins(0,dp(9),0,dp(9));
    interactive.addView(btn,p); btn.setOnClickListener(v -> callback.run()); return btn;
  }
  private void showStatus(String text) { runOnUiThread(() -> status.setText(text)); }
  private void clearUi() { interactive.removeAllViews(); }
  private void onTdEvent(JSONObject event) {
    if ("updateAuthorizationState".equals(event.optString("@type")))
      onAuthorizationState(event.optJSONObject("authorization_state"));
  }
  private void onAuthorizationState(JSONObject state) {
    if (state == null) return;
    String name=state.optString("@type");
    if ("authorizationStateWaitTdlibParameters".equals(name)) {
      if (!initializing.compareAndSet(false,true)) return;
      worker.execute(() -> {
        try {
          byte[] secret = new SessionSecrets(this).getOrCreate();
          try {
          JSONObject params=TdGateway.cmd("setTdlibParameters")
            .put("use_test_dc",BuildConfig.TELEGRAM_TEST_DC)
            .put("database_directory",new File(getFilesDir(),"tdlib/database").getAbsolutePath())
            .put("files_directory",new File(getFilesDir(),"tdlib/files").getAbsolutePath())
            .put("database_encryption_key",Base64.encodeToString(secret,Base64.NO_WRAP))
            .put("use_file_database",true).put("use_chat_info_database",true)
            .put("use_message_database",true).put("use_secret_chats",false)
            .put("api_id",BuildConfig.TELEGRAM_API_ID).put("api_hash",BuildConfig.TELEGRAM_API_HASH)
            .put("system_language_code",Locale.getDefault().getLanguage())
            .put("device_model",android.os.Build.MODEL).put("system_version",android.os.Build.VERSION.RELEASE)
            .put("application_version","0.9-test-gated-alpha");
          td.await(params,30);
          } finally {Arrays.fill(secret,(byte)0);}
        } catch(Exception e) { initializing.set(false); showStatus("Не удалось настроить TDLib: " + e.getMessage()); }
      });
      return;
    }
    runOnUiThread(() -> {
      if (exporting) return;
      clearUi();
      switch(name) {
        case "authorizationStateWaitPhoneNumber": authInput("Номер телефона в международном формате", "Номер +…", false, "Продолжить",
            value -> TdGateway.cmd("setAuthenticationPhoneNumber").put("phone_number", value)); break;
        case "authorizationStateWaitCode": authInput("Код подтверждения из Telegram", "Код", false, "Подтвердить",
            value -> TdGateway.cmd("checkAuthenticationCode").put("code", value)); break;
        case "authorizationStateWaitPassword": authInput("Пароль двухэтапной аутентификации", "Пароль", true, "Подтвердить",
            value -> TdGateway.cmd("checkAuthenticationPassword").put("password",value)); break;
        case "authorizationStateWaitEmailAddress": authInput("Email, запрошенный Telegram", "Электронная почта", false, "Отправить код",
            value -> TdGateway.cmd("setAuthenticationEmailAddress").put("email_address",value)); break;
        case "authorizationStateWaitEmailCode": authInput("Код подтверждения электронной почты", "Код",false,"Подтвердить",
            value -> TdGateway.cmd("checkAuthenticationEmailCode").put("code",TdGateway.cmd("emailAddressAuthenticationCode").put("code",value))); break;
        case "authorizationStateReady": showStatus("Подключено напрямую к Telegram. Выбери переписку."); showReady(); break;
        case "authorizationStateLoggingOut": showStatus("Завершаем только сеанс экспортёра…"); break;
        case "authorizationStateClosed":
          // Never erase the resumable archive here: a network-confirmed logout result
          // and an explicit cleanup path are required, even if this event arrives early.
          showStatus(logoutRequested ? "Telegram подтвердил закрытие сеанса. Завершаем очистку…" : "Соединение закрыто.");
          break;
        default: message("Telegram запросил дополнительный способ входа: "+name+". Пока этот этап не поддержан.");
      }
    });
  }
  @FunctionalInterface private interface AuthCommand { JSONObject create(String value) throws Exception; }
  private void authInput(String header, String hint, boolean hidden, String action, AuthCommand cmd) {
    message(header);
    EditText input=new EditText(this); input.setTextColor(WHITE); input.setHintTextColor(MUTED); input.setHint(hint);
    if(hidden) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    else if(hint.contains("+")) input.setInputType(InputType.TYPE_CLASS_PHONE);
    interactive.addView(input);
    Button submit=button(action, () -> {});
    submit.setOnClickListener(view -> {
      if (!submit.isEnabled()) return;
      String val=hidden ? input.getText().toString() : input.getText().toString().trim(); if(val.isEmpty()) return;
      submit.setEnabled(false);
      input.setText(""); showStatus("Проверяем ответ Telegram…");
      worker.execute(() -> {
        try { td.await(cmd.create(val), 60); }
        catch(Exception e) { showStatus("Вход не подтверждён. Проверь данные и повтори попытку, не запрашивая коды слишком часто."); }
        finally { runOnUiThread(() -> submit.setEnabled(true)); }
      });
    });
    message("Коды и пароль входа используются только для авторизации через TDLib; приложение не использует сервер разработчика.");
  }
  private void showReady() {
    selected=message("Чат ещё не выбран");
    button("1. Выбрать переписку",this::chooseChat);
    button("2. Скачать зашифрованный архив",this::requestPassphrase);
    message("Приложение загружает доступные фото, видео и документы через Telegram, в том числе отсутствующие в кэше. Пароль защищает сохранённый архив.");
    button("Завершить сеанс экспортёра",this::confirmLogout);
    button("Если загрузка не удалась: удалить её прогресс",this::confirmResetProgress);
  }
  private void chooseChat() {
    showStatus("Загружаем список чатов…");
    worker.execute(() -> {
      try {
        ArrayList<Long> keys=new ArrayList<>(); ArrayList<String> titles=new ArrayList<>();
        HashSet<Long> encountered=new HashSet<>();
        for(String listName:new String[]{"chatListMain","chatListArchive"}) {
          JSONObject chatList=TdGateway.cmd(listName);
          for(int batch=0;batch<4;batch++) {
            try { td.await(TdGateway.cmd("loadChats").put("chat_list",chatList).put("limit",100),60); }
            catch(Exception e) {break;} // TDLib returns 404 when no more chats are available.
          }
          JSONArray ids=td.await(TdGateway.cmd("getChats").put("chat_list",chatList).put("limit",500),45)
            .optJSONArray("chat_ids");
          if(ids==null)continue;
          for(int i=0;i<ids.length();i++) {
            long id=ids.getLong(i);if(!encountered.add(id))continue;
            JSONObject chat=td.await(TdGateway.cmd("getChat").put("chat_id",id),30);
            if(chat.optBoolean("has_protected_content",false))continue;
            JSONObject type=chat.optJSONObject("type");
            if(type!=null && "chatTypeSecret".equals(type.optString("@type")))continue;
            keys.add(id);
            titles.add(("chatListArchive".equals(listName)?"[Архив] ":"")+chat.optString("title","Без названия"));
          }
        }
        runOnUiThread(() -> {
          if(keys.isEmpty()) { showStatus("Подходящих облачных чатов нет."); return; }
          EditText search=new EditText(this);
          search.setSingleLine(true);
          search.setHint("Часть названия чата (можно оставить пустым)");
          new AlertDialog.Builder(this).setTitle("Найти переписку")
            .setView(search).setNegativeButton("Отмена",null)
            .setPositiveButton("Показать чаты",(dialog,unused)->{
              String query=search.getText().toString().toLowerCase(java.util.Locale.ROOT).trim();
              ArrayList<Integer> found=new ArrayList<>();
              ArrayList<String> names=new ArrayList<>();
              for(int n=0;n<titles.size()&&found.size()<200;n++) {
                if(titles.get(n).toLowerCase(java.util.Locale.ROOT).contains(query)){
                  found.add(n);names.add(titles.get(n));
                }
              }
              if(found.isEmpty()){showStatus("Такой чат не найден. Повтори поиск.");return;}
              new AlertDialog.Builder(this).setTitle("Выбрать чат")
                .setItems(names.toArray(new String[0]),(picked,which)->{
                  int original=found.get(which);
                  long chatId=keys.get(original);
                  selectedChatId=chatId;selectedChatTitle=titles.get(original);
                  selected.setText("Выбрано: "+selectedChatTitle);
                  showStatus("Проверяем сохранённый прогресс…");
                  worker.execute(()->{
                    byte[] secret=null;
                    try{
                      secret=new SessionSecrets(this).getOrCreate();
                      try(ResumeStore store=new ResumeStore(new File(getFilesDir(),"export-progress"),chatId,secret)){
                        ResumeStore.State state=store.state();
                        if(selectedChatId==chatId)showStatus(state.pages>0
                          ? "Продолжим экспорт: "+state.messages+" сообщений и "+state.media+" файлов."
                          : "Готово. Нажми «Скачать зашифрованный архив».");
                      }
                    }catch(Exception e){if(selectedChatId==chatId)showStatus("Не удалось проверить прогресс.");}
                    finally{if(secret!=null)Arrays.fill(secret,(byte)0);}
                  });
                }).setNegativeButton("Отмена",null).show();
            }).show();
        });
      } catch(Exception e) { showStatus("Не удалось загрузить чаты: "+e.getMessage()); }
    });
  }
  private static char[] extractAndClear(EditText field) {
    android.text.Editable value=field.getText();
    char[] password=new char[value.length()];
    for (int i=0;i<password.length;i++) password[i]=value.charAt(i);
    value.clear();
    return password;
  }
  private void requestPassphrase() {
    if(selectedChatId==0) { showStatus("Сначала выбери переписку."); return; }
    if(exporting || pendingPassphrase!=null) { showStatus("Выбор места сохранения уже выполняется."); return; }
    final long dialogChatId=selectedChatId;
    LinearLayout fields = new LinearLayout(this); fields.setPadding(dp(20),0,dp(20),0); fields.setOrientation(LinearLayout.VERTICAL);
    EditText p1=new EditText(this), p2=new EditText(this);
    for(EditText field : new EditText[]{p1,p2}) {
      field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      field.setSingleLine(true);
      fields.addView(field);
    }
    p1.setHint("Пароль для архива (от 14 символов)"); p2.setHint("Повтори пароль");
    new AlertDialog.Builder(this).setTitle("Защитить архив")
      .setMessage("Без этого пароля архив нельзя будет открыть. Не теряй его. Он не отправляется в Telegram.")
      .setView(fields).setNegativeButton("Отмена",null)
      .setPositiveButton("Выбрать место",(d,w)->{
        // Read into clearable arrays instead of retaining extra immutable password Strings.
        char[] a=extractAndClear(p1), b=extractAndClear(p2);
        boolean valid=a.length>=14 && a.length<=256 && Arrays.equals(a,b);
        Arrays.fill(b,'\0');
        if(!valid) { Arrays.fill(a,'\0'); showStatus("Пароль не совпадает или не укладывается в 14–256 символов. Повтори попытку.");return;}
        // Selection may have changed while the password dialog was open.
        // Never silently switch which private chat will be exported.
        if(dialogChatId!=selectedChatId || exporting || pendingPassphrase!=null) {
          Arrays.fill(a,'\0');
          showStatus("Выбранный чат изменился. Укажи пароль и место сохранения заново.");
          return;
        }
        // No password or pending chat choice goes into savedInstanceState.
        pendingChatId=dialogChatId;
        pendingPassphrase = a;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,"Vault_"+System.currentTimeMillis()+".tva");
        try {startActivityForResult(intent,CREATE_ARCHIVE);}
        catch(RuntimeException pickerFailure) {
          Arrays.fill(a,'\0');pendingPassphrase=null;pendingChatId=0;
          showStatus("Не удалось открыть локальное сохранение. Повтори выбор места и пароля.");
        }
      }).show();
  }
  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request,result,data);
    if(request==CREATE_HEAVY_EXPORT){
      if(result==RESULT_OK && data!=null && data.getData()!=null)saveHeavyExport(data.getData());
      else exporting=false;
      return;
    }
    if(request==CREATE_MEGA_EXPORT){
      if(result==RESULT_OK && data!=null && data.getData()!=null)saveMegaExport(data.getData());
      else exporting=false;
      return;
    }
    if(request==CREATE_SYNTHETIC_EXPORT){
      if(result==RESULT_OK&&data!=null&&data.getData()!=null)saveSyntheticExport(data.getData());
      else exporting=false;
      return;
    }
    if (request == CREATE_OFFLINE_FIXTURE) {
      if (result == RESULT_OK && data != null && data.getData() != null)
        saveOfflineFixture(data.getData());
      return;
    }
    if(request != CREATE_ARCHIVE) return;
    if(result!=RESULT_OK || data==null || data.getData()==null) {
      if(pendingPassphrase!=null)Arrays.fill(pendingPassphrase,'\0');
      pendingPassphrase=null;pendingChatId=0;return;
    }
    Uri destination=data.getData();
    // Android can recreate/kill our Activity while the document picker is open.
    // The password is intentionally NOT persisted in savedInstanceState. If it
    // vanished, reject the placeholder rather than launch with a null password.
    if (pendingPassphrase == null || pendingChatId == 0 || selectedChatId != pendingChatId || exporting) {
      if (pendingPassphrase != null) Arrays.fill(pendingPassphrase,'\0');
      pendingPassphrase=null;pendingChatId=0;
      try {getContentResolver().delete(destination,null,null);}catch(Exception ignored){}
      showStatus("Экспорт не начался: Android восстановил приложение без пароля или выбора чата. Выбери чат и пароль ещё раз.");
      return;
    }
    // Never accidentally upload a confidential archive through a cloud document provider.
    String provider=destination.getAuthority();
    if (!"com.android.externalstorage.documents".equals(provider) &&
        !"com.android.providers.downloads.documents".equals(provider) &&
        !"com.android.providers.media.documents".equals(provider)) {
      if (pendingPassphrase!=null) Arrays.fill(pendingPassphrase,'\0');
      pendingPassphrase=null;pendingChatId=0;
      try {getContentResolver().delete(destination,null,null);}catch(Exception ignored){}
      showStatus("Выбери локальную память телефона. Облачные диски не принимаются. Проверь также настройки автоматических копий на устройстве.");
      return;
    }
    char[] password=pendingPassphrase; pendingPassphrase=null;
    final long exportChatId=pendingChatId; // Identity is frozen before opening the picker.
    pendingChatId=0;
    exporting=true;
    clearUi(); message("Экспорт выполняется. Держи приложение открытым. Если связь прервётся, снова выбери тот же чат и нажми «Скачать всё / продолжить экспорт» — сохранённые зашифрованные данные останутся на телефоне.");
    showStatus("Создаём зашифрованный архив…");
    worker.execute(() -> {
      try {
        ExportEngine.Summary summary;
        try (OutputStream out=getContentResolver().openOutputStream(destination,"w")) {
          if(out == null) throw new IOException("Не удалось открыть файл назначения");
          summary=new ExportEngine(this,td,this::showStatus).export(exportChatId,out,password);
        }
        showStatus("Проверяем целостность всего архива…");
        int verified;
        try(InputStream in=getContentResolver().openInputStream(destination)) {
          if(in == null) throw new IOException("Невозможно проверить сохранённый файл");
          verified=ArchiveVerifier.verify(in,password);
        }
        int entries=verified;
        // Erase encrypted staging only AFTER the final archive has been verified.
        try {
          byte[] sessionKey=new SessionSecrets(this).getOrCreate();
          try(ResumeStore staging=new ResumeStore(new File(getFilesDir(),"export-progress"),exportChatId,sessionKey)) {
            if(staging.state().historyFinished) staging.erase();
          } finally {Arrays.fill(sessionKey,(byte)0);}
        } catch(Exception cleanupError) {
          showStatus("Архив проверен, но временные зашифрованные данные не очищены автоматически.");
        }

        runOnUiThread(()-> {
          exporting=false; clearUi();
          message("Архив проверен! Сообщений: "+summary.messages+", медиафайлов: "+summary.media+
            ", пропущенных файлов: "+summary.missing+". ZIP-записей: "+entries);
          if(summary.missing!=0 || !summary.historyFinished)
            message("ВНИМАНИЕ: архив неполный. Читалка сможет показать сохранённое, но проверь пропущенные файлы.");
          message("Пароль сохранён только в твоей памяти: приложение не сможет его восстановить.");
          button("Завершить сеанс экспортёра",this::confirmLogout);
          button("Выбрать другой чат",()->{clearUi();showReady();});
          showStatus("Архив проверен. Заверши сеанс для максимальной приватности.");
        });
      } catch(Exception e) {
        boolean unsafeCache=e instanceof ExportEngine.CachePrivacyException;
        boolean removed=false;
        try {removed=getContentResolver().delete(destination,null,null)>0;}
        catch(Exception ignored) { /* Display cleanup warning, never claim success. */ }
        final boolean partialRemoved=removed;
        final String explanation=unsafeCache
          ? "ВНИМАНИЕ: не удалось подтвердить удаление незашифрованного временного файла. Экспорт остановлен ради приватности. Не повторяй его до очистки данных Vault Exporter в настройках Android. Это удалит сеанс и незавершённый прогресс."
          : e instanceof ExportEngine.StorageCapacityException
            ? ((ExportEngine.StorageCapacityException)e).userMessage()
            : "Экспорт прерван. Зашифрованный прогресс сохранён локально, можно попробовать продолжить позже.";
        showStatus(explanation+(partialRemoved?"":" Также проверь папку сохранения и удали неполный файл вручную."));
        runOnUiThread(()-> {
          exporting=false;clearUi();
          message(explanation);
          if(!partialRemoved)message("Удаление неполного выходного архива не подтверждено. Проверь выбранную папку.");
          button("Вернуться к выбору чата",()->{clearUi();showReady();});
          if(unsafeCache && !isFinishing() && !isDestroyed())
            new AlertDialog.Builder(this).setTitle("Защита личных данных")
              .setMessage(explanation)
              .setCancelable(false).setPositiveButton("Понятно",null).show();
        });
      } finally { Arrays.fill(password,'\0'); }
    });
  }
  private void confirmResetProgress() {
    if(exporting || pendingPassphrase!=null)return;
    if(selectedChatId==0){showStatus("Сначала выбери чат.");return;}
    final long resetChatId=selectedChatId; // Freeze the target before queueing the worker.
    new AlertDialog.Builder(this).setTitle("Удалить незавершённую загрузку?")
      .setMessage("Временные зашифрованные сообщения и медиа выбранного чата будут удалены. Сохранённые .tva архивы не затрагиваются. Для нового экспорта придётся скачать всё заново.")
      .setNegativeButton("Отмена",null).setPositiveButton("Удалить прогресс",(dialog,index)->{
        worker.execute(()-> {
          byte[] secret=null;
          try{
            secret=new SessionSecrets(this).getOrCreate();
            try(ResumeStore store=new ResumeStore(new File(getFilesDir(),"export-progress"),resetChatId,secret)){
              store.erase();
            }
            showStatus("Промежуточные данные удалены. Можно начать новый экспорт.");
          }catch(Exception e){showStatus("Не удалось удалить прогресс: "+e.getMessage());}
          finally{if(secret!=null)Arrays.fill(secret,(byte)0);}
        });
      }).show();
  }
  private void confirmLogout() {
    if(exporting)return;
    new AlertDialog.Builder(this).setTitle("Завершить сеанс Vault?")
      .setMessage("Telegram завершит только сеанс, созданный этим экспортёром. Основное приложение Telegram останется подключённым. Незавершённые зашифрованные загрузки будут удалены. Для следующего экспорта потребуется снова войти.")
      .setNegativeButton("Остаться",null).setPositiveButton("Завершить сеанс",(dialog,n) -> {
        showStatus("Выходим из сеанса экспортёра…");
        logoutRequested=true;
        worker.execute(()-> {
          boolean telegramLogoutConfirmed=false;
          try{
            // Never destroy an incomplete export BEFORE Telegram confirms logout.
            td.await(TdGateway.cmd("logOut"),120);
            telegramLogoutConfirmed=true;
            File progressDir=new File(getFilesDir(),"export-progress");
            File[] chats=progressDir.listFiles();
            if(chats!=null) for(File chat:chats) erasePrivateTree(chat);
            // Removing the wrapping key makes any leftover encrypted staging unreadable.
            // Do it only after a confirmed logout, not after a network error.
            new SessionSecrets(this).discard();
            showStatus("Сеанс экспортёра завершён, локальный ключ удалён.");
          } catch(Exception e){
            if(telegramLogoutConfirmed){
              try{new SessionSecrets(this).discard();}
              catch(Exception keyError){showStatus("ВНИМАНИЕ: Telegram отключён, но не удалось удалить ключ. Не используйте устройство с конфиденциальными данными до исправления.");return;}
              showStatus("Telegram отключён и ключ удалён, но часть временных файлов могла остаться. Очистите данные приложения в настройках Android.");
            } else {
              showStatus("Telegram не подтвердил выход. Незавершённый экспорт и ключ сохранены: "+e.getClass().getSimpleName());
            }
          } finally {logoutRequested=false;}
        });
      }).show();
  }
  private static void erasePrivateTree(File item) throws IOException {
    File[] children=item.listFiles();
    if(children!=null)for(File child:children)erasePrivateTree(child);
    if(item.exists() && !item.delete())throw new IOException("Не удалось очистить закрытое хранилище");
  }
  @Override protected void onDestroy() {
    // Abandoning the picker must not retain the vault password in the Activity.
    if(pendingPassphrase!=null){Arrays.fill(pendingPassphrase,'\0');pendingPassphrase=null;}
    pendingChatId=0;
    if(td!=null) td.stop();
    worker.shutdownNow();
    super.onDestroy();
  }
}
