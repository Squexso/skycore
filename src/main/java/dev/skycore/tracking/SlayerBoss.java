package dev.skycore.tracking;

import dev.skycore.tracking.parse.MessageParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the big slayer readout shows: while the quest still needs kills, how many mobs are left (only the last few), and once the
 * boss stands, its health in a short form (7.6M, 450K, 800). The numbers come from what the game already shows: the sidebar's
 * "(cur/need) Combat XP" line and the boss's own name tag. Nothing is sent anywhere.
 */
public final class SlayerBoss {
    private SlayerBoss() {}

    public enum Mode { NONE, MOBS, HP }

    /** @param mobs mobs still needed (MOBS), @param hp the health text (HP) */
    public record View(Mode mode, int mobs, String hp) {
        static final View NONE = new View(Mode.NONE, 0, "");
    }

    /** The count shows up once this few mobs are left. */
    static final int SHOW_MOBS_UP_TO = 3;
    private static final long HP_FRESH_MS = 4_000;

    private static final Pattern COMBAT_XP = Pattern.compile("\\(([\\d,.]+[kKmM]?)/([\\d,.]+[kKmM]?)\\)\\s*Combat XP");
    private static final Pattern HP_TAG = Pattern.compile("([\\d,.]+[kKmM]?)\\s*❤");

    private static final List<Double> deltas = new ArrayList<>();
    private static double cur = -1, need = -1;
    private static int mobsLeft = -1;
    private static String phase;
    private static double hp = -1;
    private static long hpAt;

    public static synchronized void reset() {
        deltas.clear();
        cur = need = -1;
        mobsLeft = -1;
        phase = null;
        hp = -1;
        hpAt = 0;
    }

    /** The sidebar was read: its lines and the quest phase ("combat", "boss", "slain" or null). */
    public static synchronized void sidebar(List<String> lines, String slayerPhase, long now) {
        phase = slayerPhase;
        if (!"combat".equals(slayerPhase)) {
            if (!"boss".equals(slayerPhase)) { deltas.clear(); cur = need = -1; hp = -1; }
            mobsLeft = -1;
            return;
        }
        hp = -1;
        for (String raw : lines) {
            Matcher m = COMBAT_XP.matcher(raw);
            if (!m.find()) continue;
            double c = MessageParser.num(m.group(1)), n = MessageParser.num(m.group(2));
            observe(c, n);
            return;
        }
    }

    /** Pure core, also used by tests: one reading of the quest's combat XP. */
    static void observe(double c, double n) {
        if (n <= 0) return;
        if (n != need || c < cur) deltas.clear(); // a new quest, or the counter started over
        else if (c > cur && cur >= 0 && c - cur <= n / 2) { // one kill's worth
            deltas.add(c - cur);
            while (deltas.size() > 5) deltas.remove(0);
        }
        cur = c;
        need = n;
        mobsLeft = left(c, n, deltas);
    }

    /** Mobs still needed, from the typical XP of the last kills; -1 when no kill has been seen yet. */
    static int left(double c, double n, List<Double> kills) {
        if (kills.isEmpty() || c >= n) return kills.isEmpty() ? -1 : 0;
        List<Double> s = new ArrayList<>(kills);
        s.sort(Double::compare);
        double typical = s.get(s.size() / 2);
        return typical <= 0 ? -1 : (int) Math.ceil((n - c) / typical);
    }

    /** A name tag of the own boss was seen: "Voidgloom Seraph IV 7.6M❤". */
    public static synchronized void tag(String text, long now) {
        Matcher m = HP_TAG.matcher(text);
        if (!m.find()) return;
        double v = MessageParser.num(m.group(1));
        if (v < 0) return;
        hp = v;
        hpAt = now;
    }

    public static synchronized View view(long now) {
        if ("boss".equals(phase) && hp >= 0 && now - hpAt <= HP_FRESH_MS) return new View(Mode.HP, 0, hpText(hp));
        if ("combat".equals(phase) && mobsLeft >= 1 && mobsLeft <= SHOW_MOBS_UP_TO) return new View(Mode.MOBS, mobsLeft, "");
        return View.NONE;
    }

    /** Health the short way: 7.6M, 12M, 450K, 800 (no string of zeros). */
    public static String hpText(double v) {
        if (v >= 999_950) return trim(v / 1e6) + "M";
        if (v >= 1000) return trim(v / 1e3) + "K";
        return String.valueOf(Math.round(v));
    }

    private static String trim(double d) {
        String s = String.format(Locale.ROOT, d >= 100 ? "%.0f" : "%.1f", d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
