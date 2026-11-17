package com.woltwatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistent list of watched venues + the "monitoring is on" flag (survives restarts and reboots). */
final class WatchStore {

    static class Watch {
        String slug = "", name = "", url = "", lat = "0", lon = "0";
        int lastOpen = -1;           // -1 unknown, 0 closed, 1 open
        String lastText = "", nextOpen = "", error = "";
        long lastCheck = 0;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("slug", slug).put("name", name).put("url", url).put("lat", lat).put("lon", lon)
                    .put("lastOpen", lastOpen).put("lastText", lastText).put("nextOpen", nextOpen)
                    .put("error", error).put("lastCheck", lastCheck);
            return o;
        }

        static Watch fromJson(JSONObject o) {
            Watch w = new Watch();
            w.slug = o.optString("slug");
            w.name = o.optString("name", w.slug);
            w.url = o.optString("url");
            w.lat = o.optString("lat", "0");
            w.lon = o.optString("lon", "0");
            w.lastOpen = o.optInt("lastOpen", -1);
            w.lastText = o.optString("lastText");
            w.nextOpen = o.optString("nextOpen");
            w.error = o.optString("error");
            w.lastCheck = o.optLong("lastCheck", 0);
            return w;
        }
    }

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

    /**
     * Stores the result of one check. Read-modify-write under the lock so that adding or
     * removing a venue from the UI is never overwritten by the service.
     * @return previous lastOpen, or -2 if the venue was removed in the meantime
     */
    static synchronized int record(Context c, String slug, WoltClient.Status st, String error) {
        List<Watch> list = load(c);
        for (Watch w : list) {
            if (!w.slug.equals(slug)) continue;
            int prev = w.lastOpen;
            w.lastCheck = System.currentTimeMillis();
            if (st != null) {
                w.lastOpen = st.open ? 1 : 0;
                w.lastText = st.text;
                w.nextOpen = st.nextOpen;
                if (st.name.length() > 0) w.name = st.name;
                w.error = "";
            } else {
                w.error = error == null ? "" : error;
            }
            save(c, list);
            return prev;
        }
        return -2;
    }

    static boolean isRunning(Context c) { return sp(c).getBoolean("running", false); }

    static void setRunning(Context c, boolean on) { sp(c).edit().putBoolean("running", on).apply(); }
}
