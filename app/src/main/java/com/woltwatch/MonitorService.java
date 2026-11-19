package com.woltwatch;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service with smart scheduling:
 *  - every venue has its own next-check time (see WatchStore.nextCheckAfter)
 *  - short waits (up to 2.5 min): the CPU is kept awake and the executor sleeps
 *  - long waits: the wake lock is released and an alarm wakes the service up again
 */
public class MonitorService extends Service {

    static final String ACTION_TICK = "com.woltwatch.TICK";
    static final String ACTION_RECALC = "com.woltwatch.RECALC";

    static final int ID_ONGOING = 1;
    static final int ID_SCHEMA = 3;
    static final int ID_ALERT_BASE = 1000;
    static final String CH_ONGOING = "ongoing";
    static final String CH_ALERT = "alert";
    static final String CH_QUIET = "quiet";
    static final String CH_ALARM = "alarm";
    static final long SHORT_DELAY_MS = 150000;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pending;
    private PowerManager.WakeLock wakeLock;
    private int schemaErrors, successes;

    static int alertId(String slug) { return ID_ALERT_BASE + Math.abs(slug.hashCode() % 100000); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService() obliges us to call startForeground() quickly, so always do it first
        createChannels();
        try {
            goForeground(ongoing("מתחיל מעקב…"));
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!WatchStore.isRunning(this)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (executor == null) executor = Executors.newSingleThreadScheduledExecutor();
        acquireLock();
        cancelPending();
        executor.execute(new Runnable() {
            @Override public void run() { runCycle(); }
        });
        return START_STICKY;
    }

    // ------------------------------------------------------------------ cycle

    private void runCycle() {
        long nextWake;
        try {
            acquireLock();
            List<WatchStore.Watch> list = WatchStore.load(this);
            if (list.isEmpty()) {
                WatchStore.setRunning(this, false);
                stopSelf();
                return;
            }
            schemaErrors = 0;
            successes = 0;
            boolean net = hasNetwork();
            long now = System.currentTimeMillis();
            for (WatchStore.Watch w : list) {
                if (Thread.currentThread().isInterrupted()) return;
                if (net && w.nextCheckAt <= now + 2000) {
                    checkOne(w);
                    Thread.sleep(500);   // be gentle with the server
                }
            }
            apiHealth();
            timeAlerts();
            updateOngoing();
            StatusWidget.refresh(this);
            nextWake = computeNextWake(net);
        } catch (InterruptedException ie) {
            return;   // service is stopping
        } catch (Throwable t) {
            nextWake = System.currentTimeMillis() + 60000;
        }
        scheduleNext(nextWake);
    }

    private void checkOne(WatchStore.Watch w) throws InterruptedException {
        try {
            WoltClient.Status st = WoltClient.fetchStatus(w.slug, Double.parseDouble(w.lat), Double.parseDouble(w.lon));
            successes++;
            WatchStore.Rec r = WatchStore.record(this, w.slug, st, null);
            if (r != null && r.prevOpen == 0 && st.open) {
                String kind = "offline".equals(r.prevKind) ? "returned" : "opened";
                String text = st.text + (st.estimate.length() > 0 ? " · משלוח " + st.estimate : "");
                fireAlert(r.w, kind, text);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            if (e instanceof WoltClient.SchemaException) schemaErrors++;
            WatchStore.record(this, w.slug, null, String.valueOf(e));
        }
    }

    /** Warns once if Wolt's responses stopped matching what we expect (instead of silently saying "closed"). */
    private void apiHealth() {
        if (successes > 0) {
            if (Prefs.schemaFails(this) != 0) Prefs.setSchemaFails(this, 0);
            if (Prefs.schemaAlerted(this)) {
                Prefs.setSchemaAlerted(this, false);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ID_SCHEMA);
            }
        } else if (schemaErrors > 0) {
            int n = Prefs.schemaFails(this) + 1;
            Prefs.setSchemaFails(this, n);
            if (n >= 3 && !Prefs.schemaAlerted(this)) {
                Prefs.setSchemaAlerted(this, true);
                String title = "וולט שינתה את ה-API";
                String text = "מבנה התשובה לא מוכר, ולכן לא ניתן לדעת אם חנויות פתוחות";
                Notification n2 = builder(CH_ALERT).setSmallIcon(R.drawable.ic_stat).setContentTitle(title)
                        .setContentText(text).setContentIntent(openApp()).setAutoCancel(true).build();
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID_SCHEMA, n2);
                WatchStore.Watch dummy = new WatchStore.Watch();
                dummy.name = "Wolt Watch";
                Notifier.deliver(this, "api_changed", dummy, title, text);
            }
        }
    }

    /** "Closing soon" warnings and "remind me in an hour", both work from stored data, no network needed. */
    private void timeAlerts() {
        long now = System.currentTimeMillis();
        for (WatchStore.Watch w : WatchStore.load(this)) {
            if (w.lastOpen == 1 && w.warnClose > 0 && w.nextCloseAt > now && w.closeWarned != w.nextCloseAt
                    && w.nextCloseAt - now <= w.warnClose * 60000L) {
                final long nc = w.nextCloseAt;
                long mins = Math.max(1, (w.nextCloseAt - now + 30000) / 60000);
                WatchStore.update(this, w.slug, new WatchStore.Mutator() {
                    @Override public void apply(WatchStore.Watch x) { x.closeWarned = nc; }
                });
                fireAlert(w, "closing", "נסגרת בעוד " + mins + " דקות");
            }
            if (w.remindAt > 0 && now >= w.remindAt) {
                WatchStore.update(this, w.slug, new WatchStore.Mutator() {
                    @Override public void apply(WatchStore.Watch x) { x.remindAt = 0; }
                });
                if (w.lastOpen == 1) fireAlert(w, "remind", w.lastText);
            }
        }
    }

