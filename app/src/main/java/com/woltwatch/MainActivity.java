package com.woltwatch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final Pattern ANY_LINK = Pattern.compile("https?://\\S+");
    private static final Pattern VENUE_LINK =
            Pattern.compile("https?://(?:www\\.)?wolt\\.com/\\S*/(?:restaurant|venue)/[^\\s?#]+");
    private static final int[] WARN_OPTIONS = {0, 15, 30, 60};

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
                ((TextView) v.findViewById(android.R.id.text2)).setText(describe(w));
                return v;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                showWatchMenu(items.get(pos));
                return true;
            }
        });

        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onAdd(); }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });
        findViewById(R.id.settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) checkClipboard();   // clipboard is only readable while we have focus (Android 10+)
    }

    // ------------------------------------------------------------ list content

    private String describe(WatchStore.Watch w) {
        StringBuilder s = new StringBuilder();
        if (w.lastOpen == 1) s.append("פתוחה");
        else if (w.lastOpen == 0) s.append("offline".equals(w.closedKind) ? "מושבתת זמנית" : "סגורה");
        else s.append("טרם נבדקה");
        if (w.lastText.length() > 0) s.append(" – ").append(w.lastText);
        if (w.lastOpen == 1 && w.estimate.length() > 0) s.append(" · משלוח ").append(w.estimate);
        if (w.once) s.append("  [חד-פעמי]");
        if (w.warnClose > 0) s.append("  [התראת סגירה ").append(w.warnClose).append("]");
        if (w.alarm) s.append("  [אזעקה]");
        if (w.lastCheck > 0) {
            s.append("  (").append(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(w.lastCheck))).append(")");
        }
        if (w.error.length() > 0) s.append("\nשגיאה: ").append(w.error);
        return s.toString();
    }

    private void render() {
        boolean running = WatchStore.isRunning(this);
        toggle.setText(running ? "עצור מעקב" : "התחל מעקב");
        String sig = WatchStore.signature(this);
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;
        items.clear();
        items.addAll(WatchStore.load(this));
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------- add / share

    /** Share target: "Share" in the Wolt app -> this app. */
    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String t = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (t == null) return;
        Matcher m = ANY_LINK.matcher(t);
        if (m.find()) {
            urlInput.setText(m.group());
            onAdd();
        } else {
            Toast.makeText(this, "לא נמצא קישור בטקסט ששותף", Toast.LENGTH_LONG).show();
        }
    }

    private void checkClipboard() {
        String t = clipText();
        Matcher m = VENUE_LINK.matcher(t);
        if (!m.find()) return;
        final String link = m.group();
        SharedPreferences ui = getSharedPreferences("ui", MODE_PRIVATE);
        if (link.equals(ui.getString("clip_prompted", ""))) return;
        String slug;
        try {
            slug = WoltClient.parseSlug(link);
        } catch (Exception e) {
            return;
        }
        for (WatchStore.Watch w : WatchStore.load(this)) if (w.slug.equals(slug)) return;
        ui.edit().putString("clip_prompted", link).apply();
        new AlertDialog.Builder(this)
                .setMessage("בלוח יש קישור לחנות בוולט:\n" + slug + "\n\nלהוסיף אותה למעקב?")
                .setPositiveButton("הוסף", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        urlInput.setText(link);
                        onAdd();
                    }
                })
                .setNegativeButton("לא עכשיו", null).show();
    }

    private String clipText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "";
            CharSequence t = cd.getItemAt(0).getText();
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void onAdd() {
        final String input = urlInput.getText().toString();
        addBtn.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                String msg = null;
                try {
                    String url = WoltClient.resolveLink(input);
                    String slug = WoltClient.parseSlug(url);
                    WoltClient.Info info = WoltClient.fetchInfo(slug);
                    WoltClient.Status st = WoltClient.fetchStatus(slug, info.lat, info.lon);
                    long now = System.currentTimeMillis();
                    WatchStore.Watch w = new WatchStore.Watch();
                    w.slug = slug;
                    w.url = url;
                    w.shareUrl = info.shareUrl;
                    w.name = st.name.length() > 0 ? st.name : (info.name.length() > 0 ? info.name : slug);
                    w.lat = String.valueOf(info.lat);
                    w.lon = String.valueOf(info.lon);
                    w.lastOpen = st.open ? 1 : 0;
                    w.online = st.online ? 1 : 0;
                    w.lastText = st.text;
                    w.nextOpen = st.nextOpen;
                    w.nextOpenAt = st.nextOpenAt;
                    w.nextCloseAt = st.nextCloseAt;
                    w.estimate = st.estimate;
                    w.closedKind = st.open ? "" : (st.online ? "hours" : "offline");
                    w.lastCheck = now;
                    w.nextCheckAt = WatchStore.nextCheckAfter(w, now);
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
                        changed();
                    }
                });
            }
        }).start();
    }

    /** Refresh the screen and let a running service re-plan its schedule. */
    private void changed() {
        render();
        if (WatchStore.isRunning(this)) {
            try {
                startService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_RECALC));
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------- per-venue menu

    private void showWatchMenu(final WatchStore.Watch w) {
        String[] labels = {
                "היסטוריה וחיזוי",
                "מעקב חד-פעמי: " + (w.once ? "פעיל" : "כבוי"),
                "התראה לפני סגירה: " + (w.warnClose > 0 ? w.warnClose + " דק׳" : "כבויה"),
                "אזעקה חזקה: " + (w.alarm ? "פעילה" : "כבויה"),
                "פתח בוולט",
                "הסר מהמעקב"
        };
        new AlertDialog.Builder(this).setTitle(w.name).setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                switch (which) {
                    case 0:
                        showHistory(w);
                        break;
                    case 1:
                        WatchStore.update(MainActivity.this, w.slug, new WatchStore.Mutator() {
                            @Override public void apply(WatchStore.Watch x) { x.once = !x.once; }
                        });
                        changed();
                        break;
                    case 2:
                        WatchStore.update(MainActivity.this, w.slug, new WatchStore.Mutator() {
                            @Override public void apply(WatchStore.Watch x) {
                                int idx = 0;
                                for (int i = 0; i < WARN_OPTIONS.length; i++) if (WARN_OPTIONS[i] == x.warnClose) idx = i;
                                x.warnClose = WARN_OPTIONS[(idx + 1) % WARN_OPTIONS.length];
                            }
                        });
                        changed();
                        break;
                    case 3:
                        WatchStore.update(MainActivity.this, w.slug, new WatchStore.Mutator() {
                            @Override public void apply(WatchStore.Watch x) { x.alarm = !x.alarm; }
                        });
                        changed();
                        break;
                    case 4:
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(w.link())));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "לא ניתן לפתוח את הקישור", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case 5:
                        new AlertDialog.Builder(MainActivity.this)
                                .setMessage("להסיר את " + w.name + " מהמעקב?")
                                .setPositiveButton("הסר", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface dd, int ww) {
                                        WatchStore.remove(MainActivity.this, w.slug);
                                        changed();
                                    }
                                })
                                .setNegativeButton("ביטול", null).show();
                        break;
                    default:
                        break;
                }
            }
        }).show();
    }

    private void showHistory(WatchStore.Watch w) {
        List<WatchStore.Event> ev = WatchStore.events(this, w.slug);
        String typical = WatchStore.typicalOpen(this, w.slug);
        StringBuilder s = new StringBuilder();
        s.append(typical.length() == 0 ? "אין עדיין מספיק נתונים לחיזוי (דרושות 3 פתיחות לפחות בשלושה השבועות האחרונים)"
                : "נפתחת בדרך כלל בסביבות " + typical).append("\n\n");
        if (ev.isEmpty()) {
            s.append("עדיין לא נצפו פתיחות או סגירות");
        } else {
            SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
            for (int i = ev.size() - 1, n = 0; i >= 0 && n < 20; i--, n++) {
                WatchStore.Event e = ev.get(i);
                s.append(f.format(new Date(e.t))).append("  ").append(e.type.equals("open") ? "נפתחה" : "נסגרה").append("\n");
            }
        }
        new AlertDialog.Builder(this).setTitle("היסטוריה – " + w.name).setMessage(s.toString())
                .setPositiveButton("סגור", null).show();
    }

    // ----------------------------------------------------------------- settings

    private static String fmt(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private EditText field(LinearLayout box, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        box.addView(e);
        return e;
    }

    private void showSettings() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        ScrollView sv = new ScrollView(this);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        sv.addView(box);

        final CheckBox quiet = new CheckBox(this);
        quiet.setText("שעות שקט (התראות בלי צליל)");
        quiet.setChecked(Prefs.quietEnabled(this));
        box.addView(quiet);
        final int[] range = {Prefs.quietStart(this), Prefs.quietEnd(this)};
        final Button from = new Button(this);
        final Button to = new Button(this);
        from.setText("מתחיל ב-" + fmt(range[0]));
        to.setText("נגמר ב-" + fmt(range[1]));
        from.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(android.widget.TimePicker p, int h, int m) {
                        range[0] = h * 60 + m;
                        from.setText("מתחיל ב-" + fmt(range[0]));
                    }
                }, range[0] / 60, range[0] % 60, true).show();
            }
        });
        to.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(android.widget.TimePicker p, int h, int m) {
                        range[1] = h * 60 + m;
                        to.setText("נגמר ב-" + fmt(range[1]));
                    }
                }, range[1] / 60, range[1] % 60, true).show();
            }
        });
        box.addView(from);
        box.addView(to);

        TextView head = new TextView(this);
        head.setText("\nשליחת התראות גם החוצה (לא חובה)");
        box.addView(head);
        final EditText ntfy = field(box, "ntfy: https://ntfy.sh/הנושא-שלי", Prefs.get(this, "ntfy"));
        final EditText tgToken = field(box, "טלגרם: Bot token", Prefs.get(this, "tgToken"));
        final EditText tgChat = field(box, "טלגרם: Chat id", Prefs.get(this, "tgChat"));
        final EditText hook = field(box, "Webhook (POST עם JSON)", Prefs.get(this, "webhook"));

        final Runnable save = new Runnable() {
            @Override public void run() {
                Prefs.setQuiet(MainActivity.this, quiet.isChecked(), range[0], range[1]);
                Prefs.put(MainActivity.this, "ntfy", ntfy.getText().toString());
                Prefs.put(MainActivity.this, "tgToken", tgToken.getText().toString());
                Prefs.put(MainActivity.this, "tgChat", tgChat.getText().toString());
                Prefs.put(MainActivity.this, "webhook", hook.getText().toString());
            }
        };

        Button test = new Button(this);
        test.setText("שלח הודעת בדיקה");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save.run();
                new Thread(new Runnable() {
                    @Override public void run() {
                        WatchStore.Watch dummy = new WatchStore.Watch();
                        dummy.name = "Wolt Watch";
                        final String err = Notifier.deliver(MainActivity.this, "test", dummy,
                                "בדיקה", "הודעת בדיקה מ-Wolt Watch");
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, err.length() == 0 ? "נשלח" : err, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        });
        box.addView(test);

        Button export = new Button(this);
        export.setText("ייצוא הרשימה (שיתוף)");
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, WatchStore.exportJson(MainActivity.this));
                startActivity(Intent.createChooser(s, "ייצוא"));
            }
        });
        box.addView(export);

        Button imp = new Button(this);
        imp.setText("ייבוא מהלוח");
        imp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = WatchStore.importJson(MainActivity.this, clipText());
                Toast.makeText(MainActivity.this, n > 0 ? "יובאו " + n + " חנויות" : "לא נמצאו חנויות חדשות בלוח",
                        Toast.LENGTH_LONG).show();
                changed();
            }
        });
        box.addView(imp);

        new AlertDialog.Builder(this).setTitle("הגדרות").setView(sv)
                .setPositiveButton("שמור", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { save.run(); }
                })
                .setNegativeButton("ביטול", null).show();
    }

    // ------------------------------------------------- start / stop / permissions

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
        StatusWidget.refresh(this);
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
}
