package com.local.diplay.clusterbridge.audio;

import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/** Fixed audio supplement. No microphone access, free-form commands or native APK changes. */
public final class CallAudioRuntimeTool {
    private static final String SOURCE = "com.shihab.diplay", HELPER = "com.local.diplay.clusterbridge";
    private static final String SOURCE_CERT = "87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363";
    private static final String HELPER_CERT = "26e386a76a9272a40f84c3d64e608bb27ccd99956ee030d73fe086afbd106039";
    private final File directory = new File("/data/local/tmp/bridge-callaudio-runtime");
    private final Object packages;
    private final Class<?> packageApi;
    private final File apk;
    private final int helperUid, helperCode, sourceUid;
    private final RandomAccessFile lockFile;
    private final FileLock lock;
    private volatile long heartbeat = SystemClock.elapsedRealtime();
    private volatile boolean stopped;

    private CallAudioRuntimeTool() throws Exception {
        if (android.os.Process.myUid() != 0 || android.os.Build.VERSION.SDK_INT != 27)
            throw new IllegalStateException("Unsupported audio environment");
        IBinder binder = (IBinder)Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "package");
        packages = Class.forName("android.content.pm.IPackageManager$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, binder);
        packageApi = Class.forName("android.content.pm.IPackageManager");
        PackageInfo helper = info(HELPER), source = info(SOURCE);
        certificate(helper, HELPER_CERT); certificate(source, SOURCE_CERT);
        if (!"0.2.21".equals(source.versionName) || source.versionCode != 40
                || source.applicationInfo == null || helper.applicationInfo == null)
            throw new IllegalStateException("Unaudited audio version");
        helperUid = helper.applicationInfo.uid; helperCode = helper.versionCode;
        sourceUid = source.applicationInfo.uid; apk = new File(helper.applicationInfo.sourceDir);
        if (!directory.exists() && !directory.mkdir()) throw new IllegalStateException("Runtime unavailable");
        StructStat stat = Os.lstat(directory.getPath());
        if (stat.st_uid != 0 || !OsConstants.S_ISDIR(stat.st_mode)) throw new IllegalStateException("Unsafe directory");
        Os.chmod(directory.getPath(), 0700);
        File lockPath = new File(directory, "owner.lock");
        if (lockPath.exists()) regular(lockPath, 0);
        lockFile = new RandomAccessFile(lockPath, "rw");
        lock = lockFile.getChannel().tryLock();
        if (lock == null) { lockFile.close(); throw new IllegalStateException("Audio controller already active"); }
        Os.chmod(lockPath.getPath(), 0600);
        extract("frida-inject", CallAudioBuild.INJECT_SHA256, 0700);
        extract("call-volume.js", CallAudioBuild.AGENT_SHA256, 0600);
    }
    private PackageInfo info(String name) throws Exception {
        return (PackageInfo)packageApi.getMethod("getPackageInfo", String.class, int.class, int.class)
            .invoke(packages, name, 64, 0);
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private static void certificate(PackageInfo info, String expected) throws Exception {
        if (info == null || info.signatures == null || info.signatures.length != 1
                || !hex(MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray())).equals(expected))
            throw new IllegalStateException("Certificate mismatch");
    }
    private static void regular(File file, int uid) throws Exception {
        StructStat stat = Os.lstat(file.getPath());
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != uid)
            throw new IllegalStateException("Unsafe runtime file " + file.getName() + " owner=" + stat.st_uid + " expected=" + uid);
    }
    private static byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
        while ((count = input.read(bytes)) != -1) {
            if (output.size() + count > limit) throw new IllegalStateException("Output limit");
            output.write(bytes, 0, count);
        }
        return output.toByteArray();
    }
    private static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return hex(digest.digest());
    }
    private void extract(String name, String hash, int mode) throws Exception {
        File target = new File(directory, name);
        if (target.exists()) {
            regular(target, 0);
            if (sha(target).equals(hash)) { Os.chmod(target.getPath(), mode); return; }
        }
        File temporary = new File(directory, name + ".new");
        if (temporary.exists()) { regular(temporary, 0); if (!temporary.delete()) throw new IllegalStateException("Stale file"); }
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("assets/runtime/" + name);
            if (entry == null) throw new IllegalStateException("Asset unavailable");
            try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                output.getFD().sync();
            }
        }
        if (!sha(temporary).equals(hash)) { temporary.delete(); throw new IllegalStateException("Asset checksum mismatch"); }
        Os.chmod(temporary.getPath(), mode); Os.rename(temporary.getPath(), target.getPath());
    }
    private int pid() throws Exception {
        Process child = new ProcessBuilder("pidof", SOURCE).start();
        if (!child.waitFor(2, TimeUnit.SECONDS)) { child.destroy(); return -1; }
        String value = new String(read(child.getInputStream(), 128), StandardCharsets.US_ASCII).trim();
        if (!value.matches("[0-9]{1,7}")) return -1;
        int pid = Integer.parseInt(value);
        if (Os.stat("/proc/" + pid).st_uid != sourceUid) return -1;
        try (InputStream input = new FileInputStream("/proc/" + pid + "/cmdline")) {
            String command = new String(read(input, 256), StandardCharsets.US_ASCII).split("\u0000", 2)[0];
            if (!command.equals(SOURCE)) return -1;
        }
        return pid;
    }
    private String start(int pid) throws Exception {
        try (InputStream input = new FileInputStream("/proc/" + pid + "/stat")) {
            String stat = new String(read(input, 4096), StandardCharsets.US_ASCII);
            return stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+")[19];
        }
    }
    private File nativeFile(String name) { return new File("/data/user/0/" + SOURCE + "/files/" + name); }
    private void configuration(boolean enabled, int pid) throws Exception {
        File path = nativeFile("bridge-call-audio-config.json"), temporary = nativeFile("bridge-call-audio-config.tmp");
        if (path.exists()) regular(path, sourceUid);
        JSONObject value = new JSONObject().put("enabled", enabled).put("pid", pid)
            .put("process_start_ticks", pid > 0 ? start(pid) : "")
            .put("expires_elapsed", enabled ? SystemClock.elapsedRealtime() + CallAudioPolicy.LEASE_MS : 0);
        com.local.diplay.clusterbridge.runtime.RuntimeConfigWriter.write(path, temporary, sourceUid,
            value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject state(int pid) {
        File path = nativeFile("bridge-call-audio-state.json");
        try {
            regular(path, sourceUid);
            try (InputStream input = new FileInputStream(path)) {
                JSONObject value = new JSONObject(new String(read(input, 4096), StandardCharsets.UTF_8));
                if (value.optInt("pid", -1) != pid || !start(pid).equals(value.optString("process_start_ticks"))) return null;
                return value;
            }
        } catch (Exception ignored) { return null; }
    }
    private String describe(JSONObject state) throws Exception {
        if (state.optInt("agent", -1) != 1 || !CallAudioPolicy.owns(state.optString("revision"), state.optString("helper_package"),
                state.optInt("helper_uid", -1), helperUid, state.optInt("helper_code", -1), helperCode,
                state.optString("process_start_ticks"), start(state.getInt("pid")))) return "RESULT RESTART_NEEDED";
        if (!state.optString("error").isEmpty()) return "ERR AUDIO_RUNTIME";
        long age = SystemClock.elapsedRealtime() - state.optLong("at_elapsed", -1);
        if (age < 0 || age > 6000) return "ERR STALE_RUNTIME";
        return state.optBoolean("call_active") ? "RESULT CALL " + state.optInt("volume")
            + "/" + state.optInt("maximum") : "RESULT READY";
    }
    private void markAttempt(int pid) throws Exception {
        File path = new File(directory, "attach-attempt.json");
        String epoch = start(pid);
        if (path.exists()) {
            regular(path, 0);
            try (InputStream input = new FileInputStream(path)) {
                JSONObject old = new JSONObject(new String(read(input, 4096), StandardCharsets.UTF_8));
                if (old.optInt("pid", -1) == pid && epoch.equals(old.optString("process_start_ticks")))
                    throw new IllegalStateException("Restart required after incomplete attach");
            }
        }
        try (FileOutputStream output = new FileOutputStream(path)) {
            output.write(new JSONObject().put("pid", pid).put("process_start_ticks", epoch)
                .toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
        }
        Os.chmod(path.getPath(), 0600);
    }
    private String poll() throws Exception {
        try (com.local.diplay.clusterbridge.runtime.ProcessAttachLease lease =
                com.local.diplay.clusterbridge.runtime.ProcessAttachLease.acquire()) {
            if (lease == null) return "RESULT WAITING";
            return pollWithLease();
        }
    }
    private String pollWithLease() throws Exception {
        int pid = pid();
        if (pid < 1) return "RESULT WAITING";
        JSONObject existing = state(pid);
        if (existing != null && !CallAudioPolicy.owns(existing.optString("revision"), existing.optString("helper_package"),
                existing.optInt("helper_uid", -1), helperUid, existing.optInt("helper_code", -1), helperCode,
                existing.optString("process_start_ticks"), start(pid))) return "RESULT RESTART_NEEDED";
        configuration(true, pid);
        if (existing != null) return describe(existing);
        // An incomplete attachment may already have installed a method hook. Never attach
        // again in the same process merely because its report failed to appear.
        markAttempt(pid);
        Process child = new ProcessBuilder(new File(directory, "frida-inject").getPath(), "-p", Integer.toString(pid),
            "-s", new File(directory, "call-volume.js").getPath(), "-R", "qjs", "-e").redirectErrorStream(true).start();
        Thread drain = new Thread(() -> { try { read(child.getInputStream(), 65536); } catch (Exception ignored) { } }, "call-audio-output");
        drain.setDaemon(true); drain.start();
        if (!child.waitFor(20, TimeUnit.SECONDS)) { child.destroy(); return "ERR ATTACH_TIMEOUT"; }
        if (child.exitValue() != 0) return "ERR ATTACH_FAILED";
        long until = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < until) {
            JSONObject current = state(pid);
            if (current != null) return describe(current);
            SystemClock.sleep(100);
        }
        return "ERR AGENT_START";
    }
    private void disable() {
        try { configuration(false, pid()); } catch (Exception ignored) { /* Lease independently expires. */ }
    }
    public static void main(String[] args) {
        CallAudioRuntimeTool tool = null;
        try {
            if (args.length != 0) throw new IllegalArgumentException("Arguments unsupported");
            tool = new CallAudioRuntimeTool(); final CallAudioRuntimeTool owner = tool;
            Thread lease = new Thread(() -> {
                while (!owner.stopped) {
                    SystemClock.sleep(1000);
                    if (SystemClock.elapsedRealtime() - owner.heartbeat > 30000) { owner.disable(); System.exit(0); }
                }
            }, "call-audio-lease"); lease.setDaemon(true); lease.start();
            System.out.println("READY"); System.out.flush();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
                int c;
                while ((c = input.read()) != -1) {
                    if (c == '\r' || c == '\n') continue;
                    owner.heartbeat = SystemClock.elapsedRealtime();
                    String response;
                    if (c == 'P') response = owner.poll();
                    else if (c == 'D' || c == 'Q') { owner.disable(); response = "OK"; }
                    else response = "ERR COMMAND";
                    owner.heartbeat = SystemClock.elapsedRealtime();
                    System.out.println(response); System.out.flush();
                    if (c == 'Q') break;
                }
            }
        } catch (Exception error) {
            android.util.Log.w("BridgeCallAudio", "Runtime unavailable: " + error.getClass().getSimpleName());
            System.out.println("ERR UNAVAILABLE"); System.out.flush();
        } finally {
            if (tool != null) {
                tool.stopped = true; tool.disable();
                try { tool.lock.release(); tool.lockFile.close(); } catch (Exception ignored) { }
            }
        }
    }
}
