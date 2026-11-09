package dev.skycore.data;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Statistics of one exactly classified activity (one dungeon floor such as "M3", or one slayer tier such as
 * "enderman:4"). The newest {@link #DETAILED} activities are kept in full; everything older survives as lifetime
 * aggregates: count, total, min/max, a sparse log-scale histogram (median within ~3 %), timing totals and
 * monthly sums. Nothing is lost when detailed entries roll off.
 */
public class ActivityStats {
    public static final int DETAILED = 50;
    /** Histogram resolution: 40 bins per power of ten → a bin spans ~5.9 %, the median error is below ~3 %. */
    static final int BINS_PER_DECADE = 40;

    public static class Sample {
        public long t;
        /** -1 = completed but no XP was observed. */
        public double xp = -1;
        public long durationMs;
        public String id;

        public Sample() {}

        Sample(long t, double xp, long durationMs, String id) { this.t = t; this.xp = xp; this.durationMs = durationMs; this.id = id; }
    }

    /** Completed activities (including those without observed XP). */
    public long count;
    /** Activities with observed XP. */
    public long xpCount;
    public double totalXp;
    public double min = -1, max = -1;
    /** Activities with both XP and a known duration – the basis of XP per hour. */
    public long timedCount;
    public double timedMs, timedXp;
    /** Sparse lifetime histogram of observed XP: bin → count. */
    public Map<Integer, Integer> hist = new TreeMap<>();
    /** Newest last. */
    public List<Sample> recent = new ArrayList<>();
    /** "2026-11" → {count, xp}. */
    public Map<String, double[]> monthly = new TreeMap<>();
    /** How often an observed reward matched / differed from the reward table (slayer). */
    public int tableMatches, tableMismatches;
    public double lastMismatchObserved = -1, lastMismatchExpected = -1;

    public void add(long t, double xp, long durationMs, String id) {
        count++;
        boolean hasXp = xp >= 0 && Double.isFinite(xp);
        if (hasXp) {
            xpCount++;
            totalXp += xp;
            min = min < 0 ? xp : Math.min(min, xp);
            max = Math.max(max, xp);
            hist.merge(bin(xp), 1, Integer::sum);
            if (durationMs > 0) { timedCount++; timedMs += durationMs; timedXp += xp; }
        }
        double[] m = monthly.computeIfAbsent(month(t), k -> new double[2]);
        m[0]++;
        if (hasXp) m[1] += xp;
        recent.add(new Sample(t, hasXp ? xp : -1, durationMs, id));
        while (recent.size() > DETAILED) recent.remove(0);
    }

    /**
     * Add another activity's statistics (profile assignment). Detailed samples already present here (same id) are
     * left out of every total, so nothing is counted twice.
     */
    public void mergeFrom(ActivityStats o) {
        if (o == null) return;
        o.repair();
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Sample s : recent) if (s.id != null) have.add(s.id);
        long cnt = o.count, xc = o.xpCount, tc = o.timedCount;
        double tot = o.totalXp, tms = o.timedMs, txp = o.timedXp;
        Map<Integer, Integer> h = new TreeMap<>(o.hist);
        Map<String, double[]> mon = new TreeMap<>();
        for (var e : o.monthly.entrySet()) mon.put(e.getKey(), e.getValue().clone());
        List<Sample> add = new ArrayList<>();
        for (Sample s : o.recent) {
            if (s.id != null && have.contains(s.id)) {
                cnt--;
                double[] m = mon.get(month(s.t));
                if (m != null) { m[0]--; if (s.xp >= 0) m[1] -= s.xp; }
                if (s.xp >= 0) {
                    xc--; tot -= s.xp;
                    h.computeIfPresent(bin(s.xp), (k, v) -> v > 1 ? v - 1 : null);
                    if (s.durationMs > 0) { tc--; tms -= s.durationMs; txp -= s.xp; }
                }
            } else add.add(s);
        }
        count += Math.max(0, cnt);
        xpCount += Math.max(0, xc);
        totalXp += Math.max(0, tot);
        timedCount += Math.max(0, tc);
        timedMs += Math.max(0, tms);
        timedXp += Math.max(0, txp);
        for (var e : h.entrySet()) hist.merge(e.getKey(), e.getValue(), Integer::sum);
        for (var e : mon.entrySet()) {
            double[] m = monthly.computeIfAbsent(e.getKey(), k -> new double[2]);
            m[0] += Math.max(0, e.getValue()[0]);
            m[1] += Math.max(0, e.getValue()[1]);
        }
        if (o.min >= 0 && xc > 0) min = min < 0 ? o.min : Math.min(min, o.min);
        if (o.max >= 0 && xc > 0) max = Math.max(max, o.max);
        tableMatches += o.tableMatches;
        tableMismatches += o.tableMismatches;
        recent.addAll(add);
        recent.sort((a, b) -> Long.compare(a.t, b.t));
        while (recent.size() > DETAILED) recent.remove(0);
    }

    /** Lifetime completions without XP (e.g. counted before detailed records existed). */
    public void addUncounted(long n) { if (n > 0) count += n; }

    /**
     * Remove a detailed activity (reclassification). Only activities still in the detailed window can be moved.
     * @return the removed sample, or null
     */
    public Sample remove(String id) {
        for (int i = 0; i < recent.size(); i++) {
            Sample s = recent.get(i);
            if (id == null || !id.equals(s.id)) continue;
            recent.remove(i);
            count = Math.max(0, count - 1);
            double[] m = monthly.get(month(s.t));
            if (m != null) { m[0] = Math.max(0, m[0] - 1); if (s.xp >= 0) m[1] = Math.max(0, m[1] - s.xp); }
            if (s.xp >= 0) {
                xpCount = Math.max(0, xpCount - 1);
                totalXp = Math.max(0, totalXp - s.xp);
                hist.computeIfPresent(bin(s.xp), (k, v) -> v > 1 ? v - 1 : null);
                if (s.durationMs > 0) { timedCount = Math.max(0, timedCount - 1); timedMs -= s.durationMs; timedXp -= s.xp; }
                // min/max: exact when every XP sample is still detailed, otherwise the lifetime extremes stay
                if (xpCount <= recentXp().length) {
                    double[] r = recentXp();
                    min = r.length == 0 ? -1 : Arrays.stream(r).min().getAsDouble();
                    max = r.length == 0 ? -1 : Arrays.stream(r).max().getAsDouble();
                }
            }
            return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ queries
    public double average() { return xpCount == 0 ? -1 : totalXp / xpCount; }

    /** Median: exact while all XP samples are detailed, otherwise from the lifetime histogram. */
    public double median() {
        double[] r = recentXp();
        if (xpCount == 0) return -1;
        if (r.length >= xpCount) return exactMedian(r);
        long half = (xpCount + 1) / 2, seen = 0;
        for (var e : hist.entrySet()) {
            seen += e.getValue();
            if (seen >= half) return binCenter(e.getKey());
        }
        return exactMedian(r);
    }

    /** True if {@link #median()} is a histogram approximation. */
    public boolean medianApproximate() { return xpCount > recentXp().length; }

    /** Average of the newest n observed XP values (-1 if none). */
    public double averageLast(int n) {
        double[] r = recentXp();
        if (r.length == 0) return -1;
        int from = Math.max(0, r.length - n);
        double s = 0;
        for (int i = from; i < r.length; i++) s += r[i];
        return s / (r.length - from);
    }

    /** Relative change of the last 10 vs. the 10 before (e.g. 0.08 = +8 %). NaN without 20 samples. */
    public double trend() {
        double[] r = recentXp();
        if (r.length < 20) return Double.NaN;
        double a = 0, b = 0;
        for (int i = r.length - 20; i < r.length - 10; i++) a += r[i];
        for (int i = r.length - 10; i < r.length; i++) b += r[i];
        return a <= 0 ? Double.NaN : b / a - 1;
    }

    /** XP per hour from activities with a known duration; -1 with fewer than 3. */
    public double xpPerHour() { return timedCount < 3 || timedMs <= 0 ? -1 : timedXp / (timedMs / 3_600_000.0); }

    public double averageDurationMs() { return timedCount == 0 ? -1 : timedMs / timedCount; }

    /** Coefficient of variation of the detailed XP values (0 = perfectly consistent). */
    public double variation() {
        double[] r = recentXp();
        if (r.length < 2) return Double.NaN;
        double mean = Arrays.stream(r).average().orElse(0);
        if (mean <= 0) return Double.NaN;
        double var = Arrays.stream(r).map(x -> (x - mean) * (x - mean)).sum() / (r.length - 1);
        return Math.sqrt(var) / mean;
    }

    public long lastTime() { return recent.isEmpty() ? 0 : recent.get(recent.size() - 1).t; }

    public double[] recentXp() { return recent.stream().filter(s -> s.xp >= 0).mapToDouble(s -> s.xp).toArray(); }

    /** Record whether an observed reward agreed with the reward table. */
    public void tableCheck(double observed, double expected) {
        if (observed < 0 || expected <= 0) return;
        if (Math.abs(observed - expected) <= Math.max(0.5, expected * 0.001)) tableMatches++;
        else { tableMismatches++; lastMismatchObserved = observed; lastMismatchExpected = expected; }
    }

    // ------------------------------------------------------------------ helpers
    static int bin(double xp) { return xp < 1 ? -1 : (int) Math.floor(Math.log10(xp) * BINS_PER_DECADE); }

    static double binCenter(int b) { return b < 0 ? 0 : Math.pow(10, (b + 0.5) / BINS_PER_DECADE); }

    private static double exactMedian(double[] r) {
        if (r.length == 0) return -1;
        double[] s = r.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    static String month(long t) {
        var d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate();
        return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    /** Older or hand-edited files may miss fields. */
    void repair() {
        if (hist == null) hist = new TreeMap<>();
        if (recent == null) recent = new ArrayList<>();
        if (monthly == null) monthly = new TreeMap<>();
        recent.removeIf(java.util.Objects::isNull);
    }
}
