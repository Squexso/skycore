package dev.skycore.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaceRecentTest {
    @Test
    void trendUsesTheInMemoryHourNotTheShortSavedList() {
        Pace.reset();
        long now = System.currentTimeMillis();
        // 40 minutes of one gain per 10 s (240 gains: far more than the 60 Progress keeps), the last 10 minutes at double size
        for (long t = now - 40 * 60_000L; t <= now; t += 10_000L) Pace.recordGain("skill_fishing", t > now - 10 * 60_000L ? 200 : 100, t);
        Double d = Pace.trendOf("skill_fishing");
        assertNotNull(d);
        assertTrue(d > 0.3, "expected a clear upward trend but was " + d);
        assertNull(Pace.trendOf("skill_mining"));
        Pace.reset();
        assertNull(Pace.trendOf("skill_fishing"));
    }
}
