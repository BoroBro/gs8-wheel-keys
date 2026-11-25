package com.gs8.wheelkeys;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Запуск службы при загрузке магнитолы. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(KeyService.TAG, "BootReceiver: " + intent.getAction() + " — запускаю службу");
        Intent svc = new Intent(context, KeyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
