package dev.skycore.data;

import dev.skycore.core.Storage;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * "Assign to profile…": moves data recorded before the profile could be identified (storage key "default" in
 * progress.json, "unassigned" sessions, history entries labelled "default") into a profile the user chooses.
 * <ul>
 *   <li>never automatic – only on explicit confirmation after a preview</li>
 *   <li>timestamps, XP, event ids, confidence and sources are copied unchanged</li>
 *   <li>events already present in the target (same id) are skipped; the target's current levels always win</li>
 *   <li>the merge runs on a copy, which is validated before it replaces the target – a failure changes nothing</li>
 *   <li>after success the unassigned data is removed (so it cannot be assigned twice) and the move is logged</li>
 * </ul>
 */
public final class ProfileAssign {
    public static final String SOURCE = "default";

    public record Preview(boolean possible, String problem, int stats, int statsKept, int runs, int runsDuplicate, int quests,
                          int questsDuplicate, int values, int collections, int sessionBlocks, int sessionSpans, long playSeconds, int historyEvents) {
        static Preview impossible(String problem) { return new Preview(false, problem, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }

        public boolean empty() { return stats + runs + quests + values + collections + sessionBlocks + sessionSpans + historyEvents == 0; }
    }

    private ProfileAssign() {}

    public static boolean hasUnassigned() { return hasUnassigned(Progress.saved(), SessionLog.raw(), History.data()); }

    static boolean hasUnassigned(Progress.Saved s, SessionLog.Store st, History.Data h) {
        return !preview(s, st, h, null, true).empty();
    }

    public static Preview preview(String target) { return preview(Progress.saved(), SessionLog.raw(), History.data(), target, false); }

    static Preview preview(Progress.Saved s, SessionLog.Store st, History.Data h, String target, boolean any) {
        if (!any) {
            if (target == null || target.equals(SOURCE)) return Preview.impossible("assign.problem.no_target");
            if (!s.profiles.containsKey(target)) return Preview.impossible("assign.problem.target_missing");
        }
        ProfileData src = s.profiles.get(SOURCE);
        ProfileData dst = any ? null : s.profiles.get(target);
        int stats = 0, kept = 0, runs = 0, runDup = 0, quests = 0, questDup = 0, values = 0, colls = 0;
        if (src != null) {
            for (var e : src.stats.entrySet()) {
                if (e.getValue() == null || (!e.getValue().known() && e.getValue().gains.isEmpty())) continue;
                if (dst != null && dst.peek(e.getKey()).known() && e.getValue().known()) kept++; else stats++;
            }
            Set<String> ids = runIds(dst);
            for (ProfileData.DungeonRun r : src.runs) if (r != null) { if (ids.contains(id(r))) runDup++; else runs++; }
            Set<String> qids = questIds(dst);
            for (Activity.SlayerQuest q : src.slayerQuests) if (q != null) { if (qids.contains(q.id)) questDup++; else quests++; }
            for (String k : src.values.keySet()) if (dst == null || !dst.values.containsKey(k)) values++;
            for (String k : src.collections.keySet()) if (dst == null || !dst.collections.containsKey(k)) colls++;
        }
        SessionLog.Data u = st.profiles.get(SessionLog.UNASSIGNED);
        int blocks = u == null ? 0 : u.buckets.size(), spans = u == null ? 0 : u.spans.size();
        long play = u == null ? 0 : u.totalPlaySeconds;
        int events = 0;
        for (History.Event e : h.events) if (e != null && unassignedLabel(e.profile)) events++;
        return new Preview(true, null, stats, kept, runs, runDup, quests, questDup, values, colls, blocks, spans, play, events);
    }

    private static boolean unassignedLabel(String p) { return p == null || SOURCE.equals(p); }

    private static String id(ProfileData.DungeonRun r) { return r.id != null ? r.id : r.t + "|" + r.floor + "|" + r.xp; }

    private static Set<String> runIds(ProfileData d) {
        Set<String> s = new HashSet<>();
        if (d != null) for (ProfileData.DungeonRun r : d.runs) if (r != null) s.add(id(r));
        return s;
    }

    private static Set<String> questIds(ProfileData d) {
        Set<String> s = new HashSet<>();
        if (d != null) for (Activity.SlayerQuest q : d.slayerQuests) if (q != null) s.add(q.id);
        return s;
    }

    /** Live data: caller has taken a safety backup. @return number of moved items, or -1 if refused */
    public static int applyNow(String target) {
        int n = apply(Progress.saved(), SessionLog.raw(), History.data(), target);
        if (n >= 0) { Progress.markChanged(); SessionLog.markDirty(); History.markDirty(); }
        return n;
    }

    static int apply(Progress.Saved s, SessionLog.Store st, History.Data h, String target) {
        Preview p = preview(s, st, h, target, false);
        if (!p.possible() || p.empty()) return -1;
        ProfileData orig = s.profiles.get(target);
        ProfileData src = s.profiles.get(SOURCE);
        // work on a copy of the target; the original stays untouched until validation passed
        ProfileData merged = Storage.GSON.fromJson(Storage.GSON.toJson(orig), ProfileData.class);
        Progress.repairData(merged);
        int n = 0;
        if (src != null) n += mergeProfile(merged, Storage.GSON.fromJson(Storage.GSON.toJson(src), ProfileData.class));
        if (!valid(merged, orig)) return -1;
        s.profiles.put(target, merged);
        s.profiles.remove(SOURCE);
        if (SOURCE.equals(s.current)) s.current = target;
        // sessions
        SessionLog.Data u = st.profiles.remove(SessionLog.UNASSIGNED);
        if (u != null) {
            SessionLog.Data d = st.profiles.computeIfAbsent(target, k -> new SessionLog.Data());
            for (var b : u.buckets.entrySet()) SessionLog.mergeBucket(d.buckets, b.getKey(), b.getValue());
            Set<Long> starts = new HashSet<>();
            for (SessionLog.Span sp : d.spans) starts.add(sp.start);
            for (SessionLog.Span sp : u.spans) if (starts.add(sp.start)) d.spans.add(sp);
            d.spans.sort((a, b) -> Long.compare(a.start, b.start));
            d.totalPlaySeconds += u.totalPlaySeconds;
            n += u.buckets.size() + u.spans.size();
        }
        // history labels (timestamps, amounts, rules and confidence are kept)
        String name = merged.name != null ? merged.name : target;
        for (History.Event e : h.events) if (e != null && unassignedLabel(e.profile)) { e.profile = name; n++; }
        for (History.Correction c : h.corrections) if (c != null && unassignedLabel(c.profile)) c.profile = name;
        History.Repair r = new History.Repair();
        r.t = System.currentTimeMillis();
        r.check = "assigned_unassigned";
        r.profile = target;
        r.target = "profile";
        r.before = "unassigned";
        r.after = p.stats() + " stats, " + p.runs() + " runs (" + p.runsDuplicate() + " duplicates skipped), " + p.quests() + " quests, "
                + p.sessionBlocks() + " session blocks, " + p.historyEvents() + " history events → " + name;
        h.repairs.add(r);
        return n;
    }

    /** Copy everything the target does not have yet. Current values of the target always win. */
    static int mergeProfile(ProfileData dst, ProfileData src) {
        Progress.repairData(src);
        int n = 0;
        for (var e : src.stats.entrySet()) {
            ProfileData.StatProgress sp = e.getValue();
            if (sp == null) continue;
            ProfileData.StatProgress dp = dst.stats.get(e.getKey());
            if (dp == null || !dp.known() && dp.gains.isEmpty()) { dst.stats.put(e.getKey(), sp); n++; continue; }
            // both exist: keep the target's level/XP, add gains that are not already there
            Set<String> have = new HashSet<>();
            for (ProfileData.Gain g : dp.gains) have.add(g.t + "|" + g.amount);
            for (ProfileData.Gain g : sp.gains) if (have.add(g.t + "|" + g.amount)) dp.gains.add(g);
            dp.gains.sort((a, b) -> Long.compare(a.t, b.t));
            while (dp.gains.size() > 60) dp.gains.remove(0);
            dp.count += sp.count;
        }
        Set<String> ids = runIds(dst);
        Map<String, Integer> dupCompleted = new java.util.HashMap<>();
        for (ProfileData.DungeonRun r : src.runs) {
            if (ids.add(id(r))) { dst.runs.add(r); n++; }
            else if (r.complete() && r.classified()) dupCompleted.merge(r.floor, 1, Integer::sum);
        }
        dst.runs.sort((a, b) -> Long.compare(b.t, a.t));
        Progress.pruneRuns(dst);
        for (var e : src.floorCompletions.entrySet())
            dst.floorCompletions.merge(e.getKey(), Math.max(0, e.getValue() - dupCompleted.getOrDefault(e.getKey(), 0)), Integer::sum);
        for (var e : src.floorStats.entrySet()) dst.floorStats.computeIfAbsent(e.getKey(), k -> new ActivityStats()).mergeFrom(e.getValue());
        Set<String> qids = questIds(dst);
        for (Activity.SlayerQuest q : src.slayerQuests) if (qids.add(q.id)) { dst.slayerQuests.add(q); n++; }
        dst.slayerQuests.sort((a, b) -> Long.compare(b.t, a.t));
        Progress.pruneQuests(dst);
        for (var e : src.slayerStats.entrySet()) dst.slayerStats.computeIfAbsent(e.getKey(), k -> new ActivityStats()).mergeFrom(e.getValue());
        for (var e : src.values.entrySet()) if (dst.values.putIfAbsent(e.getKey(), e.getValue()) == null) n++;
        for (var e : src.collections.entrySet()) if (dst.collections.putIfAbsent(e.getKey(), e.getValue()) == null) n++;
        for (var e : src.collectionProgress.entrySet()) dst.collectionProgress.putIfAbsent(e.getKey(), e.getValue());
        dst.activity.addAll(src.activity);
        dst.activity.sort((a, b) -> Long.compare(b.t, a.t));
        while (dst.activity.size() > 60) dst.activity.remove(dst.activity.size() - 1);
        dst.bestRunXp = Math.max(dst.bestRunXp, src.bestRunXp);
        dst.notified.addAll(src.notified);
        return n;
    }

    /** The merged copy must not lose anything the target had and must contain no impossible numbers. */
    static boolean valid(ProfileData merged, ProfileData orig) {
        // detailed runs per floor never shrink below what the target had (up to the 50-per-floor limit)
        Map<String, Integer> oc = new java.util.HashMap<>(), mc = new java.util.HashMap<>();
        for (ProfileData.DungeonRun r : orig.runs) oc.merge(r.floor == null ? "?" : r.floor, 1, Integer::sum);
        for (ProfileData.DungeonRun r : merged.runs) mc.merge(r.floor == null ? "?" : r.floor, 1, Integer::sum);
        for (var e : oc.entrySet()) if (mc.getOrDefault(e.getKey(), 0) < Math.min(ActivityStats.DETAILED, e.getValue())) return false;
        for (var e : orig.stats.entrySet()) {
            ProfileData.StatProgress a = e.getValue(), b = merged.stats.get(e.getKey());
            if (a != null && a.known() && (b == null || b.level != a.level || b.xp != a.xp)) return false;
        }
        for (ProfileData.StatProgress p : merged.stats.values()) if (p != null && (!Double.isFinite(p.xp) || p.xp < 0)) return false;
        for (var e : orig.floorStats.entrySet()) {
            ActivityStats m = merged.floorStats.get(e.getKey());
            if (m == null || m.count < e.getValue().count) return false;
        }
        return true;
    }
}
