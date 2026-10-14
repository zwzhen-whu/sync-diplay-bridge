package com.local.diplay.clusterbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Handler;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Read only DiPlay's transport tag; UID filtering happens before any data is accepted. */
final class NavigationLogReader {
    final NavigationLogState state = new NavigationLogState();
    private final Context context;
    private final Handler handler;
    private final Runnable changed;
    private volatile Process process;
    private volatile Thread worker;
    private volatile boolean stopped;
    private volatile String status = "等待日志授权";

    NavigationLogReader(Context context, Handler handler, Runnable changed) { this.context = context; this.handler = handler; this.changed = changed; }
    synchronized void ensureStarted() {
        if (stopped || worker != null) return;
        if (context.checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            status = "未授权（小组件路名仍可用）"; return;
        }
        worker = new Thread(() -> {
            try {
                int uid = context.getPackageManager().getApplicationInfo(BridgeService.SOURCE, 0).uid;
                SharedPreferences preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
                if (preferences.getInt("navigation_cache_uid", -1) == uid) state.restoreManeuvers(preferences.getString("navigation_cache", ""), System.currentTimeMillis());
                Process child = new ProcessBuilder("logcat", "-b", "main", "-v", "threadtime", "-v", "uid", "-v", "epoch", "-s", "xcertplay-usb:I").redirectErrorStream(true).start();
                process = child;
                if (stopped) { child.destroy(); return; }
                status = "已授权，正在读取 DiPlay 导航记录";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                    String line;
                    while (!stopped && (line = reader.readLine()) != null) {
                        if (state.accept(line, uid, System.currentTimeMillis())) {
                            String cache = state.savedManeuvers();
                            if (!cache.equals(preferences.getString("navigation_cache", ""))) preferences.edit().putInt("navigation_cache_uid", uid).putString("navigation_cache", cache).apply();
                            handler.post(changed);
                        }
                    }
                }
                if (!stopped) status = "日志读取中断，将重试";
            } catch (Exception e) { status = "读取失败：" + e.getClass().getSimpleName(); }
            finally {
                Process child = process; if (child != null) child.destroy(); process = null;
                synchronized (NavigationLogReader.this) { worker = null; }
            }
        }, "diplay-navigation-log");
        worker.setDaemon(true); worker.start();
    }

    String status() { return status; }
    void clearState() {
        state.clear(); context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().remove("navigation_cache").remove("navigation_cache_uid").apply();
    }
    synchronized void stop() {
        stopped = true; Process child = process; if (child != null) child.destroy(); state.clear(); handler.removeCallbacks(changed);
    }
}
