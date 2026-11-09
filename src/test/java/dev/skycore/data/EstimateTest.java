package dev.skycore.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EstimateTest {
    private static ProfileData.StatProgress withRuns(double... xp) {
        ProfileData.StatProgress p = new ProfileData.StatProgress();
        p.level = 23;
        p.need = XpTables.need(XpTables.CATACOMBS, 23);
        for (double x : xp) p.gains.add(new ProfileData.Gain(0, x));
        return p;
    }

    @Test
    void noRunsMeansNoEstimate() {
        assertNull(Estimate.forXp(StatDef.get("catacombs"), withRuns(), 45_000));
    }

    @Test
    void singleRunIsPreliminary() {
        Estimate.Range r = Estimate.forXp(StatDef.get("catacombs"), withRuns(10_000), 45_000);
        assertEquals(5, r.lo());
        assertEquals(5, r.hi());
        assertTrue(r.preliminary());
    }

    @Test
    void rangeFollowsRecordedVariance() {
        // example from the spec: 45,000 remaining; runs 8,000 / 11,100 / 10,500 / 10,900
        Estimate.Range r = Estimate.forXp(StatDef.get("catacombs"), withRuns(8_000, 11_100, 10_500, 10_900), 45_000);
        assertTrue(r.lo() <= r.mid() && r.mid() <= r.hi(), r.toString());
        assertEquals(5, r.lo());  // optimistic: 75th percentile ≈ 10,950 → 4.1 → 5
        assertEquals(5, r.hi());  // pessimistic: 25th percentile ≈ 9,875 → 4.6 → 5
        assertFalse(r.preliminary());
        Estimate.Range wide = Estimate.forXp(StatDef.get("catacombs"), withRuns(4_000, 12_000, 6_000, 11_000), 45_000);
        assertTrue(wide.hi() > wide.lo(), "variable runs should give a real range: " + wide);
    }

    @Test
    void tableHelpers() {
        assertEquals(23, XpTables.levelFromNeed(XpTables.SKILL, 600_000, 60));
        assertEquals(50 + 125, XpTables.xpTo(XpTables.SKILL, 0, 0, 2), 1e-9);
        assertEquals(-1, XpTables.need(XpTables.CATACOMBS, 50));
    }
}
