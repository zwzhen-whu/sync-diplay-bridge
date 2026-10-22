package com.local.diplay.clusterbridge;

/** Exact SYNC+ instrument phone properties; no audio or call-control properties. */
public final class CallDisplayProtocol {
    public static final int CALLER = 554699825, DURATION = 557845523, BT = 557845524, DEVICE = 557845525, COMM = 557845528;
    public static final int[] IDS = {CALLER, DURATION, BT, DEVICE, COMM};
    public static int communication(int status) {
        switch (status) { case 1: case 3: return 2; case 2: return 6; case 4: return 3; case 5: return 16; default: throw new IllegalArgumentException("Invalid live call status"); }
    }
    public static String caller(int status, String number, String name) {
        return "SignalID:0x50;field:{BTDevie_Index:0;};field1:{PhoneType:0;Validity:" + (status == 1 || status == 3 ? 2 : 0)
            + ";};CallIDNumber:" + text(number, 48) + ";CallIDName:" + text(name, 32) + ";";
    }
    public static String text(String value, int maxPoints) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(); int count = 0; boolean space = false;
        for (int i = 0; i < value.length() && count < maxPoints;) {
            int c = value.codePointAt(i); i += Character.charCount(c);
            if (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.getType(c) == Character.FORMAT || c == ';' || c == '{' || c == '}' || c == ':') {
                space = result.length() > 0; continue;
            }
            if (c >= 0xD800 && c <= 0xDFFF || c == 0xFFFD) continue;
            if (space && count + 1 < maxPoints) { result.append(' '); count++; }
            space = false; result.appendCodePoint(c); count++;
        }
        return result.toString();
    }
}
