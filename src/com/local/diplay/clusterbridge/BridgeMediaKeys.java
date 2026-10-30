package com.local.diplay.clusterbridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Addressable media entry for Key Mapper's "control media for app". Does not play or request audio focus. */
final class BridgeMediaKeys {
    static final String ENABLED = "keymapper_media_keys";
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler handler;
    private final CarPlayCallDisplay calls;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledFuture<?> heartbeat;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final MediaKeyPolicy.PressGate presses = new MediaKeyPolicy.PressGate();
    private MediaSession session;
    private volatile boolean enabled, connected, stopped;
    private volatile long configuration;
    private volatile String status = "关闭", lastResult = "", menuResult = "";
    private LocalAdbShell shell;
    private long retryAfter;

    BridgeMediaKeys(Context context, SharedPreferences preferences, Handler handler, CarPlayCallDisplay calls) {
        this.context = context; this.preferences = preferences; this.handler = handler; this.calls = calls;
        heartbeat = worker.scheduleWithFixedDelay(this::keepAlive, 0, 1, TimeUnit.SECONDS);
    }
    void configure(boolean allowed, boolean carplayConnected) {
        boolean next = !stopped && allowed && preferences.getBoolean(ENABLED, false);
        if (next != enabled || connected != carplayConnected) { configuration++; presses.clear(); }
        enabled = next; connected = carplayConnected;
        if (enabled && session == null) {
            session = new MediaSession(context, "CarPlay bridge keys");
            session.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setMediaButtonReceiver(null);
            session.setCallback(callback, handler);
            session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "CarPlay 按键").build());
            session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PAUSED, 0, 0f).build());
            session.setActive(true);
        } else if (!enabled && session != null) { session.setActive(false); session.release(); session = null; }
    }
    private final MediaSession.Callback callback = new MediaSession.Callback() {
        public void onSkipToNext() { transport(MediaKeyPolicy.NEXT); }
        public void onSkipToPrevious() { transport(MediaKeyPolicy.PREVIOUS); }
        public boolean onMediaButtonEvent(Intent intent) {
            if (intent == null) return false;
            KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (event == null || event.getKeyCode() != MediaKeyPolicy.NEXT && event.getKeyCode() != MediaKeyPolicy.PREVIOUS) return false;
            if (presses.event(event.getKeyCode(), event.getAction(), event.getRepeatCount(), event.getEventTime(), SystemClock.uptimeMillis(), SystemClock.elapsedRealtime()))
                submit(event.getKeyCode());
            return true;
        }
    };
    private void transport(int key) { if (presses.press(key, SystemClock.elapsedRealtime())) submit(key); }
    private boolean available() {
        return !stopped && enabled && preferences.getBoolean(ENABLED, false)
            && context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED;
    }
    private void submit(int key) {
        if (!available()) { lastResult = "按键功能未开启或缺少日志授权，未执行"; return; }
        final MediaKeyPolicy.Snapshot captured = calls.keySnapshot();
        if (MediaKeyPolicy.action(key, captured.status) == MediaKeyPolicy.Action.IGNORE) { lastResult = captured.status == 6 ? "DiPlay 通话状态正在更新，暂不执行" : "通话期间上一曲键不执行切歌"; return; }
        if (!busy.compareAndSet(false, true)) { lastResult = "上一条按键仍在处理，未重复发送"; return; }
        final long epoch = configuration, created = SystemClock.elapsedRealtime();
        worker.execute(() -> {
            try {
                if (!current(epoch, captured, created)) { lastResult = "状态已变化，按键已取消"; return; }
                ensureShell();
                if (!current(epoch, captured, created)) { lastResult = "状态已变化或按键过期，已取消"; return; }
                KeyControlRequest request = new KeyControlRequest(UUID.randomUUID().toString().replace("-", ""), created, key, captured);
                lastResult = result(shell.exchange(request.encode()));
            } catch (Exception e) {
                dropShell(); retryAfter = SystemClock.elapsedRealtime() + 10000;
                lastResult = "按键结果未确认，请核对手机；不会自动重发";
            } finally { busy.set(false); }
        });
    }
    private boolean current(long epoch, MediaKeyPolicy.Snapshot snapshot, long created) {
        return configuration == epoch && MediaKeyPolicy.eligible(available(), connected, snapshot, calls.keySnapshot(), created, SystemClock.elapsedRealtime());
    }
    private void ensureShell() throws Exception {
        if (shell != null) return;
        if (SystemClock.elapsedRealtime() < retryAfter) throw new IllegalStateException("Control interface retry delayed");
        shell = new LocalAdbShell(context.getApplicationInfo().sourceDir, true);
    }
    private void keepAlive() {
        if (!available()) { dropShell(); status = !enabled ? "关闭" : "需要日志读取授权"; return; }
        if (SystemClock.elapsedRealtime() < retryAfter) return;
        try {
            ensureShell(); if (!"OK".equals(shell.exchange("P"))) throw new IllegalStateException("Heartbeat refused");
            String menu = shell.exchange("M");
            if (menu.equals("RESULT MENU_ANSWER")) menuResult = "DiPlay 已接受接听请求，请核对手机";
            else if (menu.equals("RESULT MENU_END")) menuResult = "DiPlay 已接受拒接／挂断请求，请核对手机";
            else if (menu.equals("RESULT MENU_CONTROLS_OFF")) menuResult = "需开启 DiPlay 的 CarPlay 通话按键";
            else if (menu.equals("RESULT MENU_VERSION_UNSUPPORTED")) menuResult = result("ERR DIPLAY_VERSION_UNSUPPORTED");
            else if (menu.equals("RESULT MENU_ANSWER_NOT_FOCUSED")) menuResult = result("ERR ANSWER_NOT_FOCUSED");
            else if (menu.equals("RESULT MENU_ANSWER_INPUT_REJECTED")) menuResult = result("ERR ANSWER_INPUT_REJECTED");
            else if (menu.equals("RESULT MENU_ANSWER_NO_RECEIPT")) menuResult = result("ERR ANSWER_NO_RECEIPT");
            else if (menu.equals("RESULT MENU_ANSWER_DECLINED")) menuResult = result("ERR ANSWER_DECLINED");
            else if (menu.equals("RESULT MENU_STATE_CHANGED")) menuResult = result("ERR STATE_CHANGED");
            else if (menu.equals("RESULT MENU_UNCONFIRMED")) menuResult = "已收到仪表操作，结果未确认，不会自动重发";
            else if (menu.equals("RESULT MENU_NONE")) menuResult = "";
            else throw new IllegalStateException("Unexpected menu response");
            status = "入口就绪，可在 Key Mapper 中选择桥接应用";
        } catch (Exception e) { dropShell(); retryAfter = SystemClock.elapsedRealtime() + 10000; status = "按键接口暂不可用，等待重试"; }
    }
    private void dropShell() { if (shell != null) shell.close(); shell = null; }
    private static String result(String response) {
        switch (response) {
            case "RESULT NEXT": return "已向 DiPlay 发送下一曲";
            case "RESULT PREVIOUS": return "已向 DiPlay 发送上一曲";
            case "RESULT CARRADIO_NEXT": return "已向随心听发送下一曲，由当前音源处理";
            case "RESULT CARRADIO_PREVIOUS": return "已向随心听发送上一曲，由当前音源处理";
            case "RESULT ANSWER": return "DiPlay 已接受接听请求，请核对手机";
            case "RESULT END": return "DiPlay 已接受拒接／挂断请求，请核对手机";
            case "RESULT IGNORE": return "通话期间上一曲键不执行切歌";
            case "ERR ANSWER_NOT_FOREGROUND": return "接听未发送：CarPlay 未在前台";
            case "ERR ANSWER_NOT_FOCUSED": return "接听未发送：CarPlay 未取得输入焦点或焦点检查超时";
            case "ERR ANSWER_INPUT_REJECTED": return "系统未确认接听键投递，请核对手机";
            case "ERR ANSWER_NO_RECEIPT": return "接听键已投递，未收到 DiPlay 接听回执";
            case "ERR ANSWER_DECLINED": return "DiPlay 收到接听键，但未接受接听请求";
            case "ERR CONTROL_FAILED": return "本机按键控制接口报错，结果未确认";
            case "ERR DIPLAY_CONTROLS_OFF": return "需在 DiPlay 开启 CarPlay 通话按键";
            case "ERR DIPLAY_VERSION_UNSUPPORTED": return "当前 DiPlay 版本尚未适配通话按键，请更新桥接应用";
            case "ERR CARPLAY_NOT_FOREGROUND": return "请先打开 CarPlay 后再接听";
            case "ERR NO_MEDIA": return "当前媒体接口不可用，未切换到其他播放器重发";
            case "ERR AUDIO_BUSY": return "通话或语音正在使用音频，未切歌";
            case "ERR STATE_CHANGED": return "通话或连接状态已变化，按键已取消";
            case "ERR DISABLED": return "按键功能已关闭，未执行";
            case "ERR DUPLICATE": return "重复按键已忽略";
            default: return "指令结果未确认，请核对手机；不会自动重发";
        }
    }
    String status() {
        MediaKeyPolicy.Snapshot call = calls.keySnapshot();
        String phase = call.status == 2 ? "来电中" : call.status == 4 ? "通话中" : call.status == 5 ? "保持中"
            : call.status == 1 || call.status == 3 ? "正在连接" : call.status == 6 ? "正在更新，暂不执行按键"
            : call.pid <= 0 ? "等待实时通话记录" : "无通话";
        return status + (enabled ? "\nDiPlay 电话状态：" + phase : "")
            + (enabled && !lastResult.isEmpty() ? "\n最近按键：" + lastResult : "")
            + (enabled && !menuResult.isEmpty() ? "\n仪表电话菜单：" + menuResult : "");
    }
    void close() {
        stopped = true; enabled = false; configuration++; heartbeat.cancel(false);
        if (session != null) { session.setActive(false); session.release(); session = null; }
        worker.execute(this::dropShell); worker.shutdown();
    }
}
