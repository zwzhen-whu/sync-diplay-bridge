package com.local.diplay.clusterbridge;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Xml;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.xmlpull.v1.XmlPullParser;

/** Fixed local control helper. No generic command execution, global media dispatch or native phone operations. */
public final class KeyControlTool implements KeyControlEngine.Port {
    private static final String SOURCE = "com.shihab.diplay", BRIDGE = "com.local.diplay.clusterbridge";
    private static final String RADIO = "com.baidu.car.radio";
    private static final String HOST = "com.shilapi.xcertplay.CarPlayHostActivity";
    private final CarPlayCallState calls = new CarPlayCallState();
    private final KeyControlEngine engine = new KeyControlEngine();
    private final Object activity, sessions;
    private final Class<?> activityApi, sessionApi, controllerApi;
    private final int uid, instrumentUid;
    private final long instrumentEnabledAt;
    private final java.util.concurrent.ExecutorService instrumentWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean instrumentBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String instrumentResult = "NONE";
    private long lastInstrumentWall = -1;
    private final String sourceVersion;
    private final boolean radioAvailable;
    private boolean publicRadioApi;
    private volatile Process logs;
    private volatile boolean logging, stopped;
    private volatile long resetAt, heartbeat;
    private long receiptSequence;
    private CallKeyReceipt receipt;
    private final Object receiptLock = new Object();

