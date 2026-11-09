package dev.skycore.data;

/**
 * SkyBlock level requirements (XP needed to go from level i to i+1, index 0 = level 0→1).
 * Values from the Hypixel SkyBlock wiki. If Hypixel rebalances a table, update it here only.
 */
public final class XpTables {
    private XpTables() {}

    public static final long[] SKILL = {50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500, 5000, 7500, 10000, 15000, 20000, 30000,
            50000, 75000, 100000, 200000, 300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000, 1100000, 1200000, 1300000,
            1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000, 2500000, 2600000,
            2750000, 2900000, 3100000, 3400000, 3700000, 4000000, 4300000, 4600000, 4900000, 5200000, 5500000, 5800000, 6100000,
            6400000, 6700000, 7000000};

    public static final long[] RUNECRAFTING = {50, 100, 125, 160, 200, 250, 315, 400, 500, 625, 785, 1000, 1250, 1600, 2000, 2465,
            3125, 4000, 5000, 6200, 7800, 9800, 12200, 15300, 19050};

    public static final long[] SOCIAL = {50, 100, 150, 250, 500, 750, 1000, 1250, 1500, 2000, 2500, 3000, 3750, 4500, 6000, 8000,
            10000, 12500, 15000, 20000, 25000, 30000, 35000, 40000, 50000};

    public static final long[] CATACOMBS = {50, 75, 110, 160, 230, 330, 470, 670, 950, 1340, 1890, 2665, 3760, 5260, 7380, 10300,
            14400, 20000, 27600, 38000, 52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000, 1065000,
            1410000, 1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000, 15000000, 19000000, 24000000,
            30000000, 38000000, 48000000, 60000000, 75000000, 93000000, 116250000};

    /** Heart of the Mountain tiers 1→2 … 9→10. */
    public static final long[] HOTM = {3000, 9000, 25000, 60000, 100000, 150000, 210000, 290000, 400000};

    // Slayer tables are cumulative on the wiki; converted to per-level requirements.
    public static final long[] SLAYER_ZOMBIE = perLevel(5, 15, 200, 1000, 5000, 20000, 100000, 400000, 1000000);
    public static final long[] SLAYER_SPIDER = perLevel(5, 25, 200, 1000, 5000, 20000, 100000, 400000, 1000000);
    public static final long[] SLAYER_STANDARD = perLevel(10, 30, 250, 1500, 5000, 20000, 100000, 400000, 1000000);
    public static final long[] SLAYER_VAMPIRE = perLevel(20, 75, 240, 840, 2400);

    private static long[] perLevel(long... cumulative) {
        long[] out = new long[cumulative.length];
        long prev = 0;
        for (int i = 0; i < cumulative.length; i++) { out[i] = cumulative[i] - prev; prev = cumulative[i]; }
        return out;
    }

    /** XP needed to go from {@code level} to {@code level+1}; -1 if maxed / unknown. */
    public static long need(long[] table, int level) {
        if (table == null || level < 0 || level >= table.length) return -1;
        return table[level];
    }

    /** Identify the current level from the "needed" figure shown in the action bar (unique per table). */
    public static int levelFromNeed(long[] table, long need, int maxLevel) {
        if (table == null) return -1;
        for (int i = 0; i < Math.min(table.length, maxLevel); i++) if (table[i] == need) return i;
        return -1;
    }

    /** Total XP from (level, xpInto) up to targetLevel. */
    public static double xpTo(long[] table, int level, double xpInto, int target) {
        if (table == null || level < 0) return -1;
        double sum = 0;
        for (int l = level; l < Math.min(target, table.length); l++) sum += table[l];
        return Math.max(0, sum - xpInto);
    }

    public static int roman(String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (Character.toUpperCase(s.charAt(i))) {
                case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; case 'C' -> 100; case 'D' -> 500; case 'M' -> 1000;
                default -> -10000;
            };
            if (v < 0) return -1;
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }
}
