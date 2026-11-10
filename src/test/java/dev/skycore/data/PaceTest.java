package dev.skycore.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PaceTest {
    private static final long B = SessionLog.BUCKET;

    private static SessionLog.Bucket bucket(int playSec, double xp) {
        SessionLog.Bucket b = new SessionLog.Bucket();
        b.playSeconds = playSec;
        b.xp.put("skill_mining", xp);
        return b;
    }

    // ---------------------------------------------------------------- pace

    @Test
    void needsTenMinutesAndXpInSession() {
        assertNull(Pace.compare(1000, 599, 10_000, 7200));
        assertNull(Pace.compare(0, 1200, 10_000, 7200));
        assertNotNull(Pace.compare(1000, 600, 10_000, 7200));
    }

    @Test
    void needsAnHourOfBaseline() {
        assertNull(Pace.compare(1000, 1200, 10_000, 3599));
        assertNull(Pace.compare(1000, 1200, 0, 7200));
        assertNotNull(Pace.compare(1000, 1200, 10_000, 3600));
    }

    @Test
    void deviationAndThreshold() {
        // baseline 10,000 XP / 2 h = 5,000/h
        Pace.Result slow = Pace.compare(1000, 1800, 10_000, 7200); // 2,000/h = -60%
        assertEquals(-0.6, slow.deviation(), 1e-9);
        assertTrue(slow.significant());
        Pace.Result down = Pace.compare(1925, 1800, 10_000, 7200); // 3,850/h = -23%
        assertEquals(-0.23, down.deviation(), 1e-9);
        assertTrue(down.significant());
        Pace.Result edge = Pace.compare(2000, 1800, 10_000, 7200); // 4,000/h = -20% exactly
        assertTrue(edge.significant());
        Pace.Result near = Pace.compare(2050, 1800, 10_000, 7200); // 4,100/h = -18%
        assertFalse(near.significant());
        Pace.Result up = Pace.compare(3000, 1800, 10_000, 7200); // 6,000/h = +20%
        assertTrue(up.significant());
        assertEquals("+20%", Pace.signedPercent(up.deviation()));
        assertEquals("-23%", Pace.signedPercent(down.deviation()));
    }

    @Test
    void baselineExcludesRunningSession() {
        long now = 10L * 24 * 3600_000L; // arbitrary, 10 days after epoch
        long sessionStart = now - 30 * 60_000L;
        TreeMap<Long, SessionLog.Bucket> m = new TreeMap<>();
        m.put((now - 3L * 24 * 3600_000L) / B, bucket(600, 1000)); // in window
        m.put((now - 2L * 24 * 3600_000L) / B, bucket(300, 500));   // in window
        m.put((now - 8L * 24 * 3600_000L) / B, bucket(600, 9999)); // too old
        m.put(sessionStart / B, bucket(600, 7777));                 // bucket containing the session start
        m.put(now / B, bucket(300, 4444));                          // running session
        double[] b = Pace.baseline(m, now, sessionStart);
        assertEquals(1500, b[0], 1e-9);
        assertEquals(900, b[1], 1e-9);
    }

    @Test
    void computeUsesOpenSpanAndIgnoresItsBuckets() {
        long now = 20L * 24 * 3600_000L;
        SessionLog.Data d = new SessionLog.Data();
        for (int i = 1; i <= 6; i++) d.buckets.put((now - i * 3600_000L * 12) / B, bucket(600, 1000)); // 3,600 s, 6,000 XP = 6,000/h
        SessionLog.Span open = new SessionLog.Span();
        open.start = now - 40 * 60_000L;
        open.playSeconds = 1800;
        open.xp.put("skill_mining", 1500d); // 3,000/h = -50%
        d.buckets.put(open.start / B, bucket(600, 50_000)); // must not leak into the baseline
        Pace.Result r = Pace.compute(d, open, now);
        assertNotNull(r);
        assertEquals(6000, r.baselinePerHour(), 1e-6);
        assertEquals(-0.5, r.deviation(), 1e-9);
        assertNull(Pace.compute(d, null, now));
    }

    // ---------------------------------------------------------------- trend

    private static List<ProfileData.Gain> steady(long now, long fromAgoMs, long stepMs, double amount) {
        List<ProfileData.Gain> l = new ArrayList<>();
        for (long a = fromAgoMs; a >= 0; a -= stepMs) l.add(new ProfileData.Gain(now - a, amount));
        return l;
    }

    @Test
    void steadyRateHasNoTrend() {
        long now = 5_000_000_000L;
        Double d = Pace.trend(steady(now, 60 * 60_000L, 60_000L, 100), now, 1000);
        assertNotNull(d);
        assertEquals(0, d, 0.05);
        assertFalse(Pace.trendSignificant(d));
    }

    @Test
    void fasterRecentRateIsPositive() {
        long now = 5_000_000_000L;
        List<ProfileData.Gain> l = steady(now, 50 * 60_000L, 60_000L, 100);
        l.addAll(steady(now - 1, 9 * 60_000L, 30_000L, 100)); // double density in the last ~9 min
        l.sort((a, b) -> Long.compare(a.t, b.t));
        Double d = Pace.trend(l, now, 1000);
        assertTrue(Pace.trendSignificant(d));
        assertTrue(d > 0.1, "trend " + d);
    }

    @Test
    void needsTwentyMinutesOfData() {
        long now = 5_000_000_000L;
        assertNull(Pace.trend(steady(now, 19 * 60_000L, 30_000L, 100), now, 1000));
        assertNotNull(Pace.trend(steady(now, 20 * 60_000L, 30_000L, 100), now, 1000));
        assertNull(Pace.trend(new ArrayList<>(), now, 1000));
    }

    @Test
    void shortSessionIsNormalisedToItsOwnSpan() {
        long now = 5_000_000_000L;
        // 25 min of play, constant rate: normalised to 25 min the trend is ~0, not "-58%" vs. a full hour
        Double d = Pace.trend(steady(now, 25 * 60_000L, 30_000L, 100), now, 1000);
        assertNotNull(d);
        assertEquals(0, d, 0.1);
    }

    @Test
    void truncatedGainListGivesNoTrend() {
        long now = 5_000_000_000L;
        List<ProfileData.Gain> l = steady(now, 15 * 60_000L, 15_000L, 10); // 61 gains, cap 61 -> list was cut
        assertNull(Pace.trend(l, now, l.size()));
    }
}
