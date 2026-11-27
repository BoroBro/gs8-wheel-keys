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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView mStatus;
    private Button mToggle;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override public void run() { render(); mUi.postDelayed(this, 1000); }
    };

    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("GS8 Wheel Keys (v3.4)");
        title.setTextSize(20); title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ряд Старт / Стоп
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        Button start = mkButton("Запустить сервис", v -> send(null));
        Button stop  = mkButton("Остановить", v -> stopService(new Intent(this, WheelKeyService.class)));
        row.addView(start, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(stop,  new LinearLayout.LayoutParams(0, WRAP, 1f));

        // большой переключатель режима — на всю ширину
        mToggle = mkButton("HUR-режим", v -> send(WheelKeyService.ACTION_TOGGLE));
        mToggle.setTextSize(17);
        mToggle.setPadding(16, 28, 16, 28);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(MATCH, WRAP);
        tp.topMargin = 16;
        root.addView(mToggle, tp);

        mStatus = new TextView(this);
        mStatus.setTextSize(15); mStatus.setTextColor(Color.WHITE);
        mStatus.setTypeface(Typeface.MONOSPACE);
        mStatus.setPadding(0, 20, 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(mStatus, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        setContentView(root);
    }

    private Button mkButton(String txt, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(txt); b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void send(String action) {
        Intent s = new Intent(this, WheelKeyService.class);
        if (action != null) s.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s); else startService(s);
    }

    @Override protected void onResume() { super.onResume(); mUi.post(mTick); }
    @Override protected void onPause()  { super.onPause(); mUi.removeCallbacks(mTick); }
    private String yn(boolean b) { return b ? "ДА" : "нет"; }

    private void render() {
        boolean rl = checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
        boolean on = WheelKeyService.sSilence;
        mToggle.setText(on
                ? "HUR-РЕЖИМ: ВКЛ  (радио заглушено)\nнажмите, чтобы вернуть радио"
                : "HUR-режим: выкл  (радио работает)\nнажмите, чтобы глушить радио под HUR");
        mToggle.setBackgroundColor(on ? Color.parseColor("#1B5E20") : Color.parseColor("#37474F"));
        mToggle.setTextColor(Color.WHITE);
        mToggle.setGravity(Gravity.CENTER);

        StringBuilder s = new StringBuilder();
        s.append("СЕРВИС\n");
        s.append("  запущен:     ").append(yn(WheelKeyService.sRunning)).append('\n');
        s.append("  связь с ГУ:  ").append(yn(WheelKeyService.sBound)).append('\n');
        s.append("  READ_LOGS:   ").append(yn(rl)).append('\n');
        s.append('\n');
        s.append("ИСТОЧНИК\n");
        s.append("  текущий:     ").append(WheelKeyService.sSource)
         .append(WheelKeyService.sSource == 4097 ? " (OFF)" : "").append('\n');
        s.append('\n');
        s.append("КНОПКИ -> HUR\n");
        s.append("  отправлено:  ").append(WheelKeyService.sFwdCount).append('\n');
        s.append("  последняя:   ").append(WheelKeyService.sLastKey).append('\n');
        s.append('\n');
        s.append("HUR установлен: ").append(yn(isInstalled(Hur.HUR_PACKAGE))).append('\n');
        mStatus.setText(s.toString());
    }

    private boolean isInstalled(String p) {
        try { getPackageManager().getPackageInfo(p, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
}
