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
 * пересылает кнопки руля в HUR:
 *   - next/prev  -> чистый канал onMediaButtonPressed (источник 15)
 *   - play/pause -> опциональное слежение за логкатом (кнопка EASYCONN)
 */
public class WheelKeyService extends Service {

    private static final String TAG = "WheelKeys";
    private static final String CH_ID = "wheelkeys";
    private static final int NOTE_ID = 1;

    // ZHLogic
    private static final String SVC_PKG = "com.zhonghong.logic";
    private static final String SVC_CLS = "com.zhonghong.logic.service.LogicService";
    private static final int DOMAIN_SOURCE = 2;
    private static final int ID_THIRD_APP = 15;   // ID_THIRD_AUDIO_APP
    private static final int ID_NULL = 4096;

    // события
    private static final String EV_SOURCE = "onSourceChanged";
    private static final String EV_THIRD = "onThirdSourceChanged";
    private static final String EV_MEDIA = "onMediaButtonPressed";

    private ILogicInterface mLogic;
    private volatile boolean mBound = false;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    // анти-дребезг пересылки в HUR
    private long mLastFwd = 0L;
    private static final long FWD_DEBOUNCE_MS = 350L;

    // слежение за логкатом (play/pause)
    private Thread mLogThread;
    private volatile boolean mLogRun = false;

    // ---------------------------------------------------------------- lifecycle
    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        bindLogic();
        startLogcatWatcher();
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mBound) bindLogic();
        if (!mLogRun) startLogcatWatcher();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopLogcatWatcher();
        unbindLogic();
        super.onDestroy();
        Log.i(TAG, "service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- foreground
    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Wheel Keys", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CH_ID)
                .setContentTitle("GS8 Wheel Keys")
                .setContentText("Кнопки руля -> HUR")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        startForeground(NOTE_ID, n);
    }

    // ---------------------------------------------------------------- ZHLogic bind
    private final ServiceConnection mConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            mLogic = ILogicInterface.Stub.asInterface(binder);
            mBound = true;
            Log.i(TAG, "LogicService connected");
            try {
                mLogic.registerLogicCallback(DOMAIN_SOURCE, mCallback);
                Log.i(TAG, "registered source callback");
            } catch (RemoteException e) { Log.w(TAG, "register err: " + e); }
            // если HUR уже играет на момент старта — сразу забираем источник
            mMain.postDelayed(WheelKeyService.this::claimIfThirdActive, 800);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            mBound = false; mLogic = null;
            Log.w(TAG, "LogicService disconnected, rebind");
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
            if (mBound && mLogic != null) {
                try { mLogic.unregisterLogicCallback(DOMAIN_SOURCE, mCallback); } catch (Exception ignore) {}
            }
            unbindService(mConn);
        } catch (Exception ignore) {}
        mBound = false; mLogic = null;
    }

    // ---------------------------------------------------------------- source logic
    private int getSourceId() {
        try { return Integer.parseInt(mLogic.getValue("getSourceId", "")); }
        catch (Exception e) { return -1; }
    }

    private int getThirdSourceId() {
        try { return Integer.parseInt(mLogic.getValue("getThirdSourceId", "")); }
        catch (Exception e) { return -1; }
    }

    private void claimSource() {
        if (mLogic == null) return;
        try {
            if (getSourceId() != ID_THIRD_APP) {
                mLogic.setCmd("setSourceId", "" + ID_THIRD_APP, null);
                Log.i(TAG, "claim: setSourceId(15)");
            }
        } catch (Exception e) { Log.w(TAG, "claim err: " + e); }
    }

    private void reclaimAfterPlayPause() {
        if (mLogic == null) return;
        int src = getSourceId();
        if (src != ID_THIRD_APP) {
            Log.i(TAG, "reclaim after play/pause: source was " + src + " -> 15");
            claimSource();
        }
    }

    private void claimIfThirdActive() {
        if (mLogic == null) return;
        if (getThirdSourceId() == ID_THIRD_APP) claimSource();
    }

    private final ILogicCallback.Stub mCallback = new ILogicCallback.Stub() {
        @Override public void onEventChanged(String event, String value, String extra) {
            try {
                if (EV_THIRD.equals(event)) {
                    int third = parse(value);
                    // HUR взял фокус (старт/возврат с радио/после звонка) -> забираем источник
                    if (third == ID_THIRD_APP) mMain.post(WheelKeyService.this::claimSource);
                    // 4096 (пауза HUR) / 13 (звонок) / 14 (голос) — НЕ трогаем, источник держим
                } else if (EV_MEDIA.equals(event)) {
                    int key = parse(value);
                    int src = parse(extra);
                    if (src == ID_THIRD_APP) forwardToHur(key);
                    // если источник локальный — игнор, пусть он сам обрабатывает
                }
                // EV_SOURCE: информативно; явный уход на локальный источник отдаём сами собой
            } catch (Exception e) { Log.w(TAG, "evt err: " + e); }
        }
    };

    private void forwardToHur(int keyCode) {
        long now = SystemClock.uptimeMillis();
        if (now - mLastFwd < FWD_DEBOUNCE_MS) return;
        mLastFwd = now;
        Hur.sendMediaKey(getApplicationContext(), keyCode);
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return Integer.MIN_VALUE; }
    }

    // ---------------------------------------------------------------- logcat (play/pause)
    private void startLogcatWatcher() {
        if (mLogRun) return;
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_LOGS не выдан — play/pause отключён (next/prev работают)");
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
            try { Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor(); } catch (Exception ignore) {}
            proc = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-v", "brief", "CanKeyHelper:I", "*:S"});
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while (mLogRun && (line = br.readLine()) != null) {
                // кнопка голоса (длинное нажатие EASYCONN) -> play/pause
                if (line.contains("KEYCODE_EASYCONN")) {
                    mMain.post(() -> {
                        forwardToHur(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                        // Прошивка на этой кнопке пытается запустить EasyConn и
                        // уводит источник от 15 -> возвращаем себе источник.
                        mMain.postDelayed(WheelKeyService.this::reclaimAfterPlayPause, 500);
                        mMain.postDelayed(WheelKeyService.this::reclaimAfterPlayPause, 1400);
                    });
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "logcat loop err: " + e);
        } finally {
            if (proc != null) proc.destroy();
        }
        // авто-перезапуск, если нас не останавливали
        if (mLogRun) {
            mLogRun = false;
            mMain.postDelayed(this::startLogcatWatcher, 3000);
        }
    }
}
