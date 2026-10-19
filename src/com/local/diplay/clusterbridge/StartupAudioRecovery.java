package com.local.diplay.clusterbridge;

import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

/** Opt-in workaround; never requests audio focus itself or changes volume. */
final class StartupAudioRecovery {
    private final Context context;
    private final SharedPreferences preferences;
    private final AudioRecoveryPolicy policy = new AudioRecoveryPolicy();
    private final ForegroundActivityState foreground = new ForegroundActivityState();
    private final int boot;
    private AudioSnapshotReader snapshots;
    private boolean seenConnected;
    private String status = "关闭";

    StartupAudioRecovery(Context context, SharedPreferences preferences) {
        this.context = context; this.preferences = preferences;
        int count;
        try { count = Settings.Global.getInt(context.getContentResolver(), "boot_count", -1); }
        catch (RuntimeException e) { count = -1; }
        boot = count;
    }

    void acceptLog(String line, int sourceUid) {
        if (preferences.getBoolean("startup_audio_recovery", false) && boot >= 0)
            policy.accept(line, sourceUid, System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    void tick(boolean connected, boolean playing, long widgetUpdatedAt) {
        long now = SystemClock.elapsedRealtime();
        if (!preferences.getBoolean("startup_audio_recovery", false)) { close(); status = "关闭"; return; }
        if (boot < 0) { status = "无法确认本次开机，不执行恢复"; return; }
        if (now > AudioRecoveryPolicy.BOOT_WINDOW_MS) { close(); status = "本次开机检查窗口已结束"; return; }
        if (seenConnected && !connected) policy.cancel();
        seenConnected |= connected;
        if (policy.recovered()) { close(); status = policy.attempted() ? "一次恢复后已确认 DiPlay 取得音频焦点" : "DiPlay 已取得音频焦点，无需恢复"; return; }
        if (policy.cancelled()) { close(); status = "条件已变化，本次不再操作播放"; return; }
        boolean used = preferences.getInt("startup_audio_attempted_boot", -1) == boot;
        if (used && !policy.attempted()) { close(); status = "本次开机已尝试一次，不再重复"; return; }
        if (!connected || widgetUpdatedAt <= 0) { status = "等待 CarPlay 播放状态"; return; }
        if (!CarPlayAutoEntry.hasAccess(context)) { status = "需使用情况访问授权以确认 DiPlay 前台"; return; }
        if (context.checkSelfPermission("android.permission.DUMP") != PackageManager.PERMISSION_GRANTED) {
            status = "需通过 ADB 授予系统诊断读取权限（DUMP）"; return;
        }
        try {
            AudioManager audio = context.getSystemService(AudioManager.class);
            boolean safe = playerIsForeground(now);
            boolean normal = audio != null && audio.getMode() == AudioManager.MODE_NORMAL;
            if (!safe || !normal) {
                if (policy.attempted()) policy.cancel();
                status = "等待 CarPlay 前台及正常音频模式"; return;
            }
            if (!playing && !policy.attempted()) { status = "当前未播放，不操作"; return; }
            if (snapshots == null) snapshots = new AudioSnapshotReader(context.getPackageManager().getApplicationInfo(BridgeService.SOURCE, 0).uid);
            snapshots.request(now);
            AudioSnapshotReader.Snapshot snapshot = snapshots.latest();
            if (snapshot == null || now < snapshot.at || now - snapshot.at > 4000) { status = "正在核对系统音频状态"; return; }
            SystemAudioState state = snapshot.audio;
            if (state.focus == SystemAudioState.Focus.SOURCE) {
                policy.confirmedSourceFocus(); close();
                status = policy.attempted() ? "一次恢复后已确认 DiPlay 取得音频焦点" : "DiPlay 已取得音频焦点，无需恢复"; return;
            }
            if (state.focus == SystemAudioState.Focus.OTHER) { policy.cancel(); close(); status = "其他音源占用，不操作播放"; return; }
            if (state.focus == SystemAudioState.Focus.STANDBY) { status = "原车仍处于音频待机，等待退出"; return; }
            if (state.focus == SystemAudioState.Focus.UNKNOWN) { status = "音频快照不可用或格式不支持，不执行恢复"; return; }
            if (!state.safeCallState) { if (policy.attempted()) policy.cancel(); status = "通话状态未确认安全，不操作播放"; return; }
            if (!snapshot.receiver) { status = "媒体按键接收方尚未确认为 DiPlay"; return; }
            if (!policy.attempted()) {
                if (!state.missingSourceFocus() || !audio.isMusicActive() || audio.isStreamMute(AudioManager.STREAM_MUSIC)
                    || audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
                    status = "尚未确认 DiPlay 音轨活跃且焦点缺失，不操作"; return;
                }
                policy.confirmedMissingFocus(state.sourcePid, now);
            }
            // Music-only widgets retain their last snapshot without a periodic render.
            // Initial liveness comes from the source UID's actual AudioTrack. After PAUSE,
            // require a newer widget callback confirming that DiPlay really paused.
            boolean fresh = policy.attempted() ? widgetUpdatedAt >= policy.pausedAt() : state.missingSourceFocus();
            AudioRecoveryPolicy.Action action = policy.next(now, connected, playing, safe, normal, fresh, used);
            if (action == AudioRecoveryPolicy.Action.PAUSE) {
                // Persist before dispatch so a process restart cannot repeat the cycle.
                if (!preferences.edit().putInt("startup_audio_attempted_boot", boot).commit()) {
                    policy.cancel(); status = "无法保存次数限制，未执行恢复"; return;
                }
                policy.paused(now);
                mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE);
                status = "首次无声：已请求暂停，等待 DiPlay 状态确认";
                Log.i("DiPlayBridge", "Startup audio recovery: PAUSE boot=" + boot);
            } else if (action == AudioRecoveryPolicy.Action.PLAY) {
                policy.resumed(now);
                mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY);
                status = "已请求继续一次，等待 DiPlay 焦点确认";
                Log.i("DiPlayBridge", "Startup audio recovery: PLAY boot=" + boot);
            } else if (policy.attempted()) {
                status = policy.cancelled() ? "未确认恢复条件，不再重复；可手动继续播放" : "一次恢复进行中，不会重复发送";
            } else status = "已确认音轨活跃、焦点为空及待机退出，等待稳定";
        } catch (Exception e) {
            policy.cancel(); status = "恢复未完成：" + e.getClass().getSimpleName();
            Log.w("DiPlayBridge", "Startup audio recovery stopped", e);
        }
    }

    void close() { if (snapshots != null) { snapshots.close(); snapshots = null; } }

    private boolean playerIsForeground(long now) {
        KeyguardManager lock = context.getSystemService(KeyguardManager.class);
        if (lock != null && lock.isKeyguardLocked()) return false;
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        long wall = System.currentTimeMillis();
        UsageEvents events = usage == null ? null : usage.queryEvents(foreground.queryStart(wall, now), wall + 1);
        if (events == null) { foreground.clear(); return false; }
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            foreground.event(event.getEventType(), event.getPackageName(), event.getClassName(), event.getTimeStamp());
        }
        foreground.queried(wall, now);
        return CarPlayEntryPolicy.SOURCE.equals(foreground.packageName()) && CarPlayEntryPolicy.PLAYER.equals(foreground.className());
    }

    private static void mediaKey(AudioManager audio, int key) {
        long time = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, key, 0));
    }

    String status() { return status; }
}
