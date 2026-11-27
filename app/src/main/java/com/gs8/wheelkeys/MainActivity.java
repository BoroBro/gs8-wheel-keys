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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        root.setBackgroundColor(Color.BLACK);
        TextView t = new TextView(this);
        t.setText("GS8 Wheel Keys (v3.3)"); t.setTextSize(20); t.setTextColor(Color.WHITE);
        root.addView(t);

        LinearLayout r1 = new LinearLayout(this); root.addView(r1);
        addBtn(r1, "Запустить сервис", v -> send(null));
        addBtn(r1, "Остановить", v -> stopService(new Intent(this, WheelKeyService.class)));

        addBtn(root, "Переключить HUR-режим (глушить радио)",
                v -> send(WheelKeyService.ACTION_TOGGLE));

        mStatus = new TextView(this);
        mStatus.setTextSize(15); mStatus.setTextColor(Color.WHITE);
        mStatus.setTypeface(Typeface.MONOSPACE); mStatus.setPadding(0, 20, 0, 0);
        ScrollView sv = new ScrollView(this); sv.addView(mStatus);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }
    private void addBtn(LinearLayout p, String txt, View.OnClickListener l) {
        Button b = new Button(this); b.setText(txt); b.setAllCaps(false); b.setOnClickListener(l);
        p.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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
        StringBuilder s = new StringBuilder();
        s.append("СЕРВИС\n");
        s.append("  запущен:     ").append(yn(WheelKeyService.sRunning)).append('\n');
        s.append("  связь с ГУ:  ").append(yn(WheelKeyService.sBound)).append('\n');
        s.append("  READ_LOGS:   ").append(yn(rl)).append('\n');
        s.append('\n');
        s.append("HUR-РЕЖИМ (глушить радио): ")
         .append(WheelKeyService.sSilence ? "ВКЛ" : "выкл").append('\n');
        s.append("  текущий источник: ").append(WheelKeyService.sSource)
         .append(WheelKeyService.sSource == 4097 ? " (OFF)" : "").append('\n');
        s.append('\n');
        s.append("КНОПКИ -> HUR\n");
        s.append("  отправлено:  ").append(WheelKeyService.sFwdCount).append('\n');
        s.append("  последняя:   ").append(WheelKeyService.sLastKey).append('\n');
        s.append('\n');
        s.append("HUR установлен: ").append(yn(isInstalled(Hur.HUR_PACKAGE))).append('\n');
        s.append('\n');
        s.append("ВКЛ: радио глушится (источник OFF), next/prev\n");
        s.append("только в HUR. Хотите радио — переключите в выкл.");
        mStatus.setText(s.toString());
    }
    private boolean isInstalled(String p) {
        try { getPackageManager().getPackageInfo(p, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
}
