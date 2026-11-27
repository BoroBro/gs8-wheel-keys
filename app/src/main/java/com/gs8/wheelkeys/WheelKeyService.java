package com.gs8.wheelkeys;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * v3: ловит кнопки руля из системного лога (CanKeyHelper) и пересылает в HUR.
 * НИЧЕГО не захватывает в ГУ (никаких источников) — поэтому HUR не ломает.
 *   KEYCODE_NEXT     -> 87 (next)
 *   KEYCODE_PREV     -> 88 (prev)
 *   KEYCODE_EASYCONN -> 85 (play/pause, длинная кнопка голоса)
 */
public class WheelKeyService extends Service {

    private static final String TAG = "WheelKeys";
    private static final String CH_ID = "wheelkeys";
    private static final int NOTE_ID = 1;
    private static final long DEBOUNCE_MS = 300L;

    public static volatile boolean sRunning = false;
    public static volatile boolean sWatching = false;
    public static volatile boolean sReadLogs = false;
    public static volatile int sFwdCount = 0;
    public static volatile String sLastKey = "—";
    public static volatile String sLastLine = "—";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Thread mLogThread;
    private volatile boolean mLogRun = false;
    private long mLastFwd = 0L;
    private int mLastKeyCode = 0;

    @Override public void onCreate() {
        super.onCreate();
        sRunning = true;
        startAsForeground();
        Log.i(TAG, "service created (v3 logcat forwarder)");
        startWatcher();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mLogRun) startWatcher();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopWatcher();
        sRunning = false;
        super.onDestroy();
        Log.i(TAG, "service destroyed");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Wheel Keys",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CH_ID)
                .setContentTitle("GS8 Wheel Keys")
                .setContentText("Кнопки руля -> HUR")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true).build();
        startForeground(NOTE_ID, n);
    }

    // ------------------------------------------------------------- logcat watcher
    private void startWatcher() {
        if (mLogRun) return;
        sReadLogs = checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!sReadLogs) {
            Log.i(TAG, "READ_LOGS не выдан — кнопки работать не будут");
            return;
        }
        mLogRun = true;
        mLogThread = new Thread(this::logcatLoop, "wk-logcat");
        mLogThread.setDaemon(true);
        mLogThread.start();
        Log.i(TAG, "logcat watcher started");
    }

    private void stopWatcher() {
        mLogRun = false;
        sWatching = false;
        if (mLogThread != null) { mLogThread.interrupt(); mLogThread = null; }
    }

    private void logcatLoop() {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-T", "1", "-v", "brief", "CanKeyHelper:I", "*:S"});
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            sWatching = true;
            String line;
            while (mLogRun && (line = br.readLine()) != null) {
                int key = 0;
                if (line.contains("KEYCODE_NEXT"))          key = KeyEvent.KEYCODE_MEDIA_NEXT;      // 87
                else if (line.contains("KEYCODE_PREV"))     key = KeyEvent.KEYCODE_MEDIA_PREVIOUS;  // 88
                else if (line.contains("KEYCODE_EASYCONN")) key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;// 85
                if (key != 0) {
                    final int k = key;
                    final String ln = line;
                    mMain.post(() -> handleKey(k, ln));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "logcat loop err: " + e);
        } finally {
            if (proc != null) proc.destroy();
            sWatching = false;
        }
        if (mLogRun) { mLogRun = false; mMain.postDelayed(this::startWatcher, 5000); }
    }

    private void handleKey(int keyCode, String line) {
        long now = SystemClock.uptimeMillis();
        // анти-дребезг: тот же код в пределах окна — пропускаем
        if (keyCode == mLastKeyCode && now - mLastFwd < DEBOUNCE_MS) return;
        if (now - mLastFwd < 120) return;
        mLastFwd = now; mLastKeyCode = keyCode;
        Hur.sendMediaKey(getApplicationContext(), keyCode);
        sFwdCount++;
        sLastKey = keyName(keyCode);
        sLastLine = line.length() > 80 ? line.substring(line.length() - 80) : line;
    }

    private String keyName(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "NEXT (87)";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "PREV (88)";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "PLAY/PAUSE (85)";
            default: return "" + k;
        }
    }
}
