package dev.skycore.data;

import dev.skycore.data.Activity.Slayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class StatsAndProjectionTest {
    @BeforeEach
    void setUp() { Rewards.load(null); }

    @Test
    void aggregationBeyondTheDetailedWindow() {
        ActivityStats st = new ActivityStats();
        Random r = new Random(7);
        double[] all = new double[300];
        for (int i = 0; i < all.length; i++) {
            all[i] = 3000 + r.nextGaussian() * 400 + i * 2;
            st.add(1_700_000_000_000L + i * 600_000L, all[i], 400_000, "id" + i);
        }
        assertEquals(300, st.count);
        assertEquals(ActivityStats.DETAILED, st.recent.size());
        assertEquals(Arrays.stream(all).average().getAsDouble(), st.average(), 1e-6, "lifetime average exact");
        assertEquals(Arrays.stream(all).min().getAsDouble(), st.min, 1e-9);
        assertEquals(Arrays.stream(all).max().getAsDouble(), st.max, 1e-9);
        double[] sorted = all.clone();
        Arrays.sort(sorted);
        double trueMedian = (sorted[149] + sorted[150]) / 2;
        assertTrue(st.medianApproximate());
        assertEquals(trueMedian, st.median(), trueMedian * 0.03, "histogram median within 3 %");
        double last10 = Arrays.stream(all, 290, 300).average().getAsDouble();
        assertEquals(last10, st.averageLast(10), 1e-6);
        assertFalse(Double.isNaN(st.trend()));
        assertTrue(st.xpPerHour() > 0);
        assertEquals(300, st.monthly.values().stream().mapToDouble(v -> v[0]).sum(), 1e-9, "monthly aggregates cover every run");
        // without enough history the median is exact
        ActivityStats small = new ActivityStats();
        for (double v : new double[]{5, 1, 9}) small.add(0, v, 0, null);
        assertFalse(small.medianApproximate());
        assertEquals(5, small.median(), 1e-9);
        assertTrue(Double.isNaN(small.trend()));
        assertEquals(-1, small.xpPerHour(), 1e-9, "no timing data → no XP/hour");
    }

    @Test
    void removalMovesOneActivity() {
        ActivityStats st = new ActivityStats();
        st.add(10, 100, 0, "a");
        st.add(20, 300, 0, "b");
        assertNotNull(st.remove("b"));
        assertEquals(1, st.count);
        assertEquals(100, st.average(), 1e-9);
        assertEquals(100, st.max, 1e-9);
        assertNull(st.remove("zzz"));
    }

    @Test
    void slayerQuestsPerTierUseTheirOwnReward() {
        StatDef def = StatDef.get("slayer_enderman");
        ProfileData.StatProgress p = new ProfileData.StatProgress();
        p.level = 4;
        p.xp = 1000;
        p.need = XpTables.need(def.table(), 4);
        double rem = Projection.remaining(def, p, 5);
        assertEquals(p.need - 1000, rem, 1e-9);

        Activity.MayorSnapshot aatrox = new Activity.MayorSnapshot();
        aatrox.mayor = "Aatrox";
        aatrox.slayerPerk = "Slayer XP Buff";
        aatrox.slayerXpPercent = 25;
        List<Projection.Option> opts = Projection.slayer(Slayer.ENDERMAN, rem, Map.of(), aatrox, 0);
        assertEquals(4, opts.size(), "T1–T4");
        Projection.Option t1 = opts.get(0), t4 = opts.get(3);
        assertEquals((long) Math.ceil(rem / 6.25), t1.activities());
        assertEquals((long) Math.ceil(rem / 625), t4.activities());
        assertTrue(t4.bonusApplied());
        assertEquals("table", t4.basis());

        List<Projection.Option> noMayor = Projection.slayer(Slayer.ENDERMAN, rem, Map.of(), null, 0);
        assertEquals((long) Math.ceil(rem / 500), noMayor.get(3).activities(), "without a confirmed mayor the base reward is used");
        assertEquals("table_unconfirmed", noMayor.get(3).basis());
        assertEquals(Projection.Confidence.LOW, noMayor.get(3).confidence());

        // observed rewards agreeing with the table raise the confidence
        ActivityStats st = new ActivityStats();
        for (int i = 0; i < 5; i++) st.tableCheck(625, 625);
        aatrox.ministerKnown = true;
        assertEquals(Projection.Confidence.HIGH, Projection.slayer(Slayer.ENDERMAN, rem, Map.of("enderman:4", st), aatrox, 0).get(3).confidence());
    }

    @Test
    void dungeonEstimatesPreferPersonalDataAndLabelFallbacks() {
        long now = 1_800_000_000_000L;
        ActivityStats f6 = new ActivityStats();
        for (int i = 0; i < 12; i++) f6.add(now - i * 600_000L, 4000 + (i % 3) * 50, 420_000, "f6-" + i);
        Projection.Option personal = Projection.floor("F6", 100_000, f6, now);
        assertEquals("personal", personal.basis());
        assertEquals((long) Math.ceil(100_000 / f6.averageLast(50)), personal.activities());
        assertEquals(Projection.Confidence.HIGH, personal.confidence());
        assertTrue(personal.millis() > 0);

        Projection.Option fallback = Projection.floor("M1", 100_000, null, now);
        assertEquals("fallback", fallback.basis());
        assertEquals(Projection.Confidence.FALLBACK, fallback.confidence());
        assertEquals(-1, fallback.millis());

        ActivityStats two = new ActivityStats();
        two.add(now, 9000, 0, "x");
        two.add(now, 9100, 0, "y");
        assertEquals("fallback", Projection.floor("M1", 100_000, two, now).basis(), "fewer than 3 runs is not enough personal data");

        List<Projection.Option> all = Projection.dungeons(100_000, Map.of("F6", f6), now);
        assertEquals(Activity.FLOORS.length, all.size(), "every floor compared");
        ProfileData.StatProgress unknown = new ProfileData.StatProgress();
        assertEquals(-1, Projection.remaining(StatDef.get("catacombs"), unknown, 30), 1e-9, "no estimate without a known level");
    }

    @Test
    void migrationBuildsStatisticsWithoutLosingAnything() {
        Progress.Saved s = new Progress.Saved();
        s.schema = 3;
        ProfileData d = new ProfileData();
        for (int i = 0; i < 3; i++) {
            ProfileData.DungeonRun r = new ProfileData.DungeonRun();
            r.t = 1000 + i;
            r.xp = 20000 + i * 100;
            r.floor = "F7";
            d.runs.add(0, r);
        }
        ProfileData.DungeonRun noFloor = new ProfileData.DungeonRun();
        noFloor.t = 5000;
        noFloor.xp = 300;
        d.runs.add(0, noFloor);
        d.floorCompletions.put("F7", 10);
        ProfileData.StatProgress eman = d.stat("slayer_enderman");
        eman.gains.add(new ProfileData.Gain(100, 500, "IV"));
        eman.gains.add(new ProfileData.Gain(200, 5, "I"));
        eman.gains.add(new ProfileData.Gain(300, 500, null)); // tier never seen → not assigned
        s.profiles.put("Apple", d);
        Progress.migrate(s);
        assertEquals(Progress.SCHEMA, s.schema);
        ActivityStats f7 = d.floorStats.get("F7");
        assertEquals(10, f7.count, "lifetime completions preserved (3 detailed + 7 older)");
        assertEquals(3, f7.xpCount);
        assertEquals(20100, f7.average(), 1e-9);
        assertEquals(4, d.runs.size(), "no run removed");
        assertFalse(noFloor.classification.confirmed);
        assertTrue(d.runs.stream().allMatch(r -> r.id != null && r.calc != null));
        assertEquals(1, d.slayerStats.get("enderman:4").count);
        assertEquals(1, d.slayerStats.get("enderman:1").count);
        assertEquals(2, d.slayerStats.values().stream().mapToLong(a -> a.count).sum(), "untagged gains are not guessed");
    }
}
