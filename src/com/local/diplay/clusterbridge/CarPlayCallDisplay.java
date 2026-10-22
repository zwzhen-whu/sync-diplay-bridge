package com.local.diplay.clusterbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Optional SYNC+ call display. Contact values live only in memory and the local helper pipe. */
final class CarPlayCallDisplay {
    static final String ENABLED = "carplay_call_display";
    private static final String RECOVERY = "carplay_call_display_recovery";
    private final SharedPreferences preferences;
    private final String apk;
    private final CarPlayCallState state = new CarPlayCallState();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledFuture<?> task;
    private volatile boolean enabled, connected, stopped;
    private volatile String status = "关闭";
    private LocalAdbShell shell;
    private long retryAfter;
    private boolean shown;

    CarPlayCallDisplay(Context context, SharedPreferences preferences) {
        this.preferences = preferences; apk = context.getApplicationInfo().sourceDir;
        state.reset(System.currentTimeMillis());
        task = worker.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }
    synchronized void configure(boolean allowed, boolean carplayConnected) {
        boolean next = !stopped && allowed && preferences.getBoolean(ENABLED, false);
        if (next != enabled || connected && !carplayConnected) state.reset(System.currentTimeMillis());
        enabled = next; connected = carplayConnected;
    }
    void acceptLog(String line, int uid) {
        if (enabled && connected && !stopped) state.accept(line, uid, System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }
    void sourceInterrupted() { state.reset(System.currentTimeMillis()); }
    String status() { return status; }
    private void tick() {
        try {
            if (!enabled || !connected || stopped) {
                finish(); status = !enabled ? "关闭" : "等待 CarPlay 连接"; return;
            }
            if (SystemClock.elapsedRealtime() < retryAfter) return;
            ensureShell();
            CarPlayCallState.Call call = state.current();
            if (call == null) {
                if (shown || preferences.getBoolean(RECOVERY, false)) finishCall();
                requireOk(shell.exchange("P")); status = "就绪，等待新通话（已解析 " + state.framesParsed() + " 帧）"; return;
            }
            // A persistent boolean covers a helper crash; no caller values enter preferences.
            if (!preferences.getBoolean(RECOVERY, false) && !preferences.edit().putBoolean(RECOVERY, true).commit())
                throw new IllegalStateException("Recovery marker unavailable");
            if (!enabled || !connected || stopped || state.current() != call) return;
            String response = shell.exchange("C\t" + call.status + "\t" + call.seconds(SystemClock.elapsedRealtime())
                + "\t" + encode(call.name) + "\t" + encode(call.number) + "\t" + call.pid);
            if (response.equals("HANDOFF")) {
                shown = false; preferences.edit().remove(RECOVERY).commit(); status = "蓝牙通话已连接，由原车显示";
            } else {
                requireOk(response); shown = true;
                status = call.phase() + "，已转发" + (call.activeSince < 0 ? "" : " " + call.seconds(SystemClock.elapsedRealtime()) + " 秒");
            }
        } catch (Exception e) {
            // Closing the pipe triggers the independent helper's owned cleanup.
            if (shell != null) shell.close(); shell = null; shown = false;
            retryAfter = SystemClock.elapsedRealtime() + 10000;
            status = "通话显示接口暂不可用，将重试（" + e.getClass().getSimpleName() + "）";
        }
    }
    private void ensureShell() throws Exception {
        if (shell != null) return;
        shell = new LocalAdbShell(apk);
        if (preferences.getBoolean(RECOVERY, false)) {
            requireOk(shell.exchange("R")); preferences.edit().remove(RECOVERY).commit();
        }
    }
    private void finishCall() throws Exception {
        requireOk(shell.exchange("E")); shown = false; preferences.edit().remove(RECOVERY).commit();
    }
    private void finish() throws Exception {
        boolean pending = preferences.getBoolean(RECOVERY, false);
        if (shell == null && pending) {
            if (SystemClock.elapsedRealtime() < retryAfter) return;
            ensureShell();
        }
        if (shell != null) {
            if (shown || pending) finishCall();
            requireOk(shell.exchange("Q")); shell.close(); shell = null;
        }
    }
    private static void requireOk(String response) { if (!"OK".equals(response)) throw new IllegalStateException("Display acknowledgement unavailable"); }
    private static String encode(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    synchronized void close() {
        stopped = true; enabled = false; state.reset(System.currentTimeMillis()); task.cancel(false);
        worker.execute(this::tick); worker.shutdown();
    }
}
