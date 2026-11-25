package com.gs8.wheelkeys;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Простой экран: статус права READ_LOGS, кнопки Старт/Стоп службы,
 * текущая раскладка и последнее пойманное событие.
 */
public class MainActivity extends Activity {

    private TextView status;
    private TextView lastEvent;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("GS8 Wheel Keys → HUR");
        title.setTextSize(22);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        root.addView(status);

        Button start = new Button(this);
        start.setText("Запустить службу");
        start.setOnClickListener(v -> {
            Intent svc = new Intent(this, KeyService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
            refresh();
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Остановить службу");
        stop.setOnClickListener(v -> { stopService(new Intent(this, KeyService.class)); refresh(); });
        root.addView(stop);

        TextView map = new TextView(this);
        StringBuilder sb = new StringBuilder("\nТекущая раскладка:\n");
        for (KeyMap.Rule r : KeyMap.rules()) sb.append("• ").append(r.label).append("\n");
        map.setText(sb.toString());
        map.setTextSize(14);
        root.addView(map);

        lastEvent = new TextView(this);
        lastEvent.setTextSize(14);
        root.addView(lastEvent);

        setContentView(root);
        tick();
    }

    private void refresh() {
        boolean logs = checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        status.setText("Право READ_LOGS: " + (logs ? "ВЫДАНО ✓" : "НЕ выдано ✗\n"
                + "Выполните на ПК по ADB:\n"
                + "adb shell pm grant com.gs8.wheelkeys android.permission.READ_LOGS"));
    }

    private void tick() {
        refresh();
        lastEvent.setText("Последнее событие: " + KeyService.lastEvent);
        ui.postDelayed(this::tick, 1000);
    }
}
