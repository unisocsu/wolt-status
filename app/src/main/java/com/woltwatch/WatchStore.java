package com.woltwatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Persistent list of watched venues, their history, and the "monitoring is on" flag. */
final class WatchStore {

    interface Mutator { void apply(Watch w); }

    static class Watch {
        String slug = "", name = "", url = "", shareUrl = "", lat = "0", lon = "0";
        int lastOpen = -1;                 // -1 unknown, 0 closed, 1 open
        int online = -1;
        int warnClose = 0;                 // minutes before closing to warn, 0 = off
        int fails = 0;                     // consecutive failed checks (drives back-off)
        String lastText = "", nextOpen = "", error = "", estimate = "";
        String closedKind = "";            // "hours" = outside opening hours, "offline" = switched off temporarily
        long lastCheck = 0, nextOpenAt = 0, nextCloseAt = 0, nextCheckAt = 0, remindAt = 0, closeWarned = 0;
        boolean once = false, alarm = false;

        String link() { return shareUrl.length() > 0 ? shareUrl : url; }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("slug", slug).put("name", name).put("url", url).put("shareUrl", shareUrl)
                    .put("lat", lat).put("lon", lon).put("lastOpen", lastOpen).put("online", online)
                    .put("warnClose", warnClose).put("fails", fails).put("lastText", lastText)
                    .put("nextOpen", nextOpen).put("error", error).put("estimate", estimate)
                    .put("closedKind", closedKind).put("lastCheck", lastCheck).put("nextOpenAt", nextOpenAt)
                    .put("nextCloseAt", nextCloseAt).put("nextCheckAt", nextCheckAt).put("remindAt", remindAt)
                    .put("closeWarned", closeWarned).put("once", once).put("alarm", alarm);
            return o;
        }

