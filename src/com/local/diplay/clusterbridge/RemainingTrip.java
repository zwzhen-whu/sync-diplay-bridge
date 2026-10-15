package com.local.diplay.clusterbridge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decode the displayed trip summary using the installed DiPlay's localized formats. */
public final class RemainingTrip {
    public final int meters, seconds;
    private RemainingTrip(int meters, int seconds) { this.meters = meters; this.seconds = seconds; }

    public boolean sameAs(RemainingTrip other) {
        return other != null && meters == other.meters && seconds == other.seconds;
    }

    public static final class Formats {
        private final Template meters, kmDecimal, km, minutes, hoursMinutes;
        public Formats(String meters, String kmDecimal, String km, String minutes, String hoursMinutes) {
            this.meters = new Template(meters, 1); this.kmDecimal = new Template(kmDecimal, 1);
            this.km = new Template(km, 1); this.minutes = new Template(minutes, 1);
            this.hoursMinutes = new Template(hoursMinutes, 2);
        }

        public RemainingTrip parse(String widgetEta) {
            if (widgetEta == null || widgetEta.length() > 512) return null;
            // DiPlay orders these as optional arrival, optional distance, optional duration.
            // With fewer than two fields, or either required field absent, send no summary.
            String[] fields = widgetEta.split("·", -1);
            if (fields.length != 2 && fields.length != 3) return null;
            String distance = fields[fields.length - 2], duration = fields[fields.length - 1];
            BigDecimal[] value = meters.parse(distance);
            BigDecimal distanceMeters = value == null ? null : value[0];
            if (distanceMeters == null) {
                value = kmDecimal.parse(distance);
                if (value == null) value = km.parse(distance);
                if (value != null) distanceMeters = value[0].multiply(BigDecimal.valueOf(1000));
            }
            value = minutes.parse(duration);
            BigDecimal timeSeconds = value == null ? null : value[0].multiply(BigDecimal.valueOf(60));
            if (timeSeconds == null) {
                value = hoursMinutes.parse(duration);
                if (value != null && value[1].compareTo(BigDecimal.valueOf(60)) < 0)
                    timeSeconds = value[0].multiply(BigDecimal.valueOf(3600)).add(value[1].multiply(BigDecimal.valueOf(60)));
            }
            if (distanceMeters == null || timeSeconds == null || distanceMeters.signum() < 0 || timeSeconds.signum() < 0
                || distanceMeters.compareTo(BigDecimal.valueOf(2000000)) > 0
                || timeSeconds.compareTo(BigDecimal.valueOf(7 * 86400)) > 0) return null;
            try {
                return new RemainingTrip(distanceMeters.setScale(0, RoundingMode.HALF_UP).intValueExact(), timeSeconds.intValueExact());
            } catch (ArithmeticException e) { return null; }
        }
    }

    private static String normalized(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u061c' || c == '\u200e' || c == '\u200f'
                || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) continue;
            int digit = Character.digit(c, 10);
            if (digit >= 0) result.append((char) ('0' + digit));
            else result.append(c == ',' || c == '\u066b' ? '.' : c);
        }
        return result.toString();
    }

    private static final class Template {
        private static final Pattern FORMAT = Pattern.compile("%([12])\\$(?:0?[0-9]+)?(?:\\.[0-9]+)?([df])");
        private Pattern pattern;
        private final List<Integer> positions = new ArrayList<>();
        private final int count;
        Template(String format, int count) {
            this.count = count;
            if (format == null || format.isEmpty() || format.length() > 128) return;
            format = normalized(format);
            Matcher tokens = FORMAT.matcher(format);
            StringBuilder regex = new StringBuilder();
            int start = 0;
            while (tokens.find()) {
                int position = Integer.parseInt(tokens.group(1)) - 1;
                if (position >= count || positions.contains(position)) return;
                regex.append(Pattern.quote(format.substring(start, tokens.start())));
                regex.append(tokens.group(2).equals("d") ? "([0-9]{1,10})" : "([0-9]{1,10}(?:\\.[0-9]{1,6})?)");
                positions.add(position); start = tokens.end();
            }
            if (positions.size() != count || format.substring(start).contains("%")) return;
            regex.append(Pattern.quote(format.substring(start)));
            pattern = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        BigDecimal[] parse(String text) {
            if (pattern == null || text == null) return null;
            Matcher match = pattern.matcher(normalized(text));
            if (!match.matches()) return null;
            BigDecimal[] values = new BigDecimal[count];
            for (int i = 0; i < count; i++) values[positions.get(i)] = new BigDecimal(match.group(i + 1));
            return values;
        }
    }
}
