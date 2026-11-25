package com.gs8.wheelkeys;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Фоновая служба: читает системный лог, ловит нажатия кнопок руля
 * (строки от штатной службы com.zhonghong.logic) и пересылает
 * медиакоманды в Headunit Revived (HUR).
 *
 * Весь рантайм — без ADB. ADB нужен только один раз, чтобы выдать
 * право READ_LOGS. После этого служба работает сама и переживает
 * перезагрузку (запускается из BootReceiver).
 */
public class KeyService extends Service {

    public static final String TAG = "GS8WheelKeys";
    private static final String HUR_PACKAGE = "com.andrerinas.headunitrevived";
    private static final String CHANNEL_ID = "gs8wheelkeys";
    private static final int NOTIF_ID = 42;
    private static final long DEBOUNCE_MS = 400;   // не повторять то же действие чаще, чем раз в 400 мс

    private volatile boolean running = false;
    private Thread readerThread;
    private final Map<String, Long> lastFired = new HashMap<>();
    private AudioManager audioManager;

    public static volatile String lastEvent = "—";   // для показа на экране приложения

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Слежу за кнопками руля"));
        if (!running) {
            running = true;
            readerThread = new Thread(this::readLogcat, "logcat-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }
        return START_STICKY;   // система перезапустит службу, если убьёт
    }

    /** Основной цикл: запускаем logcat только по нужным тегам и читаем построчно. */
    private void readLogcat() {
        while (running) {
            Process p = null;
            try {
                // -c очищает старый буфер, чтобы не проигрывать прошлые нажатия
                try { Runtime.getRuntime().exec("logcat -c").waitFor(); } catch (Exception ignore) {}

                // Берём только строки CanKeyHelper, остальное глушим (*:S) — минимум нагрузки
                p = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-v", "brief", "CanKeyHelper:I", "*:S"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                List<KeyMap.Rule> rules = KeyMap.rules();
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.indexOf("translatePanelKey") < 0) continue;
                    for (KeyMap.Rule rule : rules) {
                        if (line.contains(rule.logMatch)) {
                            handleRule(rule);
                            break;   // одно совпадение на строку
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "ошибка чтения logcat", e);
            } finally {
                if (p != null) p.destroy();
            }
            // если logcat по какой-то причине завершился — подождём и перезапустим
            if (running) {
                try { Thread.sleep(1500); } catch (InterruptedException ignore) {}
            }
        }
    }

    private void handleRule(KeyMap.Rule rule) {
        long now = SystemClock.uptimeMillis();
        Long prev = lastFired.get(rule.logMatch);
        if (prev != null && (now - prev) < DEBOUNCE_MS) return;   // антидребезг/антиповтор
        lastFired.put(rule.logMatch, now);

        lastEvent = rule.label + "  (" + timeStr() + ")";
        if (rule.mediaKeyCode == KeyMap.ACTION_NONE) {
            Log.i(TAG, "совпало: " + rule.label + " — оставляем штатное поведение");
            return;
        }
        Log.i(TAG, "совпало: " + rule.label + " -> медиа-код " + rule.mediaKeyCode);
        sendMediaKeyToHur(rule.mediaKeyCode);
    }

    /**
     * Отправка медиакнопки в HUR.
     * Основной способ — адресный broadcast MEDIA_BUTTON прямо в приёмник HUR
     * (у него объявлен MediaButtonReceiver). Это проверено как рабочий путь.
     * Резервный способ — AudioManager.dispatchMediaKeyEvent (общесистемная
     * доставка активной медиасессии), ровно как делает `input keyevent`.
     */
    private void sendMediaKeyToHur(int keyCode) {
        long now = SystemClock.uptimeMillis();
        boolean delivered = false;
        try {
            for (int act : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                KeyEvent ke = new KeyEvent(now, now, act, keyCode, 0);
                Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON);
                i.setPackage(HUR_PACKAGE);
                i.putExtra(Intent.EXTRA_KEY_EVENT, ke);
                sendBroadcast(i);
            }
            delivered = true;
        } catch (Exception e) {
            Log.e(TAG, "broadcast в HUR не прошёл", e);
        }

        // Резерв: системная доставка активной медиасессии
        if (!delivered || ALWAYS_ALSO_DISPATCH) {
            try {
                for (int act : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                    KeyEvent ke = new KeyEvent(now, now, act, keyCode, 0);
                    audioManager.dispatchMediaKeyEvent(ke);
                }
            } catch (Throwable t) {
                Log.w(TAG, "dispatchMediaKeyEvent недоступен: " + t.getMessage());
            }
        }
    }

    // Если true — шлём и broadcast, и dispatch (подстраховка). Если в машине
    // окажется, что трек переключается дважды — поставьте false.
    private static final boolean ALWAYS_ALSO_DISPATCH = false;

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "GS8 Wheel Keys", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("GS8 Wheel Keys")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    private static String timeStr() {
        return android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (readerThread != null) readerThread.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
