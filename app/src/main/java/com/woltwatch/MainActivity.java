package com.woltwatch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity {

    private EditText urlInput;
    private Button addBtn, toggle;
    private ListView listView;
    private ArrayAdapter<WatchStore.Watch> adapter;
    private final List<WatchStore.Watch> items = new ArrayList<WatchStore.Watch>();
    private String lastSignature = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
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
        applyInsets(findViewById(R.id.root));
        urlInput = (EditText) findViewById(R.id.url);
        addBtn = (Button) findViewById(R.id.add);
        toggle = (Button) findViewById(R.id.toggle);
        listView = (ListView) findViewById(R.id.list);
        listView.setEmptyView(findViewById(R.id.empty));

        adapter = new ArrayAdapter<WatchStore.Watch>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, items) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                View v = super.getView(pos, convert, parent);
                WatchStore.Watch w = getItem(pos);
                String mark = w.lastOpen == 1 ? "● " : w.lastOpen == 0 ? "○ " : "… ";
                ((TextView) v.findViewById(android.R.id.text1)).setText(mark + w.name);
                StringBuilder s = new StringBuilder(w.lastOpen == 1 ? "פתוחה" : w.lastOpen == 0 ? "סגורה" : "טרם נבדקה");
                if (w.lastText.length() > 0) s.append(" – ").append(w.lastText);
                if (w.lastCheck > 0) s.append("  (").append(DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(new Date(w.lastCheck))).append(")");
                if (w.error.length() > 0) s.append("\nשגיאה: ").append(w.error);
                ((TextView) v.findViewById(android.R.id.text2)).setText(s.toString());
                return v;
            }
        };
        listView.setAdapter(adapter);

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                final WatchStore.Watch w = items.get(pos);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("להסיר את " + w.name + " מהמעקב?")
                        .setPositiveButton("הסר", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                WatchStore.remove(MainActivity.this, w.slug);
                                render();
                            }
                        })
                        .setNegativeButton("ביטול", null).show();
                return true;
            }
        });

        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onAdd(); }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }

    private void onAdd() {
        final String url = urlInput.getText().toString();
        addBtn.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                String msg = null;
                try {
                    String slug = WoltClient.parseSlug(url);
                    double[] c = WoltClient.fetchCoords(slug);
                    WoltClient.Status st = WoltClient.fetchStatus(slug, c[0], c[1]);
                    WatchStore.Watch w = new WatchStore.Watch();
                    w.slug = slug;
                    w.url = url.trim();
                    w.name = st.name;
                    w.lat = String.valueOf(c[0]);
                    w.lon = String.valueOf(c[1]);
                    w.lastOpen = st.open ? 1 : 0;
                    w.lastText = st.text;
                    w.nextOpen = st.nextOpen;
                    w.lastCheck = System.currentTimeMillis();
                    if (!WatchStore.add(MainActivity.this, w)) msg = "החנות כבר ברשימה";
                } catch (Exception e) {
                    msg = "שגיאה: " + e;
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        addBtn.setEnabled(true);
                        if (m != null) Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
                        else urlInput.setText("");
                        render();
                    }
                });
            }
        }).start();
    }

    private void onToggle() {
        if (WatchStore.isRunning(this)) {
            WatchStore.setRunning(this, false);
            stopService(new Intent(this, MonitorService.class));
        } else {
            if (WatchStore.load(this).isEmpty()) {
                Toast.makeText(this, "הוסף קודם חנות אחת לפחות", Toast.LENGTH_SHORT).show();
                return;
            }
            WatchStore.setRunning(this, true);
            Intent i = new Intent(this, MonitorService.class);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            } catch (RuntimeException e) {
                WatchStore.setRunning(this, false);
                Toast.makeText(this, "לא ניתן להפעיל את השירות: " + e, Toast.LENGTH_LONG).show();
                render();
                return;
            }
            askPermissions();
        }
        render();
    }

    /** Android 13+: notification permission first, then (once) the battery-optimization exemption. */
    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        } else {
            askBatteryExemption();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        askBatteryExemption();
    }

    /** Without it Doze (Android 6+) pauses network access when the screen is off. Asked only once. */
    private void askBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        SharedPreferences ui = getSharedPreferences("ui", MODE_PRIVATE);
        if (ui.getBoolean("asked_battery", false)) return;
        ui.edit().putBoolean("asked_battery", true).apply();
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    /** Android 15+ draws apps edge-to-edge: keep the content clear of the system bars. */
    private void applyInsets(final View root) {
        if (Build.VERSION.SDK_INT < 20) return;
        final int pl = root.getPaddingLeft(), pt = root.getPaddingTop();
        final int pr = root.getPaddingRight(), pb = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int l, t, r, b;
                if (Build.VERSION.SDK_INT >= 30) {
                    int mask = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                    if (Build.VERSION.SDK_INT >= 35) mask |= WindowInsets.Type.ime();
                    android.graphics.Insets i = in.getInsets(mask);
                    l = i.left; t = i.top; r = i.right; b = i.bottom;
                } else {
                    l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                    r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
                }
                v.setPadding(pl + l, pt + t, pr + r, pb + b);
                return in;
            }
        });
        root.requestApplyInsets();
    }

    private void render() {
        boolean running = WatchStore.isRunning(this);
        toggle.setText(running ? "עצור מעקב" : "התחל מעקב");
        List<WatchStore.Watch> fresh = WatchStore.load(this);
        StringBuilder sig = new StringBuilder(running ? "R" : "S");
        for (WatchStore.Watch w : fresh) {
            sig.append('|').append(w.slug).append(w.lastOpen).append(w.lastText).append(w.lastCheck).append(w.error);
        }
        if (sig.toString().equals(lastSignature)) return;
        lastSignature = sig.toString();
        items.clear();
        items.addAll(fresh);
        adapter.notifyDataSetChanged();
    }
}
