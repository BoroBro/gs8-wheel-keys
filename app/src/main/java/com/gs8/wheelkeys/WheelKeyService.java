package com.gs8.wheelkeys;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.logic.common.aidl.ILogicCallback;
import com.logic.common.aidl.ILogicInterface;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Делает приложение источником №15 ZHLogic, когда активен HUR, и
 * пересылает кнопки руля в HUR. next/prev — через onMediaButtonPressed
 * (чистый AIDL-канал), play/pause — через слежение за логкатом (EASYCONN).
 */
public class WheelKeyService extends Service {

    private static final String TAG = "WheelKeys";
    private static final String CH_ID = "wheelkeys";
    private static final int NOTE_ID = 1;

    private static final String SVC_PKG = "com.zhonghong.logic";
    private static final String SVC_CLS = "com.zhonghong.logic.service.LogicService";
    private static final int DOMAIN_SOURCE = 2;
    private static final int ID_THIRD_APP = 15;

    private static final String EV_SOURCE = "onSourceChanged";
    private static final String EV_THIRD  = "onThirdSourceChanged";
    private static final String EV_MEDIA  = "onMediaButtonPressed";

    // --- состояние для UI (читается из MainActivity) ---
    public static volatile boolean sRunning = false;
    public static volatile boolean sBound = false;
    public static volatile boolean sRegistered = false;
    public static volatile boolean sWant15 = false;
    public static volatile int sCurSource = -999;
    public static volatile int sThird = -999;
    public static volatile int sFwdCount = 0;
    public static volatile String sLastEvent = "—";
    public static volatile String sLastFwd = "—";

    private ILogicInterface mLogic;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private long mLastFwd = 0L;
    private static final long FWD_DEBOUNCE_MS = 350L;

    private Thread mLogThread;
    private volatile boolean mLogRun = false;

    private final Runnable mWatchdog = new Runnable() {
        @Override public void run() {
            pollAndClaim();
            mMain.postDelayed(this, 2000);
        }
    };

    // ---------------------------------------------------------------- lifecycle
    @Override public void onCreate() {
        super.onCreate();
        sRunning = true;
        startAsForeground();
        Log.i(TAG, "service created");
        bindLogic();
        startLogcatWatcher();
        mMain.postDelayed(mWatchdog, 1500);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!sBound) bindLogic();
        if (!mLogRun) startLogcatWatcher();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        mMain.removeCallbacks(mWatchdog);
        stopLogcatWatcher();
        unbindLogic();
        sRunning = false;
        super.onDestroy();
        Log.i(TAG, "service destroyed");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- foreground
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

