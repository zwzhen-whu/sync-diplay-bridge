package com.local.diplay.clusterbridge.runtime;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;

/** Serializes map/audio instrumentation startup across independent root helper processes. */
public final class ProcessAttachLease implements AutoCloseable {
    private final RandomAccessFile file;
    private final FileLock lock;
    private final boolean originallyEnforcing;
    private ProcessAttachLease(RandomAccessFile file, FileLock lock, boolean enforcing) {
        this.file = file; this.lock = lock; originallyEnforcing = enforcing;
    }
    private static boolean enforcing() throws Exception {
        try (FileInputStream input = new FileInputStream("/sys/fs/selinux/enforce")) {
            int value = input.read();
            if (value != '0' && value != '1') throw new IllegalStateException("Unknown protection mode");
            return value == '1';
        }
    }
    public static ProcessAttachLease acquire() throws Exception {
        File path = new File("/data/local/tmp/bridge-runtime-attach.lock");
        if (path.exists()) {
            StructStat stat = Os.lstat(path.getPath());
            if (stat.st_uid != 0 || !OsConstants.S_ISREG(stat.st_mode)) throw new IllegalStateException("Unsafe attach lease");
        }
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        try {
            Os.chmod(path.getPath(), 0600);
            FileLock lock = file.getChannel().tryLock();
            if (lock == null) { file.close(); return null; }
            return new ProcessAttachLease(file, lock, enforcing());
        } catch (Exception error) { file.close(); throw error; }
    }
    public void close() throws Exception {
        try {
            // Preserve the initial system protection if an injector temporarily changes it.
            if (originallyEnforcing && !enforcing()) {
                Process restore = new ProcessBuilder("setenforce", "1").start();
                if (!restore.waitFor(3, TimeUnit.SECONDS)) { restore.destroy(); throw new IllegalStateException("Protection restore timeout"); }
                if (restore.exitValue() != 0 || !enforcing()) throw new IllegalStateException("Protection restore unavailable");
            }
        } finally { try { lock.release(); } finally { file.close(); } }
    }
}
