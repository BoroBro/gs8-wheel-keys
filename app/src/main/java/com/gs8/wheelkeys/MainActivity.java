package com.gs8.wheelkeys;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView mStatus;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override public void run() { render(); mUi.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("GS8 Wheel Keys");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        addBtn(root, "Запустить сервис", v -> {
            Intent svc = new Intent(this, WheelKeyService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
            render();
        });
        addBtn(root, "Остановить сервис", v -> {
            stopService(new Intent(this, WheelKeyService.class));
            render();
        });

        mStatus = new TextView(this);
        mStatus.setTextSize(14);
        mStatus.setTextColor(Color.WHITE);
        mStatus.setTypeface(Typeface.MONOSPACE);
        mStatus.setPadding(0, 20, 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(mStatus);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void addBtn(LinearLayout p, String t, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(t); btn.setAllCaps(false); btn.setOnClickListener(l);
        p.addView(btn);
    }

    @Override protected void onResume() { super.onResume(); mUi.post(mTick); }
    @Override protected void onPause()  { super.onPause(); mUi.removeCallbacks(mTick); }

    private String onoff(boolean b) { return b ? "ДА" : "нет"; }

    private void render() {
        boolean readLogs = checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        StringBuilder sb = new StringBuilder();
        sb.append("СЕРВИС\n");
        sb.append("  запущен:        ").append(onoff(WheelKeyService.sRunning)).append('\n');
        sb.append("  связь с ГУ:     ").append(onoff(WheelKeyService.sBound)).append('\n');
        sb.append("  колбэк источн.: ").append(onoff(WheelKeyService.sRegistered)).append('\n');
        sb.append('\n');
        sb.append("ИСТОЧНИК\n");
        sb.append("  текущий:        ").append(src(WheelKeyService.sCurSource)).append('\n');
        sb.append("  третий(HUR?):   ").append(src(WheelKeyService.sThird)).append('\n');
        sb.append("  хотим 15:       ").append(onoff(WheelKeyService.sWant15)).append('\n');
        sb.append("  забрали 15:     ").append(onoff(WheelKeyService.sCurSource == 15)).append('\n');
        sb.append('\n');
        sb.append("ПЕРЕСЫЛКА В HUR\n");
        sb.append("  отправлено:     ").append(WheelKeyService.sFwdCount).append('\n');
        sb.append("  последняя:      ").append(WheelKeyService.sLastFwd).append('\n');
        sb.append("  последнее соб.: ").append(WheelKeyService.sLastEvent).append('\n');
        sb.append('\n');
        sb.append("ПРОЧЕЕ\n");
        sb.append("  READ_LOGS (p/p):").append(onoff(readLogs)).append('\n');
        sb.append("  HUR установлен: ").append(onoff(isInstalled(Hur.HUR_PACKAGE))).append('\n');
        sb.append("  ZHLogic на ГУ:  ").append(onoff(isInstalled("com.zhonghong.logic"))).append('\n');
        sb.append('\n');
        sb.append("Подсказка: next/prev работают, когда «забрали 15» = ДА\n");
        sb.append("(HUR должен играть музыку).");
        mStatus.setText(sb.toString());
    }

    private String src(int s) {
        if (s == -999) return "—";
        String name;
        switch (s) {
            case 0: name="радио"; break;
            case 1: name="медиа"; break;
            case 3: name="BT-муз"; break;
            case 11: name="AUX"; break;
            case 13: name="BT-тел"; break;
            case 14: name="голос"; break;
            case 15: name="наш(15)"; break;
            case 4096: name="нет"; break;
            default: name=""; break;
        }
        return s + (name.isEmpty() ? "" : " (" + name + ")");
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
}
