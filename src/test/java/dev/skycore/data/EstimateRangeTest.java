package dev.skycore.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EstimateRangeTest {
    private static final StatDef.Unit TIME = StatDef.Unit.TIME;

    private static Estimate.Range time(double remaining, double... rates) {
        return Estimate.timeRange(remaining, rates, med -> 1.0, TIME);
    }

    @Test
    void noSamplesNoEstimate() {
        assertNull(time(100_000));
        assertNull(time(100_000, 0, -5));
    }

    @Test
    void orderedAndBounded() {
        // wildly scattered rates: spread must stay within [0.8x .. 1.25x] of mid
        Estimate.Range r = time(1_000_000, 100, 50_000, 20_000, 300_000, 800, 90_000);
        assertTrue(r.lo() <= r.mid() && r.mid() <= r.hi(), r.toString());
        assertTrue(r.lo() >= r.mid() * 0.8 - 1, r.toString());
        assertTrue(r.hi() <= r.mid() * 1.25 + 1, r.toString());
    }

    @Test
    void steadyRatesStayNarrow() {
        Estimate.Range r = time(1_000_000, 50_000, 51_000, 49_500, 50_500, 50_200);
        assertTrue(r.hi() - r.lo() < r.mid() * 0.1, "should stay tight: " + r);
    }

    @Test
    void singleSampleIsPreliminaryAndPointLike() {
        Estimate.Range r = time(500_000, 50_000);
        assertTrue(r.preliminary());
        assertEquals(r.lo(), r.hi());
        assertEquals(r.mid(), r.lo());
    }

    @Test
    void anchorKeepsSpreadAndScalesMid() {
        // anchor halves every rate: times double, relative spread unchanged
        Estimate.Range a = Estimate.timeRange(1_000_000, new double[]{40_000, 50_000, 60_000, 55_000}, m -> 1.0, TIME);
        Estimate.Range b = Estimate.timeRange(1_000_000, new double[]{40_000, 50_000, 60_000, 55_000}, m -> 0.5, TIME);
        assertEquals(a.mid() * 2.0, b.mid(), 2);
        assertEquals((double) a.hi() / a.lo(), (double) b.hi() / b.lo(), 0.01);
    }

    @Test
    void runsRangeIsOrderedAndSingleSampleIsPreliminary() {
        ProfileData.StatProgress p = new ProfileData.StatProgress();
        p.level = 23;
        p.need = XpTables.need(XpTables.CATACOMBS, 23);
        p.gains.add(new ProfileData.Gain(0, 10_000));
        Estimate.Range one = Estimate.forXp(StatDef.get("catacombs"), p, 45_000);
        assertTrue(one.preliminary());
        assertEquals(one.lo(), one.hi());
        for (double x : new double[]{4_000, 12_000, 6_000, 11_000}) p.gains.add(new ProfileData.Gain(0, x));
        Estimate.Range r = Estimate.forXp(StatDef.get("catacombs"), p, 45_000);
        assertTrue(r.lo() <= r.mid() && r.mid() <= r.hi(), r.toString());
        assertTrue(r.hi() > r.lo());
    }
}
