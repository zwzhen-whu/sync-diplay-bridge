package com.local.diplay.clusterbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/** Start the existing foreground reader after boot or an in-place app update. */
public final class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        SharedPreferences preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("autostart", true)) {
            Log.i("DiPlayBridge", "Auto-start disabled; ignored " + action);
            return;
        }
        try {
            context.startForegroundService(new Intent(context, BridgeService.class));
            preferences.edit().putString("last_auto_start", action).putString("auto_start_error", "").apply();
            Log.i("DiPlayBridge", "Auto-start requested: " + action);
        } catch (RuntimeException e) {
            preferences.edit().putString("auto_start_error", e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
            Log.e("DiPlayBridge", "Auto-start failed", e);
        }
    }
}
