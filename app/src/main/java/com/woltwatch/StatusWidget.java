package com.woltwatch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

/** Home-screen widget: status of up to five venues. */
public class StatusWidget extends AppWidgetProvider {

    private static final int[] ROWS = {R.id.w1, R.id.w2, R.id.w3, R.id.w4, R.id.w5};

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews rv = build(c);
        for (int id : ids) m.updateAppWidget(id, rv);
    }

    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, StatusWidget.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews rv = build(c);
            for (int id : ids) m.updateAppWidget(id, rv);
        } catch (Exception ignored) {
        }
    }

    private static RemoteViews build(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_status);
        List<WatchStore.Watch> list = WatchStore.load(c);
        rv.setTextViewText(R.id.wtitle, "Wolt Watch " + (WatchStore.isRunning(c) ? "●" : "○"));
        for (int i = 0; i < ROWS.length; i++) {
            if (i < list.size()) {
                WatchStore.Watch w = list.get(i);
                String mark = w.lastOpen == 1 ? "● " : w.lastOpen == 0 ? "○ " : "… ";
                String s = w.lastOpen == 1 ? "פתוחה" : w.lastOpen == 0 ? ("offline".equals(w.closedKind) ? "מושבתת" : "סגורה") : "—";
                rv.setTextViewText(ROWS[i], mark + w.name + " – " + s);
                rv.setViewVisibility(ROWS[i], View.VISIBLE);
            } else {
                rv.setViewVisibility(ROWS[i], View.GONE);
            }
        }
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        rv.setOnClickPendingIntent(R.id.wroot, PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), f));
        return rv;
    }
}
