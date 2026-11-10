package dev.skycore.data;

import com.google.gson.reflect.TypeToken;
import dev.skycore.core.Lang;
import dev.skycore.core.Storage;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crimson Isle reputation (Mage / Barbarian), read from chat only: the gain lines ("+50 Mage Reputation",
 * "You earned 25 Mage Reputation.", "You gained 20 Reputation for ...") and the status block that shows
 * "1490 / 3000" with the two level names. Gains are summed per calendar day; the estimate to the next level is
 * counted in play days (days with reputation), because gains are small and sporadic. Everything stays local.
 */
public final class Reputation {
    private Reputation() {}

    public static class Fac {
        /** Reputation inside the current level; -1 = not read yet (or a new level started). */
        public long cur = -1;
        /** Reputation needed for the next level (from the status block); 0 = unknown. */
        public long need;
        public String from = "", to = "";
        public long readAt;
        public long lastGainAt;
        /** yyyy-MM-dd -> reputation gained that day. */
        public TreeMap<String, Long> daily = new TreeMap<>();

        public boolean known() { return cur >= 0 && need > 0; }
        public long remaining() { return known() ? Math.max(0, need - cur) : -1; }
        public float frac() { return known() ? Math.min(1f, (float) cur / need) : -1f; }
    }

    public static class Store {
        public Map<String, Fac> factions = new LinkedHashMap<>();
        public String last;
    }

    private static Store store = new Store();
    private static boolean dirty;
    private static final Map<String, Long> SESSION = new HashMap<>();

    static final int KEEP_DAYS = 60;
    private static final Pattern GAIN_PLUS = Pattern.compile("^\\+(\\d[\\d,]*) (Mage|Barbarian) Reputation$");
    private static final Pattern GAIN_EARNED = Pattern.compile("^You earned (\\d[\\d,]*) (Mage|Barbarian) Reputation\\.$");
    private static final Pattern GAIN_ANON = Pattern.compile("^You gained (\\d[\\d,]*) Reputation\\b.*");
    private static final Pattern HEADER = Pattern.compile("^(Mage|Barbarian) Reputation$");
    private static final Pattern LEVELS = Pattern.compile("^([A-Za-z]+)\\s{2,}([A-Za-z]+)$");
    private static final Pattern PROGRESS = Pattern.compile("^(\\d[\\d,]*) / (\\d[\\d,]*) \\([\\d.]+%\\)$");

    // status block in progress: header seen -> level names -> "cur / need"
    private static String blockFaction, blockFrom = "", blockTo = "";
    private static long blockAt;

    public static void load() {
        store = Storage.load("reputation.json", new TypeToken<Store>() {}.getType(), Store::new);
        if (store.factions == null) store.factions = new LinkedHashMap<>();
        store.factions.values().removeIf(java.util.Objects::isNull);
        for (Fac f : store.factions.values()) if (f.daily == null) f.daily = new TreeMap<>();
    }

