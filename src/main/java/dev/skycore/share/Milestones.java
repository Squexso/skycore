package dev.skycore.share;

import java.util.ArrayList;
import java.util.List;

/** Milestone and goal-checkpoint detection. Pure, unit-tested. */
public final class Milestones {
    private Milestones() {}

    /** The highest milestone m with before < m <= after, or null. A jump over several only announces the highest. */
    public static Integer crossed(List<Integer> milestones, int before, int after) {
        Integer best = null;
        for (int m : milestones) if (m > before && m <= after && (best == null || m > best)) best = m;
        return best;
    }

    /** The smallest milestone above the current value, or null. */
    public static Integer next(List<Integer> milestones, int current) {
        Integer best = null;
        for (int m : milestones) if (m > current && (best == null || m < best)) best = m;
        return best;
    }

    public static boolean isMajorSlayer(ShareSettings s, int level, int max) { return level >= s.majorSlayerLevel || level == max; }

    /** A due goal checkpoint. kind = "percent" | "remaining". */
    public record Checkpoint(String kind, int value, String key) {}

    /** Key that identifies one goal cycle: a goal that is deleted and created again starts over. */
    public static String goalKey(String profile, String goalId, long created) { return profile + "|goal|" + goalId + "|" + created; }

    /**
     * Checkpoints of one goal that became due and were not announced yet. At most one percent and one "remaining"
     * checkpoint per call (the most advanced; the ones skipped by a big jump are marked as done so they do not fire
     * later). The first time a goal is seen, whatever it has already passed is marked silently: no retroactive messages.
     * Completion is not handled here (it has its own once-only key, see {@link #completion}).
     *
     * @param level    current level of the goal's stat (-1 = unknown: nothing happens)
     * @param fraction progress towards the target 0..1
     */
    public static List<Checkpoint> checkpoints(ShareSettings s, ShareState st, String goalKey, int target, int level, double fraction) {
        List<Checkpoint> out = new ArrayList<>();
        if (level < 0 || target <= 0) return out;
        boolean first = !st.has(goalKey + "|seen");
        st.fire(goalKey + "|seen");
        if (level >= target) {
            for (int p : s.checkpointPercents) st.fire(goalKey + "|pct|" + p);
            for (int r : s.checkpointRemaining) st.fire(goalKey + "|rem|" + r);
            return out;
        }
        int left = target - level;
        Integer pct = null;
        for (int p : s.checkpointPercents) if (fraction * 100 >= p && (pct == null || p > pct)) pct = p;
        if (pct != null) {
            for (int p : s.checkpointPercents) if (p <= pct) {
                String k = goalKey + "|pct|" + p;
                if (st.fire(k) && p == pct && !first) out.add(new Checkpoint("percent", p, k));
            }
        }
        Integer rem = null;
        for (int r : s.checkpointRemaining) if (left <= r && (rem == null || r < rem)) rem = r;
        if (rem != null) {
            for (int r : s.checkpointRemaining) if (r >= rem) {
                String k = goalKey + "|rem|" + r;
                if (st.fire(k) && r == rem && !first) out.add(new Checkpoint("remaining", r, k));
            }
        }
        return out;
    }

    /** True exactly once per goal cycle. */
    public static boolean completion(ShareState st, String goalKey) { return st.fire(goalKey + "|done"); }
}
