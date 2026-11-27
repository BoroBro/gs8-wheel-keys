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
 * v3.1: когда HUR владеет звуком — забираем источник 15 (чтобы радио не реагировало),
 * и ОБЯЗАТЕЛЬНО отпускаем его, как только HUR теряет фокус (пауза/звонок/выход),
 * чтобы HUR мог свободно перезапускаться. Никакого сторожа-долбёжки.
 */
public class WheelKeyService extends Service {

    private static final String TAG = "WheelKeys";
    private static final String CH_ID = "wheelkeys";
    private static final int NOTE_ID = 1;

    private static final String SVC_PKG = "com.zhonghong.logic";
    private static final String SVC_CLS = "com.zhonghong.logic.service.LogicService";
    private static final int DOMAIN_SOURCE = 2;
    private static final int ID_THIRD_APP = 15;

    private static final String EV_THIRD = "onThirdSourceChanged";
    private static final String EV_MEDIA = "onMediaButtonPressed";

    public static volatile boolean sRunning = false;
    public static volatile boolean sBound = false;
    public static volatile boolean sClaimed = false;
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
        Log.i(TAG, "service created (v3.1 claim+release)");
        bindLogic();
        startLogcatWatcher();
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (!sBound) bindLogic();
        if (!mLogRun) startLogcatWatcher();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        releaseIfClaimed("service stop");
        stopLogcatWatcher();
        unbindLogic();
        sRunning = false;
        super.onDestroy();
        Log.i(TAG, "service destroyed");
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
                .setContentTitle("GS8 Wheel Keys")
                .setContentText("Кнопки руля -> HUR")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true).build());
    }

    // ------------------------------------------------------------- bind
    private final ServiceConnection mConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            mLogic = ILogicInterface.Stub.asInterface(b);
            sBound = true;
            Log.i(TAG, "LogicService CONNECTED");
            try { mLogic.registerLogicCallback(DOMAIN_SOURCE, mCallback);
                  Log.i(TAG, "registered"); }
            catch (RemoteException e) { Log.w(TAG, "register err " + e); }
            // HUR уже играет на старте?
            mMain.postDelayed(() -> { if (getInt("getThirdSourceId") == ID_THIRD_APP) claim("connect"); }, 900);
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            sBound = false; sClaimed = false; mLogic = null;
            Log.w(TAG, "DISCONNECTED, rebind 2s");
            mMain.postDelayed(WheelKeyService.this::bindLogic, 2000);
        }
    };

    private void bindLogic() {
        try {
            Intent it = new Intent(SVC_CLS);
            it.setComponent(new ComponentName(SVC_PKG, SVC_CLS));
            Log.i(TAG, "bindService=" + bindService(it, mConn, Context.BIND_AUTO_CREATE));
        } catch (Exception e) { Log.w(TAG, "bind err " + e); }
    }

    private void unbindLogic() {
        try {
            if (sBound && mLogic != null)
                try { mLogic.unregisterLogicCallback(DOMAIN_SOURCE, mCallback); } catch (Exception ig) {}
            unbindService(mConn);
        } catch (Exception ig) {}
        sBound = false; mLogic = null;
    }

    // ------------------------------------------------------------- source claim/release
    private int getInt(String key) {
        try { return Integer.parseInt(mLogic.getValue(key, "").trim()); }
        catch (Exception e) { return -999; }
    }

    private void claim(String why) {
        if (mLogic == null) return;
        try {
            if (getInt("getSourceId") != ID_THIRD_APP) {
                mLogic.setCmd("setSourceId", "" + ID_THIRD_APP, null);
                Log.i(TAG, "CLAIM setSourceId(15) [" + why + "]");
            }
            sClaimed = true;
        } catch (Exception e) { Log.w(TAG, "claim err " + e); }
    }

    private void releaseIfClaimed(String why) {
        if (mLogic == null || !sClaimed) { sClaimed = false; return; }
        try {
            mLogic.setCmd("releaseSourceId", "" + ID_THIRD_APP, null);
            Log.i(TAG, "RELEASE releaseSourceId(15) [" + why + "]");
        } catch (Exception e) { Log.w(TAG, "release err " + e); }
        sClaimed = false;
    }

    private final ILogicCallback.Stub mCallback = new ILogicCallback.Stub() {
        @Override public void onEventChanged(String event, String value, String extra) {
            sLastEvent = event + " v=" + value + " x=" + extra;
            Log.i(TAG, "EVT " + sLastEvent);
            try {
                if (EV_THIRD.equals(event)) {
                    int third = parse(value);
                    sThird = third;
                    if (third == ID_THIRD_APP) mMain.post(() -> claim("HUR focus"));
                    else mMain.post(() -> releaseIfClaimed("third=" + third)); // пауза/звонок/голос/выход
                } else if (EV_MEDIA.equals(event)) {
                    int key = parse(value), src = parse(extra);
                    sSource = src;
                    if (src == ID_THIRD_APP) forward(key);
                    else Log.i(TAG, "key " + key + " ignored (src=" + src + ")");
                }
            } catch (Exception e) { Log.w(TAG, "evt err " + e); }
        }
    };

    private void forward(int keyCode) {
        long now = SystemClock.uptimeMillis();
        if (now - mLastFwd < DEBOUNCE_MS) return;
        mLastFwd = now;
        Hur.sendMediaKey(getApplicationContext(), keyCode);
        sFwdCount++; sLastKey = keyCode + "";
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return Integer.MIN_VALUE; }
    }

    // ------------------------------------------------------------- play/pause via logcat (EASYCONN)
    private void startLogcatWatcher() {
        if (mLogRun) return;
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_LOGS нет — play/pause выкл"); return;
        }
        mLogRun = true;
        mLogThread = new Thread(this::logcatLoop, "wk-logcat");
        mLogThread.setDaemon(true); mLogThread.start();
    }
    private void stopLogcatWatcher() {
        mLogRun = false;
        if (mLogThread != null) { mLogThread.interrupt(); mLogThread = null; }
    }
    private void logcatLoop() {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-T", "1", "-v", "brief", "CanKeyHelper:I", "*:S"});
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while (mLogRun && (line = br.readLine()) != null) {
                if (line.contains("KEYCODE_EASYCONN"))
                    mMain.post(() -> forward(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            }
        } catch (Exception e) { Log.w(TAG, "logcat err " + e); }
        finally { if (proc != null) proc.destroy(); }
        if (mLogRun) { mLogRun = false; mMain.postDelayed(this::startLogcatWatcher, 5000); }
    }
}
