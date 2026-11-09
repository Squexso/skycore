package dev.skycore.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves uncertain slayer quests and dungeon runs when reliable evidence arrives later.
 * <p>
 * Evidence used:
 * <ul>
 *   <li><b>Counters in menus the player opens</b> (slayer boss kills per tier, completions per floor). Between two
 *       readings, the increase per tier/floor minus the confirmed activities recorded in that interval leaves a
 *       residual. Only if <i>exactly one</i> tier/floor has a residual, the residual equals the number of uncertain
 *       activities of that family in the interval, and no uncertain activity's XP-based candidates contradict it,
 *       are they resolved. A negative residual (a confirmed record the counter does not support) or any other
 *       mismatch keeps everything unchanged.</li>
 *   <li><b>Late evidence for the same activity</b> (e.g. sidebar tier shown right after the quest ended, run summary
 *       floor arriving after the XP line) – handled by the tracker, applied through {@link #resolve}.</li>
 * </ul>
 * Confirmed activities are never changed. Every resolution keeps the previous classification in the audit trail
 * and is written to the correction log.
 */
public final class Reconciler {
    private Reconciler() {}

    /** A previously stored counter reading. */
    public static class CountSnapshot {
        public long t;
        public Map<String, Long> counts = new LinkedHashMap<>();
    }

    public record Uncertain(String id, long t, List<String> candidates) {}

    /** @param reason translation key suffix: resolved, conflict, ambiguous, baseline, no_change */
    public record Outcome(Map<String, String> resolved, String reason, Map<String, Long> residual) {}

    /**
     * Pure counter reconciliation (unit-tested).
     *
     * @param confirmedInWindow key → confirmed activities recorded between the two readings
     * @param uncertainInWindow uncertain activities of the same family between the two readings
     */
    public static Outcome byCounts(Map<String, Long> before, Map<String, Long> after, Map<String, Long> confirmedInWindow, List<Uncertain> uncertainInWindow) {
        Map<String, Long> residual = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>(after.keySet());
        keys.addAll(confirmedInWindow.keySet());
        long total = 0;
        boolean conflict = false;
        for (String k : keys) {
            if (!before.containsKey(k) || !after.containsKey(k)) {
                // a counter we cannot compare: only acceptable if nothing happened on it
                if (confirmedInWindow.getOrDefault(k, 0L) > 0) conflict = true;
                continue;
            }
            long r = after.get(k) - before.get(k) - confirmedInWindow.getOrDefault(k, 0L);
            if (r < 0) conflict = true;
            if (r != 0) residual.put(k, r);
            total += Math.max(0, r);
        }
        if (conflict) return new Outcome(Map.of(), "conflict", residual);
        if (uncertainInWindow.isEmpty()) return new Outcome(Map.of(), total == 0 ? "no_change" : "ambiguous", residual);
        List<String> positive = new ArrayList<>();
        for (var e : residual.entrySet()) if (e.getValue() > 0) positive.add(e.getKey());
        if (positive.size() != 1 || total != uncertainInWindow.size()) return new Outcome(Map.of(), "ambiguous", residual);
        String key = positive.get(0);
        for (Uncertain u : uncertainInWindow)
            if (!u.candidates().isEmpty() && !u.candidates().contains(key)) return new Outcome(Map.of(), "conflict", residual);
        Map<String, String> out = new LinkedHashMap<>();
        for (Uncertain u : uncertainInWindow) out.put(u.id(), key);
        return new Outcome(out, "resolved", residual);
    }

    // ------------------------------------------------------------------ applying (live data)
    /**
     * Resolve one uncertain quest. Refuses confirmed quests and keys that contradict the quest's known type or
     * candidates. @return true if changed
     */
    public static boolean resolveQuest(ProfileData d, String id, String key, double confidence, String evidence, String why) {
        for (Activity.SlayerQuest q : d.slayerQuests) {
            if (!id.equals(q.id)) continue;
            if (q.classified()) return false;
            int i = key.indexOf(':');
            Activity.Slayer s = Activity.Slayer.byId(key.substring(0, i));
            int tier = Integer.parseInt(key.substring(i + 1));
            if (s == null || (q.type != null && !q.type.equals(s.id))) return false;
            if (!q.classification.candidates.isEmpty() && !q.classification.candidates.contains(key)) return false;
            String before = describe(q.classification);
            q.type = s.id;
            q.tier = tier;
            mark(q.classification, key, confidence, evidence, why, before);
            q.calc = Rewards.slayer(s, tier, q.mayor, q.calc.observed);
            if ("complete".equals(q.status)) {
                ActivityStats st = d.slayerStats.computeIfAbsent(key, k -> new ActivityStats());
                st.add(q.t, q.calc.observed, q.durationMs, q.id);
                if (q.calc.complete) st.tableCheck(q.calc.observed, q.calc.expected);
            }
            History.repair("auto_resolved", d.name, q.id, before, key + " · " + why);
            return true;
        }
        return false;
    }

    /** Resolve one uncertain run (only runs whose floor was simply missing or ambiguous between candidates). */
    public static boolean resolveRun(ProfileData d, String id, String floor, double confidence, String evidence, String why) {
        if (!Activity.isFloor(floor)) return false;
        for (ProfileData.DungeonRun r : d.runs) {
            if (!id.equals(r.id)) continue;
            if (r.classified()) return false;
            Activity.Classification c = r.classification == null ? new Activity.Classification() : r.classification;
            if (!c.candidates.isEmpty() && !c.candidates.contains(floor)) return false;
            String before = describe(c);
            r.classification = c;
            r.floor = floor;
            mark(c, floor, confidence, evidence, why, before);
            if (r.complete()) {
                d.floorStats.computeIfAbsent(floor, k -> new ActivityStats()).add(r.t, r.xp, r.durationMs, r.id);
                d.floorCompletions.merge(floor, 1, Integer::sum);
            }
            History.repair("auto_resolved", d.name, r.id, before, floor + " · " + why);
            return true;
        }
        return false;
    }

    private static void mark(Activity.Classification c, String key, double confidence, String evidence, String why, String before) {
        c.history.add(System.currentTimeMillis() + "|" + before + "|" + key + "|" + why);
        c.key = key;
        c.confidence = confidence;
        c.confirmed = true;
        c.autoResolved = true;
        c.evidence.add(evidence + "|" + key);
        c.missing.clear();
    }

    private static String describe(Activity.Classification c) {
        return c == null || c.key == null ? "uncertain" : c.key + (c.confirmed ? "" : " (unconfirmed)");
    }
}
