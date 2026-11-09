package dev.skycore.data;

import dev.skycore.data.Activity.MayorSnapshot;
import dev.skycore.data.Activity.Slayer;
import dev.skycore.data.ProfileData.StatProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Remaining progression per activity: how many quests of each slayer tier, or runs of each floor, are needed to
 * reach a target level – with the basis of every number and a confidence rating.
 * <ul>
 *   <li>Slayer: reward = base XP of that tier × confirmed bonus of the current mayor. Without a confirmed bonus
 *       state the base is used and the confidence is capped (the true number may be lower).</li>
 *   <li>Dungeons: the player's own average XP on that exact floor (≥ 3 runs with observed XP). Otherwise the
 *       approximate reward table is used and the result is labelled as a fallback.</li>
 * </ul>
 */
public final class Projection {
    public enum Confidence {
        HIGH, MEDIUM, LOW, FALLBACK;
        public String key() { return "confidence." + name().toLowerCase(); }
    }

    /**
     * @param basis "personal" (own observed average), "table" (reward table, bonus state confirmed),
     *              "table_unconfirmed" (reward table, bonus unknown) or "fallback" (approximate table)
     */
    public record Option(String key, double perActivity, String basis, long activities, long millis, Confidence confidence,
                         int samples, boolean bonusApplied) {}

    public static final int MIN_PERSONAL = 3;
    private static final long STALE = 30L * 24 * 3600_000L;

    private Projection() {}

    /** XP from the current state to the start of {@code target}; -1 if level unknown or table missing. */
    public static double remaining(StatDef def, StatProgress p, int target) {
        if (def == null || def.table() == null || p == null || !p.known()) return -1;
        if (target <= p.level) return 0;
        // XP inside the level unknown → counted from the start of the level (an upper bound; callers show it as such)
        double xpInto = p.xpUnknown || p.need <= 0 ? 0 : p.xp;
        double r = XpTables.xpTo(def.table(), p.level, xpInto, Math.min(target, def.maxLevel()));
        return r < 0 ? -1 : r;
    }

    // ------------------------------------------------------------------ slayer
    public static List<Option> slayer(Slayer s, double remainingXp, Map<String, ActivityStats> stats, MayorSnapshot mayorNow, long now) {
        List<Option> out = new ArrayList<>();
        if (s == null || remainingXp < 0) return out;
        for (int tier = 1; tier <= s.maxTier; tier++) {
            long base = Rewards.slayerBase(s, tier);
            if (base <= 0) continue;
            Activity.XpCalc calc = Rewards.slayer(s, tier, mayorNow, -1);
            double per = calc.expected;
            ActivityStats st = stats == null ? null : stats.get(s.key(tier));
            Confidence conf;
            String basis;
            if (calc.complete) {
                basis = "table";
                // the table is trusted more when observed rewards agreed with it
                if (st != null && st.tableMatches >= MIN_PERSONAL && st.tableMismatches == 0) conf = Confidence.HIGH;
                else if (st != null && st.tableMismatches > st.tableMatches) conf = Confidence.LOW;
                else conf = Confidence.MEDIUM;
            } else {
                basis = "table_unconfirmed";
                conf = st != null && st.tableMismatches > st.tableMatches ? Confidence.LOW : Confidence.MEDIUM;
                if (conf == Confidence.MEDIUM && mayorNow == null) conf = Confidence.LOW;
            }
            long n = remainingXp <= 0 ? 0 : (long) Math.ceil(remainingXp / per);
            long ms = -1;
            if (st != null && st.timedCount >= MIN_PERSONAL) ms = (long) (n * st.averageDurationMs());
            boolean applied = calc.bonuses.stream().anyMatch(b -> b.applied);
            out.add(new Option(s.key(tier), per, basis, n, ms, conf, st == null ? 0 : (int) st.xpCount, applied));
        }
        return out;
    }

    // ------------------------------------------------------------------ dungeons
    public static List<Option> dungeons(double remainingXp, Map<String, ActivityStats> floorStats, long now) {
        List<Option> out = new ArrayList<>();
        if (remainingXp < 0) return out;
        for (String f : Activity.FLOORS) {
            ActivityStats st = floorStats == null ? null : floorStats.get(f);
            Option o = floor(f, remainingXp, st, now);
            if (o != null) out.add(o);
        }
        return out;
    }

    public static Option floor(String f, double remainingXp, ActivityStats st, long now) {
        double[] recent = st == null ? new double[0] : st.recentXp();
        if (recent.length >= MIN_PERSONAL) {
            double per = st.averageLast(ActivityStats.DETAILED);
            if (per <= 0) return null;
            long n = remainingXp <= 0 ? 0 : (long) Math.ceil(remainingXp / per);
            long ms = st.timedCount >= MIN_PERSONAL ? (long) (n * st.averageDurationMs()) : -1;
            return new Option(f, per, "personal", n, ms, personalConfidence(st, now), recent.length, false);
        }
        long fb = Rewards.floorFallback(f);
        if (fb <= 0) return null;
        long n = remainingXp <= 0 ? 0 : (long) Math.ceil(remainingXp / fb);
        return new Option(f, fb, "fallback", n, -1, Confidence.FALLBACK, recent.length, false);
    }

    /** Sample size, consistency and recency of the player's own runs. */
    static Confidence personalConfidence(ActivityStats st, long now) {
        int n = st.recentXp().length;
        double cv = st.variation();
        boolean recent = now - st.lastTime() < STALE;
        if (n >= 10 && !Double.isNaN(cv) && cv < 0.15 && recent) return Confidence.HIGH;
        if (n >= 5 && !Double.isNaN(cv) && cv < 0.35) return recent ? Confidence.MEDIUM : Confidence.LOW;
        return Confidence.LOW;
    }
}
