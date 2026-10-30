package com.local.diplay.clusterbridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only known navigation/media receiver messages; IService and phone tags are deliberately excluded. */
public final class DiagnosticOemTrace {
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+):?\\s+(\\d+)\\s+(\\d+)\\s+[VDIWEF]\\s+(\\S+)\\s*:\\s?(.*)$");
    public static String select(String line, int uid, long startWall, long nowWall) {
        if (line == null || line.length() > 4096 || uid < 0) return null;
        Matcher parsed = LINE.matcher(line);
        if (!parsed.matches()) return null;
        try {
            if (Integer.parseInt(parsed.group(3)) != uid) return null;
            long wall = Long.parseLong(parsed.group(1)) * 1000 + Long.parseLong((parsed.group(2) + "000").substring(0, 3));
            if (wall < startWall || wall > nowWall + 1000) return null;
            String tag = parsed.group(6), message = parsed.group(7);
            boolean nav = "NavPresenterImpl".equals(tag) && (message.startsWith("hqj Send to YF sendStreetName ")
                || message.startsWith("hqj Send to YF sendStreetName2 ") || "hqj Send to YF sendNaviInfo ".equals(message)
                || message.startsWith("hqj Send to YF sendNavActiveInfo ACTION_PHONE_NAV_ACTIVE_"));
            boolean music = "MediaReceiver".equals(tag) && (message.startsWith("hqj play_info RECEIVE_PLAY_INFO_ACTION title = ")
                || message.startsWith("hqj Send to YF sendMediaInformation Metadata1 = ")
                || message.startsWith("hqj Send to YF sendMediaInformation2 Metadata1 = ")
                || "hqj sendMDPlayInfo mController is null".equals(message));
            return nav || music ? (nav ? "oem_nav" : "oem_music") + " wall=" + wall + " " + tag + " " + DiagnosticReport.clean(message, 1500) : null;
        } catch (NumberFormatException e) { return null; }
    }
}
