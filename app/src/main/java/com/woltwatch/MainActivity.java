package com.woltwatch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends Activity {

    private EditText urlInput;
    private TextView statusView;
    private Button toggle;
    private SharedPreferences sp;
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences("watch", MODE_PRIVATE);
        urlInput = (EditText) findViewById(R.id.url);
        statusView = (TextView) findViewById(R.id.status);
        toggle = (Button) findViewById(R.id.toggle);
        urlInput.setText(sp.getString("url", ""));
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }

    private void onToggle() {
        if (sp.getBoolean("running", false)) {
            sp.edit().putBoolean("running", false).apply();
            stopService(new Intent(this, MonitorService.class));
            return;
        }
        final String url = urlInput.getText().toString();
        toggle.setEnabled(false);
        statusView.setText("בודק את הקישור…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String slug = WoltClient.parseSlug(url);
                    double[] c = WoltClient.fetchCoords(slug);
                    WoltClient.Status st = WoltClient.fetchStatus(slug, c[0], c[1]);
                    sp.edit().putString("url", url).putString("slug", slug)
                            .putString("lat", String.valueOf(c[0])).putString("lon", String.valueOf(c[1]))
                            .putString("name", st.name).putInt("last_open", st.open ? 1 : 0)
                            .putString("last_text", st.text).putString("next_open", st.nextOpen)
                            .putString("error", "").putLong("last_check", System.currentTimeMillis())
                            .putBoolean("running", true).apply();
                    Intent i = new Intent(MainActivity.this, MonitorService.class);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "שגיאה: " + e, Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { toggle.setEnabled(true); }
                    });
                }
            }
        }).start();
    }

    private void render() {
        boolean running = sp.getBoolean("running", false);
        toggle.setText(running ? "עצור מעקב" : "התחל מעקב");
        if (!running && sp.getLong("last_check", 0) == 0) { statusView.setText(""); return; }
        StringBuilder s = new StringBuilder();
        s.append(sp.getString("name", ""));
        int open = sp.getInt("last_open", -1);
        s.append("\n").append(open == 1 ? "● פתוחה" : open == 0 ? "○ סגורה" : "—");
        s.append("\n").append(sp.getString("last_text", ""));
        long t = sp.getLong("last_check", 0);
        if (t > 0) s.append("\nבדיקה אחרונה: ").append(DateFormat.getTimeInstance().format(new Date(t)));
        String err = sp.getString("error", "");
        if (err.length() > 0) s.append("\nשגיאה: ").append(err);
        s.append(running ? "\n(המעקב פעיל)" : "\n(המעקב כבוי)");
        statusView.setText(s.toString());
    }
}
