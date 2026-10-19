package com.local.diplay.clusterbridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One startup-only playback cycle after a trusted failed focus request and standby release. */
public final class AudioRecoveryPolicy {
    public static final long BOOT_WINDOW_MS = 30 * 60 * 1000L;
    private static final long LOG_MAX_AGE_MS = 90000, FAILURE_TTL_MS = 10 * 60 * 1000L;
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+\\d+\\s+[VDIWEF]\\s+([^:]+):\\s?(.*)$");
    private static final Pattern STANDBY = Pattern.compile(".*clientId=\\S+@999(?:\\s.*)?$");
    private static final Pattern REQUESTER = Pattern.compile("^requestAudioFocus\\(\\) from uid/pid (\\d+)/(\\d+) .*");
    public enum Action { NONE, PAUSE, PLAY }
    private long failedAt = -1, releasedAt = -1, candidateAt = -1, pausedAt = -1, resumedAt = -1;
    private int failedPid = -1;
    private int requestedPid = -1;
    private long requestedAt = -1;
    private boolean spent, cancelled, recovered;

    public synchronized void accept(String line, int sourceUid, long wall, long elapsed) {
        if (cancelled || recovered || elapsed > BOOT_WINDOW_MS) return;
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return;
        try {
            long eventWall = Long.parseLong(m.group(1)) * 1000 + Long.parseLong(m.group(2));
            long age = wall - eventWall;
            if (age < -2000 || age > LOG_MAX_AGE_MS) return;
            long event = Math.max(0, elapsed - Math.max(0, age));
            int uid = Integer.parseInt(m.group(3)), pid = Integer.parseInt(m.group(4));
            String tag = m.group(5).trim(), message = m.group(6);
            if (uid == sourceUid && "DiPlay-MediaKeys".equals(tag)) {
                boolean startFailed = message.equals("media keys active focusDisabled=false focusGranted=false");
                boolean retryFailed = message.equals("audio focus regained=false") && pid == requestedPid
                    && requestedAt >= 0 && event >= requestedAt && event - requestedAt <= 2000;
                if ((startFailed || retryFailed) && !spent) {
                    failedAt = event; failedPid = pid; candidateAt = -1;
                } else if (message.equals("audio focus regained=true") || message.equals("audio focus change=1")
                    || message.equals("media keys active focusDisabled=false focusGranted=true")) {
                    recovered = true; failedAt = -1;
                } else if (message.startsWith("media keys active focusDisabled=true")) {
                    cancel();
                } else if (pid == failedPid && message.startsWith("audio focus change=-")) cancel();
            } else if (uid == 1000 && "MediaFocusControl".equals(tag)) {
                Matcher requester = REQUESTER.matcher(message);
                if (requester.matches() && Integer.parseInt(requester.group(1)) == sourceUid
                    && message.contains("callingPack=com.shihab.diplay req=1 useage=USAGE_MEDIA ")) {
                    requestedPid = Integer.parseInt(requester.group(2)); requestedAt = event;
                }
                if (message.startsWith("abandonAudioFocus()") && STANDBY.matcher(message).matches()) releasedAt = event;
                else if (message.startsWith("requestAudioFocus()") && failedAt >= 0
                    && !(message.contains("from uid/pid " + sourceUid + "/" + failedPid + " ")
                        && message.contains("useage=USAGE_MEDIA "))
                    && !message.contains("useage=USAGE_SYSTEM_STANDBY")) cancel();
            }
        } catch (NumberFormatException ignored) { }
    }

    public synchronized Action next(long now, boolean connected, boolean playing, boolean safeForeground,
                                    boolean normalAudioMode, boolean fresh, boolean usedThisBoot) {
        if (cancelled || recovered || now > BOOT_WINDOW_MS) return Action.NONE;
        if (pausedAt >= 0) {
            if (resumedAt >= 0) return Action.NONE;
            if (!connected || !safeForeground || !normalAudioMode || now < pausedAt || now - pausedAt > 4000) {
                cancel(); return Action.NONE;
            }
            // Confirm the same live DiPlay widget actually became paused before sending PLAY.
            return fresh && !playing && now - pausedAt >= 500 ? Action.PLAY : Action.NONE;
        }
        if (spent || usedThisBoot || failedAt < 0 || releasedAt < failedAt || now < releasedAt
            || now < failedAt || now - failedAt > FAILURE_TTL_MS) return Action.NONE;
        if (!connected || !playing || !safeForeground || !normalAudioMode || !fresh) { candidateAt = -1; return Action.NONE; }
        if (candidateAt < 0) candidateAt = now;
        return now - releasedAt >= 2000 && now - candidateAt >= 1000 ? Action.PAUSE : Action.NONE;
    }

    public synchronized void paused(long now) { spent = true; pausedAt = now; }
    public synchronized void resumed(long now) { resumedAt = now; }
    public synchronized void confirmedMissingFocus(int pid, long now) {
        if (spent || recovered || cancelled || pid <= 0) return;
        if (failedAt < 0 || failedPid != pid) { failedAt = now; failedPid = pid; }
        if (releasedAt < failedAt) releasedAt = now;
    }
    public synchronized void confirmedSourceFocus() { recovered = true; failedAt = -1; }
    public synchronized long pausedAt() { return pausedAt; }
    public synchronized void cancel() { cancelled = true; candidateAt = -1; }
    public synchronized boolean pending() { return failedAt >= 0 && !cancelled && !recovered; }
    public synchronized boolean recovered() { return recovered; }
    public synchronized boolean attempted() { return spent; }
    public synchronized boolean cancelled() { return cancelled; }
}
