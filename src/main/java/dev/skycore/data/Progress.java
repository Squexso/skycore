package dev.skycore.data;

import com.google.gson.reflect.TypeToken;
import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;
import dev.skycore.data.ProfileData.StatProgress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central progression store (per SkyBlock profile). Trackers write here; UI pages and the HUD read
 * through {@link #view()}, which transparently swaps in labelled sample data when preview mode is on.
 * Every mutation bumps {@link #version()} so readers can cache derived values.
 */
public final class Progress {
    public interface Listener {
        void onGain(StatDef def, StatProgress p, double amount);
        void onLevelUp(StatDef def, StatProgress p, int newLevel);
        default void onMilestone(String what, int level) {}
        default void onBestRun(double xp) {}
    }

    static class Saved {
        int schema = 0;
        String current = "default";
        Map<String, ProfileData> profiles = new LinkedHashMap<>();
    }

    /** Current progress.json schema. Bump and add a step in {@link #migrate} when the format changes. */
    public static final int SCHEMA = 4;

    private static Saved saved = new Saved();
    private static final List<Listener> listeners = new ArrayList<>();
    private static boolean dirty;
    private static int version;
    private static ProfileData sample;
    private static final int MAX_GAINS = 60;

    /** Set by the HUD editor while it previews sample content. Never persisted. */
    public static boolean previewOverride;

    private Progress() {}

    public static void load() {
        saved = Storage.load("progress.json", new TypeToken<Saved>() {}.getType(), Saved::new);
        if (saved.profiles == null) saved.profiles = new LinkedHashMap<>();
        if (saved.current == null) saved.current = "default";
        if (saved.schema < SCHEMA && !saved.profiles.isEmpty()) dev.skycore.core.Backups.beforeMigration("progress.json", saved.schema);
        migrate(saved);
        identified = false; // until the game confirms which profile is being played
        version++;
    }

    /** Versioned, in-place migrations of older files. Never drops recorded history. */
    static void migrate(Saved s) {
        for (ProfileData d : s.profiles.values()) repair(d);
        if (s.schema < 2) {
            // schema 0/1 → 2: provenance fields introduced; existing values keep their meaning
            for (ProfileData d : s.profiles.values())
                for (StatProgress p : d.stats.values()) {
                    if (p.source == null && p.calibrated) p.source = "manual";
                    if (p.verifiedAt == 0 && !"manual".equals(p.source)) p.verifiedAt = p.updated;
                }
            s.schema = 2;
        }
        if (s.schema < 3) {
            // 2 → 3: profiles remember their in-game name (owner stays unknown until seen again)
            for (var e : s.profiles.entrySet()) if (e.getValue().name == null) e.getValue().name = e.getKey();
            s.schema = 3;
        }
        if (s.schema < 4) {
            // 3 → 4: per-floor and per-tier statistics are built from the existing records; nothing is removed
            for (ProfileData d : s.profiles.values()) migrateActivities(d);
            s.schema = 4;
        }
    }

    static void repairData(ProfileData d) { repair(d); }

    /** Older or hand-edited files may miss fields – never let that crash the UI. */
    private static void repair(ProfileData d) {
        if (d.stats == null) d.stats = new LinkedHashMap<>();
        if (d.runs == null) d.runs = new ArrayList<>();
        if (d.activity == null) d.activity = new ArrayList<>();
        if (d.collections == null) d.collections = new LinkedHashMap<>();
        if (d.collectionProgress == null) d.collectionProgress = new LinkedHashMap<>();
        if (d.notified == null) d.notified = new java.util.LinkedHashSet<>();
        if (d.values == null) d.values = new LinkedHashMap<>();
        if (d.floorCompletions == null) d.floorCompletions = new LinkedHashMap<>();
        d.stats.values().removeIf(java.util.Objects::isNull);
        for (StatProgress p : d.stats.values()) if (p.gains == null) p.gains = new ArrayList<>();
        d.runs.removeIf(java.util.Objects::isNull);
        if (d.floorStats == null) d.floorStats = new LinkedHashMap<>();
        if (d.slayerStats == null) d.slayerStats = new LinkedHashMap<>();
        if (d.slayerQuests == null) d.slayerQuests = new ArrayList<>();
        if (d.menuCounts == null) d.menuCounts = new LinkedHashMap<>();
        for (Activity.SlayerQuest q : d.slayerQuests) if (q != null && q.classification != null && q.classification.history == null) q.classification.history = new ArrayList<>();
        for (ProfileData.DungeonRun r : d.runs) if (r != null && r.classification != null && r.classification.history == null) r.classification.history = new ArrayList<>();
        d.floorStats.values().removeIf(java.util.Objects::isNull);
        d.slayerStats.values().removeIf(java.util.Objects::isNull);
        d.slayerQuests.removeIf(java.util.Objects::isNull);
        for (ActivityStats a : d.floorStats.values()) a.repair();
        for (ActivityStats a : d.slayerStats.values()) a.repair();
    }

    public static void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        Storage.save("progress.json", saved);
    }

    private static void touch() { dirty = true; version++; }

    /** Data was changed from outside the normal tracking path (integrity repair, import merge). */
    public static void markChanged() { touch(); }

    public static int version() { return version; }

    public static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    /** Real data for the active profile (what trackers write to). */
    public static ProfileData real() { return saved.profiles.computeIfAbsent(saved.current, k -> new ProfileData()); }

    /** What the UI should display. */
    public static ProfileData view() {
        if (isSample()) {
            if (sample == null) sample = SampleData.profile();
            return sample;
        }
        return real();
    }

    public static boolean isSample() { return previewOverride || Skycore.config().sampleData; }

    /** The player's Minecraft name (what the sidebar chip shows); falls back to the profile name if unavailable. */
    public static String playerName() {
        if (isSample()) return "Sample";
        try {
            var u = net.minecraft.client.Minecraft.getInstance().getUser();
            if (u != null && u.getName() != null && !u.getName().isBlank()) return u.getName();
        } catch (RuntimeException ignored) { }
        return profileName();
    }

    public static String profileName() { return isSample() ? "Sample" : displayName(saved.current); }

    /** Storage key of the active profile (may carry an account suffix, e.g. "Apple#1a2b3c4d"). */
    public static String profileKey() { return saved.current; }

    public static String displayName(String key) {
        ProfileData d = saved.profiles.get(key);
        if (d != null && d.name != null) return d.name;
        int i = key == null ? -1 : key.indexOf('#');
        return i > 0 ? key.substring(0, i) : key;
    }

    // ------------------------------------------------------------------ profile identity
    private static boolean identified;
    private static String identitySource;
    private static long identifiedAt;

    /** True once the game confirmed which profile is being played (chat, tab list or the user). */
    public static boolean isIdentified() { return identified; }

    public static String identitySource() { return identitySource; }

    public static long identifiedAt() { return identifiedAt; }

    /** Disconnect / server change: profile-specific updates are suspended until the profile is seen again. */
    public static void markUnknown() {
        if (identified) SessionLog.profileLost("profile_unknown", real());
        identified = false;
        identitySource = null;
    }

    /** Legacy entry point: identify without account information. */
    public static void setProfile(String name) { identify(name, null, "chat"); }

    /**
     * The game told us which profile is active. Profiles are kept apart per Minecraft account: if another
     * account already owns a profile with the same name, this one is stored under {@code name#<uuid prefix>}.
     * Names are compared exactly ("Apple" ≠ "apple").
     *
     * @return the storage key now active, or null if the name was unusable
     */
    public static String identify(String name, String owner, String source) {
        if (name == null || name.isBlank() || name.length() > 32) return null;
        name = name.trim();
        String key = resolveKey(saved.profiles, name, owner);
        boolean switched = !key.equals(saved.current);
        saved.current = key;
        ProfileData d = real();
        if (d.name == null) d.name = name;
        if (owner != null && d.owner == null) d.owner = owner;
        d.lastSeen = System.currentTimeMillis();
        identified = true;
        identitySource = source;
        identifiedAt = d.lastSeen;
        if (switched) d.addActivity("activity.profile", "PROFILE", name);
        SessionLog.profileIdentified(key, d);
        touch();
        return key;
    }

    /** Pure key resolution (unit-tested). */
    static String resolveKey(Map<String, ProfileData> profiles, String name, String owner) {
        if (owner == null) return name;
        ProfileData plain = profiles.get(name);
        if (plain == null || plain.owner == null || plain.owner.equals(owner)) return name;
        String shortKey = name + "#" + owner.replace("-", "").substring(0, Math.min(8, owner.replace("-", "").length()));
        ProfileData other = profiles.get(shortKey);
        if (other == null || other.owner == null || other.owner.equals(owner)) return shortKey;
        return name + "#" + owner.replace("-", "");
    }

    /** Stored profiles, most recently seen first. */
    public static List<String> profileKeys() {
        List<String> keys = new ArrayList<>(saved.profiles.keySet());
        keys.sort((a, b) -> Long.compare(saved.profiles.get(b).lastSeen, saved.profiles.get(a).lastSeen));
        return keys;
    }

    public static ProfileData stored(String key) { return saved.profiles.get(key); }

    /** Delete a stored profile that is not the active one (the caller takes a safety backup first). */
    public static boolean deleteProfile(String key) {
        if (key == null || key.equals(saved.current) || !saved.profiles.containsKey(key)) return false;
        saved.profiles.remove(key);
        touch();
        return true;
    }

    private static boolean tracks(StatDef def) { return Skycore.config().trackingEnabled && Skycore.config().tracks(def.category()); }

    /** Total XP from level 0 – used to compare a prediction with a verified reading. -1 if unknown. */
    static double totalXp(StatDef def, int level, double xpInto) {
        if (def.table() == null || level < 0) return -1;
        return XpTables.xpTo(def.table(), 0, 0, level) + xpInto;
    }

    /**
     * Reconciliation: a verified reading arrived. If it disagrees with the current (predicted) value by more
     * than rounding, log a correction. Recorded gains and session history are never rewritten.
     */
    static void reconcile(StatDef def, StatProgress p, int level, double xp, String source) {
        if (!p.known()) return;
        double before = totalXp(def, p.level, p.xp), after = totalXp(def, level, xp);
        double delta;
        if (before >= 0 && after >= 0) delta = after - before;
        else delta = level == p.level ? xp - p.xp : Double.NaN;
        boolean differs = Double.isNaN(delta) ? level != p.level : Math.abs(delta) > Math.max(1, Math.abs(after) * 0.0005);
        if (!differs) return;
        History.Correction c = new History.Correction();
        c.profile = profileName();
        c.stat = def.id();
        c.fromLevel = p.level;
        c.fromXp = p.xp;
        c.toLevel = level;
        c.toXp = xp;
        c.delta = Double.isNaN(delta) ? 0 : delta;
        c.source = source;
        c.eventsSince = p.derivedEvents;
        History.correction(c);
    }

    // ------------------------------------------------------------------ mutations
    private static void addGain(StatProgress p, double amount) {
        p.gains.add(new ProfileData.Gain(System.currentTimeMillis(), amount));
        while (p.gains.size() > MAX_GAINS) p.gains.remove(0);
    }

    /** Add XP to a stat, rolling over levels when the table is known. */
    public static void gain(StatDef def, double amount) {
        if (amount <= 0 || !tracks(def)) return;
        StatProgress p = real().stat(def.id());
        addGain(p, amount);
        p.updated = System.currentTimeMillis();
        if (p.level >= 0 && p.need > 0) {
            p.derivedEvents++;
            p.xp += amount;
            while (p.need > 0 && p.xp >= p.need && p.level < def.maxLevel()) {
                p.xp -= p.need;
                p.level++;
                long n = XpTables.need(def.table(), p.level);
                p.need = n;
                levelUp(def, p);
                if (n < 0) { p.xp = 0; break; }
            }
        }
        SessionLog.record(def, amount);
        for (Listener l : listeners) l.onGain(def, p, amount);
        touch();
    }

    /** Exact state observed in-game (action bar, slayer message). Returns the XP delta vs. the previous observation, or -1. */
    public static double observe(StatDef def, int level, double xp, double need, String source) {
        if (!tracks(def)) return -1;
        StatProgress p = real().stat(def.id());
        double delta = -1;
        if (p.level >= 0 && p.need > 0) {
            if (level == p.level) delta = xp - p.xp;
            else if (level == p.level + 1) delta = (p.need - p.xp) + xp;
        }
        int before = p.level;
        // only a jump the delta logic could not explain is a discrepancy worth logging
        if (delta < 0 || delta > Math.max(need, 1)) reconcile(def, p, level, xp, source);
        p.level = level;
        p.xp = xp;
        p.need = need;
        p.percent = -1;
        p.calibrated = true;
        p.source = source;
        p.xpUnknown = false;
        p.derivedEvents = 0;
        p.verifiedAt = p.updated = System.currentTimeMillis();
        if (before >= 0 && level > before) levelUp(def, p);
        touch();
        return delta;
    }

    /**
     * Exact values read from a SkyBlock menu. Synchronises silently (no gain is recorded because the time the
     * XP was earned is unknown – it may have been on another device).
     */
    public static void observeMenu(StatDef def, int level, double xp, double need) {
        if (!tracks(def) || level < 0) return;
        StatProgress p = real().stat(def.id());
        int before = p.level;
        if (before == level && Math.abs(p.xp - xp) < 0.5 && p.need == need && !p.xpUnknown) {
            p.verifiedAt = System.currentTimeMillis(); // confirmed unchanged
            p.derivedEvents = 0;
            p.source = "menu";
            return;
        }
        reconcile(def, p, level, xp, "menu");
        p.level = level;
        p.xp = Math.max(0, xp);
        p.need = need > 0 ? need : XpTables.need(def.table(), level);
        p.percent = -1;
        p.calibrated = true;
        p.source = "menu";
        p.xpUnknown = false;
        p.derivedEvents = 0;
        p.verifiedAt = p.updated = System.currentTimeMillis();
        if (before >= 0 && level > before) levelUp(def, p);
        touch();
    }

    /** A maxed skill shown as "MAX" in the tab list: the level is verified, there is no next level. */
    public static void observeMax(StatDef def, int level, String source) {
        if (!tracks(def) || level < 0) return;
        StatProgress p = real().stat(def.id());
        if (p.level == level && p.need <= 0 && !p.xpUnknown) { p.verifiedAt = System.currentTimeMillis(); return; }
        int before = p.level;
        if (before != level) reconcile(def, p, level, 0, source);
        p.level = level;
        p.need = -1;
        p.xp = 0;
        p.percent = -1;
        p.xpUnknown = false;
        p.source = source;
        p.derivedEvents = 0;
        p.verifiedAt = p.updated = System.currentTimeMillis();
        if (before >= 0 && level > before) levelUp(def, p);
        touch();
    }

    /** Level known exactly but XP only from a rounded percentage (tab list). Never overrides a fresher exact value. */
    public static void observeApprox(StatDef def, int level, double percent, String source) {
        if (!tracks(def) || level < 0) return;
        StatProgress p = real().stat(def.id());
        boolean freshExact = p.level == level && !p.xpUnknown && p.status() == DataStatus.VERIFIED
                && System.currentTimeMillis() - p.verifiedAt < 10 * 60_000L;
        if (freshExact) return;
        long need = XpTables.need(def.table(), level);
        int before = p.level;
        if (before != level) reconcile(def, p, level, need > 0 ? need * percent / 100 : 0, source);
        p.level = level;
        p.need = need;
        p.xp = need > 0 ? need * percent / 100 : 0;
        p.percent = need > 0 ? -1 : percent;
        p.xpUnknown = true; // rounded – shown as an estimate
        p.source = source;
        p.derivedEvents = 0;
        p.verifiedAt = p.updated = System.currentTimeMillis();
        if (before >= 0 && level > before) levelUp(def, p);
        touch();
    }

    /** Record a gain without touching level/xp (used after {@link #observe}). */
    public static void recordGainOnly(StatDef def, double amount) { recordGainOnly(def, amount, null); }

    public static void recordGainOnly(StatDef def, double amount, String tag) {
        if (amount <= 0 || !tracks(def)) return;
        StatProgress p = real().stat(def.id());
        p.gains.add(new ProfileData.Gain(System.currentTimeMillis(), amount, tag));
        while (p.gains.size() > MAX_GAINS) p.gains.remove(0);
        SessionLog.record(def, amount);
        for (Listener l : listeners) l.onGain(def, p, amount);
        touch();
    }

    public static void observePercent(StatDef def, double percent) {
        if (!tracks(def)) return;
        StatProgress p = real().stat(def.id());
        p.percent = percent;
        p.source = "action_bar";
        p.updated = System.currentTimeMillis();
        touch();
    }

    /** +1 completed run / boss kill for this stat. */
    public static void count(StatDef def) {
        if (!tracks(def)) return;
        real().stat(def.id()).count++;
        touch();
    }

    public static void setLevel(StatDef def, int level) {
        if (!tracks(def)) return;
        StatProgress p = real().stat(def.id());
        int before = p.level;
        if (before == level) return;
        p.level = level;
        p.xp = 0;
        p.need = XpTables.need(def.table(), level);
        p.source = "chat";
        p.xpUnknown = def.table() != null; // the message says the level, not the XP inside it
        p.derivedEvents = 0;
        p.updated = System.currentTimeMillis();
        if (before >= 0 && level > before) levelUp(def, p);
        else real().addActivity("activity.level", def.icon().name(), def.nameKey(), String.valueOf(level));
        touch();
    }

    /** Manual calibration from the UI. */
    public static void calibrate(StatDef def, int level, double xp) {
        StatProgress p = real().stat(def.id());
        p.level = Math.max(0, Math.min(def.maxLevel(), level));
        p.need = XpTables.need(def.table(), p.level);
        p.xp = p.need > 0 ? Math.max(0, Math.min(xp, p.need - 1)) : Math.max(0, xp);
        p.calibrated = true;
        p.source = "manual";
        p.xpUnknown = false;
        p.derivedEvents = 0;
        p.updated = System.currentTimeMillis();
        touch();
    }

    /** Store a level-less value (powder, motes, completions). */
    public static void value(String key, double v, String source) {
        if (!Skycore.config().trackingEnabled) return;
        ProfileData.ValueRecord r = real().values.computeIfAbsent(key, k -> new ProfileData.ValueRecord());
        if (r.value == v && source.equals(r.source)) { r.updated = System.currentTimeMillis(); return; }
        r.value = v;
        r.source = source;
        r.status = DataStatus.VERIFIED;
        r.updated = System.currentTimeMillis();
        touch();
    }

    public static void increment(String key, String source) {
        ProfileData.ValueRecord r = real().values.computeIfAbsent(key, k -> new ProfileData.ValueRecord());
        r.value += 1;
        r.source = source;
        r.status = DataStatus.EVENT_TRACKED;
        r.updated = System.currentTimeMillis();
        touch();
    }

    public static void recordRun(double xp, String floor, String rank, int scorePoints) { recordRun(xp, floor, rank, scorePoints, 0, null); }

    /** Run whose floor came from the sidebar only. */
    public static void recordRun(double xp, String floor, String rank, int scorePoints, long startedAt, String dungeonClass) {
        recordRun(xp, dev.skycore.tracking.DungeonClassifier.classify(floor == null ? java.util.List.of() : java.util.List.of(floor), null), rank, scorePoints, startedAt, dungeonClass);
    }

    /**
     * A completed run with its floor classification. Only runs with a confirmed exact floor count towards that
     * floor's statistics and completion counter; uncertain runs are kept separately. A second run with the same XP
     * within 10 s is a duplicate and ignored.
     */
    public static ProfileData.DungeonRun recordRun(double xp, Activity.Classification cls, String rank, int scorePoints, long startedAt, String dungeonClass) {
        ProfileData d = real();
        long now = System.currentTimeMillis();
        if (!d.runs.isEmpty()) {
            ProfileData.DungeonRun last = d.runs.get(0);
            if (last.complete() && last.xp == xp && Math.abs(now - last.t) < 10_000) return null;
        }
        ProfileData.DungeonRun r = new ProfileData.DungeonRun();
        r.t = now;
        r.xp = xp;
        r.classification = cls;
        r.floor = cls.confirmed ? cls.key : null;
        r.score = rank;
        r.scorePoints = scorePoints;
        r.startedAt = startedAt;
        r.durationMs = startedAt > 0 ? r.t - startedAt : 0;
        r.dungeonClass = dungeonClass;
        r.id = "run-" + r.t + "-" + (r.floor == null ? "unknown" : r.floor);
        r.calc = Rewards.dungeon(r.floor, xp);
        if (r.floor != null) {
            d.floorCompletions.merge(r.floor, 1, Integer::sum);
            d.floorStats.computeIfAbsent(r.floor, k -> new ActivityStats()).add(r.t, xp, r.durationMs, r.id);
        }
        d.runs.add(0, r);
        pruneRuns(d);
        d.addActivity("activity.run", "DUNGEONS", r.floor == null ? "?" : r.floor, String.valueOf(Math.round(xp)));
        boolean best = d.bestRunXp > 0 && xp > d.bestRunXp;
        d.bestRunXp = Math.max(d.bestRunXp, xp);
        if (best) for (Listener l : listeners) l.onBestRun(xp);
        touch();
        return r;
    }

    /** A run that started but ended without an XP summary (left, disconnected or failed). Never counted as completed. */
    public static void recordIncompleteRun(String floor, long startedAt, String dungeonClass) {
        recordIncompleteRun(dev.skycore.tracking.DungeonClassifier.classify(floor == null ? java.util.List.of() : java.util.List.of(floor), null), startedAt, dungeonClass);
    }

    public static void recordIncompleteRun(Activity.Classification cls, long startedAt, String dungeonClass) {
        ProfileData.DungeonRun r = new ProfileData.DungeonRun();
        r.t = System.currentTimeMillis();
        r.classification = cls;
        r.floor = cls.confirmed ? cls.key : null;
        r.status = "incomplete";
        r.startedAt = startedAt;
        r.durationMs = startedAt > 0 ? r.t - startedAt : 0;
        r.dungeonClass = dungeonClass;
        r.id = "run-" + r.t + "-incomplete";
        r.calc = Rewards.dungeon(r.floor, -1);
        ProfileData d = real();
        d.runs.add(0, r);
        pruneRuns(d);
        touch();
    }

    /** Keep the newest 50 runs per floor (and 50 unclassified); older ones live on in the floor statistics. */
    static void pruneRuns(ProfileData d) {
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        d.runs.removeIf(r -> seen.merge(r.floor == null ? "?" : r.floor, 1, Integer::sum) > ActivityStats.DETAILED);
    }

    // ------------------------------------------------------------------ slayer quests
    /**
     * Store a finished slayer quest. Completed, confirmed quests count towards their exact tier; failed, interrupted
     * and uncertain quests are kept (for inspection and later correction) but never counted.
     *
     * @return false if this quest was already recorded
     */
    public static boolean recordSlayerQuest(Activity.SlayerQuest q) {
        boolean ok = addQuest(real(), q);
        if (ok) touch();
        return ok;
    }

    static boolean addQuest(ProfileData d, Activity.SlayerQuest q) {
        for (Activity.SlayerQuest x : d.slayerQuests) if (x.id.equals(q.id)) return false;
        d.slayerQuests.add(0, q);
        if ("complete".equals(q.status) && q.classified()) {
            ActivityStats st = d.slayerStats.computeIfAbsent(q.key(), k -> new ActivityStats());
            st.add(q.t, q.calc.observed, q.durationMs, q.id);
            if (q.calc.complete) st.tableCheck(q.calc.observed, q.calc.expected);
        }
        pruneQuests(d);
        return true;
    }

    static void pruneQuests(ProfileData d) {
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        d.slayerQuests.removeIf(q -> seen.merge(q.key() == null || !q.classified() ? "?" : q.key(), 1, Integer::sum) > ActivityStats.DETAILED);
    }

    /**
     * A mayor observation arrived: quests of the same term that were recorded while the mayor was unknown get the
     * snapshot and a recalculated expectation. Observed XP is never touched.
     */
    public static int attachMayor(java.util.function.LongFunction<Activity.MayorSnapshot> at) {
        int n = 0;
        for (Activity.SlayerQuest q : real().slayerQuests) {
            if (q.mayor != null || !q.classified()) continue;
            Activity.MayorSnapshot m = at.apply(q.t);
            if (m == null) continue;
            q.mayor = m;
            q.calc = Rewards.slayer(Activity.Slayer.byId(q.type), q.tier, m, q.calc.observed);
            ActivityStats st = real().slayerStats.get(q.key());
            if (st != null && q.calc.complete) st.tableCheck(q.calc.observed, q.calc.expected);
            n++;
        }
        if (n > 0) touch();
        return n;
    }

    // ------------------------------------------------------------------ corrections
    /** User correction of a run's floor. Statistics move with it; the change is logged. */
    public static boolean reclassifyRun(String id, String floor) {
        if (!Activity.isFloor(floor)) return false;
        ProfileData d = real();
        for (ProfileData.DungeonRun r : d.runs) {
            if (!id.equals(r.id)) continue;
            if (floor.equals(r.floor)) return false;
            String before = r.floor == null ? "?" : r.floor;
            if (r.complete()) {
                if (r.floor != null) {
                    ActivityStats old = d.floorStats.get(r.floor);
                    if (old != null) old.remove(r.id);
                    d.floorCompletions.computeIfPresent(r.floor, (k, v) -> v > 1 ? v - 1 : null);
                }
                d.floorStats.computeIfAbsent(floor, k -> new ActivityStats()).add(r.t, r.xp, r.durationMs, r.id);
                d.floorCompletions.merge(floor, 1, Integer::sum);
            }
            r.floor = floor;
            if (r.classification == null) r.classification = new Activity.Classification();
            r.classification.key = floor;
            r.classification.confirmed = true;
            r.classification.manual = true;
            r.classification.confidence = 1;
            r.classification.evidence.add("evidence.manual|" + floor);
            r.classification.history.add(System.currentTimeMillis() + "|" + before + "|" + floor + "|manual");
            History.repair("reclassified", profileName(), r.id, before, floor);
            touch();
            return true;
        }
        return false;
    }

    /** User correction of a slayer quest's type/tier. */
    public static boolean reclassifyQuest(String id, String type, int tier) {
        Activity.Slayer s = Activity.Slayer.byId(type);
        if (s == null || tier < 1 || tier > s.maxTier) return false;
        ProfileData d = real();
        for (Activity.SlayerQuest q : d.slayerQuests) {
            if (!id.equals(q.id)) continue;
            String before = q.key() == null ? "?" : q.key();
            if (s.key(tier).equals(q.key()) && q.classified()) return false;
            if ("complete".equals(q.status) && q.classified()) {
                ActivityStats old = d.slayerStats.get(q.key());
                if (old != null) old.remove(q.id);
            }
            q.type = s.id;
            q.tier = tier;
            q.classification.key = s.key(tier);
            q.classification.confirmed = true;
            q.classification.manual = true;
            q.classification.confidence = 1;
            q.classification.evidence.add("evidence.manual|" + s.key(tier));
            q.classification.history.add(System.currentTimeMillis() + "|" + before + "|" + s.key(tier) + "|manual");
            q.calc = Rewards.slayer(s, tier, q.mayor, q.calc.observed);
            if ("complete".equals(q.status)) d.slayerStats.computeIfAbsent(q.key(), k -> new ActivityStats()).add(q.t, q.calc.observed, q.durationMs, q.id);
            History.repair("reclassified", profileName(), q.id, before, s.key(tier));
            touch();
            return true;
        }
        return false;
    }

    /** Schema 4: statistics from existing runs and tier-tagged slayer gains. Existing records are kept unchanged. */
    static void migrateActivities(ProfileData d) {
        repair(d);
        java.util.List<ProfileData.DungeonRun> oldest = new ArrayList<>(d.runs);
        java.util.Collections.reverse(oldest);
        java.util.Map<String, Integer> counted = new java.util.HashMap<>();
        for (ProfileData.DungeonRun r : oldest) {
            if (r.id == null) r.id = "run-" + r.t + "-" + (r.floor == null ? "unknown" : r.floor);
            if (r.classification == null) {
                Activity.Classification c = new Activity.Classification();
                if (Activity.isFloor(r.floor)) {
                    c.key = r.floor;
                    c.confidence = 0.8;
                    c.confirmed = true;
                    c.evidence.add("evidence.migrated|" + r.floor);
                } else {
                    c.missing.add("evidence.no_floor");
                    r.floor = null;
                }
                r.classification = c;
            }
            if (r.calc == null) r.calc = Rewards.dungeon(r.floor, r.complete() ? r.xp : -1);
            if (r.complete() && r.floor != null) {
                d.floorStats.computeIfAbsent(r.floor, k -> new ActivityStats()).add(r.t, r.xp > 0 ? r.xp : -1, r.durationMs, r.id);
                counted.merge(r.floor, 1, Integer::sum);
            }
        }
        // lifetime completions recorded before detailed runs existed (runs were capped at 100)
        for (var e : d.floorCompletions.entrySet()) {
            if (!Activity.isFloor(e.getKey())) continue;
            int extra = e.getValue() - counted.getOrDefault(e.getKey(), 0);
            if (extra > 0) d.floorStats.computeIfAbsent(e.getKey(), k -> new ActivityStats()).addUncounted(extra);
        }
        // slayer gains tagged with the sidebar tier
        for (Activity.Slayer sl : Activity.Slayer.values()) {
            StatProgress p = d.stats.get(sl.statId());
            if (p == null) continue;
            for (ProfileData.Gain g : p.gains) {
                if (g == null || g.tag == null) continue;
                int tier = dev.skycore.tracking.parse.MessageParser.roman(g.tag.trim());
                if (tier < 1 || tier > sl.maxTier) continue;
                d.slayerStats.computeIfAbsent(sl.key(tier), k -> new ActivityStats()).add(g.t, g.amount, 0, "migrated-" + g.t);
            }
        }
    }

    /** Attach the player's own class XP to the run that just finished (within 30 s). */
    public static void attachClassXp(String dungeonClass, double xp) {
        ProfileData d = real();
        if (d.runs.isEmpty()) return;
        ProfileData.DungeonRun r = d.runs.get(0);
        if (System.currentTimeMillis() - r.t > 30_000 || r.classXp > 0) return;
        r.dungeonClass = dungeonClass;
        r.classXp = xp;
        touch();
    }

    public static void collectionLevel(String name, int level) {
        ProfileData d = real();
        Integer old = d.collections.put(name, level);
        ProfileData.CollectionProgress cp = d.collectionProgress.get(name);
        if (cp != null && cp.level < level) { cp.level = level; cp.current = cp.need = cp.percent = -1; }
        if (old == null || old != level) {
            d.addActivity("activity.collection", "COLLECTIONS", name, String.valueOf(level));
            if (old != null) for (Listener l : listeners) l.onMilestone("collection:" + name, level);
        }
        touch();
    }

    public static void collectionProgress(String name, int level, double current, double need, double percent) {
        ProfileData d = real();
        ProfileData.CollectionProgress cp = d.collectionProgress.computeIfAbsent(name, k -> new ProfileData.CollectionProgress());
        if (cp.level == level && cp.current == current && cp.need == need && cp.percent == percent) return;
        cp.level = level;
        cp.current = current;
        cp.need = need;
        cp.percent = percent;
        cp.updated = System.currentTimeMillis();
        d.collections.put(name, level);
        touch();
    }

    /** One bestiary family tier-up: advances progress towards the next milestone when its size is known. */
    public static void bestiaryFamilyTier(String family, int tier) {
        StatDef def = StatDef.get("bestiary");
        if (!tracks(def)) return;
        StatProgress p = real().stat(def.id());
        p.count++;
        if (p.need > 0) p.xp = Math.min(p.need, p.xp + 1);
        p.updated = System.currentTimeMillis();
        real().addActivity("activity.bestiary_family", "BESTIARY", family, String.valueOf(tier));
        ProfileData.ValueRecord fam = real().values.computeIfAbsent("bestiary_family:" + family, k -> new ProfileData.ValueRecord());
        if (tier > fam.value) { fam.value = tier; fam.source = "chat"; fam.status = DataStatus.VERIFIED; fam.updated = System.currentTimeMillis(); }
        touch();
    }

    public static void milestone(StatDef def, int level) {
        StatProgress p = real().stat(def.id());
        int before = p.level;
        setLevel(def, level);
        if (def.id().equals("bestiary")) p.xp = 0;
        if (before >= 0 && level > before) for (Listener l : listeners) l.onMilestone(def.id(), level);
    }

    private static void levelUp(StatDef def, StatProgress p) {
        real().addActivity("activity.level", def.icon().name(), def.nameKey(), String.valueOf(p.level));
        for (Listener l : listeners) l.onLevelUp(def, p, p.level);
    }

    /** Remember that a one-off notification was shown (persisted per profile). @return false if already shown. */
    public static boolean markNotified(String key) {
        if (isSample()) return true;
        boolean added = real().notified.add(key);
        if (added) {
            while (real().notified.size() > 500) real().notified.remove(real().notified.iterator().next());
            touch();
        }
        return added;
    }

    /** Mayor conditions right now (sample mayor in sample mode), or null if unknown for the current term. */
    public static Activity.MayorSnapshot mayorNow() {
        return isSample() ? SampleData.mayor() : History.mayorAt(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ counters from menus → reconciliation
    /**
     * Slayer boss kills per tier read from a menu the player opened. Compared with the previous reading to resolve
     * uncertain quests of this slayer type. The first reading only sets the baseline.
     */
    public static Reconciler.Outcome observeSlayerCounts(String slayerId, Map<String, Long> counts, long now) {
        return observeCounts(real(), "slayer:" + slayerId, counts, now, true, slayerId);
    }

    /** Completions per floor read from a menu the player opened. */
    public static Reconciler.Outcome observeFloorCounts(Map<String, Long> counts, long now) {
        return observeCounts(real(), "dungeon", counts, now, false, null);
    }

    static Reconciler.Outcome observeCounts(ProfileData d, String family, Map<String, Long> counts, long now, boolean slayer, String slayerId) {
        Reconciler.CountSnapshot prev = d.menuCounts.get(family);
        Reconciler.CountSnapshot next = new Reconciler.CountSnapshot();
        next.t = now;
        if (prev != null) next.counts.putAll(prev.counts);
        next.counts.putAll(counts);
        d.menuCounts.put(family, next);
        touch();
        if (prev == null) return new Reconciler.Outcome(Map.of(), "baseline", Map.of());
        if (prev.counts.equals(next.counts)) return new Reconciler.Outcome(Map.of(), "no_change", Map.of());
        Map<String, Long> confirmed = new LinkedHashMap<>();
        List<Reconciler.Uncertain> uncertain = new ArrayList<>();
        if (slayer) {
            for (Activity.SlayerQuest q : d.slayerQuests) {
                if (q.t <= prev.t || q.t > now || !"complete".equals(q.status)) continue;
                if (q.classified()) { if (slayerId.equals(q.type)) confirmed.merge(q.key(), 1L, Long::sum); }
                else if (slayerId.equals(q.type)) uncertain.add(new Reconciler.Uncertain(q.id, q.t, q.classification.candidates));
            }
        } else {
            for (ProfileData.DungeonRun r : d.runs) {
                if (r.t <= prev.t || r.t > now || !r.complete()) continue;
                if (r.classified()) confirmed.merge(r.floor, 1L, Long::sum);
                else uncertain.add(new Reconciler.Uncertain(r.id, r.t, r.classification == null ? List.of() : r.classification.candidates));
            }
        }
        // compare only counters present in both readings
        Map<String, Long> before = new LinkedHashMap<>(prev.counts), after = new LinkedHashMap<>(counts);
        Reconciler.Outcome o = Reconciler.byCounts(before, after, confirmed, uncertain);
        for (var e : o.resolved().entrySet()) {
            if (slayer) Reconciler.resolveQuest(d, e.getKey(), e.getValue(), 0.9, "evidence.menu_counts", "menu_counts");
            else Reconciler.resolveRun(d, e.getKey(), e.getValue(), 0.9, "evidence.menu_counts", "menu_counts");
        }
        return o;
    }

    /** Late evidence for one specific activity (same quest/run, seen right after it ended). */
    public static boolean resolveQuestLate(String id, String key, String evidence) {
        boolean ok = Reconciler.resolveQuest(real(), id, key, 0.9, evidence, "late_evidence");
        if (ok) touch();
        return ok;
    }

    public static boolean resolveRunLate(String id, String floor, String evidence) {
        boolean ok = Reconciler.resolveRun(real(), id, floor, 0.85, evidence, "late_evidence");
        if (ok) touch();
        return ok;
    }

    /** Completed activities still waiting for a classification (active profile). */
    public static int unresolvedCount() {
        ProfileData d = view();
        int n = 0;
        for (Activity.SlayerQuest q : d.slayerQuests) if ("complete".equals(q.status) && !q.classified()) n++;
        for (ProfileData.DungeonRun r : d.runs) if (r.complete() && !r.classified()) n++;
        return n;
    }

    /** Read access for estimates and tests. */
    public static ActivityStats floorStats(String floor) { return view().floorStats.get(floor); }

    public static ActivityStats slayerStats(String key) { return view().slayerStats.get(key); }

    public static void resetProfile() {
        saved.profiles.put(saved.current, new ProfileData());
        touch();
    }

    // ------------------------------------------------------------------ backup / import
    public static Object snapshot() { return saved; }

    public static void restore(com.google.gson.JsonElement json) {
        Saved s = Storage.GSON.fromJson(json, Saved.class);
        if (s == null || s.profiles == null) return;
        if (s.current == null) s.current = "default";
        s.profiles.values().removeIf(java.util.Objects::isNull);
        migrate(s);
        saved = s;
        identified = false;
        touch();
    }

    /** Package access for the integrity checker and import merge. */
    static Saved saved() { return saved; }
}
