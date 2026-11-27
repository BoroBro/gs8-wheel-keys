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
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.BLACK);
        TextView t = new TextView(this);
        t.setText("GS8 Wheel Keys (v3.2 эксп.)"); t.setTextSize(18); t.setTextColor(Color.WHITE);
        root.addView(t);

        LinearLayout r1 = row(root);
        addBtn(r1, "Старт", v -> startSvc(null, 0));
        addBtn(r1, "Стоп", v -> { stopService(new Intent(this, WheelKeyService.class)); });

        TextView hint = new TextView(this);
        hint.setText("Источник (эксперимент):"); hint.setTextColor(Color.LTGRAY); root.addView(hint);
        LinearLayout r2 = row(root);
        addBtn(r2, "15", v -> startSvc(WheelKeyService.ACTION_SRC, 15));
        addBtn(r2, "радио 0", v -> startSvc(WheelKeyService.ACTION_SRC, 0));
        addBtn(r2, "BT 3", v -> startSvc(WheelKeyService.ACTION_SRC, 3));
        addBtn(r2, "OFF 4097", v -> startSvc(WheelKeyService.ACTION_SRC, 4097));
        LinearLayout r3 = row(root);
        addBtn(r3, "Release 15", v -> startSvc(WheelKeyService.ACTION_REL, 0));
        addBtn(r3, "Resume last", v -> startSvc(WheelKeyService.ACTION_RESUME, 0));

        mStatus = new TextView(this);
        mStatus.setTextSize(14); mStatus.setTextColor(Color.WHITE);
        mStatus.setTypeface(Typeface.MONOSPACE); mStatus.setPadding(0, 16, 0, 0);
        ScrollView sv = new ScrollView(this); sv.addView(mStatus);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r); return r;
    }
    private void addBtn(LinearLayout p, String txt, View.OnClickListener l) {
        Button b = new Button(this); b.setText(txt); b.setAllCaps(false); b.setOnClickListener(l);
        p.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }
    private void startSvc(String action, int id) {
        Intent s = new Intent(this, WheelKeyService.class);
        if (action != null) { s.setAction(action); s.putExtra("id", id); }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s); else startService(s);
    }

    @Override protected void onResume() { super.onResume(); mUi.post(mTick); }
    @Override protected void onPause()  { super.onPause(); mUi.removeCallbacks(mTick); }
    private String yn(boolean b) { return b ? "ДА" : "нет"; }

    private void render() {
        boolean rl = checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
        StringBuilder s = new StringBuilder();
        s.append("запущен: ").append(yn(WheelKeyService.sRunning))
         .append("  связь ГУ: ").append(yn(WheelKeyService.sBound))
         .append("  READ_LOGS: ").append(yn(rl)).append("\n\n");
        s.append("текущий источник: ").append(WheelKeyService.sSource).append('\n');
        s.append("третий:           ").append(WheelKeyService.sThird).append('\n');
        s.append("отправлено в HUR: ").append(WheelKeyService.sFwdCount)
         .append("  (посл. ").append(WheelKeyService.sLastKey).append(")\n");
        s.append("событие: ").append(WheelKeyService.sLastEvent).append("\n\n");
        s.append("HUR: ").append(yn(isInstalled(Hur.HUR_PACKAGE))).append("\n\n");
        s.append("Кнопки руля шлются в HUR всегда.\n");
        s.append("Радио глушит двойным? Пробуйте источник:\n");
        s.append("15 / BT 3 / OFF 4097 — что уберёт радио\n");
        s.append("и не сломает HUR. Release/Resume — вернуть.");
        mStatus.setText(s.toString());
    }
    private boolean isInstalled(String p) {
        try { getPackageManager().getPackageInfo(p, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
}