        static Watch fromJson(JSONObject o) {
            Watch w = new Watch();
            w.slug = o.optString("slug");
            w.name = o.optString("name", w.slug);
            w.url = o.optString("url");
            w.shareUrl = o.optString("shareUrl");
            w.lat = o.optString("lat", "0");
            w.lon = o.optString("lon", "0");
            w.lastOpen = o.optInt("lastOpen", -1);
            w.online = o.optInt("online", -1);
            w.warnClose = o.optInt("warnClose", 0);
            w.fails = o.optInt("fails", 0);
            w.lastText = o.optString("lastText");
            w.nextOpen = o.optString("nextOpen");
            w.error = o.optString("error");
            w.estimate = o.optString("estimate");
            w.closedKind = o.optString("closedKind");
            w.lastCheck = o.optLong("lastCheck", 0);
            w.nextOpenAt = o.optLong("nextOpenAt", 0);
            w.nextCloseAt = o.optLong("nextCloseAt", 0);
            w.nextCheckAt = o.optLong("nextCheckAt", 0);
            w.remindAt = o.optLong("remindAt", 0);
            w.closeWarned = o.optLong("closeWarned", 0);
            w.once = o.optBoolean("once", false);
            w.alarm = o.optBoolean("alarm", false);
            return w;
        }
    }

    static class Event { long t; String type = ""; }

    /** Result of storing one check: the state before it, and the venue after it. */
    static class Rec { int prevOpen; String prevKind = ""; Watch w; }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("watches", Context.MODE_PRIVATE);
    }

    static synchronized List<Watch> load(Context c) {
        List<Watch> out = new ArrayList<Watch>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Watch.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Watch> list) {
        try {
            JSONArray a = new JSONArray();
            for (Watch w : list) a.put(w.toJson());
            sp(c).edit().putString("list", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** @return false if a venue with this slug is already watched */
    static synchronized boolean add(Context c, Watch w) {
        List<Watch> list = load(c);
        for (Watch x : list) if (x.slug.equals(w.slug)) return false;
        list.add(w);
        save(c, list);
        return true;
    }

    static synchronized void remove(Context c, String slug) {
        List<Watch> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).slug.equals(slug)) { list.remove(i); break; }
        }
        save(c, list);
    }

    /** Read-modify-write under the lock, so UI edits and the service never overwrite each other. */
    static synchronized boolean update(Context c, String slug, Mutator m) {
        List<Watch> list = load(c);
        for (Watch w : list) {
            if (w.slug.equals(slug)) {
                m.apply(w);
                save(c, list);
                return true;
            }
        }
        return false;
    }

    /** Stores the result of one check (st != null) or one failure (st == null). */
    static synchronized Rec record(Context c, String slug, WoltClient.Status st, String error) {
        List<Watch> list = load(c);
        for (Watch w : list) {
            if (!w.slug.equals(slug)) continue;
            Rec r = new Rec();
            r.prevOpen = w.lastOpen;
            r.prevKind = w.closedKind;
            r.w = w;
            long now = System.currentTimeMillis();
            w.lastCheck = now;
            if (st != null) {
                int nowOpen = st.open ? 1 : 0;
                if (w.lastOpen != -1 && w.lastOpen != nowOpen) {
                    addEvent(c, w.slug, nowOpen == 1 ? "open" : "close", now);
                }
                w.lastOpen = nowOpen;
                w.online = st.online ? 1 : 0;
                w.lastText = st.text;
                w.nextOpen = st.nextOpen;
                w.nextOpenAt = st.nextOpenAt;
                w.nextCloseAt = st.nextCloseAt;
                w.estimate = st.estimate;
                w.closedKind = st.open ? "" : (st.online ? "hours" : "offline");
                if (st.name.length() > 0) w.name = st.name;
                w.error = "";
                w.fails = 0;
            } else {
                w.fails++;
                w.error = error == null ? "" : error;
            }
            w.nextCheckAt = nextCheckAfter(w, now);
            save(c, list);
            return r;
        }
        return null;
    }

    /**
     * Smart polling: do not hammer the server when nothing can change.
     *  - failures: exponential back-off 1, 2, 4, 8, 15 minutes
     *  - open: every 10 minutes (or right after the announced closing time)
     *  - closed by opening hours: every 30 minutes, then wake 2 minutes before the opening, then every minute
     *  - temporarily offline / unknown: every minute
     */
    static long nextCheckAfter(Watch w, long now) {
        if (w.fails > 0) return now + Math.min(15 * 60000L, 60000L << Math.min(w.fails - 1, 4));
        if (w.lastOpen == 1) {
            long t = now + 10 * 60000L;
            if (w.nextCloseAt > now) t = Math.min(t, w.nextCloseAt + 60000L);
            return Math.max(t, now + 60000L);
        }
        if ("hours".equals(w.closedKind) && w.nextOpenAt > now) {
            long until = w.nextOpenAt - now;
            if (until > 32 * 60000L) return now + 30 * 60000L;
            if (until > 3 * 60000L) return w.nextOpenAt - 2 * 60000L;
        }
        return now + 60000L;
    }

    // ---- history ----

    static synchronized void addEvent(Context c, String slug, String type, long t) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("events", "[]"));
            a.put(new JSONObject().put("s", slug).put("t", t).put("y", type));
            if (a.length() > 600) {
                JSONArray cut = new JSONArray();
                for (int i = a.length() - 600; i < a.length(); i++) cut.put(a.get(i));
                a = cut;
            }
            sp(c).edit().putString("events", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static synchronized List<Event> events(Context c, String slug) {
        List<Event> out = new ArrayList<Event>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("events", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!slug.equals(o.optString("s"))) continue;
                Event e = new Event();
                e.t = o.optLong("t");
                e.type = o.optString("y");
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Median time of day of the openings seen in the last 3 weeks ("" if fewer than 3). */
    static String typicalOpen(Context c, String slug) {
        long cutoff = System.currentTimeMillis() - 21L * 86400000L;
        List<Integer> mins = new ArrayList<Integer>();
        Calendar cal = Calendar.getInstance();
        for (Event e : events(c, slug)) {
            if (!e.type.equals("open") || e.t < cutoff) continue;
            cal.setTimeInMillis(e.t);
            mins.add(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE));
        }
        if (mins.size() < 3) return "";
        Collections.sort(mins);
        int m = mins.get(mins.size() / 2);
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    // ---- backup ----

    static synchronized String exportJson(Context c) {
        return sp(c).getString("list", "[]");
    }

    static synchronized int importJson(Context c, String text) {
        int n = 0;
        try {
            JSONArray a = new JSONArray(text.trim());
            for (int i = 0; i < a.length(); i++) {
                Watch w = Watch.fromJson(a.getJSONObject(i));
                if (w.slug.length() == 0) continue;
                w.nextCheckAt = 0;
                w.fails = 0;
                if (add(c, w)) n++;
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    static String signature(Context c) {
        return sp(c).getString("list", "") + isRunning(c);
    }

    static boolean isRunning(Context c) { return sp(c).getBoolean("running", false); }

    static void setRunning(Context c, boolean on) { sp(c).edit().putBoolean("running", on).apply(); }
}
