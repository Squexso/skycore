package dev.skycore.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.skycore.core.Storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merge import: brings data from a backup into the current database without overwriting anything.
 * <ul>
 *   <li>profiles that do not exist yet are added completely</li>
 *   <li>existing profiles only gain runs that are not already stored (same time, floor, XP and status = duplicate);
 *       current levels, XP and values always win – an import can never move progress backwards</li>
 *   <li>a profile with the same name but a different owning account is added separately, never merged</li>
 *   <li>session blocks, goals and history events are added only where missing</li>
 * </ul>
 * Settings and HUD layouts are not merged (a full restore does that).
 */
public final class ImportMerge {
    public enum ProfileAction { ADD, MERGE, ADD_SEPARATE }

    public record ProfilePlan(String key, String targetKey, String name, ProfileAction action, int runsInFile, int newRuns, int duplicateRuns, int stats) {}

    public record Preview(boolean valid, String problem, List<ProfilePlan> profiles, int newSessionBlocks, int existingSessionBlocks,
                          int newGoals, int newEvents, int duplicateEvents) {
        public static Preview invalid(String problem) { return new Preview(false, problem, List.of(), 0, 0, 0, 0, 0); }

        public int totalNew() {
            int n = newSessionBlocks + newGoals + newEvents;
            for (ProfilePlan p : profiles) n += p.action() == ProfileAction.MERGE ? p.newRuns() : 1;
            return n;
        }
    }

    private ImportMerge() {}

    // ------------------------------------------------------------------ preview (no changes)
    public static Preview preview(JsonObject sections) {
        return preview(sections, Progress.saved(), SessionLog.raw(), Goals.raw(), History.data());
    }

    static Preview preview(JsonObject sections, Progress.Saved current, SessionLog.Store sessions, List<Goals.Goal> goals, History.Data hist) {
        if (sections == null) return Preview.invalid("no data sections");
        List<ProfilePlan> plans = new ArrayList<>();
        Progress.Saved in = progress(sections);
        if (in != null) {
            Map<String, ProfileData> existing = new java.util.LinkedHashMap<>(current.profiles);
            for (var e : in.profiles.entrySet()) {
                ProfileData d = e.getValue();
                if (d == null) continue;
                ProfilePlan plan = plan(e.getKey(), d, existing);
                plans.add(plan);
                if (plan.action() != ProfileAction.MERGE) existing.put(plan.targetKey(), d); // later entries see it
            }
        }
        int newBlocks = 0, oldBlocks = 0;
        SessionLog.Store s = sessions(sections);
        if (s != null) for (var pe : s.profiles.entrySet()) {
            String key = targetFor(pe.getKey(), plans);
            SessionLog.Data cur = sessions.profiles.get(key);
            for (Long k : pe.getValue().buckets.keySet()) { if (cur != null && cur.buckets.containsKey(k)) oldBlocks++; else newBlocks++; }
        }
        int newGoals = 0;
        List<Goals.Goal> g = goals(sections);
        if (g != null) {
            Set<String> ids = new HashSet<>();
            for (Goals.Goal x : goals) ids.add(x.id);
            for (Goals.Goal x : g) if (x != null && x.id != null && !ids.contains(x.id)) newGoals++;
        }
        int newEv = 0, dupEv = 0;
        History.Data h = history(sections);
        if (h != null) {
            Set<String> keys = new HashSet<>();
            for (History.Event e : hist.events) keys.add(eventKey(e));
            for (History.Event e : h.events) if (e != null) { if (keys.contains(eventKey(e))) dupEv++; else newEv++; }
        }
        return new Preview(true, null, plans, newBlocks, oldBlocks, newGoals, newEv, dupEv);
    }

    private static ProfilePlan plan(String key, ProfileData d, Map<String, ProfileData> existing) {
        String name = d.name != null ? d.name : key;
        int runs = d.runs == null ? 0 : d.runs.size();
        int stats = d.stats == null ? 0 : d.stats.size();
        // where this profile belongs in the current database: same name + same (or unknown) account = same profile
        String target = d.owner == null ? key : Progress.resolveKey(existing, name, d.owner);
        ProfileData cur = existing.get(target);
        if (cur != null && d.owner != null && cur.owner != null && !d.owner.equals(cur.owner)) cur = null; // never merge across accounts
        if (cur == null) {
            boolean clash = existing.containsKey(target) || (!target.equals(name) && existing.containsKey(name));
            if (existing.containsKey(target)) target = target + "#imported";
            return new ProfilePlan(key, target, name, clash ? ProfileAction.ADD_SEPARATE : ProfileAction.ADD, runs, runs, 0, stats);
        }
        Set<String> have = new HashSet<>();
        for (ProfileData.DungeonRun r : cur.runs) if (r != null) have.add(runKey(r));
        int dup = 0;
        if (d.runs != null) for (ProfileData.DungeonRun r : d.runs) if (r != null && have.contains(runKey(r))) dup++;
        return new ProfilePlan(key, target, name, ProfileAction.MERGE, runs, runs - dup, dup, stats);
    }

    // ------------------------------------------------------------------ apply
    /** @return number of items added */
    public static int apply(JsonObject sections) {
        int n = apply(sections, Progress.saved(), SessionLog.raw(), Goals.raw(), History.data());
        Progress.markChanged();
        SessionLog.markDirty();
        Goals.markDirty();
        History.markDirty();
        return n;
    }

