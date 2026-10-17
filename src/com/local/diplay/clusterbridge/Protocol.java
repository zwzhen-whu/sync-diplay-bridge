package com.local.diplay.clusterbridge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse only the displayed widget protocol. No guessed guidance or private APIs. */
public final class Protocol {
    private static final Pattern DISTANCE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(km|m|公里|千米|公尺|米|كم|م|км|м)");
    public static final String[][] ARROWS = {
        {"ic_maneuver_left", "turn_left"}, {"ic_maneuver_right", "turn_right"},
        {"ic_maneuver_sharp_left", "turn_left_back"}, {"ic_maneuver_sharp_right", "turn_right_back"},
        {"ic_maneuver_slight_left", "turn_left_front"}, {"ic_maneuver_slight_right", "turn_right_front"},
        {"ic_maneuver_u_turn_left", "turn_back"}, {"ic_maneuver_u_turn_right", "turn_back"},
        {"ic_maneuver_roundabout", "turn_ring"}, {"ic_maneuver_destination", "turn_dest"},
        {"ic_maneuver_straight", "turn_front"}
    };

    public static Integer meters(String text) {
        if (text == null) return null;
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u061c' || c == '\u200e' || c == '\u200f'
                || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) continue;
            int digit = Character.digit(c, 10);
            if (digit >= 0) normalized.append((char) ('0' + digit));
            else if (c == ',' || c == '\u066b') normalized.append('.');
            else normalized.append(c);
        }
        Matcher match = DISTANCE.matcher(normalized.toString().toLowerCase(Locale.ROOT));
        if (!match.matches()) return null;
        try {
            BigDecimal value = new BigDecimal(match.group(1));
            String unit = match.group(2);
            if (!(unit.equals("m") || unit.equals("米") || unit.equals("公尺") || unit.equals("م") || unit.equals("м"))) value = value.multiply(new BigDecimal(1000));
            if (value.compareTo(new BigDecimal(2000000)) > 0) return null;
            return value.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException e) { return null; }
    }

    public static boolean knownTurn(String token) {
        for (String[] item : ARROWS) if (item[1].equals(token)) return true;
        return false;
    }

    public static String roadName(String text) {
        if (text == null) return "";
        text = text.replace("\u0000", "");
        int start = 0, end = text.length();
        while (start < end && padding(text.charAt(start))) start++;
        while (end > start && padding(text.charAt(end - 1))) end--;
        String result = text.substring(start, end);
        return result.equalsIgnoreCase("null") || result.equalsIgnoreCase("(null)") || result.equalsIgnoreCase("undefined") ? "" : result;
    }
    private static boolean padding(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }

    public static String clusterRoad(String text) {
        String value = roadName(text);
        // The OEM receiver drops an empty StreetName field, displaying the literal null.
        return value.isEmpty() ? "道路名称未提供" : value;
    }

    public static String turnForAppleType(int type) {
        switch (type) {
            case 1: case 20: return "turn_left";
            case 2: case 21: return "turn_right";
            case 47: return "turn_left_back";
            case 48: return "turn_right_back";
            case 13: case 22: case 49: case 52: return "turn_left_front";
            case 14: case 23: case 50: case 53: return "turn_right_front";
            case 4: case 18: case 19: case 26: return "turn_back";
            case 6: case 7: return "turn_ring";
            case 10: case 12: case 24: case 25: case 27: return "turn_dest";
            case 0: case 3: case 5: case 8: case 9: case 11: case 15: case 16: case 17: return "turn_front";
            default: return type >= 28 && type <= 46 ? "turn_ring" : null;
        }
    }

    public static int roundedWidgetMeters(int rawMeters) {
        if (rawMeters < 0) return -1;
        long raw = rawMeters;
        long rounded = raw < 1000 ? (raw + 5) / 10 * 10 : raw < 10000 ? (raw + 50) / 100 * 100 : (raw + 500) / 1000 * 1000;
        return rounded > Integer.MAX_VALUE ? -1 : (int) rounded;
    }

    public static final class Song {
        public final String text;
        public final boolean playing;
        Song(String text, boolean playing) { this.text = text; this.playing = playing; }
    }

    public static Song song(String text) {
        if (text == null) return null;
        String prefix = text.startsWith("♪ ") ? "♪ " : text.startsWith("❚❚ ") ? "❚❚ " : null;
        if (prefix == null) return null;
        String content = text.substring(prefix.length()).trim();
        return content.isEmpty() ? null : new Song(content, prefix.equals("♪ "));
    }
}
