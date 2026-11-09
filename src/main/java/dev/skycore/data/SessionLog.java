package dev.skycore.data;

import com.google.gson.reflect.TypeToken;
import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Time-bucketed record of XP gains and playtime (10-minute buckets, 35 days retention) plus a session history –
 * <b>kept separately for every SkyBlock profile</b>.
 * <p>
 * Activity is attributed only to a confirmed profile. While the profile is unknown (after joining, during a
 * detection failure) playtime is held for up to two minutes and then given to the profile that is confirmed;
 * if none is confirmed in time it is stored as <i>unassigned</i> – never guessed. A profile's session ends when
 * another profile is confirmed, when the profile becomes unknown (disconnect) or when the game closes.
 * <p>
 * sessions.json schema 2. Schema 1 (one shared log) is migrated to "unassigned" because its profile cannot be
 * determined; the user can assign it (Settings → Profiles).
 */
public final class SessionLog {
    public static final long BUCKET = 10 * 60_000L;
    private static final long RETENTION = 35L * 24 * 3600_000L;
    public static final int SCHEMA = 2;
    /** Storage key for activity whose profile is unknown. */
    public static final String UNASSIGNED = "__unassigned__";
    private static final int MAX_SPANS = 300;
    static final long HOLD = 120_000;

    public static class Bucket {
        public int playSeconds;
        public Map<String, Double> xp = new HashMap<>();
    }

    /** One continuous play session on one profile. */
    public static class Span {
        public long start, end;
        public int playSeconds;
        /** XP per stat id gained during the session. */
        public Map<String, Double> xp = new LinkedHashMap<>();
        /** "profile_switch", "profile_unknown", "shutdown", "open" */
        public String endReason = "open";
        /** Levels at the start / end (stat id → fractional level) – progression changes during the session. */
        public Map<String, Double> levelsStart = new LinkedHashMap<>(), levelsEnd = new LinkedHashMap<>();

        public double totalXp() { return xp.values().stream().mapToDouble(Double::doubleValue).sum(); }
    }

    /** Sessions of one profile. */
    public static class Data {
        public TreeMap<Long, Bucket> buckets = new TreeMap<>();
        /** Newest last. */
        public List<Span> spans = new ArrayList<>();
        /** Lifetime playtime (not pruned with the buckets). */
        public long totalPlaySeconds;
    }

    /** File root. */
    public static class Store {
        public int schema = SCHEMA;
        public Map<String, Data> profiles = new LinkedHashMap<>();
        /** Schema 1 only: the shared log. Moved to {@link #UNASSIGNED} by the migration. */
        public TreeMap<Long, Bucket> buckets;
    }

    private static Store store = new Store();
    private static Data sample;
    private static boolean dirty;
    private static final Data EMPTY = new Data();
    /** Profile shown on the Sessions page; null = the active profile. */
    private static String selected;
    // open session + playtime waiting for profile confirmation
    private static String openKey;
    private static Span open;
    private static int pendingSeconds;
    private static long pendingSince;

    private SessionLog() {}

    public static void load() {
        store = Storage.load("sessions.json", new TypeToken<Store>() {}.getType(), Store::new);
        // a schema-1 file still has the shared "buckets" log (its schema field did not exist yet)
        if (store.buckets != null && !store.buckets.isEmpty()) dev.skycore.core.Backups.beforeMigration("sessions.json", 1);
        migrate(store);
        prune();
    }

    /** Versioned migration; running it again on migrated data changes nothing. */
    static void migrate(Store s) {
        if (s.profiles == null) s.profiles = new LinkedHashMap<>();
        if (s.schema < 2 || s.buckets != null) {
            // schema 1 → 2: the profile of the shared log is unknown – keep it unassigned, never guess
            if (s.buckets != null && !s.buckets.isEmpty()) {
                Data u = s.profiles.computeIfAbsent(UNASSIGNED, k -> new Data());
                for (var e : s.buckets.entrySet()) if (e.getValue() != null) mergeBucket(u.buckets, e.getKey(), e.getValue());
                for (Bucket b : u.buckets.values()) u.totalPlaySeconds += b.playSeconds;
            }
            s.buckets = null;
            s.schema = SCHEMA;
        }
        s.profiles.values().removeIf(java.util.Objects::isNull);
        for (Data d : s.profiles.values()) repair(d);
    }

