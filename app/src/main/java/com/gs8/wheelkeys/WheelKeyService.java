package com.gs8.wheelkeys;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
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
 * v3.3 (финальная схема):
 *  - кнопки руля ловим из ЛОГА (CanKeyHelper) -> шлём в HUR. Надёжно при любом источнике/фокусе.
 *  - "HUR-режим": держим источник OFF (4097), чтобы радио не реагировало (безопасно, HUR не ломает).
 *  - переключатель режима (ВКЛ по умолчанию).
 */
public class WheelKeyService extends Service {

    private static final String TAG = "WheelKeys";
    private static final String CH_ID = "wheelkeys";
    private static final int NOTE_ID = 1;
    private static final String PREF = "wk";

    public static final String ACTION_TOGGLE = "com.gs8.wheelkeys.TOGGLE";
    public static final String ACTION_SRC = "com.gs8.wheelkeys.SRC";

    private static final String SVC_PKG = "com.zhonghong.logic";
    private static final String SVC_CLS = "com.zhonghong.logic.service.LogicService";
    private static final int DOMAIN_SOURCE = 2;
    private static final int ID_OFF = 4097;      // ID_OFF_ARM — глушит радио, HUR не ломает

    public static volatile boolean sRunning = false;
    public static volatile boolean sBound = false;
    public static volatile boolean sSilence = true;   // HUR-режим: глушить радио
    public static volatile int sSource = -999;
    public static volatile int sFwdCount = 0;
    public static volatile String sLastKey = "—";

    private ILogicInterface mLogic;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private long mLastFwd = 0L;
    private static final long DEBOUNCE_MS = 300L;
    private Thread mLogThread;
    private volatile boolean mLogRun = false;

    private final Runnable mWatchdog = new Runnable() {
        @Override public void run() { silenceTick(); mMain.postDelayed(this, 3000); }
    };

    @Override public void onCreate() {
        super.onCreate();
        sRunning = true;
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        sSilence = sp.getBoolean("silence", true);
        startAsForeground();
        Log.i(TAG, "service created (v3.3) silence=" + sSilence);
        bindLogic();
        startLogcatWatcher();
        mMain.postDelayed(mWatchdog, 1500);
    }

    @Override public int onStartCommand(Intent intent, int f, int id) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACTION_TOGGLE.equals(a)) {
                sSilence = !sSilence;
                getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("silence", sSilence).apply();
                Log.i(TAG, "silence -> " + sSilence);
                if (!sSilence) cmd("resumeLastSource", "");   // выключили режим — вернуть звук/радио
                else silenceTick();
            } else if (ACTION_SRC.equals(a)) {
                cmd("setSourceId", "" + intent.getIntExtra("id", ID_OFF));
            }
        }
        if (!sBound) bindLogic();
        if (!mLogRun) startLogcatWatcher();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        mMain.removeCallbacks(mWatchdog);
        // вернуть источник, чтобы не оставлять ГУ в OFF
        if (sSilence) cmd("resumeLastSource", "");
        stopLogcatWatcher(); unbindLogic(); sRunning = false;
        super.onDestroy(); Log.i(TAG, "destroyed");
    }
    @Override public IBinder onBind(Intent i) { return null; }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Wheel Keys",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false); nm.createNotificationChannel(ch);
        }
        startForeground(NOTE_ID, new Notification.Builder(this, CH_ID)
                .setContentTitle("GS8 Wheel Keys").setContentText("Кнопки руля -> HUR")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build());
    }

    // ---------------- bind (для управления источником) ----------------
    private final ServiceConnection mConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            mLogic = ILogicInterface.Stub.asInterface(b); sBound = true;
            Log.i(TAG, "CONNECTED");
            try { mLogic.registerLogicCallback(DOMAIN_SOURCE, mCallback); } catch (RemoteException e) {}
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            sBound = false; mLogic = null;
            mMain.postDelayed(WheelKeyService.this::bindLogic, 2000);
        }
    };
    private void bindLogic() {
        try { Intent it = new Intent(SVC_CLS); it.setComponent(new ComponentName(SVC_PKG, SVC_CLS));
            Log.i(TAG, "bindService=" + bindService(it, mConn, Context.BIND_AUTO_CREATE)); }
        catch (Exception e) { Log.w(TAG, "bind err " + e); }
    }
    private void unbindLogic() {
        try { if (sBound && mLogic != null)
                try { mLogic.unregisterLogicCallback(DOMAIN_SOURCE, mCallback); } catch (Exception ig) {}
            unbindService(mConn); } catch (Exception ig) {}
        sBound = false; mLogic = null;
    }
    private final ILogicCallback.Stub mCallback = new ILogicCallback.Stub() {
        @Override public void onEventChanged(String e, String v, String x) {
            if ("onSourceChanged".equals(e)) sSource = parse(v);
        }
    };

    private int getSrc() { try { return Integer.parseInt(mLogic.getValue("getSourceId", "").trim()); } catch (Exception e) { return -999; } }
    private void cmd(String c, String v) {
        if (mLogic == null) return;
        try { mLogic.setCmd(c, v, null); Log.i(TAG, "CMD " + c + "(" + v + ")"); } catch (Exception e) { Log.w(TAG, "cmd err " + e); }
    }

    /** держим источник OFF, пока включён HUR-режим и текущий источник — радио/медиа */
    private void silenceTick() {
        if (!sSilence || mLogic == null) return;
        int s = getSrc(); sSource = s;
        if (s == 0 || s == 1) cmd("setSourceId", "" + ID_OFF);   // радио/медиа -> OFF
    }

    // ---------------- кнопки из лога ----------------
    private void startLogcatWatcher() {
        if (mLogRun) return;
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) { Log.i(TAG, "no READ_LOGS"); return; }
        mLogRun = true; mLogThread = new Thread(this::logcatLoop, "wk-logcat");
        mLogThread.setDaemon(true); mLogThread.start();
    }
    private void stopLogcatWatcher() { mLogRun = false; if (mLogThread != null) { mLogThread.interrupt(); mLogThread = null; } }
    private void logcatLoop() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"logcat","-T","1","-v","brief","CanKeyHelper:I","*:S"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while (mLogRun && (line = br.readLine()) != null) {
                int key = 0;
                if (line.contains("KEYCODE_NEXT")) key = KeyEvent.KEYCODE_MEDIA_NEXT;
                else if (line.contains("KEYCODE_PREV")) key = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                else if (line.contains("KEYCODE_EASYCONN")) key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                if (key != 0) { final int k = key; mMain.post(() -> forward(k)); }
            }
        } catch (Exception e) { Log.w(TAG, "logcat err " + e); }
        finally { if (p != null) p.destroy(); }
        if (mLogRun) { mLogRun = false; mMain.postDelayed(this::startLogcatWatcher, 5000); }
    }

    private void forward(int keyCode) {
        long now = SystemClock.uptimeMillis();
        if (now - mLastFwd < DEBOUNCE_MS) return;
        mLastFwd = now;
        Hur.sendMediaKey(getApplicationContext(), keyCode);
        sFwdCount++; sLastKey = "" + keyCode;
    }
    private static int parse(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -999; } }
}
