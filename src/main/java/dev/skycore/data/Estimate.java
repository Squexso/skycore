package dev.skycore.data;

import dev.skycore.core.Lang;
import dev.skycore.data.ProfileData.StatProgress;

import java.util.Arrays;

/**
 * Turns recorded progression into honest ranges.
 * <p>
 * Run/boss XP varies, so instead of dividing by a fixed average we take the spread of the most recent
 * real gains: optimistic = remaining / 75th-percentile gain, pessimistic = remaining / 25th-percentile gain.
 * With fewer than 4 samples we fall back to the max / min observed gain. With no samples there is no estimate.
 * The range updates automatically after every recorded run.
 */
public final class Estimate {
    private Estimate() {}

    /** lo..hi in units (runs, bosses) or milliseconds (TIME). samples = how many data points were used. */
    public record Range(long lo, long hi, long mid, int samples, StatDef.Unit unit) {
        /** Fewer than 3 data points: shown as a preliminary estimate. */
        public boolean preliminary() { return samples < 3; }

        public String format() {
            if (unit == StatDef.Unit.TIME) {
                if (hi < 60_000) return Lang.t("time.lt_min");
                if (lo == hi) return "~" + Lang.duration(lo);
                return Lang.durationShort(lo) + " – " + Lang.duration(hi);
            }
            String p = preliminary() ? "~" : "";
            if (lo == hi) return p + Lang.num(lo);
            return p + Lang.num(lo) + "–" + Lang.num(hi);
        }

        /**
         * Compact from-to text without a lead-in: "1h 15m - 2h 30m" (TIME) or "5-7 runs". A "~" marks a preliminary
         * estimate (fewer than 3 samples); lo == hi shows a single value.
         */
        public String span() {
            if (unit == StatDef.Unit.TIME) {
                if (hi < 60_000) return Lang.t("time.lt_min");
                String p = preliminary() ? "~" : "";
                if (lo == hi) return p + Lang.duration(lo);
                return p + Lang.durationShort(lo) + " - " + Lang.duration(hi);
            }
            String u = unitLabel();
            return format() + (u.isEmpty() ? "" : " " + u);
        }

        public String unitLabel() {
            return switch (unit) {
                case RUNS -> Lang.t(mid == 1 ? "unit.run" : "unit.runs");
                case BOSSES -> Lang.t(mid == 1 ? "unit.boss" : "unit.bosses");
                default -> "";
            };
        }
    }

    public static Range forNextLevel(StatDef def, StatProgress p) {
        double rem = p.remaining();
        if (rem < 0) return null;
        return forXp(def, p, rem);
    }

    public static Range forXp(StatDef def, StatProgress p, double remaining) {
        if (remaining <= 0) return new Range(0, 0, 0, 0, def.unit());
        if (def.unit() == StatDef.Unit.RUNS || def.unit() == StatDef.Unit.BOSSES) {
            // slayer: compare like with like – only bosses of the tier you are currently doing
            String tag = p.gains.isEmpty() ? null : p.gains.get(p.gains.size() - 1).tag;
            java.util.List<ProfileData.Gain> pool = p.gains;
            if (tag != null) {
                java.util.List<ProfileData.Gain> same = p.gains.stream().filter(x -> tag.equals(x.tag)).toList();
                if (!same.isEmpty()) pool = same;
            }
            double[] g = pool.stream().skip(Math.max(0, pool.size() - 20)).mapToDouble(x -> x.amount).filter(v -> v > 0).toArray();
            if (g.length == 0) return null;
            Arrays.sort(g);
            double hiGain = g.length < 4 ? g[g.length - 1] : pct(g, 0.75);
            double loGain = g.length < 4 ? g[0] : pct(g, 0.25);
            double med = pct(g, 0.5);
            long mid = (long) Math.ceil(remaining / med);
            long lo = Math.min(mid, (long) Math.ceil(remaining / hiGain)), hi = Math.max(mid, (long) Math.ceil(remaining / loGain));
            return new Range(lo, hi, mid, g.length, def.unit()); // one sample: lo == hi, flagged preliminary
        }
        if (def.unit() == StatDef.Unit.TIME) {
            return timeRange(remaining, SessionLog.rateSamples(def.id()), med -> anchorFactor(def.id(), med), def.unit());
        }
        return null;
    }

    /** Longest gap between two kills/runs that still counts as "playing on"; longer breaks are not part of the time per boss. */
    static final long MAX_GAP_MS = 20 * 60_000L, MIN_GAP_MS = 5_000L;
    static final int MIN_GAPS = 3;

