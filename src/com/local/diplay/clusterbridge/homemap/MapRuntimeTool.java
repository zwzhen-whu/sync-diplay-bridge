package com.local.diplay.clusterbridge.homemap;

import android.content.pm.ApplicationInfo;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.HashMap;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;
import org.json.JSONObject;

/** Fixed, version-checked runtime installer reached only over the car's existing loopback ADB. */
public final class MapRuntimeTool {
    private static final String SOURCE = "com.shihab.diplay", HELPER = "com.local.diplay.clusterbridge";
    private static final String LEGACY = "com.local.diplay.homemap";
    private static final String SOURCE_CERT = "87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363";
    private static final String HELPER_CERT = "26e386a76a9272a40f84c3d64e608bb27ccd99956ee030d73fe086afbd106039";
    private final File directory = new File("/data/local/tmp/bridge-homemap-runtime");
    private final Object packages;
    private final Class<?> packageApi;
    private final File apk;
    private volatile long heartbeat = SystemClock.elapsedRealtime();
    private volatile boolean stopped;
    private int sourceUid, helperUid, lastPid = -1;

    private MapRuntimeTool() throws Exception {
        if (android.os.Process.myUid() != 0 || android.os.Build.VERSION.SDK_INT != 27)
            throw new IllegalStateException("Unsupported environment");
        IBinder binder = (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "package");
        packages = Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        packageApi = Class.forName("android.content.pm.IPackageManager");
        PackageInfo helper = packageInfo(HELPER);
        requireCertificate(helper, HELPER_CERT);
        ApplicationInfo app = helper.applicationInfo;
        if (app == null || app.sourceDir == null) throw new IllegalStateException("Missing helper APK");
        apk = new File(app.sourceDir);
        helperUid = app.uid;
        validateSource();
        if (!directory.exists() && !directory.mkdir()) throw new IllegalStateException("Runtime directory unavailable");
        StructStat stat = Os.lstat(directory.getPath());
        if (stat.st_uid != 0 || !OsConstants.S_ISDIR(stat.st_mode)) throw new IllegalStateException("Unsafe runtime directory");
        Os.chmod(directory.getPath(), 0700);
        extract("frida-inject", RuntimeBuild.INJECT_SHA256, true);
        extract("display-scheduler.js", RuntimeBuild.AGENT_SHA256, false);
    }
    private PackageInfo packageInfo(String name) throws Exception {
        return (PackageInfo)packageApi.getMethod("getPackageInfo", String.class, int.class, int.class).invoke(packages, name, 64, 0);
    }
    private static void requireCertificate(PackageInfo info, String expected) throws Exception {
        if (info == null || info.signatures == null || info.signatures.length != 1 ||
            !hex(MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray())).equals(expected))
            throw new IllegalStateException("Certificate mismatch");
    }
    private void validateSource() throws Exception {
        PackageInfo source = packageInfo(SOURCE);
        requireCertificate(source, SOURCE_CERT);
        if (!"0.2.21".equals(source.versionName) || source.versionCode != 40 || source.applicationInfo == null)
            throw new IllegalStateException("Unaudited source version");
        sourceUid = source.applicationInfo.uid;
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(); for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    private void extract(String name, String expected, boolean executable) throws Exception {
        File target = new File(directory, name);
        if (target.exists()) {
            StructStat stat = Os.lstat(target.getPath());
            if (stat.st_uid != 0 || !OsConstants.S_ISREG(stat.st_mode)) throw new IllegalStateException("Unsafe runtime file");
            if (sha(target).equals(expected)) { Os.chmod(target.getPath(), executable ? 0700 : 0600); return; }
        }
        File temporary = new File(directory, name + ".new");
        if (temporary.exists() && !temporary.delete()) throw new IllegalStateException("Stale runtime file");
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("assets/runtime/" + name);
            if (entry == null) throw new IllegalStateException("Missing runtime asset");
            try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
        }
        if (!sha(temporary).equals(expected)) { temporary.delete(); throw new IllegalStateException("Runtime checksum mismatch"); }
        Os.chmod(temporary.getPath(), executable ? 0700 : 0600);
        Os.rename(temporary.getPath(), target.getPath());
    }
    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
        while ((count = input.read(bytes)) != -1) {
            if (output.size() + count > limit) throw new IllegalStateException("Output limit");
            output.write(bytes, 0, count);
        }
        return output.toByteArray();
    }
    private int pid() throws Exception {
        Process child = new ProcessBuilder("pidof", SOURCE).start();
        if (!child.waitFor(2, TimeUnit.SECONDS)) { child.destroy(); return -1; }
        String value = new String(readLimited(child.getInputStream(), 128), StandardCharsets.US_ASCII).trim();
        if (!value.matches("[0-9]{1,7}")) return -1;
        int result = Integer.parseInt(value);
        File proc = new File("/proc/" + result);
        if (Os.stat(proc.getPath()).st_uid != sourceUid) return -1;
        try (InputStream input = new FileInputStream(new File(proc, "cmdline"))) {
            String cmd = new String(readLimited(input, 256), StandardCharsets.US_ASCII);
            if (!RuntimeIdentity.matchesCmdline(cmd, SOURCE)) return -1;
        }
        return result;
    }
    private JSONObject state(int pid) {
        try (InputStream input = new FileInputStream("/data/user/0/" + SOURCE + "/files/homemap-runtime-state.json")) {
            JSONObject result = new JSONObject(new String(readLimited(input, 4096), StandardCharsets.UTF_8));
            if (result.optInt("pid", -1) != pid) return null;
            // Never confuse a persisted report with a reused Linux PID after restart.
            if (result.optInt("agent") == 2) {
                String stat;
                try (InputStream proc = new FileInputStream("/proc/" + pid + "/stat")) {
                    stat = new String(readLimited(proc, 4096), StandardCharsets.US_ASCII);
                }
                String start = stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+")[19];
                if (!start.equals(result.optString("process_start_ticks"))) return null;
            }
            return result;
        } catch (Exception ignored) { return null; }
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
        if (pid < 1) { lastPid = -1; return "RESULT WAITING"; }
        if (lastPid != pid) { validateSource(); lastPid = pid; }
        JSONObject state = state(pid);
        if (state != null) {
            if (!ownsRuntime(state)) return "RESULT RESTART_NEEDED";
            return activeResult(state);
        }
        // Preload while the app is starting; the agent itself waits for stable video before local changes.
        long attachStarted = SystemClock.elapsedRealtime();
        android.util.Log.i("HomeMapRuntimeTool", "Attach begin pid=" + pid + " phase=" +
            (hasConnectedSession(pid) ? "video-active" : "pre-video"));
        Process child = new ProcessBuilder(new File(directory, "frida-inject").getPath(), "-p", Integer.toString(pid),
            "-s", new File(directory, "display-scheduler.js").getPath(), "-R", "qjs", "-e").redirectErrorStream(true).start();
        // Drain bounded diagnostics separately; never forward native process data to the UI.
        Thread drain = new Thread(() -> { try { readLimited(child.getInputStream(), 65536); } catch (Exception ignored) { } }, "map-inject-output");
        drain.setDaemon(true); drain.start();
        if (!child.waitFor(20, TimeUnit.SECONDS)) { child.destroy(); return "ERR ATTACH_TIMEOUT"; }
        android.util.Log.i("HomeMapRuntimeTool", "Attach finished pid=" + pid + " elapsedMs=" +
            (SystemClock.elapsedRealtime() - attachStarted) + " exit=" + child.exitValue());
        if (child.exitValue() != 0) return "ERR ATTACH_FAILED";
        long until = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < until) {
            state = state(pid);
            if (state != null && state.optInt("agent") == 2)
                return activeResult(state);
            SystemClock.sleep(100);
        }
        return "ERR AGENT_START";
    }
    private static String activeResult(JSONObject state) {
        if (!state.optString("error").isEmpty()) return "ERR RUNTIME_FAILED";
        return state.optBoolean("map_stream_active") && state.optBoolean("session_active") ? "RESULT ACTIVE_MAP" : "RESULT ACTIVE";
    }
    private boolean ownsRuntime(JSONObject state) {
        return state.optInt("agent") == 2 && MapMigrationPolicy.ownsRuntime(state.optString("revision"),
            state.optString("helper_package"), state.optInt("helper_uid", -1), helperUid);
    }
    private PackageInfo legacyInfo() throws Exception {
        PackageInfo legacy = packageInfo(LEGACY);
        if (legacy == null) return null;
        requireCertificate(legacy, HELPER_CERT);
        if (legacy.versionCode < 1 || legacy.versionCode > 12 || legacy.applicationInfo == null)
            throw new IllegalStateException("Unsupported legacy version");
        return legacy;
    }
    private String migration() throws Exception {
        PackageInfo legacy = legacyInfo();
        Map<String, String> values = new HashMap<>();
        if (legacy != null) {
            File prefs = new File(legacy.applicationInfo.dataDir, "shared_prefs/map.xml");
            if (prefs.exists()) {
                StructStat stat = Os.lstat(prefs.getPath());
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != legacy.applicationInfo.uid)
                    throw new IllegalStateException("Unsafe legacy preferences");
                try (InputStream input = new FileInputStream(prefs)) {
                    XmlPullParser parser = android.util.Xml.newPullParser(); parser.setInput(input, "UTF-8");
                    for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                        if (event != XmlPullParser.START_TAG) continue;
                        String name = parser.getAttributeValue(null, "name");
                        if ("int".equals(parser.getName()) || "boolean".equals(parser.getName()))
                            values.put(name, parser.getAttributeValue(null, "value"));
                    }
                }
            }
        }
        JSONObject result = new JSONObject(MapMigrationPolicy.appearance(values));
        result.put("legacy_found", legacy != null);
        return "RESULT MIGRATION " + result.toString();
    }
    private void fixedCommand(String... args) throws Exception {
        Process child = new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroy(); throw new IllegalStateException("Command timeout"); }
        readLimited(child.getInputStream(), 65536);
        if (child.exitValue() != 0) throw new IllegalStateException("Command failed");
    }
    private String claim() throws Exception {
        PackageInfo legacy = legacyInfo();
        boolean oldAppWasEnabled = legacy != null && legacy.applicationInfo.enabled;
        if (legacy != null) {
            fixedCommand("am", "force-stop", "--user", "0", LEGACY);
            fixedCommand("pm", "disable-user", "--user", "0", LEGACY);
            PackageInfo disabled = packageInfo(LEGACY);
            if (disabled == null || disabled.applicationInfo.enabled) throw new IllegalStateException("Legacy app still enabled");
        }
        int pid = pid();
        JSONObject current = pid < 1 ? null : state(pid);
        boolean restart = pid > 0 && (oldAppWasEnabled || current != null && !ownsRuntime(current));
        return restart ? "RESULT CLAIMED_RESTART" : "RESULT CLAIMED";
    }
    private static boolean hasConnectedSession(int pid) {
        try (RandomAccessFile log = new RandomAccessFile("/data/user/0/" + SOURCE + "/files/logs/diplay.log", "r")) {
            String header = log.readLine();
            if (header == null || !header.contains("pid=" + pid + " ")) return false;
            long offset = Math.max(log.getFilePointer(), log.length() - 65536);
            log.seek(offset);
            byte[] tail = new byte[(int)(log.length() - offset)]; log.readFully(tail);
            String text = new String(tail, StandardCharsets.UTF_8);
            return RuntimeSessionEvidence.connected(text);
        } catch (Exception ignored) { return false; }
    }
    public static void main(String[] args) {
        MapRuntimeTool tool = null;
        try {
            if (args.length != 0) throw new IllegalArgumentException("Arguments unsupported");
            tool = new MapRuntimeTool(); final MapRuntimeTool owner = tool;
            Thread lease = new Thread(() -> {
                while (!owner.stopped) {
                    SystemClock.sleep(1000);
                    if (SystemClock.elapsedRealtime() - owner.heartbeat > 60000) System.exit(0);
                }
            }, "map-runtime-lease"); lease.setDaemon(true); lease.start();
            System.out.println("READY"); System.out.flush();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
                int c;
                while ((c = input.read()) != -1) {
                    if (c == '\r' || c == '\n') continue;
                    owner.heartbeat = SystemClock.elapsedRealtime();
                    String result;
                    if (c == 'P') result = owner.poll();
                    else if (c == 'M') result = owner.migration();
                    else if (c == 'C') result = owner.claim();
                    else if (c == 'S') { owner.validateSource(); owner.fixedCommand("am", "force-stop", "--user", "0", SOURCE); result = "OK"; }
                    else if (c == 'O') { owner.validateSource(); owner.fixedCommand("am", "start", "--user", "0", "-f", "0x10020000", "-n", SOURCE + "/com.shilapi.xcertplay.CarPlayHostActivity"); result = "OK"; }
                    else result = c == 'Q' ? "OK" : "ERR COMMAND";
                    owner.heartbeat = SystemClock.elapsedRealtime();
                    System.out.println(result); System.out.flush();
                    if (c == 'Q') break;
                }
            }
        } catch (Exception error) {
            android.util.Log.w("HomeMapRuntimeTool", "Runtime unavailable: " + error.getClass().getSimpleName());
            System.out.println("ERR UNAVAILABLE"); System.out.flush();
        }
        finally { if (tool != null) tool.stopped = true; }
    }
}
