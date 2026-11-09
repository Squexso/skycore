package dev.skycore.data;

import dev.skycore.data.ProfileData.StatProgress;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ReconcileTest {
    private static final StatDef CATA = StatDef.get("catacombs");

    @Test
    void eventsThenVerificationWithoutDoubleCounting() {
        Progress.setProfile("ReconcileA");
        Progress.calibrate(CATA, 23, 1000);
        StatProgress p = Progress.real().stat("catacombs");
        assertEquals(DataStatus.MANUAL, p.status());

        Progress.gain(CATA, 500);
        Progress.gain(CATA, 500);
        assertEquals(2000, p.xp, 1e-9);
        assertEquals(DataStatus.EVENT_TRACKED, p.status());
        assertEquals(2, p.gains.size());

        // menu confirms exactly what events predicted → no correction, verified, gains untouched
        int corrections = History.data().corrections.size();
        Progress.observeMenu(CATA, 23, 2000, XpTables.need(XpTables.CATACOMBS, 23));
        assertEquals(DataStatus.VERIFIED, p.status());
        assertEquals(corrections, History.data().corrections.size());
        assertEquals(2, p.gains.size());

        // a gain we saw + progress we missed (e.g. played on another device)
        Progress.gain(CATA, 1000);
        Progress.observeMenu(CATA, 23, 3500, XpTables.need(XpTables.CATACOMBS, 23));
        assertEquals(corrections + 1, History.data().corrections.size());
        History.Correction c = History.data().corrections.get(History.data().corrections.size() - 1);
        assertEquals(500, c.delta, 1e-6, "verified − predicted");
        assertEquals(1, c.eventsSince);
        assertEquals(3500, p.xp, 1e-9, "verified value wins");
        assertEquals(3, p.gains.size(), "recorded events are never rewritten or duplicated");
    }

    @Test
    void levelUpWithoutXpIsAnEstimate() {
        Progress.setProfile("ReconcileB");
        StatDef mining = StatDef.get("skill_mining");
        Progress.setLevel(mining, 40);
        StatProgress p = Progress.real().stat("skill_mining");
        assertEquals(DataStatus.ESTIMATED, p.status());
        Progress.observe(mining, 40, 1234, XpTables.need(XpTables.SKILL, 40), "action_bar");
        assertEquals(DataStatus.VERIFIED, p.status());
    }

    @Test
    void roundedPercentageIsAnEstimateAndNeverOverridesFreshExactData() {
        Progress.setProfile("ReconcileC");
        StatDef farming = StatDef.get("skill_farming");
        Progress.observe(farming, 30, 500_000, XpTables.need(XpTables.SKILL, 30), "action_bar");
        Progress.observeApprox(farming, 30, 45.3, "tab");
        StatProgress p = Progress.real().stat("skill_farming");
        assertEquals(500_000, p.xp, 1e-9, "fresh exact action-bar value kept");
        assertEquals(DataStatus.VERIFIED, p.status());

        StatDef combat = StatDef.get("skill_combat");
        Progress.observeApprox(combat, 20, 50, "tab");
        StatProgress c = Progress.real().stat("skill_combat");
        assertEquals(DataStatus.ESTIMATED, c.status());
        assertEquals(XpTables.need(XpTables.SKILL, 20) / 2.0, c.xp, 1e-6);
    }

    @Test
    void profilesAreIsolated() {
        Progress.setProfile("Apple");
        Progress.calibrate(CATA, 10, 0);
        Progress.setProfile("Banana");
        assertFalse(Progress.real().peek("catacombs").known(), "a new profile starts empty");
        Progress.setProfile("Apple");
        assertEquals(10, Progress.real().peek("catacombs").level);
    }

    @Test
    void migrationKeepsDataAndAddsProvenance() {
        Progress.Saved s = new Progress.Saved();
        s.schema = 0;
        ProfileData d = new ProfileData();
        d.values = null;
        d.floorCompletions = null;
        StatProgress p = d.stat("catacombs");
        p.level = 12;
        p.calibrated = true;
        p.updated = 123;
        s.profiles = new LinkedHashMap<>();
        s.profiles.put("Old", d);
        Progress.migrate(s);
        assertEquals(Progress.SCHEMA, s.schema);
        assertEquals("manual", p.source);
        assertEquals(12, p.level);
        assertNotNull(d.values);
        assertNotNull(d.floorCompletions);
    }

    @Test
    void historyMigrationAndBounds() {
        History.Data d = new History.Data();
        d.schema = 0;
        History.Event e = new History.Event();
        d.events.add(e);
        d.events.add(null);
        d.lastScan = null;
        History.migrate(d);
        assertEquals(History.SCHEMA, d.schema);
        assertEquals(1, d.events.size());
        assertEquals(0.9, d.events.get(0).confidence, 1e-9);
        assertNotNull(d.lastScan);
    }

    @Test
    void slayerEstimatesUseTheCurrentTier() {
        StatProgress p = new StatProgress();
        p.level = 6;
        p.need = 80_000;
        p.xp = 0;
        for (int i = 0; i < 5; i++) p.gains.add(new ProfileData.Gain(i, 100, "III"));
        for (int i = 0; i < 3; i++) p.gains.add(new ProfileData.Gain(10 + i, 500, "IV"));
        Estimate.Range r = Estimate.forXp(StatDef.get("slayer_zombie"), p, 5_000);
        assertEquals(10, r.lo(), "5,000 XP at 500 XP per tier-IV boss");
        assertEquals(10, r.hi());
    }
}