    private KeyControlTool() throws Exception {
        if (android.os.Process.myUid() != 0 || android.os.Build.VERSION.SDK_INT < 26 || android.os.Build.VERSION.SDK_INT > 27)
            throw new IllegalStateException("Unsupported control environment");
        activityApi = Class.forName("android.app.IActivityManager");
        activity = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object pm = asInterface("android.content.pm.IPackageManager", binder("package"));
        Class<?> packageApi = Class.forName("android.content.pm.IPackageManager");
        ApplicationInfo source = (ApplicationInfo)packageApi.getMethod("getApplicationInfo", String.class, int.class, int.class).invoke(pm, SOURCE, 0, 0);
        PackageInfo info = (PackageInfo)packageApi.getMethod("getPackageInfo", String.class, int.class, int.class).invoke(pm, SOURCE, 0, 0);
        if (source == null || info == null) throw new IllegalStateException("DiPlay unavailable");
        uid = source.uid; sourceVersion = info.versionName;
        radioAvailable = packageApi.getMethod("getApplicationInfo", String.class, int.class, int.class).invoke(pm, RADIO, 0, 0) != null;
        ApplicationInfo instrument = (ApplicationInfo)packageApi.getMethod("getApplicationInfo", String.class, int.class, int.class)
            .invoke(pm, "com.baidu.xiaoduos.instrumentcluster", 0, 0);
        instrumentUid = instrument == null ? -1 : instrument.uid;
        instrumentEnabledAt = System.currentTimeMillis();
        sessionApi = Class.forName("android.media.session.ISessionManager");
        controllerApi = Class.forName("android.media.session.ISessionController");
        sessions = asInterface(sessionApi.getName(), binder("media_session"));
        resetAt = heartbeat = elapsed(); calls.reset(System.currentTimeMillis()); startLogReader();
    }
    private static IBinder binder(String service) throws Exception {
        IBinder binder = (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, service);
        if (binder == null || !binder.isBinderAlive()) throw new IllegalStateException("Required service unavailable");
        return binder;
    }
    private static Object asInterface(String name, IBinder binder) throws Exception {
        return Class.forName(name + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    private void startLogReader() throws Exception {
        logs = new ProcessBuilder("logcat", "-b", "main", "-v", "threadtime", "-v", "uid", "-v", "epoch", "-T", "1", "-s",
            "xcertplay-usb:I", "DiPlay-CallKeys:I", "InsClusterController:I").redirectErrorStream(true).start();
        logging = true;
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(logs.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!stopped && (line = input.readLine()) != null) {
                    MediaKeyPolicy.Snapshot before = calls.keySnapshot();
                    calls.accept(line, uid, System.currentTimeMillis(), elapsed());
                    if (calls.keySnapshot().generation != before.generation) resetAt = elapsed();
                    InstrumentCallInput menu = InstrumentCallInput.parse(line, instrumentUid, instrumentEnabledAt, System.currentTimeMillis());
                    if (menu != null) acceptInstrument(menu);
                    CallKeyReceipt next = CallKeyReceipt.parse(line, uid);
                    if (next != null) synchronized (receiptLock) {
                        receipt = next; receiptSequence++; receiptLock.notifyAll();
                    }
                }
            } catch (Exception ignored) { /* Invalidate on EOF/error; never retain an assumed live call. */ }
            finally { logging = false; calls.reset(System.currentTimeMillis()); resetAt = elapsed(); }
        }, "bridge-key-control-log"); reader.setDaemon(true); reader.start();
    }
    private void acceptInstrument(InstrumentCallInput menu) {
        // One selected command per log timestamp. The reader must remain free to receive DiPlay's reply.
        if (menu.wall <= lastInstrumentWall) return;
        lastInstrumentWall = menu.wall;
        MediaKeyPolicy.Snapshot captured = snapshot();
        CarPlayCallState.Call current = calls.current();
        if (current == null || current.pid != captured.pid || current.status != captured.status
            || !MediaKeyPolicy.hash(current.key).equals(captured.call)) return;
        int key = menu.key(captured, current.wall);
        if (key < 0 || !instrumentBusy.compareAndSet(false, true)) return;
        long created = elapsed() - (System.currentTimeMillis() - menu.wall);
        instrumentWorker.execute(() -> {
            try {
                // Display ownership: never mirror the native dialer's menu while HFP owns that card.
                if (!enabled() || !readBoolean(BRIDGE, "bridge", "carplay_call_display") || nativePhoneConnected()) return;
                KeyControlRequest request = new KeyControlRequest(java.util.UUID.randomUUID().toString().replace("-", ""), created, key, captured);
                String result = engine.execute(request, this);
                instrumentResult = result.equals("RESULT ANSWER") ? "ANSWER" : result.equals("RESULT END") ? "END"
                    : result.equals("ERR DIPLAY_CONTROLS_OFF") ? "CONTROLS_OFF"
                    : result.equals("ERR DIPLAY_VERSION_UNSUPPORTED") ? "VERSION_UNSUPPORTED"
                    : result.equals("ERR ANSWER_NOT_FOCUSED") ? "ANSWER_NOT_FOCUSED"
                    : result.equals("ERR ANSWER_INPUT_REJECTED") ? "ANSWER_INPUT_REJECTED"
                    : result.equals("ERR ANSWER_NO_RECEIPT") ? "ANSWER_NO_RECEIPT"
                    : result.equals("ERR ANSWER_DECLINED") ? "ANSWER_DECLINED"
                    : result.equals("ERR STATE_CHANGED") ? "STATE_CHANGED" : "UNCONFIRMED";
            } catch (Exception e) { instrumentResult = "UNCONFIRMED"; }
            finally { instrumentBusy.set(false); }
        });
    }
    private boolean nativePhoneConnected() throws Exception {
        IBinder hfp = (IBinder)activityApi.getMethod("peekService", Intent.class, String.class, String.class).invoke(activity,
            new Intent("android.bluetooth.IBluetoothHeadsetClient").setComponent(
                new ComponentName("com.android.bluetooth", "com.android.bluetooth.hfpclient.HeadsetClientService")), null, "com.android.shell");
        if (hfp == null) return false;
        Object proxy = asInterface("android.bluetooth.IBluetoothHeadsetClient", hfp);
        return !((List<?>)Class.forName("android.bluetooth.IBluetoothHeadsetClient").getMethod("getConnectedDevices").invoke(proxy)).isEmpty();
    }
    public long elapsed() { return SystemClock.elapsedRealtime(); }
    public long resetAt() { return resetAt; }
    public boolean enabled() throws Exception { return logging && !stopped && readBoolean(BRIDGE, "bridge", "keymapper_media_keys"); }
    public MediaKeyPolicy.Snapshot snapshot() { return calls.keySnapshot(); }
    public boolean sourceAlive(int pid) throws Exception {
        if (pid <= 0) return false;
        String command = new String(Files.readAllBytes(Paths.get("/proc/" + pid + "/cmdline")), StandardCharsets.UTF_8);
        int end = command.indexOf(0); if (end >= 0) command = command.substring(0, end);
        if (!command.equals(SOURCE) && !command.startsWith(SOURCE + ":")) return false;
        for (String line : Files.readAllLines(Paths.get("/proc/" + pid + "/status"), StandardCharsets.US_ASCII))
            if (line.startsWith("Uid:")) return Integer.parseInt(line.substring(4).trim().split("\\s+")[0]) == uid;
        return false;
    }
    public int audioMode() throws Exception {
        Object audio = asInterface("android.media.IAudioService", binder("audio"));
        return (Integer)Class.forName("android.media.IAudioService").getMethod("getMode").invoke(audio);
    }
    public boolean callControlsEnabled() throws Exception {
        if (!DiPlayCallControl.supportedVersion(sourceVersion))
            throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.DIPLAY_VERSION_UNSUPPORTED);
        return readBoolean(SOURCE, "diplay_byd_outputs", "carplay_call_controls_experimental");
    }
    private boolean foreground() throws Exception {
        List<?> tasks = DiPlayCallControl.runningTasks(activityApi, activity);
        if (tasks.isEmpty() || !(tasks.get(0) instanceof ActivityManager.RunningTaskInfo)) return false;
        ComponentName top = ((ActivityManager.RunningTaskInfo)tasks.get(0)).topActivity;
        return top != null && top.getPackageName().equals(SOURCE) && top.getClassName().equals(HOST);
    }
    private boolean inputFocused() throws Exception {
        Process child = new ProcessBuilder("/system/bin/dumpsys", "window", "windows").redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicBoolean valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            try (java.io.InputStream input = child.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 131072) { valid.set(false); return; }
                    bytes.write(buffer, 0, count);
                }
            } catch (Exception e) { valid.set(false); }
        }, "bridge-call-window-check");
        reader.setDaemon(true); reader.start();
        try {
            if (!child.waitFor(400, TimeUnit.MILLISECONDS) || child.exitValue() != 0) return false;
            reader.join(100);
            return !reader.isAlive() && valid.get() && DiPlayCallControl.focused(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally { child.destroy(); child.getInputStream().close(); }
    }
    public boolean openCarPlay() throws Exception {
        if (foreground()) return true;
        if (!runFixed(700, "/system/bin/am", "start", "--user", "0", "-n", SOURCE + "/" + HOST, "--activity-reorder-to-front")) return false;
        long deadline = elapsed() + 400;
        do { if (foreground()) return true; Thread.sleep(20); } while (elapsed() < deadline);
        return false;
    }
    public MediaRouting.Target media(KeyControlRequest request) throws Exception {
        return MediaRouting.execute(request.key, new MediaRouting.Port() {
            public MediaRouting.State state() throws Exception { return mediaState(); }
            public boolean current() throws Exception { return stillCurrent(request); }
            public int audioMode() throws Exception { return KeyControlTool.this.audioMode(); }
            public boolean send(MediaRouting.Target target, MediaRouting.State captured, int key) throws Exception {
                // Keep the final call/audio gate next to the side effect as well as the routing decision.
                if (audioMode() != 0) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.AUDIO_BUSY);
                if (!current()) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.STATE_CHANGED);
                if (target == MediaRouting.Target.CARRADIO) {
                    // CarRadio already owns online/BT/USB/radio routing. Do not launch it or change its source.
                    return runFixed(700, "/system/bin/am", "broadcast", "--user", "0", "-a",
                        RADIO + (key == MediaKeyPolicy.NEXT ? ".NEXT" : ".PRE"), "-p", RADIO, "--receiver-registered-only");
                }
                Object controller = asInterface(controllerApi.getName(), (IBinder)captured.carplaySession);
                Method send = controllerApi.getMethod("sendMediaButton", KeyEvent.class);
                long now = SystemClock.uptimeMillis();
                try { return (Boolean)send.invoke(controller, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0)); }
                finally { send.invoke(controller, new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0)); }
            }
        });
    }
    private MediaRouting.State mediaState() throws Exception {
        List<?> binders = (List<?>)sessionApi.getMethod("getSessions", ComponentName.class, int.class).invoke(sessions, null, 0);
        IBinder token = null; int playback = 0, count = 0;
        for (Object candidate : binders) {
            if (!(candidate instanceof IBinder)) continue;
            Object controller = asInterface(controllerApi.getName(), (IBinder)candidate);
            if (SOURCE.equals(controllerApi.getMethod("getPackageName").invoke(controller))
                && "DiPlay CarPlay".equals(controllerApi.getMethod("getTag").invoke(controller))) {
                count++; token = (IBinder)candidate;
                android.media.session.PlaybackState state = (android.media.session.PlaybackState)
                    controllerApi.getMethod("getPlaybackState").invoke(controller);
                playback = state == null ? 0 : state.getState();
            }
        }
        return new MediaRouting.State(readNativeSource(), playback, token, radioAvailable, count > 1);
    }
    private int readNativeSource() throws Exception {
        if (radioAvailable) {
            String query = java.util.UUID.randomUUID().toString(); long created = elapsed();
            String output = queryRadio(query, created);
            RadioStateReply state = RadioStateReply.parse(output, query, created, elapsed());
            if (state != null) { publicRadioApi = true; return state.source; }
            // After a successful API-2 query, loss of the live endpoint must not silently use old disk state.
            if (publicRadioApi || output.contains("CAR_MEDIA_V2:")) throw new IllegalStateException("CarRadio state query invalidated");
        }
        // Compatibility with installed API 1, before the matching CarRadio update is installed.
        java.nio.file.Path path = Paths.get("/data/user/0/" + RADIO + "/shared_prefs/carplay_source_v1.xml");
        if (!Files.exists(path)) return MediaRouting.UNKNOWN; // Older/unmodified CarRadio has no native-source extension.
        if (Files.size(path) > 65536) throw new IllegalStateException("Source settings too large");
        try (FileInputStream input = new FileInputStream(path.toFile())) {
            XmlPullParser parser = Xml.newPullParser(); parser.setInput(input, "UTF-8");
            for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next())
                if (event == XmlPullParser.START_TAG && "int".equals(parser.getName()) && "current_source".equals(parser.getAttributeValue(null, "name"))) {
                    int source = Integer.parseInt(parser.getAttributeValue(null, "value"));
                    if (source < MediaRouting.ONLINE || source > MediaRouting.CARPLAY) throw new IllegalStateException("Unsupported native source");
                    return source;
                }
        }
        return MediaRouting.UNKNOWN;
    }
    private String queryRadio(String request, long created) throws Exception {
        Process child = new ProcessBuilder("/system/bin/am", "broadcast", "--user", "0", "-a", RADIO + ".MEDIA_REQUEST", "-p", RADIO,
            "--es", "operation", "get_state", "--es", "request", request, "--el", "deadline", Long.toString(created + 700),
            "--receiver-registered-only").redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicBoolean valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            try (java.io.InputStream input = child.getInputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 8192) { valid.set(false); return; }
                    bytes.write(buffer, 0, count);
                }
            } catch (Exception e) { valid.set(false); }
        }, "bridge-media-state"); reader.setDaemon(true); reader.start();
        try {
            if (!child.waitFor(700, TimeUnit.MILLISECONDS) || child.exitValue() != 0) throw new IllegalStateException("CarRadio state query unavailable");
            reader.join(100);
            if (reader.isAlive() || !valid.get()) throw new IllegalStateException("CarRadio state query incomplete");
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally { child.destroy(); child.getInputStream().close(); }
    }
    public boolean call(KeyControlRequest request, MediaKeyPolicy.Action action) throws Exception {
        if (action != MediaKeyPolicy.Action.ANSWER && action != MediaKeyPolicy.Action.END) throw new IllegalArgumentException("Unsupported call action");
        int pid = snapshot().pid; long fence, wall = System.currentTimeMillis();
        synchronized (receiptLock) { fence = receiptSequence; }
        boolean command;
        if (action == MediaKeyPolicy.Action.ANSWER) {
            if (!foreground()) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.ANSWER_NOT_FOREGROUND);
            if (!inputFocused()) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.ANSWER_NOT_FOCUSED);
            if (!callControlsEnabled()) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.DIPLAY_CONTROLS_OFF);
            if (!stillCurrent(request)) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.STATE_CHANGED);
            Object input = asInterface("android.hardware.input.IInputManager", binder("input"));
            Method inject = Class.forName("android.hardware.input.IInputManager").getMethod("injectInputEvent", Class.forName("android.view.InputEvent"), int.class);
            long downTime = SystemClock.uptimeMillis();
            // Async injection avoids waiting for Android accessibility or spawning another VM.
            // Only DiPlay's exact receipt below is considered an acknowledgement.
            command = DiPlayCallControl.dispatchAnswer((keyAction, code) -> (Boolean)inject.invoke(input,
                new KeyEvent(downTime, SystemClock.uptimeMillis(), keyAction, code, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD), 0));
        } else {
            if (!callControlsEnabled() || !stillCurrent(request)) return false;
            command = runFixed(1000, "/system/bin/am", "broadcast", "--user", "0", "-a", "com.byd.btcall.action.CLOSE_BLUETOOTHSETTING",
                "-p", SOURCE, "--ei", "keycode", "314", "--receiver-registered-only");
        }
        if (!command && action != MediaKeyPolicy.Action.ANSWER) return false;
        long deadline = elapsed() + 1200;
        synchronized (receiptLock) {
            for (;;) {
                if (receiptSequence > fence && receipt != null && receipt.matches(action, pid, wall)) {
                    if (!receipt.sent && action == MediaKeyPolicy.Action.ANSWER) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.ANSWER_DECLINED);
                    return receipt.sent;
                }
                long remaining = deadline - elapsed();
                if (remaining <= 0 || !logging || stopped) {
                    if (action == MediaKeyPolicy.Action.ANSWER) throw new KeyControlEngine.Fault(command
                        ? KeyControlEngine.FaultCode.ANSWER_NO_RECEIPT : KeyControlEngine.FaultCode.ANSWER_INPUT_REJECTED);
                    return false;
                }
                receiptLock.wait(Math.min(remaining, 100));
            }
        }
    }
    private boolean stillCurrent(KeyControlRequest request) throws Exception {
        return enabled() && (request.expected.pid == -1 || sourceAlive(request.expected.pid)) && request.matches(snapshot(), resetAt(), elapsed());
    }
    private static boolean readBoolean(String packageName, String file, String key) throws Exception {
        java.nio.file.Path path = Paths.get("/data/user/0/" + packageName + "/shared_prefs/" + file + ".xml");
        if (!Files.exists(path)) return false;
        if (Files.size(path) > 65536) throw new IllegalStateException("Settings too large");
        try (FileInputStream input = new FileInputStream(path.toFile())) {
            XmlPullParser parser = Xml.newPullParser(); parser.setInput(input, "UTF-8");
            for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next())
                if (event == XmlPullParser.START_TAG && "boolean".equals(parser.getName()) && key.equals(parser.getAttributeValue(null, "name")))
                    return "true".equals(parser.getAttributeValue(null, "value"));
        }
        return false;
    }
    private static boolean runFixed(long timeout, String... args) throws Exception {
        Process child = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            if (!child.waitFor(timeout, TimeUnit.MILLISECONDS)) return false;
            return child.exitValue() == 0;
        } finally { child.destroy(); }
    }
    private String command(String line) throws Exception {
        heartbeat = elapsed();
        if (line.equals("P") || line.equals("Q")) return "OK";
        if (line.equals("M")) return "RESULT MENU_" + instrumentResult;
        return engine.execute(KeyControlRequest.parse(line), this);
    }
    private void close() { stopped = true; instrumentWorker.shutdownNow(); Process child = logs; if (child != null) child.destroy(); }
    public static void main(String[] args) {
        KeyControlTool tool = null;
        try {
            if (args.length != 0) throw new IllegalArgumentException("Arguments unsupported");
            tool = new KeyControlTool(); final KeyControlTool owner = tool;
            Thread lease = new Thread(() -> {
                try {
                    while (!owner.stopped) {
                        Thread.sleep(1000);
                        if (owner.elapsed() - owner.heartbeat > 6000) { owner.close(); System.exit(0); }
                    }
                } catch (InterruptedException ignored) { owner.close(); }
            }, "bridge-key-control-lease"); lease.setDaemon(true); lease.start();
            System.out.println("READY"); System.out.flush();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
                String line;
                while ((line = readLine(input)) != null) {
                    try { System.out.println(tool.command(line)); }
                    catch (Exception e) { System.out.println("ERR CONTROL_FAILED"); }
                    System.out.flush();
                    if (line.equals("Q")) break;
                }
            }
        } catch (Exception e) { System.out.println("ERR UNAVAILABLE"); System.out.flush(); }
        finally { if (tool != null) tool.close(); }
    }
    private static String readLine(BufferedReader input) throws Exception {
        StringBuilder out = new StringBuilder(); int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') return out.toString(); if (c == '\r') continue;
            if (out.length() >= 256) throw new IllegalArgumentException("Key request too long"); out.append((char)c);
        }
        return null;
    }
}
