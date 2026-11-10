package dev.skycore.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creature counters next to a skill.
 * <ul>
 *   <li><b>Fishing:</b> every sea creature gets its own chat line ("You caught a Sea Walker.", "You reeled in a Sea Archer.",
 *       "You have angered a legendary creature... Lord Jawbus has arrived."), so each line counts one. A double hook
 *       prints one line per creature. The Fishing XP that arrives within a few seconds is added to that creature.</li>
 *   <li><b>Mining and foraging:</b> kills show up as Combat XP, so a Combat gain while that skill is active (last two minutes)
 *       counts as one mob.</li>
 * </ul>
 * Counts live in memory for the current game session.
 */
public final class SideXp {
    private SideXp() {}

    public record Counter(int count, double xp) {}

    // Anchored at the start of the line: item catches ("GOOD CATCH! You caught ...") and player chat ("[MVP] name: ...") never match.
    // Every form below was seen in real Hypixel chat logs (counts in brackets): "A Squid appeared." (180), "You caught a Sea Walker."
    // (130), "It looks like you've disrupted the Sea Witch's brewing session..." (111), "You reeled in a Sea Archer." (76),
    // "The Rider of the Deep has emerged." (67), "Huh? A Catfish!" (48), "You've discovered a Guardian Defender of the sea." (14).
    private static final Pattern CREATURE = Pattern.compile("^(?:"
            + "You caught an? [A-Z][^.!]{1,40}\\."
            + "|You reeled in an? [A-Z][^.!]{1,40}\\."
            + "|An? [A-Z][A-Za-z' -]{1,30} appeared\\."
            + "|Huh\\? An? [A-Z][A-Za-z' -]{1,30}!"
            + "|You've discovered an? [A-Z][^.!]{1,40}\\."
            + "|It looks like you've disrupted the Sea Witch's brewing session\\. Watch out, she's furious!"
            + "|The Rider of the Deep has emerged\\."
            + "|(?:DOUBLE HOOK! )?You have angered a legendary creature\\.\\.\\. [^.!]{1,40} has arrived\\."
            + ")$");

    private static final long XP_LINK_MS = 6_000L;
    private static final long HOST_WINDOW_MS = 120_000L;
    private static final long PENDING_MS = 5_000L;
    private static final String FISHING = "skill_fishing";
    private static final String COMBAT = "skill_combat";
    private static final List<String> COMBAT_HOSTS = List.of("skill_mining", "skill_foraging");
    private static final List<String> COUNTED = List.of(FISHING, "skill_mining", "skill_foraging");

    private static final Map<String, Long> lastHostGain = new HashMap<>();
    private static final Map<String, int[]> counts = new HashMap<>();
    private static final Map<String, double[]> xps = new HashMap<>();
    private static final List<double[]> pending = new ArrayList<>(); // {time, xp} of combat gains with no host yet
    private static long catchAt = -1;       // when the newest creature line arrived and still waits for its XP
    private static long xpAt = -1;          // when the newest Fishing gain arrived and no creature line claimed it yet
    private static double xpAmount;

    public static boolean isHost(String statId) { return COUNTED.contains(statId); }

    public static synchronized void reset() {
        lastHostGain.clear();
        counts.clear();
        xps.clear();
        pending.clear();
        catchAt = xpAt = -1;
    }

    public static synchronized Counter get(String hostId) {
        int[] c = counts.get(hostId);
        double[] x = xps.get(hostId);
        return new Counter(c == null ? 0 : c[0], x == null ? 0 : x[0]);
    }

    /** True if a chat line (colors stripped) announces a caught sea creature. */
    public static boolean isCreatureLine(String text) {
        if (text == null) return false;
        Matcher m = CREATURE.matcher(text.trim());
        return m.matches();
    }

    /** Call for every game chat line. */
    public static synchronized void onChat(String text, long now) {
        if (!isCreatureLine(text)) return;
        credit(FISHING, 0);
        if (xpAt >= 0 && now - xpAt <= XP_LINK_MS) { // the XP came first
            xps.computeIfAbsent(FISHING, k -> new double[1])[0] += xpAmount;
            xpAt = -1;
            catchAt = -1;
        } else {
            catchAt = now;
        }
    }

    /** Call for every recorded skill gain. */
    public static synchronized void onGain(String statId, double amount, long now) {
        if (amount <= 0) return;
        if (FISHING.equals(statId)) {
            if (catchAt >= 0 && now - catchAt <= XP_LINK_MS) { // the creature line came first
                xps.computeIfAbsent(FISHING, k -> new double[1])[0] += amount;
                catchAt = -1;
                xpAt = -1;
            } else {
                xpAt = now;
                xpAmount = amount;
            }
            return;
        }
        pending.removeIf(p -> now - p[0] > PENDING_MS);
        if (COMBAT.equals(statId)) {
            String host = activeHost(now);
            if (host != null) credit(host, amount);
            else pending.add(new double[]{now, amount});
        } else if (COMBAT_HOSTS.contains(statId)) {
            lastHostGain.put(statId, now);
            for (double[] p : pending) credit(statId, p[1]); // the combat bar came first
            pending.clear();
        }
    }

    private static String activeHost(long now) {
        String best = null;
        long bestT = 0;
        for (Map.Entry<String, Long> e : lastHostGain.entrySet()) {
            if (now - e.getValue() <= HOST_WINDOW_MS && e.getValue() > bestT) { best = e.getKey(); bestT = e.getValue(); }
        }
        return best;
    }

    private static void credit(String host, double xp) {
        counts.computeIfAbsent(host, k -> new int[1])[0]++;
        xps.computeIfAbsent(host, k -> new double[1])[0] += xp;
    }

    /**
     * The XP of one action bar reading. The bar shows one skill at a time, so when a kill gives Combat and Fishing XP at the
     * same moment the Fishing part can be hidden and only shows up in the next reading's total. If the totals moved by
     * more than the shown gain within two minutes, the difference is real XP and is used instead of the shown gain.
     */
    public static double gainFromDelta(double delta, double shown, double need, long sinceMs) {
        if (delta < 0) return shown;
        if (delta <= Math.max(shown * 20, 1)) return delta;
        if (sinceMs > 0 && sinceMs <= 120_000L && need > 0 && delta <= need * 0.05) return delta;
        return shown;
    }
}
