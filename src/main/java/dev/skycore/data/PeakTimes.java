package dev.skycore.data;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Peak-time analysis: at which hours of the day / weekdays the XP per hour was historically highest
 * (last 28 days, local time zone). Pure logic on recorded buckets; nothing is estimated - too little data
 * yields {@code enough == false}.
 */
public final class PeakTimes {
    public static final long WINDOW_MILLIS = 28L * 24 * 3600_000L;
    /** Buckets with less active play than this are ignored (noise). */
    public static final int MIN_BUCKET_SECONDS = 60;
    /** A slot (hour of day / weekday) is rated only with at least this much play time. */
    public static final int MIN_SLOT_SECONDS = 20 * 60;
    public static final int MIN_TOTAL_SECONDS = 3 * 3600;
    public static final int MIN_RATED_HOURS = 3;

    /** One time bucket: start (epoch ms), active play seconds and total XP. */
    public record Point(long startMillis, int playSeconds, double xp) {}

    /**
     * @param hourRate    XP/h per hour of day 0-23, {@link Double#NaN} if not rated
     * @param bestStart   start hour of the best 2-hour window (wraps past midnight), -1 if none
     * @param bestRate    play-time weighted XP/h of that window (0 if none)
     * @param bestWeekday best weekday (rated slots only), null if none
     * @param average     overall XP/h of all considered play time
     * @param advantage   best window vs. the average in percent (0 if none)
     * @param enough      false = show "not enough data", no estimate
     */
    public record Result(double[] hourRate, int bestStart, double bestRate, DayOfWeek bestWeekday,
                         double average, double advantage, boolean enough) {
        public boolean rated(int hour) { return !Double.isNaN(hourRate[hour]); }
        public boolean inBest(int hour) { return bestStart >= 0 && (hour == bestStart || hour == (bestStart + 1) % 24); }
    }

    private PeakTimes() {}

    /** Convenience: analysis of a profile's bucket map (key = epochMillis / {@link SessionLog#BUCKET}). */
    public static Result analyze(Map<Long, SessionLog.Bucket> buckets, ZoneId zone, long now) {
        List<Point> pts = new ArrayList<>();
        if (buckets != null) for (var e : buckets.entrySet()) {
            SessionLog.Bucket b = e.getValue();
            if (b == null) continue;
            double xp = 0;
            if (b.xp != null) for (Double v : b.xp.values()) if (v != null && v > 0) xp += v;
            pts.add(new Point(e.getKey() * SessionLog.BUCKET, b.playSeconds, xp));
        }
        return analyze(pts, zone, now);
    }

    public static Result analyze(List<Point> points, ZoneId zone, long now) {
        double[] hSec = new double[24], hXp = new double[24];
        double[] dSec = new double[7], dXp = new double[7];
        double totSec = 0, totXp = 0;
        for (Point p : points) {
            if (p.playSeconds() < MIN_BUCKET_SECONDS) continue;
            if (p.startMillis() < now - WINDOW_MILLIS || p.startMillis() > now) continue;
            ZonedDateTime t = Instant.ofEpochMilli(p.startMillis()).atZone(zone);
            double xp = Math.max(0, p.xp());
            hSec[t.getHour()] += p.playSeconds();
            hXp[t.getHour()] += xp;
            int d = t.getDayOfWeek().getValue() - 1;
            dSec[d] += p.playSeconds();
            dXp[d] += xp;
            totSec += p.playSeconds();
            totXp += xp;
        }
        double[] rate = new double[24];
        int rated = 0;
        for (int h = 0; h < 24; h++) {
            if (hSec[h] >= MIN_SLOT_SECONDS) { rate[h] = hXp[h] / (hSec[h] / 3600.0); rated++; }
            else rate[h] = Double.NaN;
        }
        // best contiguous 2-hour window out of rated hours, weighted by play time (ties: earliest)
        int best = -1;
        double bestRate = 0;
        for (int h = 0; h < 24; h++) {
            int n = (h + 1) % 24;
            if (Double.isNaN(rate[h]) || Double.isNaN(rate[n])) continue;
            double r = (hXp[h] + hXp[n]) / ((hSec[h] + hSec[n]) / 3600.0);
            if (best < 0 || r > bestRate) { best = h; bestRate = r; }
        }
        DayOfWeek bestDay = null;
        double bestDayRate = -1;
        for (int d = 0; d < 7; d++) {
            if (dSec[d] < MIN_SLOT_SECONDS) continue;
            double r = dXp[d] / (dSec[d] / 3600.0);
            if (r > bestDayRate) { bestDayRate = r; bestDay = DayOfWeek.of(d + 1); }
        }
        double avg = totSec > 0 ? totXp / (totSec / 3600.0) : 0;
        // a window is required too: without one there is nothing to show
        boolean enough = totSec >= MIN_TOTAL_SECONDS && rated >= MIN_RATED_HOURS && best >= 0;
        double adv = enough && avg > 0 ? (bestRate / avg - 1) * 100 : 0;
        return new Result(rate, enough ? best : -1, enough ? bestRate : 0, enough ? bestDay : null, avg, adv, enough);
    }
}
