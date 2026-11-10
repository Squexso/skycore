package dev.skycore.data;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PeakTimesTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    // Wednesday 2026-11-11 12:00 UTC
    private static final long NOW = LocalDateTime.of(2026, 11, 11, 12, 0).atZone(UTC).toInstant().toEpochMilli();

    private static long at(int day, int hour, int min) {
        return LocalDateTime.of(2026, 11, day, hour, min).atZone(UTC).toInstant().toEpochMilli();
    }

    /** Six 10-minute buckets (one hour of play) with a given XP/h. */
    private static void hour(List<PeakTimes.Point> l, int day, int hour, double xpPerHour) {
        for (int i = 0; i < 6; i++) l.add(new PeakTimes.Point(at(day, hour, i * 10), 600, xpPerHour / 6));
    }

    @Test void findsBestWindow() {
        List<PeakTimes.Point> l = new ArrayList<>();
        hour(l, 2, 10, 100_000);
        hour(l, 3, 18, 200_000);
        hour(l, 3, 19, 160_000);
        hour(l, 4, 20, 90_000);
        PeakTimes.Result r = PeakTimes.analyze(l, UTC, NOW);
        assertTrue(r.enough());
        assertEquals(18, r.bestStart());
        assertEquals(180_000, r.bestRate(), 1);
        assertEquals(137_500, r.average(), 1);
        assertEquals((180_000 / 137_500.0 - 1) * 100, r.advantage(), 0.01);
        assertTrue(r.inBest(18) && r.inBest(19) && !r.inBest(20));
        assertTrue(r.rated(10) && !r.rated(11));
    }

    @Test void tooLittleData() {
        List<PeakTimes.Point> l = new ArrayList<>();
        hour(l, 3, 18, 200_000);
        hour(l, 3, 19, 160_000);
        PeakTimes.Result r = PeakTimes.analyze(l, UTC, NOW);
        assertFalse(r.enough());
        assertEquals(-1, r.bestStart());
        assertNull(r.bestWeekday());
        assertFalse(PeakTimes.analyze(List.of(), UTC, NOW).enough());
    }

    @Test void slotsUnder20MinutesAreNotRated() {
        List<PeakTimes.Point> l = new ArrayList<>();
        for (int h = 10; h < 14; h++) hour(l, 2, h, 100_000);
        // only 10 minutes at 15:xx, with huge XP: must not be rated nor win
        l.add(new PeakTimes.Point(at(3, 15, 0), 600, 5_000_000));
        // buckets under 60 s are ignored entirely
        l.add(new PeakTimes.Point(at(3, 16, 0), 59, 9_000_000));
        PeakTimes.Result r = PeakTimes.analyze(l, UTC, NOW);
        assertTrue(r.enough());
        assertFalse(r.rated(15));
        assertFalse(r.rated(16));
        assertEquals(100_000, r.bestRate(), 1);
    }

    @Test void oldBucketsAreIgnored() {
        List<PeakTimes.Point> l = new ArrayList<>();
        for (int h = 10; h < 14; h++) hour(l, 2, h, 100_000);
        List<PeakTimes.Point> old = new ArrayList<>();
        for (PeakTimes.Point p : l) old.add(new PeakTimes.Point(p.startMillis() - 40L * 24 * 3600_000L, p.playSeconds(), p.xp()));
        assertFalse(PeakTimes.analyze(old, UTC, NOW).enough());
        assertTrue(PeakTimes.analyze(l, UTC, NOW).enough());
    }

    @Test void zoneAndWeekday() {
        // Tuesday 2026-11-03 18:00-20:00 UTC = Wednesday 03:00-05:00 in Tokyo
        List<PeakTimes.Point> l = new ArrayList<>();
        hour(l, 3, 18, 200_000);
        hour(l, 3, 19, 200_000);
        hour(l, 4, 18, 50_000);
        hour(l, 4, 19, 50_000);
        hour(l, 5, 12, 100_000); // third rated hour so the analysis has enough data
        PeakTimes.Result utc = PeakTimes.analyze(l, UTC, NOW);
        assertEquals(18, utc.bestStart());
        assertEquals(DayOfWeek.TUESDAY, utc.bestWeekday());
        PeakTimes.Result tokyo = PeakTimes.analyze(l, TOKYO, NOW);
        assertEquals(3, tokyo.bestStart());
        assertEquals(DayOfWeek.WEDNESDAY, tokyo.bestWeekday());
    }

    @Test void fromBucketMap() {
        TreeMap<Long, SessionLog.Bucket> m = new TreeMap<>();
        for (int i = 0; i < 12; i++) {
            SessionLog.Bucket b = new SessionLog.Bucket();
            b.playSeconds = 600;
            b.xp.put("combat", 1000.0);
            b.xp.put("mining", 500.0);
            m.put((at(3, 18, 0) + i * SessionLog.BUCKET) / SessionLog.BUCKET, b);
        }
        for (int i = 0; i < 6; i++) {
            SessionLog.Bucket b = new SessionLog.Bucket();
            b.playSeconds = 600;
            b.xp.put("combat", 100.0);
            m.put((at(4, 8, 0) + i * SessionLog.BUCKET) / SessionLog.BUCKET, b);
        }
        PeakTimes.Result r = PeakTimes.analyze(m, UTC, NOW);
        assertTrue(r.enough());
        assertEquals(18, r.bestStart());
        assertEquals(9000, r.hourRate()[18], 0.001);
    }
}