    // ---------------------------------------------------------------- ZHLogic bind
    private final ServiceConnection mConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            mLogic = ILogicInterface.Stub.asInterface(binder);
            sBound = true;
            Log.i(TAG, "LogicService CONNECTED");
            try {
                mLogic.registerLogicCallback(DOMAIN_SOURCE, mCallback);
                sRegistered = true;
                Log.i(TAG, "registerLogicCallback(domain=2) OK");
            } catch (RemoteException e) { Log.w(TAG, "register err: " + e); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            sBound = false; sRegistered = false; mLogic = null;
            Log.w(TAG, "LogicService DISCONNECTED, rebind in 2s");
            mMain.postDelayed(WheelKeyService.this::bindLogic, 2000);
        }
    };

    private void bindLogic() {
        try {
            Intent it = new Intent(SVC_CLS);
            it.setComponent(new ComponentName(SVC_PKG, SVC_CLS));
            boolean ok = bindService(it, mConn, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService=" + ok);
        } catch (Exception e) { Log.w(TAG, "bind err: " + e); }
    }

    private void unbindLogic() {
        try {
            if (sBound && mLogic != null)
                try { mLogic.unregisterLogicCallback(DOMAIN_SOURCE, mCallback); } catch (Exception ignore) {}
            unbindService(mConn);
        } catch (Exception ignore) {}
        sBound = false; sRegistered = false; mLogic = null;
    }

    // ---------------------------------------------------------------- source logic
    private int getInt(String key) {
        try { return Integer.parseInt(mLogic.getValue(key, "").trim()); }
        catch (Exception e) { return -999; }
    }

    private void claimSource(String why) {
        if (mLogic == null) return;
        try {
            int cur = getInt("getSourceId");
            if (cur != ID_THIRD_APP) {
                mLogic.setCmd("setSourceId", "" + ID_THIRD_APP, null);
                Log.i(TAG, "CLAIM setSourceId(15) [" + why + "] prev=" + cur);
            }
        } catch (Exception e) { Log.w(TAG, "claim err: " + e); }
    }

    /** Периодический сторож: держим источник 15, пока HUR владеет звуком. */
    private void pollAndClaim() {
        if (mLogic == null) return;
        int src = getInt("getSourceId");
        int third = getInt("getThirdSourceId");
        sCurSource = src; sThird = third;
        // HUR владеет звуком -> хотим источник 15
        if (third == ID_THIRD_APP) sWant15 = true;
        // пользователь ушёл на локальный источник (радио/медиа) -> отпускаем
        if (src != ID_THIRD_APP && third != ID_THIRD_APP && third != -999
                && src != -999 && src != 13 && src != 14) {
            sWant15 = false;
        }
        if (sWant15 && src != ID_THIRD_APP) claimSource("watchdog third=" + third);
    }

    private final ILogicCallback.Stub mCallback = new ILogicCallback.Stub() {
        @Override public void onEventChanged(String event, String value, String extra) {
            sLastEvent = event + " v=" + value + " x=" + extra;
            Log.i(TAG, "EVT " + sLastEvent);
            try {
                if (EV_THIRD.equals(event)) {
                    if (parse(value) == ID_THIRD_APP) {
                        sWant15 = true;
                        mMain.post(() -> claimSource("onThirdSourceChanged=15"));
                    }
                } else if (EV_MEDIA.equals(event)) {
                    int key = parse(value), src = parse(extra);
                    if (src == ID_THIRD_APP) forwardToHur(key);
                    else Log.i(TAG, "media key " + key + " ignored (src=" + src + ")");
                }
            } catch (Exception e) { Log.w(TAG, "evt err: " + e); }
        }
    };

    private void forwardToHur(int keyCode) {
        long now = SystemClock.uptimeMillis();
        if (now - mLastFwd < FWD_DEBOUNCE_MS) return;
        mLastFwd = now;
        Hur.sendMediaKey(getApplicationContext(), keyCode);
        sFwdCount++;
        sLastFwd = "key " + keyCode + " @" + (now / 1000 % 1000);
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return Integer.MIN_VALUE; }
    }

    // ---------------------------------------------------------------- logcat (play/pause)
    private void startLogcatWatcher() {
        if (mLogRun) return;
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_LOGS не выдан — play/pause выключен (next/prev работают)");
            return;
        }
        mLogRun = true;
        mLogThread = new Thread(this::logcatLoop, "wk-logcat");
        mLogThread.setDaemon(true);
        mLogThread.start();
        Log.i(TAG, "logcat watcher started (play/pause)");
    }

    private void stopLogcatWatcher() {
        mLogRun = false;
        if (mLogThread != null) { mLogThread.interrupt(); mLogThread = null; }
    }

    private void logcatLoop() {
        Process proc = null;
        try {
            // НЕ чистим общий лог (logcat -c) — только читаем новое
            proc = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-T", "1", "-v", "brief", "CanKeyHelper:I", "*:S"});
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while (mLogRun && (line = br.readLine()) != null) {
                if (line.contains("KEYCODE_EASYCONN")) {
                    mMain.post(() -> {
                        forwardToHur(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                        mMain.postDelayed(() -> claimSource("after play/pause"), 500);
                        mMain.postDelayed(() -> claimSource("after play/pause"), 1400);
                    });
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "logcat loop err: " + e);
        } finally {
            if (proc != null) proc.destroy();
        }
        if (mLogRun) { mLogRun = false; mMain.postDelayed(this::startLogcatWatcher, 5000); }
    }
}
