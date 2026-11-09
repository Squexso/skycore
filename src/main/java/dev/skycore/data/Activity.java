package dev.skycore.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact activity identity and the audit records stored with every classified activity:
 * slayer type + tier, dungeon floor + mode, the evidence used, the XP calculation and the mayor conditions.
 */
public final class Activity {
    private Activity() {}

    // ------------------------------------------------------------------ slayer identity
    public enum Slayer {
        ZOMBIE("zombie", "Revenant Horror", "Zombies", "Zombie", 5),
        SPIDER("spider", "Tarantula Broodfather", "Spiders", "Spider", 4),
        WOLF("wolf", "Sven Packmaster", "Wolves", "Wolf", 4),
        ENDERMAN("enderman", "Voidgloom Seraph", "Endermen", "Enderman", 4),
        BLAZE("blaze", "Inferno Demonlord", "Blazes", "Blaze", 4),
        VAMPIRE("vampire", "Riftstalker Bloodfiend", "Vampires", "Vampire", 5);

        public final String id, boss, mobs, levelName;
        public final int maxTier;

        Slayer(String id, String boss, String mobs, String levelName, int maxTier) {
            this.id = id; this.boss = boss; this.mobs = mobs; this.levelName = levelName; this.maxTier = maxTier;
        }

        public String statId() { return "slayer_" + id; }

        public String key(int tier) { return id + ":" + tier; }

        public static Slayer byId(String id) {
            for (Slayer s : values()) if (s.id.equalsIgnoreCase(id)) return s;
            return null;
        }

        /** "Voidgloom Seraph" → ENDERMAN (exact boss name only). */
        public static Slayer byBoss(String boss) {
            if (boss == null) return null;
            for (Slayer s : values()) if (s.boss.equalsIgnoreCase(boss.trim())) return s;
            return null;
        }

        /** "Endermen" (quest requirement line) → ENDERMAN. */
        public static Slayer byMobs(String mobs) {
            if (mobs == null) return null;
            for (Slayer s : values()) if (s.mobs.equalsIgnoreCase(mobs.trim())) return s;
            return null;
        }

        /** "Enderman" (level message) → ENDERMAN. */
        public static Slayer byLevelName(String name) {
            if (name == null) return null;
            for (Slayer s : values()) if (s.levelName.equalsIgnoreCase(name.trim())) return s;
            return null;
        }
    }

    // ------------------------------------------------------------------ dungeon identity
    /** Entrance, normal floors F1–F7 and Master Mode M1–M7 – never merged. */
    public static final String[] FLOORS = {"E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"};

    public static boolean isFloor(String f) {
        if (f == null) return false;
        for (String x : FLOORS) if (x.equals(f)) return true;
        return false;
    }

    /** "normal", "master" or "entrance"; null for anything that is not an exact floor. */
    public static String mode(String floor) {
        if (!isFloor(floor)) return null;
        return floor.equals("E") ? "entrance" : floor.startsWith("M") ? "master" : "normal";
    }

    /** Summary line "Master Mode Catacombs - Floor III" → "M3"; entrance → "E". */
    public static String floor(boolean master, int number, boolean entrance) {
        if (entrance) return master ? null : "E";
        if (number < 1 || number > 7) return null;
        return (master ? "M" : "F") + number;
    }

    // ------------------------------------------------------------------ audit records
    /** How an activity was identified. Never filled from assumptions: missing evidence is listed instead. */
    public static class Classification {
        /** "enderman:4", "M3" – null when it could not be determined. */
        public String key;
        public double confidence;
        public boolean confirmed;
        public List<String> evidence = new ArrayList<>();
        /** What would have been needed to classify it (translation keys). */
        public List<String> missing = new ArrayList<>();
        /** Possible keys when the activity is uncertain (e.g. tiers whose reward matches the observed XP). */
        public List<String> candidates = new ArrayList<>();
        /** True if the user corrected the classification. */
        public boolean manual;
        /** True if SKYCORE resolved it later from reliable evidence. */
        public boolean autoResolved;
        /** Audit trail: "time|before|after|reason" for every later change (automatic or manual). */
        public List<String> history = new ArrayList<>();
    }

    /** One modifier considered for a reward. Applied only when {@code confirmed}. */
    public static class Bonus {
        public String source;   // "mayor:Aatrox", "minister:…"
        public String perk;     // "Slayer XP Buff"
        public double percent = -1;
        public boolean confirmed;
        public boolean applied;
        /** Why it was (not) applied – translation key. */
        public String reason;
    }

    /**
     * XP of one activity. {@code observed} is what the game reported, {@code expected} what the reward table and
     * confirmed bonuses predict. An observed value is never replaced by a calculated one.
     */
    public static class XpCalc {
        public double observed = -1, base = -1, expected = -1;
        public List<Bonus> bonuses = new ArrayList<>();
        /** "observed", "calculated", "estimated" or "unknown" – what {@link #recorded()} is. */
        public String kind = "unknown";
        /** All inputs of the calculation were confirmed. */
        public boolean complete;
        /** Translation key explaining gaps or discrepancies. */
        public String note;
        /** "builtin-1" or "user:rewards.json". */
        public String rewardSource;

        public double recorded() { return observed >= 0 ? observed : expected; }
    }

    /** Mayor conditions at the time of an activity (kept with the activity forever). */
    public static class MayorSnapshot {
        public String mayor;
        public String minister;
        /** The minister of that term was seen too (it can also hold a Slayer XP perk). */
        public boolean ministerKnown;
        public List<String> perks = new ArrayList<>();
        /** Slayer XP bonus read from the perk description; -1 = no such perk / unknown. */
        public double slayerXpPercent = -1;
        public String slayerPerk;
        /** Slayer ids the bonus applies to; empty = all slayers. */
        public List<String> slayerScope = new ArrayList<>();
        public long observedAt;
        public String source;
        public long termStart;

        public boolean appliesTo(String slayerId) { return slayerXpPercent > 0 && (slayerScope.isEmpty() || slayerScope.contains(slayerId)); }
    }

    public static class SlayerQuest {
        public long t;
        public String id;
        /** "enderman"; null if even the type is unknown. */
        public String type;
        /** 1..5; -1 = tier not determined. */
        public int tier = -1;
        /** "complete", "failed", "interrupted". */
        public String status = "complete";
        public Classification classification = new Classification();
        public XpCalc calc = new XpCalc();
        /** null = mayor unknown at that time. */
        public MayorSnapshot mayor;
        public long startedAt;
        public long durationMs;

        public boolean classified() { return classification != null && classification.confirmed && type != null && tier > 0; }

        public String key() { return type == null || tier <= 0 ? null : type + ":" + tier; }
    }

    public static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n); };
    }

    public static String slayerLabel(String key) {
        if (key == null) return "?";
        int i = key.indexOf(':');
        Slayer s = Slayer.byId(i > 0 ? key.substring(0, i) : key);
        String name = s == null ? key : s.boss;
        return i > 0 ? name + " T" + key.substring(i + 1) : name;
    }

}
