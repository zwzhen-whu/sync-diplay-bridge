package com.local.diplay.clusterbridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Call input shared by the audited DiPlay 0.2.19 and 0.2.21 implementations. */
public final class DiPlayCallControl {
    public static final int ANSWER_KEY = 313, END_KEY = 314;
    public static boolean supportedVersion(String version) {
        return "0.2.19".equals(version) || "0.2.21".equals(version);
    }
    private static final Pattern FOCUS = Pattern.compile("(?m)^\\s*mCurrentFocus=Window\\{[^\\r\\n}]*\\bu0 ([^\\s}]+)[^\\r\\n}]*\\}\\s*$");
    private static final String WINDOW = "com.shihab.diplay/com.shilapi.xcertplay.CarPlayHostActivity";
    public static boolean focused(String dump) {
        if (dump == null || dump.length() > 131072) return false;
        Matcher matcher = FOCUS.matcher(dump);
        return matcher.find() && WINDOW.equals(matcher.group(1)) && !matcher.find();
    }
    /** Android 8.0/8.1 IActivityManager uses (maxNum, flags), unlike newer interfaces. */
    public static java.util.List<?> runningTasks(Class<?> api, Object activity) throws Exception {
        return (java.util.List<?>)api.getMethod("getTasks", int.class, int.class).invoke(activity, 1, 0);
    }
    public interface Injection { boolean inject(int action, int keyCode) throws Exception; }
    public static boolean dispatchAnswer(Injection target) throws Exception {
        boolean down = false, up;
        try { down = target.inject(0, ANSWER_KEY); }
        finally { up = target.inject(1, ANSWER_KEY); }
        return down && up;
    }
}
