package com.woltwatch;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/** Talks to the endpoints the Wolt website itself uses (unofficial, may change at any time). */
public class WoltClient {

    static final String BASE = "https://consumer-api.wolt.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** The response no longer looks the way we expect: Wolt probably changed or blocked the API. */
    public static class SchemaException extends IOException {
        SchemaException(String m) { super(m); }
    }

    public static class Info {
        public double lat, lon;
        public String name = "", shareUrl = "";
    }

    public static class Status {
        public boolean open;       // online AND inside opening hours
        public boolean online;
        public String text = "", nextOpen = "", name = "", estimate = "", styleType = "";
        public long nextOpenAt = 0, nextCloseAt = 0;   // epoch ms on THIS device's clock (0 = unknown)
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

    /** Short/share links are followed to their final address; full venue links are returned as is. */
    public static String resolveLink(String input) {
        String s = input == null ? "" : input.trim();
        if (!s.startsWith("http")) return s;
        try {
            List<String> seg = Uri.parse(s).getPathSegments();
            if (seg.contains("restaurant") || seg.contains("venue")) return s;
            HttpURLConnection c = open(s);
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.getResponseCode();
            String fin = c.getURL().toString();
            c.disconnect();
            return fin;
        } catch (Exception e) {
            return s;
        }
    }

    public static Info fetchInfo(String slug) throws Exception {
        String body = http("GET", BASE + "/order-xp/web/v1/pages/venue/slug/" + slug + "/static", null, null, true);
        JSONObject root;
        try { root = new JSONObject(body); } catch (JSONException e) { throw new SchemaException("תשובה שאינה JSON"); }
        JSONObject raw = root.optJSONObject("venue_raw");
        JSONObject loc = raw == null ? null : raw.optJSONObject("location");
        JSONArray c = loc == null ? null : loc.optJSONArray("coordinates");
        if (c == null || c.length() < 2) throw new SchemaException("חסר venue_raw.location.coordinates");
        Info i = new Info();
        i.lat = c.getDouble(1);   // GeoJSON order is [lon, lat]
        i.lon = c.getDouble(0);
        JSONObject v = root.optJSONObject("venue");
        if (v != null) {
            i.name = str(v, "name");
            i.shareUrl = str(v, "share_url");
        }
        return i;
    }

    public static Status fetchStatus(String slug, double lat, double lon) throws Exception {
        String url = BASE + "/order-xp/web/v1/venue/slug/" + slug + "/dynamic/?lat="
                + String.format(Locale.US, "%.6f", lat) + "&lon=" + String.format(Locale.US, "%.6f", lon);
        String body = http("GET", url, null, null, true);
        JSONObject root;
        try { root = new JSONObject(body); } catch (JSONException e) { throw new SchemaException("תשובה שאינה JSON"); }
        JSONObject v = root.optJSONObject("venue");
        if (v == null || !v.has("online")) throw new SchemaException("חסר venue.online");
        JSONObject ds = v.optJSONObject("delivery_open_status");
        if (ds == null) ds = v.optJSONObject("open_status");
        if (ds == null || !ds.has("is_open")) throw new SchemaException("חסר is_open");

        Status st = new Status();
        st.name = str(v, "name");
        st.online = v.optBoolean("online", false);
        st.text = str(ds, "value");
        st.nextOpen = str(ds, "next_open");
        JSONObject style = ds.optJSONObject("style");
        st.styleType = style == null ? "" : str(style, "type");
        st.open = st.online && ds.optBoolean("is_open", false);

        long serverNow = parseIso(str(ds, "now"));
        long skew = serverNow > 0 ? serverNow - System.currentTimeMillis() : 0;   // server clock vs. ours
        long no = parseIso(st.nextOpen);
        st.nextOpenAt = no > 0 ? no - skew : 0;
        long nc = parseIso(str(ds, "next_close"));
        st.nextCloseAt = nc > 0 ? nc - skew : 0;

        JSONArray cfg = v.optJSONArray("delivery_configs");
        if (cfg != null) {
            for (int i = 0; i < cfg.length(); i++) {
                JSONObject o = cfg.optJSONObject(i);
                if (o != null && "standard".equals(str(o, "schedule"))) {
                    JSONObject e = o.optJSONObject("estimate");
                    if (e != null) st.estimate = str(e, "label");
                    break;
                }
            }
        }
        return st;
    }

    private static String str(JSONObject o, String k) {
        return o.isNull(k) ? "" : o.optString(k, "");
    }

    private static final Pattern ISO =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})$");

    /** ISO-8601 with offset -> epoch ms (SimpleDateFormat 'X' does not exist before API 24). */
    static long parseIso(String s) {
        if (s == null) return 0;
        Matcher m = ISO.matcher(s.trim());
        if (!m.matches()) return 0;
        String off = m.group(2);
        off = off.equals("Z") ? "+0000" : off.replace(":", "");
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            return f.parse(m.group(1) + off).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        if (c instanceof HttpsURLConnection) Tls.apply((HttpsURLConnection) c);
        return c;
    }

    /** Generic HTTP helper, also used for notification targets (wolt=false). */
    static String http(String method, String u, String body, String contentType, boolean wolt) throws IOException {
        HttpURLConnection c = open(u);
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        if (wolt) {
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("App-Language", "en");
            c.setRequestProperty("App-Locale", "en-IL");
        }
        if (body != null) {
            byte[] b = body.getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            os.write(b);
            os.close();
        }
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
        String text = sb.toString();
        if (code >= 200 && code < 300) return text;
        String snippet = text.length() > 160 ? text.substring(0, 160) : text;
        if (wolt && (code == 404 || code == 410 || code == 430)) {
            throw new SchemaException("HTTP " + code + " " + snippet);   // removed / blocked / "update the app"
        }
        throw new IOException("HTTP " + code + " " + snippet);
    }
}
