package com.local.diplay.clusterbridge.homemap;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Integration and one-time ownership hand-off; the instrument reader's preferences stay separate. */
public final class HomeMapController {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    interface Callback { void done(boolean success, String message); }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("home_map", Context.MODE_PRIVATE); }
    public static void restoreIfEnabled(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean("enabled", false) || !prefs.getBoolean("ownership_claimed", false)) return;
        if (!Settings.canDrawOverlays(context) || !MapSettingsActivity.usageAllowed(context)) {
            prefs.edit().putString("status", "桌面地图等待浮窗及使用情况授权").apply(); return;
        }
        try { context.startForegroundService(new Intent(context, MapService.class)); }
        catch (RuntimeException ignored) { prefs.edit().putString("status", "桌面地图启动失败，请从设置重新开启").apply(); }
    }
    static void importAppearance(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            try (MapAdbShell shell = new MapAdbShell(app.getApplicationInfo().sourceDir)) {
                importOnce(app, shell);
                main.post(() -> callback.done(true, "现有浮窗设置已准备好"));
            } catch (Exception error) {
                main.post(() -> callback.done(false, "旧设置暂时无法读取，可先调整外观，或稍后重新打开本页"));
            }
        });
    }
    private static void importOnce(Context app, MapAdbShell shell) throws Exception {
        SharedPreferences prefs = prefs(app);
        if (prefs.getBoolean("migration_done", false)) return;
        String result = shell.exchange("M"), prefix = "RESULT MIGRATION ";
        if (!result.startsWith(prefix)) throw new IllegalStateException("Migration unavailable");
        JSONObject values = new JSONObject(result.substring(prefix.length()));
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : new String[]{"width", "height", "x", "y", "feather_percent"})
            if (!prefs.contains(key)) edit.putInt(key, values.getInt(key));
        edit.putBoolean("legacy_found", values.getBoolean("legacy_found"))
            .putBoolean("legacy_was_enabled", values.getBoolean("enabled"))
            .putBoolean("migration_done", true).putInt("geometry_version", 2);
        if (!edit.commit()) throw new IllegalStateException("Migration save failed");
    }
    static void enable(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            boolean restart = false;
            try (MapAdbShell shell = new MapAdbShell(app.getApplicationInfo().sourceDir)) {
                importOnce(app, shell);
                String claimed = shell.exchange("C");
                if (!claimed.equals("RESULT CLAIMED") && !claimed.equals("RESULT CLAIMED_RESTART"))
                    throw new IllegalStateException("Ownership handoff failed");
                restart = claimed.equals("RESULT CLAIMED_RESTART");
                if (restart && !shell.exchange("S").equals("OK")) throw new IllegalStateException("Restart preparation failed");
                SharedPreferences prefs = prefs(app);
                if (!prefs.edit().putBoolean("ownership_claimed", true).putBoolean("enabled", true)
                        .commit()) throw new IllegalStateException("Map save failed");
                main.post(() -> restoreIfEnabled(app));
                long until = SystemClock.elapsedRealtime() + 5000;
                while (!MapService.isDisplayReady() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
                if (restart && !shell.exchange("O").equals("OK")) throw new IllegalStateException("DiPlay restart failed");
                if (!MapService.isDisplayReady()) throw new IllegalStateException("Map display unavailable");
                String message = restart ? "已接管旧浮窗并保留外观，DiPlay 正在重新连接" : "桌面地图已开启";
                main.post(() -> callback.done(true, message));
            } catch (Exception error) {
                prefs(app).edit().putString("status", "桌面地图接管未完成，请重试开启").apply();
                if (restart) main.post(() -> {
                    try { app.startActivity(new Intent().setClassName("com.shihab.diplay", "com.shilapi.xcertplay.CarPlayHostActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
                    catch (RuntimeException ignored) { }
                });
                main.post(() -> callback.done(false, "接管未完成，请重试开启；旧浮窗应用和设置仍保留"));
            }
        });
    }
    static void disable(Context context) {
        prefs(context).edit().putBoolean("enabled", false).apply();
        context.stopService(new Intent(context, MapService.class));
    }
}
