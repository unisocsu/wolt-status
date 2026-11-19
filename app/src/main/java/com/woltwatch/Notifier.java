package com.woltwatch;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.net.URLEncoder;

/** Sends an event outside the phone: automation broadcast, ntfy, Telegram bot, generic webhook. */
final class Notifier {

    static final String EVENT_ACTION = "com.woltwatch.EVENT";

    /** @return a short list of failures, empty when everything that is configured succeeded */
    static String deliver(Context c, String event, WatchStore.Watch w, String title, String text) {
        StringBuilder errors = new StringBuilder();
        try {   // for Tasker and similar apps
            Intent i = new Intent(EVENT_ACTION).putExtra("event", event).putExtra("slug", w.slug)
                    .putExtra("name", w.name).putExtra("text", text);
            c.sendBroadcast(i);
        } catch (Exception ignored) {
        }
        String msg = title + (text.length() > 0 ? "\n" + text : "");

        String ntfy = Prefs.get(c, "ntfy");
        if (!ntfy.isEmpty()) {
            try {
                WoltClient.http("POST", ntfy, msg, "text/plain; charset=utf-8", false);
            } catch (Exception e) {
                errors.append("ntfy: ").append(e.getMessage()).append('\n');
            }
        }
        String token = Prefs.get(c, "tgToken"), chat = Prefs.get(c, "tgChat");
        if (!token.isEmpty() && !chat.isEmpty()) {
            try {
                String body = "chat_id=" + URLEncoder.encode(chat, "UTF-8") + "&text=" + URLEncoder.encode(msg, "UTF-8");
                WoltClient.http("POST", "https://api.telegram.org/bot" + token + "/sendMessage", body,
                        "application/x-www-form-urlencoded", false);
            } catch (Exception e) {
                errors.append("Telegram: ").append(e.getMessage()).append('\n');
            }
        }
        String hook = Prefs.get(c, "webhook");
        if (!hook.isEmpty()) {
            try {
                JSONObject o = new JSONObject().put("event", event).put("slug", w.slug).put("name", w.name)
                        .put("title", title).put("text", text).put("time", System.currentTimeMillis());
                WoltClient.http("POST", hook, o.toString(), "application/json; charset=utf-8", false);
            } catch (Exception e) {
                errors.append("Webhook: ").append(e.getMessage()).append('\n');
            }
        }
        return errors.toString().trim();
    }
}
