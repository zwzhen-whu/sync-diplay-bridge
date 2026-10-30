package com.local.diplay.clusterbridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DiPlay's explicit receipt confirms controller enqueueing, not iPhone execution. */
public final class CallKeyReceipt {
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+):?\\s+(\\d+)\\s+\\d+\\s+I\\s+DiPlay-CallKeys\\s*:\\s*(.*)$");
    private static final Pattern MESSAGE = Pattern.compile("(?:call key (\\d+): answer|(?:hang-up key|key) (\\d+): end) CarPlay call sent=(true|false)");
    public final long wall;
    public final int pid, keyCode;
    public final MediaKeyPolicy.Action action;
    public final boolean sent;
    private CallKeyReceipt(long wall, int pid, int keyCode, MediaKeyPolicy.Action action, boolean sent) {
        this.wall = wall; this.pid = pid; this.keyCode = keyCode; this.action = action; this.sent = sent;
    }
    public boolean matches(MediaKeyPolicy.Action expected, int sourcePid, long after) {
        return action == expected && pid == sourcePid && wall >= after
            && keyCode == (expected == MediaKeyPolicy.Action.ANSWER ? DiPlayCallControl.ANSWER_KEY : DiPlayCallControl.END_KEY);
    }
    public static CallKeyReceipt parse(String line, int uid) {
        if (line == null || line.length() > 512) return null;
        Matcher log = LINE.matcher(line);
        if (!log.matches()) return null;
        try {
            if (Integer.parseInt(log.group(3)) != uid) return null;
            Matcher message = MESSAGE.matcher(log.group(5));
            if (!message.matches()) return null;
            long wall = Long.parseLong(log.group(1)) * 1000 + Long.parseLong((log.group(2) + "000").substring(0, 3));
            boolean answer = message.group(1) != null;
            int key = Integer.parseInt(message.group(answer ? 1 : 2));
            return new CallKeyReceipt(wall, Integer.parseInt(log.group(4)), key, answer ? MediaKeyPolicy.Action.ANSWER : MediaKeyPolicy.Action.END,
                Boolean.parseBoolean(message.group(3)));
        } catch (NumberFormatException e) { return null; }
    }
}