    static void repair(Data d) {
        if (d.buckets == null) d.buckets = new TreeMap<>();
        if (d.spans == null) d.spans = new ArrayList<>();
        d.buckets.values().removeIf(java.util.Objects::isNull);
        d.spans.removeIf(java.util.Objects::isNull);
        for (Bucket b : d.buckets.values()) if (b.xp == null) b.xp = new HashMap<>();
        for (Span sp : d.spans) {
            if (sp.xp == null) sp.xp = new LinkedHashMap<>();
            if (sp.levelsStart == null) sp.levelsStart = new LinkedHashMap<>();
            if (sp.levelsEnd == null) sp.levelsEnd = new LinkedHashMap<>();
        }
    }

    static void mergeBucket(TreeMap<Long, Bucket> into, long key, Bucket b) {
        Bucket t = into.computeIfAbsent(key, k -> new Bucket());
        t.playSeconds = Math.min(600, t.playSeconds + Math.max(0, b.playSeconds));
        if (b.xp != null) for (var x : b.xp.entrySet()) if (x.getValue() != null && x.getValue() > 0) t.xp.merge(x.getKey(), x.getValue(), Double::sum);
    }

    public static void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        prune();
        Storage.save("sessions.json", store);
    }

    private static void prune() {
        long cut = (System.currentTimeMillis() - RETENTION) / BUCKET;
        for (Data d : store.profiles.values()) {
            d.buckets.headMap(cut).clear();
            while (d.spans.size() > MAX_SPANS) d.spans.remove(0);
        }
    }

    /** Where activity goes right now: the confirmed profile, otherwise unassigned. */
    private static Data target() {
        String key = Progress.isIdentified() ? Progress.profileKey() : UNASSIGNED;
        return store.profiles.computeIfAbsent(key, k -> new Data());
    }

    private static Bucket bucketNow(Data d) {
        return d.buckets.computeIfAbsent(System.currentTimeMillis() / BUCKET, k -> new Bucket());
    }

    public static void record(StatDef def, double amount) {
        if (amount <= 0) return;
        bucketNow(target()).xp.merge(def.id(), amount, Double::sum);
        if (open != null && Progress.isIdentified() && Progress.profileKey().equals(openKey)) open.xp.merge(def.id(), amount, Double::sum);
        dirty = true;
    }

    /** One second of active play. Held while the profile is unknown (see class comment). */
    public static void addPlaySeconds(int s) {
        long now = System.currentTimeMillis();
        if (!Progress.isIdentified()) {
            if (pendingSeconds == 0) pendingSince = now;
            pendingSeconds += s;
            if (now - pendingSince > HOLD) flushPending(UNASSIGNED);
            return;
        }
        play(target(), s);
        if (open != null && Progress.profileKey().equals(openKey)) open.playSeconds += s;
        dirty = true;
    }

    private static void play(Data d, int s) {
        Bucket b = bucketNow(d);
        b.playSeconds = Math.min(600, b.playSeconds + s);
        d.totalPlaySeconds += s;
    }

    private static void flushPending(String key) {
        if (pendingSeconds <= 0) return;
        Data d = store.profiles.computeIfAbsent(key, k -> new Data());
        Bucket b = d.buckets.computeIfAbsent(pendingSince / BUCKET, k -> new Bucket());
        b.playSeconds = Math.min(600, b.playSeconds + pendingSeconds);
        d.totalPlaySeconds += pendingSeconds;
        if (open != null && key.equals(openKey)) open.playSeconds += pendingSeconds;
        pendingSeconds = 0;
        dirty = true;
    }

    /** A profile was confirmed: close the previous profile's session, start (or continue) this one. */
    public static void profileIdentified(String key, ProfileData levels) {
        long now = System.currentTimeMillis();
        if (open != null && !key.equals(openKey)) closeOpen(now, "profile_switch", null);
        if (open == null) {
            open = new Span();
            open.start = now;
            openKey = key;
            if (levels != null) snapshotLevels(levels, open.levelsStart);
            Data d = store.profiles.computeIfAbsent(key, k -> new Data());
            d.spans.add(open);
        }
        // playtime held during detection belongs to this profile only if it was confirmed within the hold window
        if (pendingSeconds > 0) flushPending(now - pendingSince <= HOLD ? key : UNASSIGNED);
        dirty = true;
    }

    /** Profile became unknown (disconnect / detection failure) or the game closes: end the session. */
    public static void profileLost(String reason, ProfileData levels) {
        flushPending(UNASSIGNED);
        closeOpen(System.currentTimeMillis(), reason, levels);
    }

    private static void closeOpen(long now, String reason, ProfileData levels) {
        if (open == null) return;
        open.end = now;
        open.endReason = reason;
        if (levels != null) snapshotLevels(levels, open.levelsEnd);
        // an empty session (no play, no XP) is not worth keeping
        if (open.playSeconds == 0 && open.xp.isEmpty()) {
            Data d = store.profiles.get(openKey);
            if (d != null) d.spans.remove(open);
        }
        open = null;
        openKey = null;
        dirty = true;
    }

    private static void snapshotLevels(ProfileData d, Map<String, Double> into) {
        into.clear();
        for (var e : d.stats.entrySet()) if (e.getValue() != null && e.getValue().known()) into.put(e.getKey(), Math.round(e.getValue().fractionalLevel() * 1000) / 1000.0);
    }

    /** The real (non-sample) data – for integrity checks, merges and exports. */
    public static Store raw() { return store; }

    public static void markDirty() { dirty = true; }

    /** Profiles that have session data (for the Sessions page selector). */
    public static List<String> keys() { return new ArrayList<>(store.profiles.keySet()); }

    /** Show another profile on the Sessions page (null = the active profile). */
    public static void select(String key) { selected = key; }

    public static String selectedKey() { return selected != null ? selected : Progress.profileKey(); }

    public static String openKey() { return openKey; }

    /** Sessions of one profile (empty if none). */
    public static Data of(String key) {
        Data d = key == null ? null : store.profiles.get(key);
        return d == null ? EMPTY : d;
    }

    public static Data view() {
        if (Progress.isSample()) {
            if (sample == null) sample = SampleData.sessions();
            return sample;
        }
        return of(selectedKey());
    }

    // ------------------------------------------------------------------ queries
    public enum Period { TODAY, YESTERDAY, WEEK, MONTH }

    public record Range(long start, long end, long step, int points) {}

    public static Range range(Period p) {
        ZoneId z = ZoneId.systemDefault();
        long startToday = LocalDate.now(z).atStartOfDay(z).toInstant().toEpochMilli();
        long hour = 3600_000L, day = 24 * hour;
        return switch (p) {
            case TODAY -> new Range(startToday, startToday + day, hour, 24);
            case YESTERDAY -> new Range(startToday - day, startToday, hour, 24);
            case WEEK -> new Range(startToday - 6 * day, startToday + day, day, 7);
            case MONTH -> new Range(startToday - 29 * day, startToday + day, day, 30);
        };
    }

    /** The equivalent period immediately before r (for comparisons). */
    public static Range previous(Range r) {
        long len = r.end() - r.start();
        return new Range(r.start() - len, r.start(), r.step(), r.points());
    }

    public record Totals(double xp, long playMillis, Map<String, Double> byGroup, Map<String, Double> byStat) {}

    public static Totals totals(Range r) {
        Data d = view();
        double xp = 0;
        long play = 0;
        Map<String, Double> groups = new LinkedHashMap<>();
        for (String g : StatDef.GROUPS) groups.put(g, 0d);
        Map<String, Double> stats = new LinkedHashMap<>();
        for (var e : d.buckets.subMap(r.start() / BUCKET, true, (r.end() - 1) / BUCKET, true).entrySet()) {
            Bucket b = e.getValue();
            play += b.playSeconds * 1000L;
            for (var x : b.xp.entrySet()) {
                StatDef def = StatDef.get(x.getKey());
                String g = def == null ? "other" : def.group();
                groups.merge(g, x.getValue(), Double::sum);
                stats.merge(x.getKey(), x.getValue(), Double::sum);
                xp += x.getValue();
            }
        }
        return new Totals(xp, play, groups, stats);
    }

    /** XP per step across the range (optionally only one group; null = all). */
    public static double[] series(Range r, String group) {
        double[] out = new double[r.points()];
        Data d = view();
        for (var e : d.buckets.subMap(r.start() / BUCKET, true, (r.end() - 1) / BUCKET, true).entrySet()) {
            long t = e.getKey() * BUCKET;
            int i = (int) ((t - r.start()) / r.step());
            if (i < 0 || i >= out.length) continue;
            for (var x : e.getValue().xp.entrySet()) {
                if (group != null) {
                    StatDef def = StatDef.get(x.getKey());
                    if (def == null || !def.group().equals(group)) continue;
                }
                out[i] += x.getValue();
            }
        }
        return out;
    }

    /**
     * XP per hour for each step of the range (all groups). Steps without enough recorded playtime are
     * {@link Double#NaN} – unknown, not zero.
     */
    public static double[] rateSeries(Range r) {
        double[] xp = new double[r.points()], play = new double[r.points()];
        Data d = view();
        for (var e : d.buckets.subMap(r.start() / BUCKET, true, (r.end() - 1) / BUCKET, true).entrySet()) {
            int i = (int) ((e.getKey() * BUCKET - r.start()) / r.step());
            if (i < 0 || i >= xp.length) continue;
            play[i] += e.getValue().playSeconds;
            for (double v : e.getValue().xp.values()) xp[i] += v;
        }
        double[] out = new double[xp.length];
        for (int i = 0; i < out.length; i++) out[i] = Rates.perHour(xp[i], play[i]);
        return out;
    }

    /** Most recent time a group received XP (0 if never). */
    public static long lastActivity(String group) {
        Data d = view();
        for (var e : d.buckets.descendingMap().entrySet()) {
            for (var x : e.getValue().xp.keySet()) {
                StatDef def = StatDef.get(x);
                if (def != null && def.group().equals(group)) return e.getKey() * BUCKET;
            }
        }
        return 0;
    }

    /** XP per hour samples for a stat over the last 7 days (one per active bucket). */
    public static double[] rateSamples(String statId) {
        Data d = view();
        long from = (System.currentTimeMillis() - 7L * 24 * 3600_000L) / BUCKET;
        return d.buckets.tailMap(from).values().stream()
                .filter(b -> b.playSeconds >= 120 && b.xp.getOrDefault(statId, 0d) > 0)
                .mapToDouble(b -> b.xp.get(statId) / (b.playSeconds / 3600.0))
                .toArray();
    }

    public static Object snapshot() { return store; }

    /** Restore from a backup (schema 1 or 2). */
    public static void restore(com.google.gson.JsonElement json) {
        Store s = parse(json);
        if (s == null) return;
        store = s;
        open = null;
        openKey = null;
        dirty = true;
    }

    /** Parse a sessions section of any schema (null if unusable). */
    public static Store parse(com.google.gson.JsonElement json) {
        Store s = Storage.GSON.fromJson(json, Store.class);
        if (s == null || (s.profiles == null && s.buckets == null)) return null;
        if (s.profiles == null) s.schema = 1;
        migrate(s);
        return s;
    }

    /** True if any playtime was recorded in the range – distinguishes "no data" from "0 XP". */
    public static boolean played(Range r) {
        for (Bucket b : view().buckets.subMap(r.start() / BUCKET, true, (r.end() - 1) / BUCKET, true).values()) if (b.playSeconds > 0 || !b.xp.isEmpty()) return true;
        return false;
    }

    /** "Reset session history" – only the profile shown on the Sessions page. */
    public static void reset() {
        String key = selectedKey();
        if (key != null) store.profiles.remove(key);
        if (key != null && key.equals(openKey)) { open = null; openKey = null; }
        dirty = true;
    }
}