    private long computeNextWake(boolean net) {
        long now = System.currentTimeMillis();
        long wake = net ? Long.MAX_VALUE : now + 120000;
        for (WatchStore.Watch w : WatchStore.load(this)) {
            if (net) wake = Math.min(wake, w.nextCheckAt <= 0 ? now + 60000 : w.nextCheckAt);
            if (w.lastOpen == 1 && w.warnClose > 0 && w.closeWarned != w.nextCloseAt && w.nextCloseAt > now) {
                wake = Math.min(wake, w.nextCloseAt - w.warnClose * 60000L);
            }
            if (w.remindAt > 0) wake = Math.min(wake, w.remindAt);
        }
        if (wake == Long.MAX_VALUE) wake = now + 60000;
        return Math.max(wake, now + 5000);
    }

    // ------------------------------------------------------------- scheduling

    private void scheduleNext(long at) {
        if (executor == null || executor.isShutdown()) return;
        long delay = at - System.currentTimeMillis();
        cancelPending();
        if (delay <= SHORT_DELAY_MS) {
            acquireLock();
            pending = executor.schedule(new Runnable() {
                @Override public void run() { runCycle(); }
            }, Math.max(delay, 0), TimeUnit.MILLISECONDS);
        } else {
            setAlarm(at);
            releaseLock();
        }
    }

    private synchronized void cancelPending() {
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    private PendingIntent tickIntent() {
        Intent i = new Intent(this, MonitorService.class).setAction(ACTION_TICK);
        return PendingIntent.getService(this, 7, i, piFlags());
    }

    private void setAlarm(long at) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tickIntent());
        else am.set(AlarmManager.RTC_WAKEUP, at, tickIntent());
    }

    private void cancelAlarm() {
        try {
            ((AlarmManager) getSystemService(ALARM_SERVICE)).cancel(tickIntent());
        } catch (Exception ignored) {
        }
    }

    private synchronized void acquireLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "woltwatch:check");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(10 * 60 * 1000L);   // safety timeout; renewed on every cycle
    }

    private synchronized void releaseLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;   // if we cannot tell, just try
        }
    }

    // ---------------------------------------------------------- notifications

    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_ONGOING, n);
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_ONGOING, "מעקב פעיל", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "חנות נפתחה", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_QUIET, "התראות שקטות (שעות שקט)", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alarm = new NotificationChannel(CH_ALARM, "אזעקה", NotificationManager.IMPORTANCE_HIGH);
        AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        alarm.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), aa);
        alarm.enableVibration(true);
        alarm.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        alarm.setBypassDnd(true);
        nm.createNotificationChannel(alarm);
    }

    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
    }

    private int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;   // required from Android 12
        return f;
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags());
    }

    private PendingIntent actionIntent(WatchStore.Watch w, String action) {
        Intent i = new Intent(this, ActionReceiver.class).setAction(action).putExtra("slug", w.slug);
        return PendingIntent.getBroadcast(this, (w.slug + action).hashCode(), i, piFlags());
    }

    private PendingIntent viewIntent(WatchStore.Watch w) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(w.link())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, (w.slug + "view").hashCode(), i, piFlags());
    }

    private Notification ongoing(String text) {
        return builder(CH_ONGOING).setSmallIcon(R.drawable.ic_stat).setContentTitle("Wolt Watch")
                .setContentText(text).setContentIntent(openApp()).setOngoing(true).build();
    }

    private void updateOngoing() {
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

    /** kind: opened | returned | closing | remind */
    private void fireAlert(WatchStore.Watch w, String kind, String text) {
        String title;
        if (kind.equals("opened")) title = w.name + " נפתחה!";
        else if (kind.equals("returned")) title = w.name + " חזרה לפעילות!";
        else if (kind.equals("closing")) title = w.name + " נסגרת בקרוב";
        else title = "תזכורת: " + w.name + " פתוחה";

        boolean loud = w.alarm && !kind.equals("closing");
        boolean quiet = !loud && Prefs.isQuietNow(this);
        String channel = loud ? CH_ALARM : quiet ? CH_QUIET : CH_ALERT;

        Notification.Builder b = builder(channel).setSmallIcon(R.drawable.ic_stat).setContentTitle(title)
                .setContentText(text).setContentIntent(openApp()).setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) {   // before Android 8 sound/priority are set per notification
            if (loud) {
                b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioManager.STREAM_ALARM)
                        .setVibrate(new long[]{0, 600, 300, 600, 300, 600}).setPriority(Notification.PRIORITY_MAX);
            } else if (quiet) {
                b.setPriority(Notification.PRIORITY_LOW);
            } else {
                b.setDefaults(Notification.DEFAULT_ALL).setPriority(Notification.PRIORITY_HIGH);
            }
        }
        b.addAction(0, "פתח בוולט", viewIntent(w));
        boolean opening = kind.equals("opened") || kind.equals("returned");
        if (!(opening && w.once) && !kind.equals("closing")) b.addAction(0, "הזכר בעוד שעה", actionIntent(w, ActionReceiver.SNOOZE));
        b.addAction(0, "הפסק לעקוב", actionIntent(w, ActionReceiver.UNWATCH));
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(alertId(w.slug), b.build());

        Notifier.deliver(this, kind, w, title, text);
        if (w.once && opening) WatchStore.remove(this, w.slug);   // one-shot: done after the first alert
    }

    @Override
    public void onDestroy() {
        cancelPending();
        if (executor != null) executor.shutdownNow();
        releaseLock();
        cancelAlarm();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
