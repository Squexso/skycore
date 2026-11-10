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
    private static final class Holder { static final Pattern P = Pattern.compile("^(?:"
            + "You caught an? [A-Z][^.!]{1,40}\\."
            + "|You reeled in an? [A-Z][^.!]{1,40}\\."
            // "has appeared" is excluded on purpose: "A Spirit Bear has appeared!" is not a sea creature
            + "|(?:An? )?[A-Z](?:(?! has )[A-Za-z' -]){1,30} appeared[.!]"
            // Forms from a list of SkyBlock sea creature messages the user supplied (not all seen in their own logs):
            + "|(?:The )?[A-Z][A-Za-z' -]{1,30} (?:has )?surfaced!"
            + "|An? [A-Z][A-Za-z' -]{1,30} surfaces!"
            + "|The [A-Z][A-Za-z' -]{1,30} swam to the surface!"
            + "|The [A-Z][A-Za-z' -]{1,30} rose from the depths!"
            + "|(?:The )?[A-Z][A-Za-z' -]{1,30} has awakened!"
            + "|[A-Z][A-Za-z' -]{1,30} emerged!"
            + "|(?:The Emperor|The Grim Reaper|Great White Shark) (?:has )?appeared!"
            + "|Frozen Steve froze for ages, until now!"
            + "|Frosty the Snowman is throwing snowballs!"
            + "|The Grinch is stealing your gifts!"
            + "|It must be a full moon, it's a Werewolf!"
            + "|Huh\\? An? [A-Z][A-Za-z' -]{1,30}!"
            + "|You've discovered an? [A-Z][^.!]{1,40}\\."
            + "|It looks like you've disrupted the Sea Witch's brewing session\\. Watch out, she's furious!"
            + "|The Rider of the Deep has emerged\\."
            + "|(?:DOUBLE HOOK! )?You have angered a legendary creature\\.\\.\\. [^.!]{1,40} has arrived\\."
            + "|" + exactAlternation()
            + ")$"); }

    /** Complete message texts from a list of SkyBlock sea creature messages (colour codes removed), compared as a whole line. */
    private static final String[] EXACT = {
            "A Blue Ringed Octopus emerged from the deep!",
            "A Blue Shark dashes towards you!",
            "A Bogged rises from the mud.",
            "A Catfish!",
            "A Fiery Scuttler scuttles out of the lava.",
            "A Fire Eel slithers out of the lava!",
            "A Fireproof Witch cackles as she rises!",
            "A Flaming Worm surfaces from the lava!",
            "A Lava Blaze flies out of the magma!",
            "A Lava Flame sparks into existence!",
            "A Lava Leech hitches a ride on your line!",
            "A Lava Pigman storm out of the lava!",
            "A Magma Pillar erupts from the depths!",
            "A Magma Slug crawled out of the lava.",
            "A Manta Ray swam up to you!",
            "A massive Titanoboa slithers out of the swamp!",
            "A Moogma splashes out of the lava.",
            "A Nightmare lunges out of the water!",
            "A Nutcracker wants to crack your skull!",
            "A Plhlegblast oozes out of the magma!",
            "A Poisoned Water Worm surfaces!",
            "A Pyroclastic Worm slithers out of the magma!",
            "A Sea Leech!",
            "A Snapping Turtle surfaced!",
            "A Squid appeared.",
            "A Stridersurfer rides the waves to you!",
            "A Tadgang hops onto dry land!",
            "A Tiger Shark is hunting you!",
            "A tiny fin emerges from the water, you've caught a Nurse Shark...",
            "A Volcanic Snail crawled out of the lava.",
            "A Water Worm surfaces!",
            "A Wetwing flutters into the air.",
            "An Abyssal Miner strikes from the crystal depths!",
            "An Ent roots out of the swamp!",
            "An Inkling appeared.",
            "An Oasis Rabbit hops out of the water!",
            "An Oasis Sheep splashed out of the water!",
            "BrrRrRRr! A Yeti appeared!",
            "Frosty the Snowman is throwing snowballs!",
            "Frozen Steve froze for ages, until now!",
            "Hide your kids, hide your wife, it's a Great White Shark!",
            "It looks like you've disrupted the Sea Witch's brewing session. Watch out, she's furious!",
            "It must be a full moon, it's a Werewolf!",
            "It's only a scarecrow.",
            "It's the Carrot King!",
            "Pitch Darkness reveals a Night Squid.",
            "The ancient terror of the Crimson Isle, Lord Jawbus, has broken free!",
            "The elusive Nessie surfaces to greet you!",
            "The end of days is here! Ragnarok has awoken!",
            "The Grinch tries to steal your Christmas!",
            "The ground shakes as Thunder strikes from the magma!",
            "The legendary Loch Emperor makes his grand entrance!",
            "The mighty Taurus charges out of the lava!",
            "The Phantom Fisher arises!",
            "The Rider of the Deep has emerged.",
            "The Sea Emperor arises from the depths.",
            "The sky darkens... The Reindrake has flown into battle!",
            "The water bubbles and froths. A massive form emerges- you have disturbed the Wiki Tiki! You shall pay the price.",
            "The Water Hydra has surfaced!",
            "This can't be happening, the Grim Reaper is here!",
            "You caught a Sea Walker.",
            "You fished up some gooey Bayou Sludge.",
            "You fished up... Fried Chicken?",
            "You have awoken the Deep Sea Protector, prepare for a battle!",
            "You reeled in a Frog Man.",
            "You reeled in a Sea Archer.",
            "You stumbled upon a Sea Guardian.",
            "Your Chumcap Bucket trembles, it's an Agarimoo.",
            "You've discovered a Guardian Defender of the sea."
    };

    private static String exactAlternation() {
        StringBuilder b = new StringBuilder();
        for (String m : EXACT) { if (b.length() > 0) b.append('|'); b.append(Pattern.quote(m)); }
        return b.toString();
    }

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
        Matcher m = Holder.P.matcher(text.trim());
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
