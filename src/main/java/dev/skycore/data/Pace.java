package dev.skycore.data;

import dev.skycore.data.ProfileData.Gain;

import java.util.List;
import java.util.TreeMap;

/**
 * Pure pace logic for the HUD: session XP/h vs. the 7-day average, and the short-term XP-rate trend.
 * The core methods take plain parameters (no game state); the thin wrappers at the bottom read the stored data.
 */
public final class Pace {
    private Pace() {}

    /** Minimum active play of the open session before a pace is shown. */
    static final long MIN_SESSION_SEC = 10 * 60;
    /** Minimum active play in the baseline window before it counts as an average. */
    static final long MIN_BASELINE_SEC = 60 * 60;
    /** |deviation| at or above this is "significant" (pace hint). */
    static final double PACE_THRESHOLD = 0.20;
    static final long WEEK_MS = 7L * 24 * 3600_000L;

    /** Rate comparison. deviation = current / baseline - 1. */
    public record Result(double currentPerHour, double baselinePerHour, double deviation) {
        public boolean significant() { return Math.abs(deviation) >= PACE_THRESHOLD - 1e-9; }
    }

    /** Core rule; null when data is missing. xp in XP, play in active seconds. */
    static Result compare(double sessionXp, long sessionPlaySec, double baseXp, long basePlaySec) {
        if (sessionPlaySec < MIN_SESSION_SEC || sessionXp <= 0) return null;
        if (basePlaySec < MIN_BASELINE_SEC || baseXp <= 0) return null;
        double cur = sessionXp / (sessionPlaySec / 3600.0);
        double base = baseXp / (basePlaySec / 3600.0);
        return new Result(cur, base, cur / base - 1);
    }

    /**
     * {xp, playSeconds} of all buckets in the 7 days before now, without the running session:
     * buckets from the one containing the session start onward are excluded.
     */
    static double[] baseline(TreeMap<Long, SessionLog.Bucket> buckets, long nowMs, long sessionStartMs) {
        long from = (nowMs - WEEK_MS) / SessionLog.BUCKET;
        long until = sessionStartMs / SessionLog.BUCKET; // exclusive
        double xp = 0;
        long play = 0;
        for (SessionLog.Bucket b : buckets.subMap(from, true, until, false).values()) {
            if (b == null) continue;
            play += b.playSeconds;
            if (b.xp != null) for (Double v : b.xp.values()) if (v != null) xp += v;
        }
        return new double[]{xp, play};
    }

    /** Full computation from stored data; the open span must be passed in. */
    static Result compute(SessionLog.Data data, SessionLog.Span open, long nowMs) {
        if (data == null || open == null) return null;
        double[] b = baseline(data.buckets, nowMs, open.start);
        return compare(open.totalXp(), open.playSeconds, b[0], (long) b[1]);
    }

    /** Pace of the running session, or null (no open session, sample mode, too little data). */
    public static Result current() {
        if (Progress.isSample()) return null;
        String key = SessionLog.openKey();
        if (key == null) return null;
        SessionLog.Data d = SessionLog.of(key);
        SessionLog.Span open = null;
        for (int i = d.spans.size() - 1; i >= 0; i--) {
            SessionLog.Span s = d.spans.get(i);
            if (s.end == 0 && "open".equals(s.endReason)) { open = s; break; }
        }
        return compute(d, open, System.currentTimeMillis());
    }

    /** "-23%" / "+25%" (whole percent, explicit sign). */
    public static String signedPercent(double deviation) {
        long p = Math.round(deviation * 100);
        return (p > 0 ? "+" : "") + p + "%";
    }

    // ------------------------------------------------------------------ XP-rate trend

    static final long TREND_RECENT_MS = 10 * 60_000L;
    static final long TREND_WINDOW_MS = 60 * 60_000L;
    static final long TREND_MIN_SPAN_MS = 20 * 60_000L;
    static final double TREND_THRESHOLD = 0.10;

    /**
     * Deviation of the last-10-minutes rate from the last-hour rate, or null when there is not enough data.
     * The hour window is normalised to the time since its first gain (at least 20 minutes of data). If the gain
     * list was cut at maxGains and no longer reaches back to the window start, the window is incomplete: null.
     */
    static Double trend(List<Gain> gains, long nowMs, int maxGains) {
        if (gains == null || gains.isEmpty()) return null;
        long winStart = nowMs - TREND_WINDOW_MS;
        long firstT = Long.MAX_VALUE;
        double hourXp = 0, recentXp = 0;
        for (Gain g : gains) {
            if (g.t < winStart || g.t > nowMs) continue;
            firstT = Math.min(firstT, g.t);
            hourXp += g.amount;
            if (g.t > nowMs - TREND_RECENT_MS) recentXp += g.amount;
        }
        if (firstT == Long.MAX_VALUE) return null;
        boolean cut = gains.size() >= maxGains && gains.get(0).t > winStart;
        if (cut) return null;
        long span = Math.min(TREND_WINDOW_MS, nowMs - firstT);
        if (span < TREND_MIN_SPAN_MS || hourXp <= 0) return null;
        double hourRate = hourXp / span, recentRate = recentXp / TREND_RECENT_MS;
        return recentRate / hourRate - 1;
    }

    /** True when the trend is worth showing (|deviation| >= 10%). */
    public static boolean trendSignificant(Double dev) { return dev != null && Math.abs(dev) >= TREND_THRESHOLD - 1e-9; }

    // Progress keeps only the last 60 gains per stat, which is a few minutes for fast skills. The trend therefore keeps its own
    // in-memory list of the last hour (not saved, empty after a restart).
    private static final java.util.Map<String, java.util.ArrayDeque<Gain>> RECENT = new java.util.HashMap<>();

    /** Call for every skill XP gain. */
    public static synchronized void recordGain(String statId, double amount, long now) {
        if (amount <= 0) return;
        java.util.ArrayDeque<Gain> dq = RECENT.computeIfAbsent(statId, k -> new java.util.ArrayDeque<>());
        dq.addLast(new Gain(now, amount));
        while (!dq.isEmpty() && now - dq.peekFirst().t > TREND_WINDOW_MS) dq.pollFirst();
    }

    public static synchronized void reset() { RECENT.clear(); }

    /** Significant trend for a stat (null = nothing to show, also while AFK). */
    public static synchronized Double trendOf(String statId) {
        java.util.ArrayDeque<Gain> dq = RECENT.get(statId);
        if (dq == null || dev.skycore.tracking.HypixelTracker.afk()) return null;
        Double d = trend(new java.util.ArrayList<>(dq), System.currentTimeMillis(), Integer.MAX_VALUE);
        return trendSignificant(d) ? d : null;
    }
}
