package com.local.diplay.clusterbridge.runtime;

import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Root-only, fixed probe filenames; never changes the real audio configuration. */
public final class RuntimeConfigDeviceTest {
    private static final int UID = 10089;
    private static final File TARGET = file(".json"), TEMP = file(".tmp"), SENTINEL = file(".sentinel");
    private static int checks;
    private static File file(String suffix) {
        return new File("/data/user/0/com.shihab.diplay/files/bridge-config-writer-probe" + suffix);
    }
    private static void check(boolean result, String reason) {
        checks++; if (!result) throw new AssertionError(reason);
    }
    private static void bytes(File path, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(path)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
    private static String text(File path) throws Exception {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] data = new byte[100]; int count = in.read(data);
            return count < 0 ? "" : new String(data, 0, count, StandardCharsets.UTF_8);
        }
    }
    private static void write(String text) throws Exception {
        RuntimeConfigWriter.write(TARGET, TEMP, UID, text.getBytes(StandardCharsets.UTF_8));
    }
    private static void rejected(String reason) throws Exception {
        boolean rejected = false;
        try { write("must not write"); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, reason);
        check("second".equals(text(TARGET)), "rejected temp leaves active config intact");
    }
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) throw new IllegalStateException("Root test only");
        // Refuse existing probe files to avoid overwriting anything outside this test.
        for (File path : new File[]{TARGET, TEMP, SENTINEL})
            if (path.exists()) throw new IllegalStateException("Probe path already exists");
        try {
            bytes(TEMP, "interrupted root write"); Os.chmod(TEMP.getPath(), 0666);
            write("first");
            StructStat result = Os.lstat(TARGET.getPath());
            check(result.st_uid == UID && (result.st_mode & 0777) == 0600, "root-owned stale temp recovers to private app config");
            check("first".equals(text(TARGET)) && !TEMP.exists(), "recovery atomically replaces content");
            bytes(TEMP, "interrupted app write"); Os.chown(TEMP.getPath(), UID, UID);
            write("second");
            check("second".equals(text(TARGET)), "app-owned stale temp recovers");
            bytes(SENTINEL, "sentinel"); Os.symlink(SENTINEL.getPath(), TEMP.getPath());
            rejected("symlink rejected");
            check("sentinel".equals(text(SENTINEL)), "symlink destination unchanged"); Os.remove(TEMP.getPath());
            bytes(TEMP, "foreign"); Os.chown(TEMP.getPath(), 2000, 2000);
            rejected("foreign-owned temp rejected"); Os.remove(TEMP.getPath());
            check(TEMP.mkdir(), "create directory probe"); rejected("directory rejected");
            System.out.println("RuntimeConfigDeviceTest: " + checks + " checks passed");
            System.out.flush();
        } finally {
            TEMP.delete(); TARGET.delete(); SENTINEL.delete();
        }
    }
}
