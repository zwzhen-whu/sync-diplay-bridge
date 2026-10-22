package com.local.diplay.clusterbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Build;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Read only DiPlay's transport tag; UID filtering happens before any data is accepted. */
final class NavigationLogReader {
    final NavigationLogState state = new NavigationLogState();
    private final Context context;
    private final Handler handler;
    private final Runnable changed;
    private final StartupAudioRecovery audioRecovery;
    private final CarPlayCallDisplay callDisplay;
    private volatile Process process;
    private volatile Thread worker;
    private volatile boolean stopped;
    private volatile String status = "等待日志授权";
    private final int bootCount;

    NavigationLogReader(Context context, Handler handler, Runnable changed, StartupAudioRecovery audioRecovery, CarPlayCallDisplay callDisplay) {
        this.context = context; this.handler = handler; this.changed = changed; this.audioRecovery = audioRecovery; this.callDisplay = callDisplay;
        int boot;
        try { boot = Settings.Global.getInt(context.getContentResolver(), "boot_count", -1); }
        catch (RuntimeException e) { boot = -1; }
        bootCount = boot;
    }
    synchronized void ensureStarted() {
        if (stopped || worker != null) return;
        if (context.checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            status = "未授权（小组件路名仍可用）"; return;
        }
        String accessHint = "日志已授权，等待 DiPlay 导航记录（尚未收到）";
        if (Build.VERSION.SDK_INT <= 28) {
            try {
                Boolean group = LogAccess.hasLogGroup(new String(Files.readAllBytes(Paths.get("/proc/self/status")), StandardCharsets.UTF_8));
                if (Boolean.FALSE.equals(group)) {
                    accessHint = "日志权限已授予，进程未见日志组；建议强行停止后重新打开，当前仍尝试读取";
                }
            } catch (Exception ignored) { /* Some firmwares restrict proc; use the normal reader. */ }
        }
        final String waitingStatus = accessHint;
        worker = new Thread(() -> {
            try {
                int uid = context.getPackageManager().getApplicationInfo(BridgeService.SOURCE, 0).uid;
                SharedPreferences preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
                synchronized (NavigationLogReader.this) {
                    if (stopped) return;
                    if (LogAccess.sameCacheEpoch(preferences.getInt("navigation_cache_uid", -1), uid,
                        preferences.getInt("navigation_cache_boot", -1), bootCount))
                        state.restoreManeuvers(preferences.getString("navigation_cache", ""), System.currentTimeMillis());
                }
                Process child = new ProcessBuilder("logcat", "-b", "main", "-v", "threadtime", "-v", "uid", "-v", "epoch", "-s",
                    "xcertplay-usb:I", "DiPlay-MediaKeys:I", "MediaFocusControl:I").redirectErrorStream(true).start();
                process = child;
                if (stopped) { child.destroy(); return; }
                status = waitingStatus;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                    String line;
                    while (!stopped && (line = reader.readLine()) != null) {
                        if (audioRecovery != null) audioRecovery.acceptLog(line, uid);
                        if (callDisplay != null) callDisplay.acceptLog(line, uid);
                        synchronized (NavigationLogReader.this) {
                            if (stopped) break;
                            boolean updated = state.accept(line, uid, System.currentTimeMillis());
                            if (state.framesSeen() > 0) status = "收到导航帧 " + state.framesSeen() + "，解析成功 " + state.framesParsed();
                            if (updated) {
                                String cache = state.savedManeuvers();
                                if (!cache.equals(preferences.getString("navigation_cache", ""))) preferences.edit()
                                    .putInt("navigation_cache_uid", uid).putInt("navigation_cache_boot", bootCount).putString("navigation_cache", cache).apply();
                                handler.removeCallbacks(changed); handler.post(changed);
                            }
                        }
                    }
                }
                if (!stopped) status = "日志读取中断，将重试";
            } catch (Exception e) { status = "读取失败：" + e.getClass().getSimpleName(); }
            finally {
                if (callDisplay != null) callDisplay.sourceInterrupted();
                Process child = process; if (child != null) child.destroy(); process = null;
                synchronized (NavigationLogReader.this) { worker = null; }
            }
        }, "diplay-navigation-log");
        worker.setDaemon(true); worker.start();
    }

    String status() { return status; }
    synchronized void clearState() {
        state.clear(); context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
            .remove("navigation_cache").remove("navigation_cache_uid").remove("navigation_cache_boot").apply();
    }
    synchronized void stop() {
        if (callDisplay != null) callDisplay.sourceInterrupted();
        stopped = true; Process child = process; if (child != null) child.destroy(); state.clear(); handler.removeCallbacks(changed);
    }
}
