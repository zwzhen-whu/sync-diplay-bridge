package com.local.diplay.clusterbridge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse only the displayed widget protocol. No guessed guidance or private APIs. */
public final class Protocol {
    public static final String ROAD_ORIGINAL = "original", ROAD_CLEAN = "without_prompts";
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
        StringBuilder normalized = new StringBuilder();
        boolean space = false;
        for (int cp : text.codePoints().toArray()) {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) { space = normalized.length() > 0; continue; }
            // Formatting marks have no road-name content and are unsupported by
            // some instrument fonts. Do not guess or remove literal '?' / U+FFFD.
            if (Character.getType(cp) == Character.FORMAT || Character.isISOControl(cp)) continue;
            if (space) normalized.append(' ');
            normalized.appendCodePoint(cp); space = false;
        }
        String result = normalized.toString();
        return result.equalsIgnoreCase("null") || result.equalsIgnoreCase("(null)") || result.equalsIgnoreCase("undefined") ? "" : result;
    }

    public static String clusterRoad(String text) {
        return clusterRoad(text, ROAD_ORIGINAL);
    }

    public static String roadDisplayMode(String savedMode) {
        // Old display choices migrate to prompt removal while retaining every
        // road, including before the persisted preference migration completes.
        return ROAD_CLEAN.equals(savedMode) || "first".equals(savedMode) || "comma".equals(savedMode) ? ROAD_CLEAN : ROAD_ORIGINAL;
    }

    public static String clusterRoad(String text, String displayMode) {
        // Availability checks, log fallback and final output must use the same
        // cleanup; formatting-only input must not hide a valid log road.
        String value = roadName(text);
        // The OEM receiver drops an empty StreetName field, displaying the literal null.
        if (value.isEmpty()) return "道路名称未提供";
        // Apply display choices only here; source matching keeps the full text.
        if (ROAD_CLEAN.equals(roadDisplayMode(displayMode))) return roadsWithoutPrompts(value);
        return value;
    }

    private static String roadsWithoutPrompts(String value) {
        // Strip only leading standalone prompts. Keep every following token,
        // including later roads, internal spaces and multiword English names.
        int start = 0;
        for (String part : value.split(" ")) {
            if (!roadPrompt(part)) break;
            start += part.length() + 1;
        }
        return start < value.length() ? value.substring(start) : "道路名称未提供";
    }

    private static boolean roadPrompt(String value) {
        switch (value) {
            case "现在": case "请": case "进入": case "驶入": case "转入":
            case "沿": case "沿着": case "继续": case "前往": case "去往": case "现在进入":
            case "左转": case "右转": case "直行": case "向左": case "向右":
                return true;
            default:
                return value.endsWith("后") && meters(value.substring(0, value.length() - 1)) != null;
        }
    }

    /** Log lengths and exceptional code points, never the user's actual road text. */
    public static String roadDiagnostic(String text) {
        if (text == null) text = "";
        StringBuilder special = new StringBuilder(); int seen = 0;
        for (int cp : text.codePoints().toArray()) {
            int type = Character.getType(cp);
            if (((Character.isWhitespace(cp) || Character.isSpaceChar(cp)) && cp != ' ')
                || cp == '?' || cp == 0xfffd || type == Character.FORMAT || type == Character.PRIVATE_USE
                // codePoints joins valid surrogate pairs. A surrogate-range
                // value here is an unpaired UTF-16 code unit.
                || (cp >= 0xd800 && cp <= 0xdfff) || Character.isISOControl(cp)) {
                if (seen < 8) {
                    if (special.length() > 0) special.append(',');
                    special.append(String.format(Locale.ROOT, "U+%04X", cp));
                }
                seen++;
            }
        }
        return "utf16=" + text.length() + " utf8=" + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + " special=[" + special + "] count=" + seen;
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
