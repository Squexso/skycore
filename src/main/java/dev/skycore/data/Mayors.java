package dev.skycore.data;

import dev.skycore.data.Activity.MayorSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Mayor and minister observations with timestamps (stored in history.json).
 * <p>
 * A mayor is only considered active for the SkyBlock year (term) in which it was <i>seen in a menu the player
 * opened</i>. Terms change on Late Spring 27 of every SkyBlock year (SkyBlock time: year 1 started at
 * {@link #EPOCH}, 1 day = 20 minutes, 1 year = 372 days). An observation from an earlier term is never used for a
 * later activity. Jerry's rotating perks are only trusted within 30 minutes of being seen.
 * Each activity stores its own snapshot, so later mayor changes never alter past records.
 */
public final class Mayors {
    public static final long EPOCH = 1_560_275_700_000L;
    public static final long DAY = 20 * 60_000L;
    public static final long YEAR = 372 * DAY;
    /** Late Spring 27 = day 88 of the year (0-based). */
    public static final long TERM_OFFSET = 88 * DAY;
    private static final long JERRY_WINDOW = 30 * 60_000L;
    private static final int MAX_OBSERVATIONS = 120;

    public static class Observation {
        public long t;
        /** Last time the same information was seen again. */
        public long confirmedAt;
        /** "mayor" or "minister" */
        public String role;
        public String name;
        public List<String> perks = new ArrayList<>();
        public double slayerXpPercent = -1;
        public String slayerPerk;
        public List<String> slayerScope = new ArrayList<>();
        public String source;
    }

    private Mayors() {}

    public static long termStart(long t) {
        long k = Math.floorDiv(t - EPOCH - TERM_OFFSET, YEAR);
        return EPOCH + TERM_OFFSET + k * YEAR;
    }

    public static long termEnd(long t) { return termStart(t) + YEAR; }

    /** Record what a menu showed. Re-seeing identical information only refreshes {@code confirmedAt}. */
    public static void observe(List<Observation> list, Observation o) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Observation p = list.get(i);
            if (termStart(p.t) != termStart(o.t)) break;
            if (same(p, o)) { p.confirmedAt = o.t; return; }
        }
        if (o.confirmedAt == 0) o.confirmedAt = o.t;
        list.add(o);
        while (list.size() > MAX_OBSERVATIONS) list.remove(0);
    }

    private static boolean same(Observation a, Observation b) {
        return eq(a.role, b.role) && eq(a.name, b.name) && a.perks.equals(b.perks) && a.slayerXpPercent == b.slayerXpPercent && eq(a.slayerPerk, b.slayerPerk);
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static boolean validFor(Observation o, long t) {
        if (termStart(o.t) != termStart(t)) return false;
        if ("Jerry".equalsIgnoreCase(o.name)) return Math.abs(o.confirmedAt - t) <= JERRY_WINDOW || Math.abs(o.t - t) <= JERRY_WINDOW;
        return true;
    }

    /**
     * Mayor conditions for an activity at time {@code t}: the newest mayor (and minister) observation of the same
     * term. Returns null when no observation exists for that term – the mayor is then unknown.
     */
    public static MayorSnapshot at(List<Observation> list, long t) {
        Observation mayor = null, minister = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            Observation o = list.get(i);
            if (!validFor(o, t)) continue;
            if ("minister".equals(o.role)) { if (minister == null) minister = o; }
            else if (mayor == null) mayor = o;
        }
        if (mayor == null) return null;
        MayorSnapshot s = new MayorSnapshot();
        s.mayor = mayor.name;
        s.perks.addAll(mayor.perks);
        s.observedAt = mayor.confirmedAt;
        s.source = mayor.source;
        s.termStart = termStart(t);
        s.slayerPerk = mayor.slayerPerk;
        s.slayerXpPercent = mayor.slayerXpPercent;
        s.slayerScope.addAll(mayor.slayerScope);
        if (minister != null) {
            s.minister = minister.name;
            s.ministerKnown = true;
            for (String p : minister.perks) s.perks.add(p + " (" + minister.name + ")");
            if (s.slayerPerk == null && minister.slayerPerk != null) {
                s.slayerPerk = minister.slayerPerk + " (" + minister.name + ")";
                s.slayerXpPercent = minister.slayerXpPercent;
                s.slayerScope.addAll(minister.slayerScope);
            }
        }
        return s;
    }
}