    static int apply(JsonObject sections, Progress.Saved current, SessionLog.Store sessions, List<Goals.Goal> goals, History.Data hist) {
        int n = 0;
        List<ProfilePlan> plans = new ArrayList<>();
        Progress.Saved in = progress(sections);
        if (in != null) {
            for (var e : in.profiles.entrySet()) {
                ProfileData d = e.getValue();
                if (d == null) continue;
                ProfilePlan plan = plan(e.getKey(), d, current.profiles);
                plans.add(plan);
                switch (plan.action()) {
                    case ADD, ADD_SEPARATE -> {
                        if (d.name == null) d.name = e.getKey();
                        current.profiles.put(plan.targetKey(), d);
                        n++;
                    }
                    case MERGE -> {
                        ProfileData cur = current.profiles.get(plan.targetKey());
                        Set<String> have = new HashSet<>();
                        for (ProfileData.DungeonRun r : cur.runs) if (r != null) have.add(runKey(r));
                        for (ProfileData.DungeonRun r : d.runs) if (r != null && have.add(runKey(r))) {
                            cur.runs.add(r);
                            // imported completed runs with a confirmed floor count towards that floor
                            if (r.complete() && r.classified()) {
                                cur.floorStats.computeIfAbsent(r.floor, k -> new ActivityStats()).add(r.t, r.xp, r.durationMs, r.id);
                                cur.floorCompletions.merge(r.floor, 1, Integer::sum);
                            }
                            n++;
                        }
                        for (Activity.SlayerQuest q : d.slayerQuests) if (q != null && q.id != null && Progress.addQuest(cur, q)) n++;
                        cur.runs.sort((a, b) -> Long.compare(b.t, a.t));
                        Progress.pruneRuns(cur);
                        // only fill gaps: values and collections the current profile has never seen
                        for (var v : d.values.entrySet()) if (v.getValue() != null && cur.values.putIfAbsent(v.getKey(), v.getValue()) == null) n++;
                        for (var c : d.collections.entrySet()) if (cur.collections.putIfAbsent(c.getKey(), c.getValue()) == null) n++;
                        for (var st : d.stats.entrySet())
                            if (st.getValue() != null && st.getValue().known() && !cur.peek(st.getKey()).known()) { cur.stats.put(st.getKey(), st.getValue()); n++; }
                        if (cur.owner == null && d.owner != null) cur.owner = d.owner;
                    }
                }
            }
        }
        SessionLog.Store s = sessions(sections);
        if (s != null) for (var pe : s.profiles.entrySet()) {
            // sessions follow their profile (also when it was stored separately); existing blocks are never changed
            SessionLog.Data cur = sessions.profiles.computeIfAbsent(targetFor(pe.getKey(), plans), k -> new SessionLog.Data());
            for (var b : pe.getValue().buckets.entrySet()) if (b.getValue() != null && cur.buckets.putIfAbsent(b.getKey(), b.getValue()) == null) {
                cur.totalPlaySeconds += Math.max(0, b.getValue().playSeconds);
                n++;
            }
            java.util.Set<Long> starts = new HashSet<>();
            for (SessionLog.Span sp : cur.spans) starts.add(sp.start);
            for (SessionLog.Span sp : pe.getValue().spans) if (starts.add(sp.start)) { cur.spans.add(sp); n++; }
            cur.spans.sort((a, b) -> Long.compare(a.start, b.start));
        }
        List<Goals.Goal> g = goals(sections);
        if (g != null) {
            Set<String> ids = new HashSet<>();
            for (Goals.Goal x : goals) ids.add(x.id);
            for (Goals.Goal x : g) if (x != null && x.id != null && x.stat != null && StatDef.get(x.stat) != null && ids.add(x.id)) { if (x.name == null) x.name = ""; goals.add(x); n++; }
        }
        History.Data h = history(sections);
        if (h != null) {
            Set<String> keys = new HashSet<>();
            for (History.Event e : hist.events) keys.add(eventKey(e));
            boolean added = false;
            for (History.Event e : h.events) if (e != null && keys.add(eventKey(e))) { hist.events.add(e); n++; added = true; }
            if (added) {
                hist.events.sort((a, b) -> Long.compare(a.t, b.t));
                while (hist.events.size() > 1500) hist.events.remove(0);
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ helpers
    static String runKey(ProfileData.DungeonRun r) { return r.t + "|" + r.floor + "|" + r.xp + "|" + r.status; }

    static String eventKey(History.Event e) { return e.t + "|" + e.kind + "|" + e.stat + "|" + e.amount; }

    private static Progress.Saved progress(JsonObject s) {
        JsonElement el = s.get("progress");
        if (el == null || !el.isJsonObject()) return null;
        try {
            Progress.Saved in = Storage.GSON.fromJson(el, Progress.Saved.class);
            if (in == null || in.profiles == null) return null;
            in.profiles.values().removeIf(java.util.Objects::isNull);
            Progress.migrate(in);
            return in;
        } catch (Exception e) { return null; }
    }

    private static SessionLog.Store sessions(JsonObject s) {
        try {
            return s.has("sessions") ? SessionLog.parse(s.get("sessions")) : null;
        } catch (Exception e) { return null; }
    }

    private static String targetFor(String key, List<ProfilePlan> plans) {
        for (ProfilePlan p : plans) if (p.key().equals(key)) return p.targetKey();
        return key;
    }

    private static List<Goals.Goal> goals(JsonObject s) {
        try {
            return s.has("goals") ? Storage.GSON.fromJson(s.get("goals"), new TypeToken<List<Goals.Goal>>() {}.getType()) : null;
        } catch (Exception e) { return null; }
    }

    private static History.Data history(JsonObject s) {
        try {
            History.Data h = s.has("history") ? Storage.GSON.fromJson(s.get("history"), History.Data.class) : null;
            if (h != null) History.migrate(h);
            return h;
        } catch (Exception e) { return null; }
    }
}
