package com.local.diplay.clusterbridge;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** At most one bounded background diagnostic request; no raw dumps are persisted. */
final class AudioSnapshotReader {
    static final class Snapshot {
        final SystemAudioState audio; final boolean receiver; final long at; final String error;
        Snapshot(SystemAudioState audio, boolean receiver, String error) {
            this.audio = audio; this.receiver = receiver; this.error = error; at = SystemClock.elapsedRealtime();
        }
    }
    private final int uid;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "bridge-audio-state"); t.setDaemon(true); return t; });
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;
    private volatile Process process;
    private volatile Snapshot latest;
    private long lastAttempt = -1;
    AudioSnapshotReader(int uid) { this.uid = uid; }
    Snapshot latest() { return latest; }
    void request(long now) {
        if (closed || lastAttempt >= 0 && now - lastAttempt < 2000 || !busy.compareAndSet(false, true)) return;
        lastAttempt = now;
        worker.execute(() -> {
            try {
                SystemAudioState audio = SystemAudioState.parse(read("audio"), uid);
                boolean receiver = audio.focus == SystemAudioState.Focus.EMPTY && SystemAudioState.mediaReceiverIsSource(read("media_session"));
                if (!closed) latest = new Snapshot(audio, receiver, "");
            } catch (Exception e) {
                if (!closed) latest = new Snapshot(SystemAudioState.unknown(), false, e.getClass().getSimpleName());
            } finally { busy.set(false); }
        });
    }
    private String read(String service) throws Exception {
        Process child = new ProcessBuilder("dumpsys", "-t", "2", service).redirectErrorStream(true).start();
        process = child;
        long deadline = SystemClock.elapsedRealtime() + 2500;
        try (InputStream input = child.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            while (!closed) {
                int available = input.available();
                if (available > 0) {
                    int n = input.read(buffer, 0, Math.min(buffer.length, available));
                    if (n > 0) bytes.write(buffer, 0, n);
                    if (bytes.size() > 128 * 1024) throw new IOException("Diagnostic output too large");
                } else if (!child.isAlive()) break;
                else Thread.sleep(20);
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Diagnostic timeout");
            }
            if (closed || child.isAlive() || child.exitValue() != 0) throw new IOException("Diagnostic unavailable");
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally { child.destroy(); if (process == child) process = null; }
    }
    void close() { closed = true; Process child = process; if (child != null) child.destroy(); worker.shutdownNow(); }
}
