package com.local.diplay.clusterbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opt-in, read-only wake/audio evidence that also works without Wi-Fi/ADB. */
final class WakeDiagnostics {
    private static final long MAX_RUN_MS = 45 * 60 * 1000L;
    private static final Pattern UID = Pattern.compile("^\\s*\\d+\\.\\d+\\s+(\\d+):?\\s+.*");
    private final Context context;
    private final long started = SystemClock.elapsedRealtime();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    private volatile java.lang.Process process;
    private volatile String status = "正在启动本地诊断", bridge = "unknown";
    private BoundedDiagnosticLog log;
    private String lastSample;
    private long lastWritten = -1;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) {
            try { sampler.execute(() -> sample("event=" + intent.getAction())); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
    };

    WakeDiagnostics(Context context) {
        this.context = context.getApplicationContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
        this.context.registerReceiver(screen, filter);
        sampler.execute(() -> {
            try {
                File folder = this.context.getExternalFilesDir("diagnostics");
                if (folder == null) folder = new File(this.context.getFilesDir(), "diagnostics");
                log = new BoundedDiagnosticLog(folder, 256 * 1024);
                write("start boot=" + Settings.Global.getInt(this.context.getContentResolver(), "boot_count", -1));
                status = "记录中（仅本地，最多45分钟／512KB，不录音）";
                Thread reader = new Thread(this::readFocus, "bridge-wake-log"); reader.setDaemon(true); reader.start();
            } catch (Exception e) { status = "诊断启动失败：" + e.getClass().getSimpleName(); close(); }
        });
        try { sampler.scheduleWithFixedDelay(() -> sample("poll"), 0, 2, TimeUnit.SECONDS); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { /* Initialization already failed and closed. */ }
    }

    void snapshot(boolean connected, boolean playing, boolean navigation) {
        bridge = "connected=" + connected + " playing=" + playing + " nav=" + navigation;
    }
    String status() { return status; }

    private void sample(String reason) {
        if (closed.get()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - started >= MAX_RUN_MS) { write("stop: 45 minute limit"); status = "本轮诊断已到45分钟，停止记录"; close(); return; }
        try {
            AudioManager audio = context.getSystemService(AudioManager.class);
            PowerManager power = context.getSystemService(PowerManager.class);
            String next = "interactive=" + (power != null && power.isInteractive())
                + " mode=" + (audio == null ? -1 : audio.getMode())
                + " musicActive=" + (audio != null && audio.isMusicActive()) + " " + bridge;
            if (!"poll".equals(reason) || !next.equals(lastSample) || now - lastWritten >= 10000) {
                write(reason + " " + next); lastSample = next; lastWritten = now;
            }
        } catch (RuntimeException e) { write("sample failed: " + e.getClass().getSimpleName()); }
    }

    private void readFocus() {
        try {
            if (context.checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
                write("READ_LOGS absent; state samples only"); return;
            }
            int sourceUid = context.getPackageManager().getApplicationInfo(BridgeService.SOURCE, 0).uid;
            java.lang.Process child = new ProcessBuilder("logcat", "-b", "main", "-v", "threadtime", "-v", "uid", "-v", "epoch",
                "-s", "DiPlay-MediaKeys:I", "DiPlay-AudioFocus:I", "AudioService:I", "MediaFocusControl:I",
                "CarAudioService:I", "CAR.AUDIO:I", "AudioPolicyManager:I", "AudioFlinger:W").redirectErrorStream(true).start();
            process = child;
            if (closed.get()) { child.destroy(); return; }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    if (line.length() > 4096) continue;
                    Matcher m = UID.matcher(line);
                    if (!m.matches()) continue;
                    int uid = Integer.parseInt(m.group(1));
                    if (uid == sourceUid || uid == 1000 || uid == 1041) write("focus " + line);
                }
            }
        } catch (Exception e) { if (!closed.get()) write("focus reader failed: " + e.getClass().getSimpleName()); }
        finally { java.lang.Process child = process; if (child != null) child.destroy(); process = null; }
    }

    private void write(String message) {
        BoundedDiagnosticLog output = log;
        if (output == null) return;
        try { output.append("wall=" + System.currentTimeMillis() + " elapsed=" + SystemClock.elapsedRealtime() + " " + message); }
        catch (Exception e) { status = "本地诊断写入失败：" + e.getClass().getSimpleName(); }
    }
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        sampler.shutdownNow();
        java.lang.Process child = process; if (child != null) child.destroy();
        try { context.unregisterReceiver(screen); } catch (RuntimeException ignored) { }
    }
}
