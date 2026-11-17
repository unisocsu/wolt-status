package com.woltwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Foreground service: checks the venue every minute, alerts when it goes closed -> open. */
public class MonitorService extends Service {

    static final int ID_ONGOING = 1;
    static final int ID_ALERT = 2;
    static final String CH_ONGOING = "ongoing";
    static final String CH_ALERT = "alert";
    static final long PERIOD_SECONDS = 60;

    private ScheduledExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences sp;
    private int errors = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sp = getSharedPreferences("watch", MODE_PRIVATE);
        if (!sp.getBoolean("running", false)) {
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
                @Override public void run() { check(); }
            }, 0, PERIOD_SECONDS, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private void check() {
        String slug = sp.getString("slug", "");
        String name = sp.getString("name", slug);
        try {
            double lat = Double.parseDouble(sp.getString("lat", "0"));
            double lon = Double.parseDouble(sp.getString("lon", "0"));
            WoltClient.Status st = WoltClient.fetchStatus(slug, lat, lon);
            int prev = sp.getInt("last_open", -1);
            int now = st.open ? 1 : 0;
            sp.edit().putInt("last_open", now).putString("last_text", st.text)
                    .putString("next_open", st.nextOpen).putString("error", "")
                    .putLong("last_check", System.currentTimeMillis()).apply();
            errors = 0;
            if (prev == 0 && now == 1) alert(name, st.text);
            notifyOngoing(name + ": " + (st.open ? "פתוחה" : "סגורה") + " – " + st.text);
        } catch (Exception e) {
            errors++;
            sp.edit().putString("error", String.valueOf(e))
                    .putLong("last_check", System.currentTimeMillis()).apply();
            if (errors >= 3) notifyOngoing("שגיאה בבדיקה: " + e.getMessage());
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH_ONGOING, "מעקב פעיל",
                    NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "החנות נפתחה",
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

    private void notifyOngoing(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID_ONGOING, ongoing(text));
    }

    private void alert(String name, String text) {
        Notification.Builder b = builder(CH_ALERT).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(name + " פתוחה עכשיו!").setContentText(text)
                .setContentIntent(openApp()).setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) {
            b.setDefaults(Notification.DEFAULT_ALL).setPriority(Notification.PRIORITY_HIGH);
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID_ALERT, b.build());
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
