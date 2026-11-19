package com.woltwatch;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Quick Settings tile (Android 7+): turn monitoring on and off from the notification shade. */
@TargetApi(24)
public class ToggleTile extends TileService {

    @Override public void onStartListening() { refreshTile(); }

    @Override
    public void onClick() {
        if (WatchStore.isRunning(this)) {
            WatchStore.setRunning(this, false);
            stopService(new Intent(this, MonitorService.class));
        } else if (WatchStore.load(this).isEmpty()) {
            Toast.makeText(this, "הוסף חנות באפליקציה קודם", Toast.LENGTH_SHORT).show();
        } else {
            WatchStore.setRunning(this, true);
            try {
                Intent i = new Intent(this, MonitorService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            } catch (RuntimeException e) {
                WatchStore.setRunning(this, false);
                Toast.makeText(this, "לא ניתן להפעיל מכאן, פתח את האפליקציה", Toast.LENGTH_LONG).show();
            }
        }
        refreshTile();
    }

    private void refreshTile() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(WatchStore.isRunning(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Wolt Watch");
        t.updateTile();
    }
}
