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
 * v3.2 (эксперимент): кнопку шлём в HUR всегда (из onMediaButtonPressed, любой src).
 * Плюс команды ручного управления источником через startService:
 *   action SRC extra "id" = N  -> setSourceId(N)
 *   action REL              -> releaseSourceId(15)
 *   action RESUME           -> resumeLastSource
 * Чтобы на месте найти, что убирает радио и не ломает HUR.
 */
public class WheelKeyService extends Service {

    private static final String TAG = "WheelKeys";
    private static final String CH_ID = "wheelkeys";
    private static final int NOTE_ID = 1;

    public static final String ACTION_SRC = "com.gs8.wheelkeys.SRC";
    public static final String ACTION_REL = "com.gs8.wheelkeys.REL";
    public static final String ACTION_RESUME = "com.gs8.wheelkeys.RESUME";

    private static final String SVC_PKG = "com.zhonghong.logic";
    private static final String SVC_CLS = "com.zhonghong.logic.service.LogicService";
    private static final int DOMAIN_SOURCE = 2;

    private static final String EV_THIRD = "onThirdSourceChanged";
    private static final String EV_MEDIA = "onMediaButtonPressed";
    private static final String EV_SOURCE = "onSourceChanged";

    public static volatile boolean sRunning = false;
    public static volatile boolean sBound = false;
    public static volatile int sSource = -999;
    public static volatile int sThird = -999;
    public static volatile int sFwdCount = 0;
    public static volatile String sLastEvent = "—";
    public static volatile String sLastKey = "—";

    private ILogicInterface mLogic;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private long mLastFwd = 0L;
    private static final long DEBOUNCE_MS = 300L;
    private Thread mLogThread;
    private volatile boolean mLogRun = false;

    @Override public void onCreate() {
        super.onCreate();
        sRunning = true;
        startAsForeground();
        Log.i(TAG, "service created (v3.2 exp)");
        bindLogic();
        startLogcatWatcher();
    }

    @Override public int onStartCommand(Intent intent, int f, int id) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACTION_SRC.equals(a))      setSource(intent.getIntExtra("id", 0));
            else if (ACTION_REL.equals(a)) cmd("releaseSourceId", "15");
            else if (ACTION_RESUME.equals(a)) cmd("resumeLastSource", "");
        }
        if (!sBound) bindLogic();
        if (!mLogRun) startLogcatWatcher();
        return START_STICKY;
    }

    @Override public void onDestroy() {
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

    private final ServiceConnection mConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            mLogic = ILogicInterface.Stub.asInterface(b); sBound = true;
            Log.i(TAG, "CONNECTED");
            try { mLogic.registerLogicCallback(DOMAIN_SOURCE, mCallback); Log.i(TAG, "registered"); }
            catch (RemoteException e) { Log.w(TAG, "reg err " + e); }
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

    private void cmd(String c, String v) {
        if (mLogic == null) { Log.w(TAG, "cmd no bind"); return; }
        try { mLogic.setCmd(c, v, null); Log.i(TAG, "CMD " + c + "(" + v + ")"); }
        catch (Exception e) { Log.w(TAG, "cmd err " + e); }
    }
    private void setSource(int id) { cmd("setSourceId", "" + id); }

    private final ILogicCallback.Stub mCallback = new ILogicCallback.Stub() {
        @Override public void onEventChanged(String event, String value, String extra) {
            sLastEvent = event + " v=" + value + " x=" + extra;
            Log.i(TAG, "EVT " + sLastEvent);
            try {
                if (EV_MEDIA.equals(event)) {
                    int key = parse(value); sSource = parse(extra);
                    forward(key);                 // шлём ВСЕГДА, независимо от src
                } else if (EV_THIRD.equals(event)) {
                    sThird = parse(value);
                } else if (EV_SOURCE.equals(event)) {
                    sSource = parse(value);
                }
            } catch (Exception e) { Log.w(TAG, "evt err " + e); }
        }
    };

    private void forward(int keyCode) {
        long now = SystemClock.uptimeMillis();
        if (now - mLastFwd < DEBOUNCE_MS) return;
        mLastFwd = now;
        Hur.sendMediaKey(getApplicationContext(), keyCode);
        sFwdCount++; sLastKey = "" + keyCode;
    }
    private static int parse(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return Integer.MIN_VALUE; } }

    // play/pause via EASYCONN
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
            while (mLogRun && (line = br.readLine()) != null)
                if (line.contains("KEYCODE_EASYCONN"))
                    mMain.post(() -> forward(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        } catch (Exception e) { Log.w(TAG, "logcat err " + e); }
        finally { if (p != null) p.destroy(); }
        if (mLogRun) { mLogRun = false; mMain.postDelayed(this::startLogcatWatcher, 5000); }
    }
}