    public static void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        Storage.save("reputation.json", store);
    }

    /** Tests and profile-independent resets. */
    static void reset() {
        store = new Store();
        dirty = false;
        SESSION.clear();
        blockFaction = null;
    }

    public static synchronized Fac get(String faction) { return faction == null ? null : store.factions.get(faction); }

    /** The faction the player gains reputation in (the one seen last), or null. */
    public static synchronized String current() { return store.last; }

    public static synchronized long session(String faction) { return SESSION.getOrDefault(faction, 0L); }

    public static synchronized long today(String faction, long now) {
        Fac f = store.factions.get(faction);
        return f == null ? 0 : f.daily.getOrDefault(dayKey(now), 0L);
    }

    /** Time of the newest gain of the current faction (0 = none yet). */
    public static synchronized long lastGainAt() {
        Fac f = get(store.last);
        return f == null ? 0 : f.lastGainAt;
    }

    private static String dayKey(long ms) { return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString(); }

    private static long num(String s) { return Long.parseLong(s.replace(",", "")); }

    /** Feed every chat line (colour codes already stripped). */
    public static synchronized void onChat(String raw, long now) {
        String t = raw.strip();
        if (t.isEmpty()) return;
        if (blockFaction != null && now - blockAt > 3000) blockFaction = null;
        Matcher m;
        if ((m = GAIN_PLUS.matcher(t)).matches() || (m = GAIN_EARNED.matcher(t)).matches()) {
            gain(m.group(2), num(m.group(1)), now);
            return;
        }
        if ((m = GAIN_ANON.matcher(t)).matches()) { // miniboss kill: no faction named, it goes to the one the player is in
            if (store.last != null) gain(store.last, num(m.group(1)), now);
            return;
        }
        if ((m = HEADER.matcher(t)).matches()) {
            blockFaction = m.group(1);
            blockFrom = blockTo = "";
            blockAt = now;
            return;
        }
        if (blockFaction == null) return;
        if ((m = LEVELS.matcher(t)).matches()) { blockFrom = m.group(1); blockTo = m.group(2); return; }
        if ((m = PROGRESS.matcher(t)).matches()) {
            Fac f = fac(blockFaction);
            f.cur = num(m.group(1));
            f.need = num(m.group(2));
            if (!blockFrom.isEmpty()) { f.from = blockFrom; f.to = blockTo; }
            f.readAt = now;
            store.last = blockFaction;
            blockFaction = null;
            dirty = true;
        }
    }

    private static Fac fac(String name) { return store.factions.computeIfAbsent(name, k -> new Fac()); }

    static void gain(String faction, long amount, long now) {
        if (amount <= 0) return;
        Fac f = fac(faction);
        store.last = faction;
        f.lastGainAt = now;
        f.daily.merge(dayKey(now), amount, Long::sum);
        while (f.daily.size() > KEEP_DAYS) f.daily.pollFirstEntry();
        SESSION.merge(faction, amount, Long::sum);
        // the status block lists the new total already, so a gain printed right after it must not be added twice
        if (f.known() && now - f.readAt > 2000) {
            f.cur += amount;
            if (f.cur >= f.need) { f.cur = -1; f.need = 0; f.from = f.to; f.to = ""; } // next level: needs a new reading
        }
        dirty = true;
    }

    /** Days until the next level: fast / mid / slow, from the typical reputation per day played. */
    public record Eta(double fast, double mid, double slow, int days, long perDay) {
        public boolean preliminary() { return days < 3; }
    }

    static Eta eta(Fac f, long now) {
        if (f == null || !f.known()) return null;
        long rem = f.remaining();
        if (rem <= 0) return null;
        String today = dayKey(now);
        // completed days only (today is still running); with no completed day yet, today's total is a first guess
        double[] v = f.daily.subMap(dayKey(now - 14L * 24 * 3600_000L), true, today, false).values().stream().filter(x -> x > 0).mapToDouble(Long::doubleValue).toArray();
        if (v.length == 0) {
            long td = f.daily.getOrDefault(today, 0L);
            if (td <= 0) return null;
            v = new double[]{td};
        }
        Arrays.sort(v);
        double med = pct(v, 0.5);
        double hi = v.length < 3 ? v[v.length - 1] : pct(v, 0.75);
        double lo = v.length < 3 ? v[0] : pct(v, 0.25);
        double mid = rem / med;
        double fast = Math.max(Math.min(mid, rem / hi), mid * 0.6);
        double slow = Math.min(Math.max(mid, rem / lo), mid * 1.6);
        return new Eta(fast, mid, slow, v.length, Math.round(med));
    }

    public static synchronized Eta eta(String faction, long now) { return eta(get(faction), now); }

    /** "about 2-3 days", "~1 day" (preliminary), "< 1 day". */
    public static String formatDays(Eta e) {
        long a = Math.max(1, Math.round(e.fast())), b = Math.max(1, Math.round(e.slow()));
        String p = e.preliminary() ? "~" : "";
        if (e.slow() < 1) return Lang.t("rep.lt_day");
        if (a >= b) return p + (b == 1 ? Lang.t("rep.day") : Lang.t("rep.days", b));
        return p + Lang.t("rep.days_range", a, b);
    }

    private static double pct(double[] sorted, double q) {
        double idx = q * (sorted.length - 1);
        int i = (int) Math.floor(idx);
        double fr = idx - i;
        return i + 1 < sorted.length ? sorted[i] * (1 - fr) + sorted[i + 1] * fr : sorted[i];
    }
}
