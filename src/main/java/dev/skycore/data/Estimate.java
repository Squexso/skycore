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
                if (lo == hi) return "~" + Lang.duration(lo);
                return Lang.duration(lo) + " – " + Lang.duration(hi);
            }
            String p = preliminary() ? "~" : "";
            if (lo == hi) return p + Lang.num(lo);
            return p + Lang.num(lo) + "–" + Lang.num(hi);
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
            return new Range((long) Math.ceil(remaining / hiGain), (long) Math.ceil(remaining / loGain), (long) Math.ceil(remaining / med), g.length, def.unit());
        }
        if (def.unit() == StatDef.Unit.TIME) {
            double[] rates = SessionLog.rateSamples(def.id());
            if (rates.length == 0) return null;
            Arrays.sort(rates);
            double hi = rates.length < 4 ? rates[rates.length - 1] : pct(rates, 0.75);
            double lo = rates.length < 4 ? rates[0] : pct(rates, 0.25);
            double med = pct(rates, 0.5);
            return new Range(ms(remaining, hi), ms(remaining, lo), ms(remaining, med), rates.length, def.unit());
        }
        return null;
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
