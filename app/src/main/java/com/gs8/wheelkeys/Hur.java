package com.gs8.wheelkeys;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

/** Пересылка медиа-клавиш в Headunit Revived (HUR). */
public final class Hur {
    private static final String TAG = "WheelKeys";
    public static final String HUR_PACKAGE = "com.andrerinas.headunitrevived";

    private Hur() {}

    public static void sendMediaKey(Context ctx, int keyCode) {
        long t = SystemClock.uptimeMillis();
        dispatch(ctx, new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        dispatch(ctx, new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
        Log.i(TAG, "-> HUR media key " + keyCode);
    }

    private static void dispatch(Context ctx, KeyEvent ev) {
        Intent it = new Intent(Intent.ACTION_MEDIA_BUTTON);
        it.setPackage(HUR_PACKAGE);
        it.putExtra(Intent.EXTRA_KEY_EVENT, ev);
        try {
            ctx.sendBroadcast(it);
        } catch (Exception e) {
            Log.w(TAG, "dispatch failed: " + e);
        }
    }
}
