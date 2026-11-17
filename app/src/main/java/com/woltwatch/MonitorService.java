package com.woltwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service: every minute checks all watched venues one after another and posts an
 * alert for each venue that went closed -> open.
 */
public class MonitorService extends Service {

    static final int ID_ONGOING = 1;
    static final int ID_ALERT_BASE = 1000;
    static final String CH_ONGOING = "ongoing";
    static final String CH_ALERT = "alert";
    static final long PERIOD_SECONDS = 60;

    private ScheduledExecutorService executor;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!WatchStore.isRunning(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (executor == null) {
            createChannels();
            startForeground(ID_ONGOING, ongoing("מתחיל מעקב…"));
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "woltwatch:check");
            wakeLock.acquire();
            executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() { checkAll(); }
            }, 0, PERIOD_SECONDS, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private void checkAll() {
        try {
            List<WatchStore.Watch> list = WatchStore.load(this);
            if (list.isEmpty()) {
                WatchStore.setRunning(this, false);
                stopSelf();
                return;
            }
            for (WatchStore.Watch w : list) {
                try {
                    WoltClient.Status st = WoltClient.fetchStatus(w.slug,
                            Double.parseDouble(w.lat), Double.parseDouble(w.lon));
                    int prev = WatchStore.record(this, w.slug, st, null);
                    if (prev == 0 && st.open) alert(w, st);
                } catch (InterruptedException ie) {
                    return;
                } catch (Exception e) {
                    WatchStore.record(this, w.slug, null, String.valueOf(e));
                }
                Thread.sleep(500); // be gentle with the server
            }
            updateOngoingSummary();
        } catch (InterruptedException ie) {
            // service is stopping
        } catch (Throwable t) {
            // never let the scheduled task die
        }
    }

    private void updateOngoingSummary() {
        List<WatchStore.Watch> list = WatchStore.load(this);
        int open = 0, err = 0;
        for (WatchStore.Watch w : list) {
            if (w.lastOpen == 1) open++;
            if (w.error.length() > 0) err++;
        }
        String s = list.size() + " חנויות במעקב · " + open + " פתוחות";
        if (err > 0) s += " · " + err + " עם שגיאה";
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID_ONGOING, ongoing(s));
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH_ONGOING, "מעקב פעיל",
                    NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "חנות נפתחה",
                    NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
    }

    private Notification ongoing(String text) {
        return builder(CH_ONGOING).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Wolt Watch").setContentText(text)
                .setContentIntent(openApp()).setOngoing(true).build();
    }

    private void alert(WatchStore.Watch w, WoltClient.Status st) {
        Notification.Builder b = builder(CH_ALERT).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(w.name + " פתוחה עכשיו!").setContentText(st.text)
                .setContentIntent(openApp()).setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) {
            b.setDefaults(Notification.DEFAULT_ALL).setPriority(Notification.PRIORITY_HIGH);
        }
        int id = ID_ALERT_BASE + Math.abs(w.slug.hashCode() % 100000); // one notification per venue
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(id, b.build());
    }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
