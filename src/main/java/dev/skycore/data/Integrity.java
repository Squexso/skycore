package dev.skycore.data;

import dev.skycore.data.ProfileData.DungeonRun;
import dev.skycore.data.ProfileData.StatProgress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data integrity checker. Finds duplicates, impossible values (negative XP, NaN), level decreases,
 * level/XP conflicts, missing timestamps, references to unknown profiles, corrupted history entries,
 * implausible session totals and inconsistent runs.
 * <p>
 * Rules: nothing is silently deleted, nothing is invented. {@link #check} only reports. {@link #repair}
 * changes only values that are provably invalid (NaN, negative where impossible, exact duplicates, out-of-range
 * confidence) and logs every change in the history repair log. Plausible-but-suspicious values (a level
 * decrease, a missing timestamp) are reported and left alone – the next verified reading will settle them.
 */
public final class Integrity {
    public enum Severity { INFO, WARNING, ERROR }

    /** @param detail translation key suffix ("integrity.d.&lt;detail&gt;"), formatted with {@code args} */
    public record Issue(String check, Severity severity, String profile, String target, String detail, Object[] args, boolean repairable) {}

    public record Report(long t, List<Issue> issues, int profiles, int runs, int events) {
        public long count(Severity s) { return issues.stream().filter(i -> i.severity() == s).count(); }
        public long repairable() { return issues.stream().filter(Issue::repairable).count(); }
        public boolean clean() { return issues.isEmpty(); }
    }

    private static Report last;

    private Integrity() {}

    public static Report last() { return last; }

    /** Check the live data. */
    public static Report checkNow() {
        last = check(Progress.saved(), History.data(), SessionLog.raw());
        return last;
    }

    /** Repair the live data (caller takes a safety backup first). @return number of changes made. */
    public static int repairNow() {
        int n = repair(Progress.saved(), History.data(), SessionLog.raw());
        if (n > 0) { Progress.markChanged(); History.markDirty(); SessionLog.markDirty(); }
        checkNow();
        return n;
    }

    // ------------------------------------------------------------------ checks (pure)
    static Report check(Progress.Saved saved, History.Data hist, SessionLog.Store sessions) {
        List<Issue> out = new ArrayList<>();
        int runs = 0;
        Set<String> keys = saved.profiles.keySet();
        if (!keys.isEmpty() && !keys.contains(saved.current) && !"default".equals(saved.current))
            out.add(new Issue("profile_ref", Severity.WARNING, saved.current, "current", "no_profile_data", new Object[0], false));
        for (var pe : saved.profiles.entrySet()) {
            String prof = pe.getKey();
            ProfileData d = pe.getValue();
            if (d == null) { out.add(new Issue("corrupt_profile", Severity.ERROR, prof, "profile", "empty_entry", new Object[0], true)); continue; }
            for (var se : d.stats.entrySet()) checkStat(out, prof, se.getKey(), se.getValue());
            runs += d.runs.size();
            checkRuns(out, prof, d);
            for (var v : d.values.entrySet()) {
                ProfileData.ValueRecord r = v.getValue();
                if (r == null) { out.add(new Issue("corrupt_value", Severity.ERROR, prof, v.getKey(), "empty_entry", new Object[0], true)); continue; }
                if (!Double.isFinite(r.value)) out.add(new Issue("invalid_number", Severity.ERROR, prof, v.getKey(), "nan", new Object[0], true));
                else if (r.value < 0) out.add(new Issue("negative_value", Severity.ERROR, prof, v.getKey(), "negative", new Object[]{r.value}, true));
                if (r.updated <= 0) out.add(new Issue("missing_timestamp", Severity.INFO, prof, v.getKey(), "no_timestamp", new Object[0], false));
            }
        }
        checkHistory(out, hist, keys);
        checkSessions(out, sessions);
        checkActivities(out, saved, sessions);
        return new Report(System.currentTimeMillis(), out, keys.size(), runs, hist.events.size());
    }

    private static void checkStat(List<Issue> out, String prof, String id, StatProgress p) {
        if (p == null) { out.add(new Issue("corrupt_stat", Severity.ERROR, prof, id, "empty_entry", new Object[0], true)); return; }
        StatDef def = StatDef.get(id);
        if (!Double.isFinite(p.xp) || !Double.isFinite(p.need) || !Double.isFinite(p.percent))
            out.add(new Issue("invalid_number", Severity.ERROR, prof, id, "nan", new Object[0], true));
        if (p.xp < 0) out.add(new Issue("negative_xp", Severity.ERROR, prof, id, "negative", new Object[]{p.xp}, true));
        if (p.level < -1) out.add(new Issue("level_range", Severity.ERROR, prof, id, "level", new Object[]{p.level}, true));
        if (def != null && p.level > def.maxLevel()) out.add(new Issue("level_range", Severity.WARNING, prof, id, "level_above_max", new Object[]{p.level, def.maxLevel()}, false));
        if (p.level >= 0 && p.need > 0 && p.xp > p.need * 1.001 && def != null && p.level < def.maxLevel())
            out.add(new Issue("xp_conflict", Severity.WARNING, prof, id, "xp_exceeds", new Object[]{Math.round(p.xp), Math.round(p.need)}, false));
        if (p.level >= 0 && p.updated <= 0 && p.verifiedAt <= 0)
            out.add(new Issue("missing_timestamp", Severity.INFO, prof, id, "no_timestamp", new Object[0], false));
        int badGains = 0, dupGains = 0, untimed = 0;
        Set<String> seen = new HashSet<>();
        for (ProfileData.Gain g : p.gains) {
            if (g == null || !Double.isFinite(g.amount) || g.amount <= 0) { badGains++; continue; }
            if (g.t <= 0) untimed++;
            if (!seen.add(g.t + "|" + g.amount + "|" + g.tag) && g.t > 0) dupGains++;
        }
        if (badGains > 0) out.add(new Issue("invalid_gain", Severity.ERROR, prof, id, "bad_gains", new Object[]{badGains}, true));
        if (dupGains > 0) out.add(new Issue("duplicate_gain", Severity.WARNING, prof, id, "dup_gains", new Object[]{dupGains}, true));
        if (untimed > 0) out.add(new Issue("missing_timestamp", Severity.INFO, prof, id, "untimed_gains", new Object[]{untimed}, false));
    }

    private static void checkRuns(List<Issue> out, String prof, ProfileData d) {
        Set<String> seen = new HashSet<>();
        Map<String, Integer> completeByFloor = new HashMap<>();
        int dups = 0;
        for (DungeonRun r : d.runs) {
            if (r == null) { out.add(new Issue("corrupt_run", Severity.ERROR, prof, "runs", "empty_entry", new Object[0], true)); continue; }
            if (r.t <= 0) out.add(new Issue("missing_timestamp", Severity.INFO, prof, "runs", "no_timestamp", new Object[0], false));
            if (!Double.isFinite(r.xp) || r.xp < 0) out.add(new Issue("negative_xp", Severity.ERROR, prof, "run " + r.floor, "negative", new Object[]{r.xp}, true));
            if (r.complete() && r.xp == 0) out.add(new Issue("run_inconsistent", Severity.WARNING, prof, "run " + r.floor, "run_no_xp", new Object[0], false));
            if (!r.complete() && r.xp > 0) out.add(new Issue("run_inconsistent", Severity.WARNING, prof, "run " + r.floor, "incomplete_with_xp", new Object[0], false));
            if (r.durationMs < 0 || (r.startedAt > 0 && r.t > 0 && r.startedAt > r.t))
                out.add(new Issue("run_inconsistent", Severity.WARNING, prof, "run " + r.floor, "end_before_start", new Object[0], false));
            if (r.t > 0 && !seen.add(r.t + "|" + r.floor + "|" + r.xp + "|" + r.status)) dups++;
            if (r.complete() && r.floor != null) completeByFloor.merge(r.floor, 1, Integer::sum);
        }
        if (dups > 0) out.add(new Issue("duplicate_run", Severity.WARNING, prof, "runs", "dup_runs", new Object[]{dups}, true));
        for (var e : completeByFloor.entrySet()) {
            int counted = d.floorCompletions.getOrDefault(e.getKey(), 0);
            if (counted < e.getValue())
                out.add(new Issue("run_inconsistent", Severity.WARNING, prof, e.getKey(),
                        "completions_low", new Object[]{counted, e.getValue()}, false));
        }
    }

    private static void checkHistory(List<Issue> out, History.Data h, Set<String> keys) {
        int bad = 0, conf = 0, unknownProfile = 0;
        Set<String> displayNames = new HashSet<>(keys);
        for (String k : keys) { int i = k.indexOf('#'); if (i > 0) displayNames.add(k.substring(0, i)); }
        for (History.Event e : h.events) {
            if (e == null || e.kind == null || e.t <= 0 || !Double.isFinite(e.amount)) { bad++; continue; }
            if (!(e.confidence >= 0 && e.confidence <= 1)) conf++;
            if (e.profile != null && !displayNames.contains(e.profile) && !"Sample".equals(e.profile) && !"default".equals(e.profile)) unknownProfile++;
        }
        if (bad > 0) out.add(new Issue("corrupt_history", Severity.ERROR, null, "events", "damaged_events", new Object[]{bad}, true));
        if (conf > 0) out.add(new Issue("corrupt_history", Severity.WARNING, null, "events", "bad_confidence", new Object[]{conf}, true));
        if (unknownProfile > 0) out.add(new Issue("profile_ref", Severity.INFO, null, "events", "unknown_profile_events", new Object[]{unknownProfile}, false));
        // level decreases between daily snapshots (per profile & stat)
        Map<String, Double> prev = new HashMap<>();
        Set<String> reported = new HashSet<>();
        for (var day : h.snapshots.entrySet()) {
            if (day.getValue() == null) continue;
            for (var pe : day.getValue().entrySet()) {
                if (pe.getValue() == null) continue;
                for (var se : pe.getValue().entrySet()) {
                    String k = pe.getKey() + "|" + se.getKey();
                    Double before = prev.put(k, se.getValue());
                    if (before != null && se.getValue() != null && Math.floor(se.getValue()) < Math.floor(before) && reported.add(k))
                        out.add(new Issue("level_decrease", Severity.WARNING, pe.getKey(), se.getKey(),
                                "level_decrease", new Object[]{(int) Math.floor(before), (int) Math.floor(se.getValue()), day.getKey()}, false));
                }
            }
        }
        for (History.SessionRecord s : h.sessions)
            if (s != null && s.end > 0 && s.start > s.end) out.add(new Issue("session_inconsistent", Severity.WARNING, s.profile, "session", "session_order", new Object[0], false));
    }

    private static void checkSessions(List<Issue> out, SessionLog.Store store) {
        int over = 0, neg = 0;
        for (SessionLog.Data sessions : store.profiles.values())
        for (SessionLog.Bucket b : sessions.buckets.values()) {
            if (b == null) { neg++; continue; }
            if (b.playSeconds > 600) over++;
            if (b.playSeconds < 0) neg++;
            if (b.xp != null) for (Double v : b.xp.values()) if (v == null || !Double.isFinite(v) || v < 0) neg++;
        }
        if (over > 0) out.add(new Issue("session_totals", Severity.WARNING, null, "sessions", "session_over", new Object[]{over}, true));
        if (neg > 0) out.add(new Issue("session_totals", Severity.ERROR, null, "sessions", "session_invalid", new Object[]{neg}, true));
    }

    /**
     * Activity records and aggregates: the same event in two profiles, statistics referring to another profile's
     * events, broken aggregates, run counters disagreeing with stored runs, classifications that contradict
     * themselves, session totals below their own blocks, session data of profiles that no longer exist.
     */
    private static void checkActivities(List<Issue> out, Progress.Saved saved, SessionLog.Store sessions) {
        Map<String, String> owner = new HashMap<>();
        Set<String> reported = new HashSet<>();
        for (var pe : saved.profiles.entrySet()) {
            ProfileData d = pe.getValue();
            if (d == null) continue;
            for (DungeonRun r : d.runs) if (r != null && r.id != null) {
                String prev = owner.putIfAbsent(r.id, pe.getKey());
                if (prev != null && !prev.equals(pe.getKey()) && reported.add(r.id))
                    out.add(new Issue("profile_conflict", Severity.WARNING, pe.getKey(), "runs", "same_event_two_profiles", new Object[]{prev}, false));
            }
            for (Activity.SlayerQuest q : d.slayerQuests) if (q != null && q.id != null) {
                String prev = owner.putIfAbsent(q.id, pe.getKey());
                if (prev != null && !prev.equals(pe.getKey()) && reported.add(q.id))
                    out.add(new Issue("profile_conflict", Severity.WARNING, pe.getKey(), "quests", "same_event_two_profiles", new Object[]{prev}, false));
                if (q.classified() && q.key() != null && !q.key().equals(q.classification.key))
                    out.add(new Issue("classification_conflict", Severity.ERROR, pe.getKey(), "quests", "key_mismatch", new Object[]{q.key(), q.classification.key}, false));
            }
        }
        for (var pe : saved.profiles.entrySet()) {
            ProfileData d = pe.getValue();
            if (d == null) continue;
            Map<String, Integer> classifiedRuns = new HashMap<>();
            for (DungeonRun r : d.runs) if (r != null && r.complete() && r.classified()) classifiedRuns.merge(r.floor, 1, Integer::sum);
            for (var e : d.floorStats.entrySet()) {
                checkAggregate(out, pe.getKey(), e.getKey(), e.getValue());
                int stored = classifiedRuns.getOrDefault(e.getKey(), 0);
                if (e.getValue() != null && e.getValue().count < stored)
                    out.add(new Issue("run_counts", Severity.WARNING, pe.getKey(), e.getKey(), "stats_below_runs", new Object[]{e.getValue().count, stored}, false));
                checkReferences(out, pe.getKey(), e.getKey(), e.getValue(), owner);
            }
            for (var e : d.slayerStats.entrySet()) {
                checkAggregate(out, pe.getKey(), e.getKey(), e.getValue());
                checkReferences(out, pe.getKey(), e.getKey(), e.getValue(), owner);
            }
        }
        for (var e : sessions.profiles.entrySet()) {
            SessionLog.Data d = e.getValue();
            long sum = 0;
            for (SessionLog.Bucket b : d.buckets.values()) if (b != null) sum += Math.max(0, b.playSeconds);
            if (d.totalPlaySeconds < sum)
                out.add(new Issue("session_totals", Severity.WARNING, e.getKey(), "sessions", "total_below_blocks", new Object[]{d.totalPlaySeconds, sum}, true));
            if (!e.getKey().equals(SessionLog.UNASSIGNED) && !saved.profiles.containsKey(e.getKey()))
                out.add(new Issue("profile_ref", Severity.INFO, e.getKey(), "sessions", "sessions_unknown_profile", new Object[0], false));
        }
    }

    private static void checkAggregate(List<Issue> out, String prof, String key, ActivityStats a) {
        if (a == null) return;
        double[] r = a.recentXp();
        double sum = java.util.Arrays.stream(r).sum();
        long histSum = a.hist.values().stream().mapToLong(Integer::longValue).sum();
        long xpBelowOne = java.util.Arrays.stream(r).filter(x -> x < 1).count();
        boolean broken = a.count < a.recent.size() || a.xpCount < r.length || a.totalXp + 0.5 < sum
                || (a.min >= 0 && a.max >= 0 && a.min > a.max)
                || (a.xpCount > 0 && (a.average() < a.min - 0.5 || a.average() > a.max + 0.5))
                || histSum + xpBelowOne < Math.min(a.xpCount, r.length) || !Double.isFinite(a.totalXp);
        if (broken) out.add(new Issue("aggregate_broken", Severity.ERROR, prof, key, "aggregate_broken", new Object[0], a.xpCount == r.length && a.count == a.recent.size()));
    }

    private static void checkReferences(List<Issue> out, String prof, String key, ActivityStats a, Map<String, String> owner) {
        if (a == null) return;
        for (ActivityStats.Sample s : a.recent) {
            String o = s.id == null ? null : owner.get(s.id);
            if (o != null && !o.equals(prof)) {
                out.add(new Issue("invalid_reference", Severity.WARNING, prof, key, "reference_other_profile", new Object[]{o}, false));
                return;
            }
        }
    }

    /** Rebuild aggregates only when every activity is still stored in detail; raise session totals to their blocks. */
    private static int repairActivities(Progress.Saved saved, History.Data hist, SessionLog.Store store) {
        int n = 0;
        for (var pe : saved.profiles.entrySet()) {
            ProfileData d = pe.getValue();
            if (d == null) continue;
            for (var maps : List.of(d.floorStats, d.slayerStats))
                for (var e : maps.entrySet()) {
                    ActivityStats a = e.getValue();
                    if (a == null) continue;
                    List<Issue> tmp = new ArrayList<>();
                    checkAggregate(tmp, pe.getKey(), e.getKey(), a);
                    if (tmp.isEmpty() || !tmp.get(0).repairable()) continue;
                    ActivityStats rebuilt = new ActivityStats();
                    for (ActivityStats.Sample s : a.recent) rebuilt.add(s.t, s.xp, s.durationMs, s.id);
                    rebuilt.tableMatches = a.tableMatches;
                    rebuilt.tableMismatches = a.tableMismatches;
                    rebuilt.lastMismatchExpected = a.lastMismatchExpected;
                    rebuilt.lastMismatchObserved = a.lastMismatchObserved;
                    String before = a.count + " runs / " + Math.round(a.totalXp) + " XP";
                    maps.put(e.getKey(), rebuilt);
                    hist(hist, "aggregate_broken", pe.getKey(), e.getKey(), before, rebuilt.count + " runs / " + Math.round(rebuilt.totalXp) + " XP (rebuilt from detailed records)");
                    n++;
                }
        }
        for (var e : store.profiles.entrySet()) {
            SessionLog.Data d = e.getValue();
            long sum = 0;
            for (SessionLog.Bucket b : d.buckets.values()) if (b != null) sum += Math.max(0, b.playSeconds);
            if (d.totalPlaySeconds < sum) {
                hist(hist, "session_totals", e.getKey(), "total", String.valueOf(d.totalPlaySeconds), String.valueOf(sum));
                d.totalPlaySeconds = sum;
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ repairs (pure, logged)
    static int repair(Progress.Saved saved, History.Data hist, SessionLog.Store store) {
        int n = 0;
        var it = saved.profiles.entrySet().iterator();
        while (it.hasNext()) {
            var pe = it.next();
            if (pe.getValue() == null) { hist(hist, "corrupt_profile", pe.getKey(), "profile", "null", "removed empty entry"); it.remove(); n++; }
        }
        for (var pe : saved.profiles.entrySet()) {
            String prof = pe.getKey();
            ProfileData d = pe.getValue();
            var si = d.stats.entrySet().iterator();
            while (si.hasNext()) {
                var se = si.next();
                StatProgress p = se.getValue();
                if (p == null) { hist(hist, "corrupt_stat", prof, se.getKey(), "null", "removed empty entry"); si.remove(); n++; continue; }
                if (!Double.isFinite(p.xp) || p.xp < 0) {
                    // the XP inside the level is unknown – keep the level, never guess a number
                    hist(hist, p.xp < 0 ? "negative_xp" : "invalid_number", prof, se.getKey(), String.valueOf(p.xp), "0 (marked unknown)");
                    p.xp = 0;
                    p.xpUnknown = true;
                    n++;
                }
                if (!Double.isFinite(p.need)) { hist(hist, "invalid_number", prof, se.getKey() + ".need", String.valueOf(p.need), "-1 (unknown)"); p.need = -1; n++; }
                if (!Double.isFinite(p.percent)) { hist(hist, "invalid_number", prof, se.getKey() + ".percent", String.valueOf(p.percent), "-1 (unknown)"); p.percent = -1; n++; }
                if (p.level < -1) { hist(hist, "level_range", prof, se.getKey(), String.valueOf(p.level), "-1 (unknown)"); p.level = -1; n++; }
                int before = p.gains.size();
                p.gains.removeIf(g -> g == null || !Double.isFinite(g.amount) || g.amount <= 0);
                Set<String> seen = new HashSet<>();
                p.gains.removeIf(g -> g.t > 0 && !seen.add(g.t + "|" + g.amount + "|" + g.tag));
                if (p.gains.size() != before) { hist(hist, "invalid_gain", prof, se.getKey(), before + " gains", p.gains.size() + " gains"); n++; }
            }
            int rb = d.runs.size();
            d.runs.removeIf(java.util.Objects::isNull);
            Set<String> seen = new HashSet<>();
            d.runs.removeIf(r -> r.t > 0 && !seen.add(r.t + "|" + r.floor + "|" + r.xp + "|" + r.status));
            for (DungeonRun r : d.runs) if (!Double.isFinite(r.xp) || r.xp < 0) {
                hist(hist, "negative_xp", prof, "run " + r.floor, String.valueOf(r.xp), "0");
                r.xp = 0;
                n++;
            }
            if (d.runs.size() != rb) { hist(hist, "duplicate_run", prof, "runs", rb + " runs", d.runs.size() + " runs"); n++; }
            var vi = d.values.entrySet().iterator();
            while (vi.hasNext()) {
                var ve = vi.next();
                if (ve.getValue() == null) { hist(hist, "corrupt_value", prof, ve.getKey(), "null", "removed empty entry"); vi.remove(); n++; continue; }
                ProfileData.ValueRecord r = ve.getValue();
                if (!Double.isFinite(r.value) || r.value < 0) {
                    // an impossible reading is dropped; it will be read again from the game
                    hist(hist, "invalid_number", prof, ve.getKey(), String.valueOf(r.value), "removed (will be read again)");
                    vi.remove();
                    n++;
                }
            }
        }
        int eb = hist.events.size();
        hist.events.removeIf(e -> e == null || e.kind == null || e.t <= 0 || !Double.isFinite(e.amount));
        if (hist.events.size() != eb) { hist(hist, "corrupt_history", null, "events", eb + " events", hist.events.size() + " events"); n++; }
        int fixedConf = 0;
        for (History.Event e : hist.events) if (!(e.confidence >= 0 && e.confidence <= 1)) { e.confidence = Math.max(0, Math.min(1, Double.isNaN(e.confidence) ? 0 : e.confidence)); fixedConf++; }
        if (fixedConf > 0) { hist(hist, "corrupt_history", null, "events", fixedConf + " confidences out of range", "clamped to 0..1"); n++; }
        int clamped = 0;
        for (SessionLog.Data sessions : store.profiles.values()) {
        var bi = sessions.buckets.entrySet().iterator();
        while (bi.hasNext()) {
            var be = bi.next();
            SessionLog.Bucket b = be.getValue();
            if (b == null) { bi.remove(); clamped++; continue; }
            if (b.playSeconds > 600 || b.playSeconds < 0) { b.playSeconds = Math.max(0, Math.min(600, b.playSeconds)); clamped++; }
            if (b.xp != null && b.xp.values().removeIf(v -> v == null || !Double.isFinite(v) || v < 0)) clamped++;
        }
        }
        if (clamped > 0) { hist(hist, "session_totals", null, "sessions", clamped + " invalid blocks", "clamped / invalid values removed"); n++; }
        n += repairActivities(saved, hist, store);
        return n;
    }

    private static void hist(History.Data h, String check, String profile, String target, String before, String after) {
        History.Repair r = new History.Repair();
        r.t = System.currentTimeMillis();
        r.check = check;
        r.profile = profile;
        r.target = target;
        r.before = before;
        r.after = after;
        h.repairs.add(r);
        while (h.repairs.size() > 300) h.repairs.remove(0);
    }
}
