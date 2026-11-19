package com.woltwatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/** Global app settings (quiet hours, external notification targets, API-change counters). */
final class Prefs {

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean quietEnabled(Context c) { return sp(c).getBoolean("quietOn", false); }
    static int quietStart(Context c) { return sp(c).getInt("quietStart", 22 * 60); }
    static int quietEnd(Context c) { return sp(c).getInt("quietEnd", 7 * 60); }

    static void setQuiet(Context c, boolean on, int start, int end) {
        sp(c).edit().putBoolean("quietOn", on).putInt("quietStart", start).putInt("quietEnd", end).apply();
    }

    static boolean isQuietNow(Context c) {
        if (!quietEnabled(c)) return false;
        Calendar cal = Calendar.getInstance();
        int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int s = quietStart(c), e = quietEnd(c);
        return s <= e ? (m >= s && m < e) : (m >= s || m < e);
    }

    static String get(Context c, String key) { return sp(c).getString(key, ""); }
    static void put(Context c, String key, String value) { sp(c).edit().putString(key, value == null ? "" : value.trim()).apply(); }

    static int schemaFails(Context c) { return sp(c).getInt("schemaFails", 0); }
    static void setSchemaFails(Context c, int n) { sp(c).edit().putInt("schemaFails", n).apply(); }
    static boolean schemaAlerted(Context c) { return sp(c).getBoolean("schemaAlerted", false); }
    static void setSchemaAlerted(Context c, boolean v) { sp(c).edit().putBoolean("schemaAlerted", v).apply(); }
}
