package com.local.diplay.clusterbridge.homemap;

/** Android pads argv[0] with NULs; additional nonempty arguments are not an app identity. */
final class RuntimeIdentity {
    static boolean matchesCmdline(String cmdline, String expected) {
        int end = cmdline.indexOf('\0');
        if (end < 0 || !cmdline.substring(0, end).equals(expected)) return false;
        for (int i = end; i < cmdline.length(); i++) if (cmdline.charAt(i) != '\0') return false;
        return true;
    }
}
