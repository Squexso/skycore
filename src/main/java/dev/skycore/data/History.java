package dev.skycore.data;

import com.google.gson.reflect.TypeToken;
import dev.skycore.core.Storage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Local history database (history.json): every recognised event with source and confidence, every
 * reconciliation correction, daily progression snapshots and per-source scan timestamps.
 * Bounded ring buffers keep the file small. Nothing here stores chat text or other players' data –
 * only the rule that matched and the extracted numbers.
 */
public final class History {
    public static final int SCHEMA = 3;
    private static final int MAX_EVENTS = 1500, MAX_CORRECTIONS = 300, SNAPSHOT_DAYS = 120, MAX_SESSIONS = 200, MAX_REPAIRS = 300;

    public static class Event {
        public long t;
        public String profile;
        /** e.g. "xp", "run", "run_incomplete", "boss", "level", "milestone", "value", "profile". */
        public String kind;
        public String stat;
        public double amount;
        /** chat, action_bar, menu, tab, scoreboard, manual */
        public String source;
        /** Matching rule id (never the raw message). */
        public String rule;
        /** 0..1 – how sure the recogniser was. */
        public double confidence;
        /** false = recognised but not applied (confidence too low / tracking off). */
        public boolean applied = true;
    }

    /** A verified value disagreed with what SKYCORE had predicted. History is kept, the current value corrected. */
    public static class Correction {
        public long t;
        public String profile;
        public String stat;
        public int fromLevel, toLevel;
        public double fromXp, toXp;
        /** Total-XP difference (verified − predicted). Positive = progress SKYCORE did not see. */
        public double delta;
        public String source;
        /** Number of event-tracked gains since the previous verification. */
        public int eventsSince;
    }

    /** One client session (game start → stop). Interrupted = the game ended without a clean shutdown. */
    public static class SessionRecord {
        public long start, end;
        public String profile;
        /** "clean", "interrupted" */
        public String endReason;
        public int disconnects;
    }

    /** A change made by the integrity checker – nothing is changed without one of these. */
    public static class Repair {
        public long t;
        public String check;
        public String profile;
        public String target;
        public String before, after;
    }

    public static class Data {
        public int schema = SCHEMA;
        public List<Event> events = new ArrayList<>();
        public List<Correction> corrections = new ArrayList<>();
        public List<SessionRecord> sessions = new ArrayList<>();
        public List<Repair> repairs = new ArrayList<>();
        /** Mayor / minister observations from menus, with timestamps (see {@link Mayors}). */
        public List<Mayors.Observation> mayors = new ArrayList<>();
        /** day (ISO) → profile → stat → fractional level */
        public TreeMap<String, Map<String, Map<String, Double>>> snapshots = new TreeMap<>();
        /** source → last successful scan time */
        public Map<String, Long> lastScan = new LinkedHashMap<>();
    }

    private static Data data = new Data();
    private static boolean dirty;

    private History() {}

    public static void load() {
        data = Storage.load("history.json", new TypeToken<Data>() {}.getType(), Data::new);
        if (data.schema < SCHEMA && data.events != null && !data.events.isEmpty()) dev.skycore.core.Backups.beforeMigration("history.json", data.schema);
        migrate(data);
    }

    /** Versioned migrations – each step upgrades an older file in place. */
    static void migrate(Data d) {
        if (d.events == null) d.events = new ArrayList<>();
        if (d.corrections == null) d.corrections = new ArrayList<>();
        if (d.snapshots == null) d.snapshots = new TreeMap<>();
        if (d.lastScan == null) d.lastScan = new LinkedHashMap<>();
        if (d.sessions == null) d.sessions = new ArrayList<>();
        if (d.repairs == null) d.repairs = new ArrayList<>();
        if (d.mayors == null) d.mayors = new ArrayList<>();
        d.mayors.removeIf(java.util.Objects::isNull);
        d.events.removeIf(java.util.Objects::isNull);
        d.corrections.removeIf(java.util.Objects::isNull);
        d.sessions.removeIf(java.util.Objects::isNull);
        d.repairs.removeIf(java.util.Objects::isNull);
        // schema 0 → 1: older events had no confidence; they were all applied, exact matches
        if (d.schema < 1) {
            for (Event e : d.events) if (e.confidence == 0) e.confidence = 0.9;
            d.schema = 1;
        }
        // schema 1 → 2: session records and integrity repair log (lists initialised above)
        if (d.schema < 2) d.schema = 2;
        // schema 2 → 3: mayor observations (list initialised above)
        if (d.schema < 3) d.schema = 3;
    }

