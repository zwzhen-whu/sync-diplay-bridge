package com.local.diplay.clusterbridge.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Opt-in wired microphone packet controller. All runtime work stays off the bridge/UI/audio threads. */
public final class MicPacketController {
    public static final String ENABLED = "wired_mic_packet_fix";
    private final SharedPreferences preferences;
    private final String apk;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean compatible, closed;
    private volatile String status = "关闭";
    private MicPacketShell shell;
    private long retryAt;

    public MicPacketController(Context context, SharedPreferences preferences) {
        this.preferences = preferences; apk = context.getApplicationInfo().sourceDir;
        worker.scheduleWithFixedDelay(this::tick, 0, 2, TimeUnit.SECONDS);
    }
    public void configure(boolean compatible) { this.compatible = compatible; }
    public String status() { return status; }
    private void finish() {
        MicPacketShell current = shell; shell = null;
        if (current != null) {
            try { current.exchange("Q"); } catch (Exception ignored) { /* Native lease expires independently. */ }
            current.close();
        }
    }
    private void tick() {
        try {
            if (closed || !preferences.getBoolean(ENABLED, false)) { finish(); status = "关闭"; return; }
            if (!compatible) { finish(); status = "等待本车兼容检查通过"; return; }
            if (android.os.Build.VERSION.SDK_INT != 27) { status = "此语音兼容仅适配 Android 8.1"; return; }
            if (SystemClock.elapsedRealtime() < retryAt) return;
            if (shell == null) shell = new MicPacketShell(apk);
            String response = shell.exchange("P");
            if (response.equals("RESULT APPLIED")) status = "开启，已兼容有线语音输入";
            else if (response.equals("RESULT READY")) status = "开启，等待有线语音输入";
            else if (response.equals("RESULT WAITING")) status = "等待 DiPlay 启动";
            else if (response.equals("RESULT RESTART_NEEDED")) status = "请重开一次 DiPlay 后生效";
            else throw new IllegalStateException("Audio runtime unavailable");
        } catch (Exception error) {
            finish(); retryAt = SystemClock.elapsedRealtime() + 5000;
            status = "语音兼容暂不可用，保留原处理；需 DiPlay 0.2.21 和本机调试接口";
        }
    }
    public void close() {
        closed = true;
        worker.execute(this::finish); worker.shutdown();
    }
}
