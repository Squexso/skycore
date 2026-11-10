package dev.skycore.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EstimateTimePerUnitTest {
    private static List<ProfileData.Gain> gainsEvery(long... gapsMs) {
        List<ProfileData.Gain> l = new ArrayList<>();
        long t = 1_000_000;
        l.add(new ProfileData.Gain(t, 500));
        for (long g : gapsMs) { t += g; l.add(new ProfileData.Gain(t, 500)); }
        return l;
    }

    @Test
    void medianGapIsTheTimePerBoss() {
        assertEquals(60_000, Estimate.timePerUnitMs(gainsEvery(50_000, 60_000, 70_000, 60_000)));
    }

    @Test
    void longBreaksAndDoubleReportsAreIgnored() {
        // a 2 hour break and a 1 second duplicate do not count
        assertEquals(60_000, Estimate.timePerUnitMs(gainsEvery(60_000, 7_200_000, 60_000, 1_000, 60_000)));
    }

    @Test
    void tooFewGapsGiveNoTime() {
        assertEquals(0, Estimate.timePerUnitMs(gainsEvery(60_000, 60_000)));
        assertEquals(0, Estimate.timePerUnitMs(new ArrayList<>()));
    }

    @Test
    void timeRangeScalesTheUnitRange() {
        StatDef def = StatDef.get("slayer_wolf");

        ProfileData.StatProgress p = new ProfileData.StatProgress();
        p.gains.addAll(gainsEvery(60_000, 60_000, 60_000, 60_000));
        Estimate.Range units = new Estimate.Range(100, 140, 120, 5, def.unit());
        Estimate.Range time = Estimate.withTime(def, p, units);
        assertNotNull(time);
        assertEquals(StatDef.Unit.TIME, time.unit());
        assertEquals(100 * 60_000L, time.lo());
        assertEquals(140 * 60_000L, time.hi());
        assertNull(Estimate.withTime(StatDef.get("skill_fishing"), p, units));
    }
}