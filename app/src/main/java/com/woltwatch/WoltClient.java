package com.woltwatch;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/** Talks to the endpoints the Wolt website itself uses (unofficial, may change). */
public class WoltClient {

    static final String BASE = "https://consumer-api.wolt.com";

    public static class Status {
        public boolean open;      // online AND inside opening hours
        public boolean online;
        public String text = "";  // e.g. "Open until 23:55"
        public String nextOpen = "";
        public String name = "";
    }

    /** Accepts a full venue link (…/restaurant/<slug> or …/venue/<slug>) or a bare slug. */
    public static String parseSlug(String input) throws IOException {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) throw new IOException("לא הוזן קישור");
        if (!s.contains("/")) return s;
        List<String> seg = Uri.parse(s).getPathSegments();
        for (int i = 0; i < seg.size() - 1; i++) {
            String p = seg.get(i);
            if (p.equals("restaurant") || p.equals("venue")) return seg.get(i + 1);
        }
        throw new IOException("לא נמצא שם חנות בקישור");
    }

    /** Returns {lat, lon, nameIgnored} from the static venue endpoint. */
    public static double[] fetchCoords(String slug) throws Exception {
        String body = get(BASE + "/order-xp/web/v1/pages/venue/slug/" + slug + "/static");
        JSONObject root = new JSONObject(body);
        JSONArray c = root.getJSONObject("venue_raw").getJSONObject("location").getJSONArray("coordinates");
        return new double[]{c.getDouble(1), c.getDouble(0)}; // GeoJSON order is [lon, lat]
    }

    public static String fetchName(String slug) {
        try {
            String body = get(BASE + "/order-xp/web/v1/pages/venue/slug/" + slug + "/static");
            return new JSONObject(body).getJSONObject("venue").optString("name", slug);
        } catch (Exception e) {
            return slug;
        }
    }

    public static Status fetchStatus(String slug, double lat, double lon) throws Exception {
        String url = BASE + "/order-xp/web/v1/venue/slug/" + slug + "/dynamic/?lat="
                + String.format(Locale.US, "%.6f", lat) + "&lon=" + String.format(Locale.US, "%.6f", lon);
        JSONObject v = new JSONObject(get(url)).getJSONObject("venue");
        Status st = new Status();
        st.name = v.optString("name", slug);
        st.online = v.optBoolean("online", false);
        JSONObject ds = v.optJSONObject("delivery_open_status");
        if (ds == null) ds = v.optJSONObject("open_status");
        if (ds == null) throw new IOException("מבנה תשובה לא מוכר (אין open_status)");
        st.text = ds.optString("value", "");
        st.nextOpen = ds.optString("next_open", "");
        st.open = st.online && ds.optBoolean("is_open", false);
        return st;
    }

    private static String get(String u) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) new URL(u).openConnection();
        Tls.apply(c);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("App-Language", "en");
        c.setRequestProperty("App-Locale", "en-IL");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
        }
        c.disconnect();
        if (code != 200) {
            String b = sb.toString();
            throw new IOException("HTTP " + code + (b.length() > 160 ? " " + b.substring(0, 160) : " " + b));
        }
        return sb.toString();
    }
}
