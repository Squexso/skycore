package dev.skycore.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skycore.data.Activity.Bonus;
import dev.skycore.data.Activity.MayorSnapshot;
import dev.skycore.data.Activity.Slayer;
import dev.skycore.data.Activity.XpCalc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reward database. Built-in values are versioned ({@link #BUILTIN_VERSION}); the user can override single entries in
 * {@code config/skycore/rewards.json}, which is validated entry by entry – invalid entries are reported and ignored,
 * built-in data is never modified.
 * <p>
 * Slayer: base Slayer XP per boss and tier (per slayer type – they are not assumed to be identical).
 * Dungeons: <i>approximate</i> completion XP per floor, used only as a clearly labelled fallback until enough
 * personal runs exist (real rewards depend on score, class, items and bonuses).
 * Neither table has been verified against the live server; observed rewards are compared with it and every
 * difference is counted instead of silently changing the table.
 */
public final class Rewards {
    public static final int BUILTIN_VERSION = 1;

    private static final Map<Slayer, long[]> BUILTIN_SLAYER = new EnumMap<>(Slayer.class);
    private static final Map<String, Long> BUILTIN_FLOORS = new LinkedHashMap<>();

    static {
        BUILTIN_SLAYER.put(Slayer.ZOMBIE, new long[]{5, 25, 100, 500, 1500});
        BUILTIN_SLAYER.put(Slayer.SPIDER, new long[]{5, 25, 100, 500});
        BUILTIN_SLAYER.put(Slayer.WOLF, new long[]{5, 25, 100, 500});
        BUILTIN_SLAYER.put(Slayer.ENDERMAN, new long[]{5, 25, 100, 500});
        BUILTIN_SLAYER.put(Slayer.BLAZE, new long[]{5, 25, 100, 500});
        BUILTIN_SLAYER.put(Slayer.VAMPIRE, new long[]{10, 25, 60, 120, 150});
        long[] f = {50, 80, 160, 400, 1420, 2000, 4000, 20000};
        String[] fk = {"E", "F1", "F2", "F3", "F4", "F5", "F6", "F7"};
        for (int i = 0; i < fk.length; i++) BUILTIN_FLOORS.put(fk[i], f[i]);
        long[] m = {10000, 15000, 36500, 48500, 70000, 100000, 300000};
        for (int i = 0; i < m.length; i++) BUILTIN_FLOORS.put("M" + (i + 1), m[i]);
    }

    private static final Map<String, Long> userSlayer = new LinkedHashMap<>();
    private static final Map<String, Long> userFloors = new LinkedHashMap<>();
    private static List<String> errors = new ArrayList<>();
    private static int userVersion;

    private Rewards() {}

    // ------------------------------------------------------------------ lookups
    /** Base Slayer XP for one boss of this tier, or -1 if unknown. */
    public static long slayerBase(Slayer s, int tier) {
        if (s == null || tier < 1 || tier > s.maxTier) return -1;
        Long u = userSlayer.get(s.key(tier));
        if (u != null) return u;
        long[] b = BUILTIN_SLAYER.get(s);
        return b != null && tier <= b.length ? b[tier - 1] : -1;
    }

    public static boolean slayerOverridden(Slayer s, int tier) { return userSlayer.containsKey(s.key(tier)); }

    /** Approximate completion XP for a floor (fallback only), or -1. */
    public static long floorFallback(String floor) {
        Long u = userFloors.get(floor);
        if (u != null) return u;
        return BUILTIN_FLOORS.getOrDefault(floor, -1L);
    }

    public static String source(boolean overridden) { return overridden ? "user:rewards.json v" + userVersion : "builtin-" + BUILTIN_VERSION; }

    public static List<String> errors() { return errors; }

    public static int overrideCount() { return userSlayer.size() + userFloors.size(); }

    // ------------------------------------------------------------------ user overrides
    /**
     * Load overrides. Format:
     * <pre>{ "version": 1, "slayer": { "enderman": [5, 25, 100, 500] }, "floors": { "F7": 28000 } }</pre>
     * Use {@code null} in a slayer array to keep the built-in value for that tier.
     */
    public static void load(String json) {
        userSlayer.clear();
        userFloors.clear();
        List<String> err = new ArrayList<>();
        userVersion = 0;
        if (json != null && !json.isBlank()) {
            try {
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.has("version")) userVersion = root.get("version").getAsInt();
                if (root.has("slayer")) for (var e : root.getAsJsonObject("slayer").entrySet()) {
                    Slayer s = Slayer.byId(e.getKey());
                    if (s == null) { err.add("slayer '" + e.getKey() + "': unknown type"); continue; }
                    if (!e.getValue().isJsonArray()) { err.add("slayer '" + e.getKey() + "': expected a list of XP per tier"); continue; }
                    var arr = e.getValue().getAsJsonArray();
                    if (arr.size() > s.maxTier) err.add("slayer '" + e.getKey() + "': more tiers than " + s.maxTier + " – extra ignored");
                    for (int i = 0; i < Math.min(arr.size(), s.maxTier); i++) {
                        JsonElement v = arr.get(i);
                        if (v.isJsonNull()) continue;
                        double x = number(v);
                        if (x <= 0 || x > 10_000_000) { err.add("slayer '" + e.getKey() + "' T" + (i + 1) + ": invalid XP"); continue; }
                        userSlayer.put(s.key(i + 1), (long) x);
                    }
                }
                if (root.has("floors")) for (var e : root.getAsJsonObject("floors").entrySet()) {
                    if (!Activity.isFloor(e.getKey())) { err.add("floor '" + e.getKey() + "': unknown floor"); continue; }
                    double x = number(e.getValue());
                    if (x <= 0 || x > 100_000_000) { err.add("floor '" + e.getKey() + "': invalid XP"); continue; }
                    userFloors.put(e.getKey(), (long) x);
                }
            } catch (Exception ex) {
                err.add("rewards.json is not valid: " + ex.getClass().getSimpleName());
                userSlayer.clear();
                userFloors.clear();
            }
        }
        errors = err;
    }

    private static double number(JsonElement v) {
        try { return v.getAsDouble(); } catch (Exception e) { return -1; }
    }

    // ------------------------------------------------------------------ calculation
    /**
     * Slayer reward for one classified quest: base XP from the table, plus a mayor bonus only when the mayor and
     * the perk are confirmed for the time of the quest, the bonus size was read from the game, and it applies to
     * this slayer type. The observed XP (if any) is stored next to it, never replaced.
     */
    public static XpCalc slayer(Slayer s, int tier, MayorSnapshot mayor, double observed) {
        XpCalc c = new XpCalc();
        c.observed = observed;
        if (s == null) { c.kind = observed >= 0 ? "observed" : "unknown"; c.note = "calc.note.no_base"; return c; }
        long base = slayerBase(s, tier);
        c.base = base;
        c.rewardSource = source(s != null && slayerOverridden(s, tier));
        boolean bonusKnown;
        Bonus b = new Bonus();
        if (mayor == null) {
            b.reason = "calc.bonus.mayor_unknown";
            bonusKnown = false;
        } else if (mayor.slayerPerk == null && !mayor.ministerKnown) {
            b.source = "mayor:" + mayor.mayor;
            b.reason = "calc.bonus.minister_unknown";
            bonusKnown = false;
        } else if (mayor.slayerPerk == null) {
            b.source = "mayor:" + mayor.mayor;
            b.reason = "calc.bonus.no_slayer_perk";
            bonusKnown = true;
        } else if (mayor.slayerXpPercent <= 0) {
            b.source = "mayor:" + mayor.mayor;
            b.perk = mayor.slayerPerk;
            b.reason = "calc.bonus.effect_unknown";
            bonusKnown = false;
        } else if (!mayor.appliesTo(s.id)) {
            b.source = "mayor:" + mayor.mayor;
            b.perk = mayor.slayerPerk;
            b.percent = mayor.slayerXpPercent;
            b.confirmed = true;
            b.reason = "calc.bonus.not_applicable";
            bonusKnown = true;
        } else {
            b.source = "mayor:" + mayor.mayor;
            b.perk = mayor.slayerPerk;
            b.percent = mayor.slayerXpPercent;
            b.confirmed = true;
            b.applied = true;
            b.reason = "calc.bonus.applied";
            bonusKnown = true;
        }
        c.bonuses.add(b);
        if (base > 0) {
            double mult = 1 + (b.applied ? b.percent / 100.0 : 0);
            c.expected = Math.round(base * mult * 100) / 100.0;
        }
        c.complete = base > 0 && bonusKnown;
        if (observed >= 0) {
            c.kind = "observed";
            if (c.complete && Math.abs(observed - c.expected) > Math.max(0.5, c.expected * 0.001)) c.note = "calc.note.discrepancy";
            else if (!c.complete) c.note = "calc.note.incomplete_observed";
        } else if (base > 0) {
            c.kind = c.complete ? "calculated" : "estimated";
            c.note = c.complete ? null : "calc.note.incomplete";
        } else {
            c.kind = "unknown";
            c.note = "calc.note.no_base";
        }
        return c;
    }

    /** Dungeon run: the observed Catacombs XP is the record; no modifiers are claimed because none can be verified. */
    public static XpCalc dungeon(String floor, double observed) {
        XpCalc c = new XpCalc();
        c.observed = observed;
        c.rewardSource = source(userFloors.containsKey(floor));
        c.kind = observed >= 0 ? "observed" : "unknown";
        c.complete = observed >= 0;
        c.note = observed >= 0 ? "calc.note.dungeon_observed" : "calc.note.no_xp";
        return c;
    }

    /**
     * Tiers whose expected reward (with or without the known bonus) equals the observed XP – used only to list
     * candidates for an uncertain quest, never to decide the tier on its own.
     */
    public static List<Integer> tiersMatching(Slayer s, double observed, MayorSnapshot mayor) {
        List<Integer> out = new ArrayList<>();
        if (s == null || observed <= 0) return out;
        for (int tier = 1; tier <= s.maxTier; tier++) {
            long base = slayerBase(s, tier);
            if (base <= 0) continue;
            boolean plain = Math.abs(base - observed) < 0.5;
            boolean boosted = mayor != null && mayor.appliesTo(s.id) && Math.abs(base * (1 + mayor.slayerXpPercent / 100.0) - observed) < 0.5;
            if (plain || boosted) out.add(tier);
        }
        return out;
    }
}