    public static void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        Storage.save("history.json", data);
    }

    public static Data data() { return data; }

    public static Event event(String profile, String kind, String stat, double amount, String source, String rule, double confidence, boolean applied) {
        Event e = new Event();
        e.t = System.currentTimeMillis();
        e.profile = profile;
        e.kind = kind;
        e.stat = stat;
        e.amount = amount;
        e.source = source;
        e.rule = rule;
        e.confidence = Math.round(confidence * 100) / 100.0;
        e.applied = applied;
        data.events.add(e);
        while (data.events.size() > MAX_EVENTS) data.events.remove(0);
        dirty = true;
        return e;
    }

    public static void correction(Correction c) {
        c.t = System.currentTimeMillis();
        if (dev.skycore.tracking.DebugLog.enabled()) {
            java.util.Map<String, String> v = new java.util.LinkedHashMap<>();
            v.put("stat", String.valueOf(c.stat));
            v.put("from", c.fromLevel + "/" + Math.round(c.fromXp));
            v.put("to", c.toLevel + "/" + Math.round(c.toXp));
            v.put("delta", String.valueOf(Math.round(c.delta)));
            dev.skycore.tracking.DebugLog.add(new dev.skycore.tracking.DebugLog.Entry(c.t, dev.skycore.tracking.DebugLog.Filter.STORAGE, "correction",
                    c.source, 1, v, c.profile, "-", true, "reconciled", "history"));
        }
        data.corrections.add(c);
        while (data.corrections.size() > MAX_CORRECTIONS) data.corrections.remove(0);
        dirty = true;
    }

    public static void scanned(String source) {
        data.lastScan.put(source, System.currentTimeMillis());
        dirty = true;
    }

    public static long lastScan(String source) { return data.lastScan.getOrDefault(source, 0L); }

    public static Event lastEvent() { return data.events.isEmpty() ? null : data.events.get(data.events.size() - 1); }

    /** Record today's fractional level for every known stat (called periodically; overwrites today's entry). */
    public static void snapshot(String profile, ProfileData d) {
        String day = LocalDate.now().toString();
        Map<String, Double> m = data.snapshots.computeIfAbsent(day, k -> new LinkedHashMap<>()).computeIfAbsent(profile, k -> new LinkedHashMap<>());
        for (var e : d.stats.entrySet()) if (e.getValue().known()) m.put(e.getKey(), Math.round(e.getValue().fractionalLevel() * 1000) / 1000.0);
        while (data.snapshots.size() > SNAPSHOT_DAYS) data.snapshots.pollFirstEntry();
        dirty = true;
    }

    public static void session(SessionRecord r) {
        data.sessions.add(r);
        while (data.sessions.size() > MAX_SESSIONS) data.sessions.remove(0);
        dirty = true;
    }

    public static void repair(String check, String profile, String target, String before, String after) {
        Repair r = new Repair();
        r.t = System.currentTimeMillis();
        r.check = check;
        r.profile = profile;
        r.target = target;
        r.before = before;
        r.after = after;
        data.repairs.add(r);
        while (data.repairs.size() > MAX_REPAIRS) data.repairs.remove(0);
        dirty = true;
    }

    public static void markDirty() { dirty = true; }

    /** Mayor conditions at time t, or null if unknown for that term. */
    public static Activity.MayorSnapshot mayorAt(long t) { return Mayors.at(data.mayors, t); }

    public static void observeMayor(Mayors.Observation o) {
        Mayors.observe(data.mayors, o);
        dirty = true;
    }

    /** Replace everything (restore / import). */
    public static void restore(com.google.gson.JsonElement json) {
        Data d = Storage.GSON.fromJson(json, Data.class);
        if (d == null) return;
        migrate(d);
        data = d;
        dirty = true;
    }

    /** Clears events, corrections, snapshots and scan times. Session records and the repair audit log are kept. */
    public static void clear() {
        Data n = new Data();
        n.sessions = data.sessions;
        n.repairs = data.repairs;
        n.mayors = data.mayors;
        data = n;
        dirty = true;
    }
}
