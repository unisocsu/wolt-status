package com.woltwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Restarts monitoring after a reboot (or after the app is updated) if it was on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        boolean ok = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!ok) return;
        if (WatchStore.isRunning(ctx) && !WatchStore.load(ctx).isEmpty()) {
            try {
                Intent i = new Intent(ctx, MonitorService.class);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
            } catch (RuntimeException e) {
                // the system refused a background start; the user can start monitoring from the app
            }
        }
    }
}
