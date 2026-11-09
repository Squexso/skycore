package dev.skycore.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything SKYCORE knows about one SkyBlock profile. Pure data – no UI or Minecraft references. */
public class ProfileData {
    public Map<String, StatProgress> stats = new LinkedHashMap<>();
    public List<DungeonRun> runs = new ArrayList<>();
    public List<Activity> activity = new ArrayList<>();
    public Map<String, Integer> collections = new LinkedHashMap<>();
    /** Exact collection progress read from the collection menu (name → progress). */
    public Map<String, CollectionProgress> collectionProgress = new LinkedHashMap<>();
    /** Notification keys already shown (thresholds per level) – prevents repeats across restarts. */
    public java.util.Set<String> notified = new java.util.LinkedHashSet<>();
    /** Best single-run Catacombs XP recorded. */
    public double bestRunXp;
    /** Completed runs per floor ("F7", "M5" …) recorded by SKYCORE. */
    public Map<String, Integer> floorCompletions = new LinkedHashMap<>();
    /** Level-less values observed in game: powder, motes, Kuudra completions … (key → record). */
    public Map<String, ValueRecord> values = new LinkedHashMap<>();
    /**
     * Minecraft account (UUID) this profile belongs to. Two accounts can both have a profile called "Apple";
     * the owner keeps them apart. null = recorded before ownership was tracked.
     */
    public String owner;
    /** SkyBlock profile name as shown in game (the storage key may carry a suffix for a second account). */
    public String name;
    /** Last time this profile was positively identified in game. */
    public long lastSeen;
    /** Statistics per exact dungeon floor ("F6", "M1" …) – normal and Master Mode never mixed. */
    public Map<String, ActivityStats> floorStats = new LinkedHashMap<>();
    /** Statistics per slayer type and tier ("enderman:4"). */
    public Map<String, ActivityStats> slayerStats = new LinkedHashMap<>();
    /** Slayer quests with classification and calculation (newest first, at most 50 per tier + 50 unclassified). */
    public List<dev.skycore.data.Activity.SlayerQuest> slayerQuests = new ArrayList<>();
    /** Last counter readings from menus ("slayer:enderman" → kills per tier, "dungeon" → completions per floor). */
    public Map<String, Reconciler.CountSnapshot> menuCounts = new LinkedHashMap<>();

    public ValueRecord value(String key) { return values.get(key); }

    public StatProgress stat(String id) { return stats.computeIfAbsent(id, k -> new StatProgress()); }

    public StatProgress peek(String id) {
        StatProgress p = stats.get(id);
        return p == null ? StatProgress.EMPTY : p;
    }

    public void addActivity(String key, String icon, String... args) {
        Activity a = new Activity();
        a.t = System.currentTimeMillis();
        a.key = key;
        a.icon = icon;
        a.args = args;
        activity.add(0, a);
        while (activity.size() > 60) activity.remove(activity.size() - 1);
    }

    public static class StatProgress {
        public static final StatProgress EMPTY = new StatProgress();
        /** -1 = unknown */
        public int level = -1;
        public double xp;
        /** XP required for the next level; -1 = unknown or maxed. */
        public double need = -1;
        /** Fallback when only a percentage is known (action bar in % mode). */
        public double percent = -1;
        public boolean calibrated;
        public long updated;
        /** Individual gains: one entry per run / boss / action-bar update. */
        public List<Gain> gains = new ArrayList<>();
        /** Completed runs / boss kills recorded by SKYCORE for this stat. */
        public int count;
        /** Where the current value came from: "chat", "action_bar", "menu", "tab", "manual". */
        public String source;
        /** Last time level/XP were read directly from the game (menu, action bar, slayer message). */
        public long verifiedAt;
        /** XP events added on top of the last verified or calibrated base. */
        public int derivedEvents;
        /** Level is known but XP inside the level is not (level-up message only, or rounded percentage). */
        public boolean xpUnknown;

        /** Reliability of the current level/XP – see {@link DataStatus}. */
        public DataStatus status() {
            if (level < 0) return DataStatus.UNAVAILABLE;
            if (xpUnknown || (need <= 0 && percent >= 0)) return DataStatus.ESTIMATED;
            if (derivedEvents > 0) return DataStatus.EVENT_TRACKED;
            if ("manual".equals(source) || source == null) return DataStatus.MANUAL;
            return DataStatus.VERIFIED;
        }

        public boolean known() { return level >= 0; }

        public float frac() {
            if (need > 0) return (float) Math.max(0, Math.min(1, xp / need));
            if (percent >= 0) return (float) Math.min(1, percent / 100.0);
            return 0;
        }

        public double remaining() { return need > 0 ? Math.max(0, need - xp) : -1; }

        public double fractionalLevel() { return level < 0 ? -1 : level + frac(); }

        public double lastGain() { return gains.isEmpty() ? 0 : gains.get(gains.size() - 1).amount; }
    }

    public static class Gain {
        public long t;
        public double amount;

        public Gain() {}

        /** Optional context, e.g. slayer tier "IV" – estimates prefer gains with the same tag. */
        public String tag;

        public Gain(long t, double amount) {
            this.t = t;
            this.amount = amount;
        }

        public Gain(long t, double amount, String tag) {
            this(t, amount);
            this.tag = tag;
        }
    }

    public static class DungeonRun {
        public long t;
        public double xp;
        public String floor;
        /** Score rank (S+, S, A …) when the summary showed it. */
        public String score;
        public int scorePoints;
        public String dungeonClass;
        public double classXp;
        /** "complete" (XP recorded) or "incomplete" (left / failed before an XP summary was seen). */
        public String status = "complete";
        public long startedAt;
        public long durationMs;
        /** Unique event id. */
        public String id;
        /** How the floor was identified; {@link #floor} is null when it could not be determined. */
        public dev.skycore.data.Activity.Classification classification;
        public dev.skycore.data.Activity.XpCalc calc;

        public boolean complete() { return status == null || status.equals("complete"); }

        /** Exact floor confirmed by evidence (or by the user). */
        public boolean classified() { return floor != null && dev.skycore.data.Activity.isFloor(floor) && (classification == null || classification.confirmed); }
    }

    /** A value without levels (powder, motes, completion counts). */
    public static class ValueRecord {
        public double value;
        public String source;
        public long updated;
        public DataStatus status = DataStatus.VERIFIED;
    }

    public static class CollectionProgress {
        public int level;
        public double current = -1, need = -1, percent = -1;
        public long updated;

        public float frac() {
            if (need > 0 && current >= 0) return (float) Math.max(0, Math.min(1, current / need));
            return percent >= 0 ? (float) Math.min(1, percent / 100) : 0;
        }
    }

    public static class Activity {
        public long t;
        public String key;
        public String icon;
        public String[] args;
    }
}
