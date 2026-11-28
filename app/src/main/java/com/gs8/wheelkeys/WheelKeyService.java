package com.gs8.wheelkeys;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
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
 * v3.5:
 *  - кнопки руля ловим из ЛОГА (CanKeyHelper) -> шлём в HUR. Надёжно при любом источнике/фокусе.
 *  - Глушение радио СОБЫТИЙНОЕ (один раз на событие), без постоянного вотчдога — чтобы
 *    можно было вручную включить радио (напр. поставив AA на паузу), и оно не выключалось само.
 *  - АВТО-режим: ловим SESSION_STATE от HUR. projecting/connected -> глушим радио;
 *    disconnected/failed -> возвращаем прежний источник (resumeLastSource).
 *  - Ручной переключатель HUR-режима = оверрайд (работает и если HUR не шлёт SESSION_STATE).
 *  - После пересылки клавиши при включённом глушении — разовое повторное задавливание источника
 *    (точечная защита от двойного действия, ручное радио в режиме "выкл" не трогаем).
 */
public class WheelKeyService extends Service {

    private static final String TAG = "WheelKeys";
    private static final String CH_ID = "wheelkeys";
    private static final int NOTE_ID = 1;
    private static final String PREF = "wk";

    public static final String ACTION_TOGGLE = "com.gs8.wheelkeys.TOGGLE"; // ручной HUR-режим
    public static final String ACTION_AUTO   = "com.gs8.wheelkeys.AUTO";   // авто по AA вкл/выкл
    public static final String ACTION_SRC    = "com.gs8.wheelkeys.SRC";

    // broadcast состояния сессии от Headunit Revived
    private static final String HUR_SESSION_ACTION = "com.andrerinas.headunitrevived.SESSION_STATE";

    private static final String SVC_PKG = "com.zhonghong.logic";
    private static final String SVC_CLS = "com.zhonghong.logic.service.LogicService";
    private static final int DOMAIN_SOURCE = 2;
    private static final int ID_OFF = 4097;      // ID_OFF_ARM — глушит радио, HUR не ломает

    public static volatile boolean sRunning = false;
    public static volatile boolean sBound = false;
    public static volatile boolean sSilence = true;        // радио заглушено (эффективное состояние)
    public static volatile boolean sAuto = true;           // авто-переключение по AA-сессии
    public static volatile boolean sManualOverride = false;// пользователь вручную выбрал состояние
    public static volatile int sSource = -999;
    public static volatile int sFwdCount = 0;
    public static volatile String sLastKey = "—";
    public static volatile String sAaState = "—";          // последнее состояние AA
    public static volatile String sAaRaw = "—";            // отладка: сырой broadcast

    private ILogicInterface mLogic;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private long mLastFwd = 0L;
    private static final long DEBOUNCE_MS = 300L;
    private Thread mLogThread;
    private volatile boolean mLogRun = false;

    private final BroadcastReceiver mSessionRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent it) { onSession(it); }
    };

    @Override public void onCreate() {
        super.onCreate();
        sRunning = true;
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        sSilence = sp.getBoolean("silence", true);
        sAuto = sp.getBoolean("auto", true);
        startAsForeground();
        Log.i(TAG, "service created (v3.5) silence=" + sSilence + " auto=" + sAuto);
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(HUR_SESSION_ACTION);
            registerReceiver(mSessionRx, f);
            Log.i(TAG, "SESSION_STATE receiver registered");
        } catch (Exception e) { Log.w(TAG, "rx reg err " + e); }
        bindLogic();
        startLogcatWatcher();
    }

    @Override public int onStartCommand(Intent intent, int f, int id) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACTION_TOGGLE.equals(a)) {
                sManualOverride = true;                 // ручной выбор — выше авто
                setSilence(!sSilence);
                Log.i(TAG, "manual silence -> " + sSilence);
            } else if (ACTION_AUTO.equals(a)) {
                sAuto = !sAuto;
                getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("auto", sAuto).apply();
                if (sAuto) sManualOverride = false;     // снова доверяем AA
                Log.i(TAG, "auto -> " + sAuto);
            } else if (ACTION_SRC.equals(a)) {
                cmd("setSourceId", "" + intent.getIntExtra("id", ID_OFF));
            }
        }
        if (!sBound) bindLogic();
        if (!mLogRun) startLogcatWatcher();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(mSessionRx); } catch (Exception ig) {}
        if (sSilence) cmd("resumeLastSource", "");   // не оставлять ГУ в OFF
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

    // ---------------- AA session ----------------
    private void onSession(Intent it) {
        String act = it.getAction();
        String raw = dumpExtras(it);
        String st = extractState(it);
        sAaRaw = (act == null ? "?" : act) + " | " + raw;
        Log.i(TAG, "SESSION recv: " + sAaRaw + " -> state=" + st);
        if (st == null) return;
        sAaState = st;
        String s = st.toLowerCase();
        if (s.contains("disconnect") || s.contains("fail") || s.contains("stopped") || s.contains("ended")) {
            sManualOverride = false;                 // сессия кончилась — сброс оверрайда
            if (sAuto) setSilence(false);            // вернуть радио/прежний источник
        } else if (s.contains("project") || s.contains("connected")) {
            if (sAuto && !sManualOverride) setSilence(true);  // AA активен -> заглушить радио
        }
        // "connecting" — ничего
    }

    private String extractState(Intent it) {
        Bundle ex = it.getExtras();
        if (ex == null) return null;
        String[] keys = {"state", "session_state", "sessionState", "status", "SESSION_STATE"};
        for (String k : keys) { Object o = ex.get(k); if (o != null) return String.valueOf(o); }
        for (String k : ex.keySet()) {
            Object o = ex.get(k);
            if (o instanceof String) {
                String v = ((String) o).toLowerCase();
                if (v.contains("project") || v.contains("connect") || v.contains("disconnect") || v.contains("fail"))
                    return (String) o;
            }
        }
        return null;
    }
    private String dumpExtras(Intent it) {
        Bundle ex = it.getExtras();
        if (ex == null) return "(no extras)";
        StringBuilder sb = new StringBuilder();
        for (String k : ex.keySet()) { sb.append(k).append('=').append(ex.get(k)).append(' '); }
        return sb.toString().trim();
    }

    // ---------------- silence ----------------
    private void setSilence(boolean on) {
        sSilence = on;
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("silence", on).apply();
        applySilence(on);
    }
    private void applySilence(boolean on) {
        if (on) cmd("setSourceId", "" + ID_OFF);
        else cmd("resumeLastSource", "");
    }

    // ---------------- bind (управление источником) ----------------
    private final ServiceConnection mConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            mLogic = ILogicInterface.Stub.asInterface(b); sBound = true;
            Log.i(TAG, "CONNECTED");
            try { mLogic.registerLogicCallback(DOMAIN_SOURCE, mCallback); } catch (RemoteException e) {}
            sSource = getSrc();
            if (sSilence) applySilence(true);   // применить сохранённое глушение один раз
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
        // точечная защита от двойного действия: если радио должно быть заглушено — повторно задавим
        if (sSilence) mMain.postDelayed(() -> { if (sSilence) cmd("setSourceId", "" + ID_OFF); }, 600);
    }
    private static int parse(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -999; } }
}
