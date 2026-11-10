package dev.skycore.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kills made while a gathering skill is active (sea creatures while fishing, mobs while mining or foraging).
 * <p>
 * Nothing here is read from a special message: a kill shows up as Combat XP on the action bar, so a Combat gain that
 * arrives while the host skill gained XP in the last two minutes (or within five seconds after it) counts as one
 * creature for that skill. Counts live in memory for the current game session.
 */
public final class SideXp {
    private SideXp() {}

    public record Counter(int count, double xp) {}

    private static final long HOST_WINDOW_MS = 120_000L;
    private static final long PENDING_MS = 5_000L;
    private static final String COMBAT = "skill_combat";
    private static final List<String> HOSTS = List.of("skill_fishing", "skill_mining", "skill_foraging");

    private static final Map<String, Long> lastHostGain = new HashMap<>();
    private static final Map<String, int[]> counts = new HashMap<>();
    private static final Map<String, double[]> xps = new HashMap<>();
    private static final List<double[]> pending = new ArrayList<>(); // {time, xp} of combat gains with no host yet

    public static boolean isHost(String statId) { return HOSTS.contains(statId); }

    public static synchronized void reset() {
        lastHostGain.clear();
        counts.clear();
        xps.clear();
        pending.clear();
    }

    public static synchronized Counter get(String hostId) {
        int[] c = counts.get(hostId);
        double[] x = xps.get(hostId);
        return new Counter(c == null ? 0 : c[0], x == null ? 0 : x[0]);
    }

    /** Call for every recorded skill gain. */
    public static synchronized void onGain(String statId, double amount, long now) {
        if (amount <= 0) return;
        pending.removeIf(p -> now - p[0] > PENDING_MS);
        if (COMBAT.equals(statId)) {
            String host = activeHost(now);
            if (host != null) credit(host, amount);
            else pending.add(new double[]{now, amount});
        } else if (HOSTS.contains(statId)) {
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