    /**
     * Typical time between two kills or runs (median gap in ms) from gain timestamps, oldest first. Breaks over 20 minutes and
     * gaps under 5 seconds (double reports) are ignored. 0 when fewer than 3 usable gaps exist.
     */
    static long timePerUnitMs(java.util.List<ProfileData.Gain> gains) {
        double[] gaps = new double[Math.max(0, gains.size() - 1)];
        int n = 0;
        for (int i = 1; i < gains.size(); i++) {
            long gap = gains.get(i).t - gains.get(i - 1).t;
            if (gap >= MIN_GAP_MS && gap <= MAX_GAP_MS) gaps[n++] = gap;
        }
        if (n < MIN_GAPS) return 0;
        double[] used = Arrays.copyOf(gaps, n);
        Arrays.sort(used);
        return (long) pct(used, 0.5);
    }

    /**
     * The same estimate as a time span, for runs/bosses goals: units range x typical time per unit. Null if the stat is not
     * counted in runs/bosses or there is not enough history to know the time per unit.
     */
    public static Range withTime(StatDef def, StatProgress p, Range units) {
        if (units == null || (def.unit() != StatDef.Unit.RUNS && def.unit() != StatDef.Unit.BOSSES)) return null;
        String tag = p.gains.isEmpty() ? null : p.gains.get(p.gains.size() - 1).tag;
        java.util.List<ProfileData.Gain> pool = p.gains;
        if (tag != null) {
            java.util.List<ProfileData.Gain> same = p.gains.stream().filter(x -> tag.equals(x.tag)).toList();
            if (!same.isEmpty()) pool = same;
        }
        long per = timePerUnitMs(pool.subList(Math.max(0, pool.size() - 21), pool.size()));
        if (per <= 0) return null;
        return new Range(units.lo() * per, units.hi() * per, units.mid() * per, Math.min(units.samples(), pool.size() - 1), StatDef.Unit.TIME);
    }

    /** Spread limit around mid for TIME ranges (lo >= 0.5x mid, hi <= 2x mid). */
    static final double MIN_FACTOR = 0.5, MAX_FACTOR = 2.0;

    /**
     * TIME range from XP/h samples. Bucket rates are per active second, so they run higher than the XP/h shown on the
     * HUD (today's XP over today's playtime); anchor maps the bucket median to the shown rate and only the spread of the
     * buckets is kept. Result is ordered lo <= mid <= hi and limited to [0.5x .. 2x] around mid; steady rates stay narrow.
     */
    static Range timeRange(double remaining, double[] samples, java.util.function.DoubleUnaryOperator anchor, StatDef.Unit unit) {
        double[] rates = Arrays.stream(samples).filter(v -> v > 0).toArray();
        if (rates.length == 0) return null;
        Arrays.sort(rates);
        double hi = rates.length < 4 ? rates[rates.length - 1] : pct(rates, 0.75);
        double lo = rates.length < 4 ? rates[0] : pct(rates, 0.25);
        double med = pct(rates, 0.5);
        double k = anchor.applyAsDouble(med);
        hi *= k; lo *= k; med *= k;
        long mid = ms(remaining, med);
        long fast = Math.min(mid, ms(remaining, hi)), slow = Math.max(mid, ms(remaining, lo));
        fast = Math.max(fast, (long) (mid * MIN_FACTOR));
        slow = Math.min(slow, (long) (mid * MAX_FACTOR));
        return new Range(fast, Math.max(fast, slow), mid, rates.length, unit);
    }

    /** today's shown XP/h divided by the bucket median (1 when there is no usable rate for today). */
    static double anchorFactor(String statId, double bucketMedian) {
        if (bucketMedian <= 0) return 1;
        try {
            SessionLog.Totals tt = SessionLog.totals(SessionLog.range(SessionLog.Period.TODAY));
            double hours = tt.playMillis() / 3600_000.0;
            double xp = tt.byStat().getOrDefault(statId, 0d);
            if (hours < 0.05 || xp <= 0) return 1;
            return Math.max(0.2, Math.min(1.5, (xp / hours) / bucketMedian));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static long ms(double xp, double perHour) { return (long) (xp / perHour * 3600_000L); }

    private static double pct(double[] sorted, double q) {
        double idx = q * (sorted.length - 1);
        int i = (int) Math.floor(idx);
        double f = idx - i;
        return i + 1 < sorted.length ? sorted[i] * (1 - f) + sorted[i + 1] * f : sorted[i];
    }

    /** Average XP per gain over the recent window (for display). */
    public static double averageGain(StatProgress p) {
        return p.gains.stream().skip(Math.max(0, p.gains.size() - 20)).mapToDouble(x -> x.amount).average().orElse(0);
    }
}
