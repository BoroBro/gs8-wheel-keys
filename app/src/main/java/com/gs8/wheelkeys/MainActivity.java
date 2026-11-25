package com.gs8.wheelkeys;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView mStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("GS8 Wheel Keys");
        title.setTextSize(22);
        root.addView(title);

        mStatus = new TextView(this);
        mStatus.setTextSize(14);
        mStatus.setPadding(0, 24, 0, 24);
        root.addView(mStatus);

        addBtn(root, "Запустить сервис", v -> {
            Intent svc = new Intent(this, WheelKeyService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
            refresh();
        });
        addBtn(root, "Остановить сервис", v -> {
            stopService(new Intent(this, WheelKeyService.class));
            refresh();
        });
        addBtn(root, "Обновить статус", v -> refresh());

        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void addBtn(LinearLayout p, String t, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(t); btn.setAllCaps(false); btn.setOnClickListener(l);
        p.addView(btn);
    }

    private void refresh() {
        boolean readLogs = checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        boolean hur = isInstalled(Hur.HUR_PACKAGE);
        boolean logic = isInstalled("com.zhonghong.logic");

        StringBuilder sb = new StringBuilder();
        sb.append("ZHLogic (ГУ): ").append(logic ? "найден ✓" : "НЕ найден ✗").append('\n');
        sb.append("HUR установлен: ").append(hur ? "да ✓" : "НЕТ ✗").append('\n');
        sb.append("READ_LOGS (play/pause): ").append(readLogs ? "выдан ✓" : "нет — только next/prev").append('\n');
        sb.append('\n');
        sb.append("next/prev: через источник 15 (чисто)\n");
        sb.append("play/pause: ").append(readLogs ? "включён (кнопка голоса)" : "выкл (нужен READ_LOGS)").append('\n');
        if (!readLogs) {
            sb.append('\n').append("Для play/pause дайте разрешение с ПК:\n");
            sb.append("adb shell pm grant com.gs8.wheelkeys android.permission.READ_LOGS");
        }
        mStatus.setTextColor(logic ? Color.DKGRAY : Color.RED);
        mStatus.setText(sb.toString());
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
}
