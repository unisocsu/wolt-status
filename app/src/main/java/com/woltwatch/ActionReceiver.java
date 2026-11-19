package com.woltwatch;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Handles the buttons on an alert notification: stop watching / remind me in an hour. */
public class ActionReceiver extends BroadcastReceiver {

    static final String UNWATCH = "com.woltwatch.UNWATCH";
    static final String SNOOZE = "com.woltwatch.SNOOZE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final String slug = intent.getStringExtra("slug");
        if (slug == null) return;
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(MonitorService.alertId(slug));
        if (UNWATCH.equals(intent.getAction())) {
            WatchStore.remove(ctx, slug);
        } else if (SNOOZE.equals(intent.getAction())) {
            WatchStore.update(ctx, slug, new WatchStore.Mutator() {
                @Override public void apply(WatchStore.Watch w) { w.remindAt = System.currentTimeMillis() + 3600000L; }
            });
        }
        if (WatchStore.isRunning(ctx)) {   // let the service re-plan its next wake-up
            try {
                Intent i = new Intent(ctx, MonitorService.class).setAction(MonitorService.ACTION_RECALC);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
