package gg.gokublack.crown.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;

/**
 * Deadlines are wall-clock epoch millis, not game ticks: terms are defined in real days and this
 * server restarts often (spec 3.2).
 */
public final class CrownTime {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    private CrownTime() {
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    public static long days(long n) {
        return TimeUnit.DAYS.toMillis(n);
    }

    public static long hours(long n) {
        return TimeUnit.HOURS.toMillis(n);
    }

    public static long minutes(long n) {
        return TimeUnit.MINUTES.toMillis(n);
    }

    public static String format(long epochMillis) {
        return DISPLAY.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Human-friendly remaining time, e.g. {@code 2d 4h}, {@code 37m}. */
    public static String remaining(long untilEpochMillis) {
        long ms = untilEpochMillis - now();
        if (ms <= 0) {
            return "0m";
        }
        Duration d = Duration.ofMillis(ms);
        long dd = d.toDays();
        long hh = d.toHoursPart();
        long mm = d.toMinutesPart();
        if (dd > 0) {
            return dd + "d " + hh + "h";
        }
        if (hh > 0) {
            return hh + "h " + mm + "m";
        }
        return mm + "m";
    }

    /**
     * Parses an ISO-8601 datetime. Accepts a zoned/offset form
     * ({@code 2026-08-20T19:00:00Z}) or a local form ({@code 2026-08-20T19:00}), which is read in
     * the server's own zone.
     *
     * @return epoch millis, or {@code -1} if unparseable
     */
    public static long parseIso(String text) {
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // fall through to the local form
        }
        try {
            return java.time.LocalDateTime.parse(text)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return -1L;
        }
    }
}
