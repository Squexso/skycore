package dev.skycore.tracking;

import dev.skycore.data.Activity;
import dev.skycore.data.Activity.Slayer;
import dev.skycore.data.Activity.SlayerQuest;
import dev.skycore.data.ActivityStats;
import dev.skycore.data.Progress;
import dev.skycore.data.Rewards;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlayerClassificationTest {
    private SlayerSession session;
    private long t;

    @BeforeEach
    void setUp() {
        Rewards.load(null);
        session = new SlayerSession(x -> null);
        t = 1_000_000;
    }

    /** A full quest as Hypixel shows it: start, requirement line, sidebar, kill, level message. */
    private SlayerQuest quest(Slayer s, int tier, double xp) {
        session.started(t += 1000);
        session.requirement(t += 100, s.mobs);
        session.sidebar(t += 1000, s.boss, Activity.roman(tier), "combat");
        session.sidebar(t += 30_000, s.boss, Activity.roman(tier), "boss");
        assertTrue(session.completed(t += 20_000));
        return session.level(t += 500, s.levelName, xp);
    }

    @Test
    void everySlayerTypeAndTierIsSeparate() {
        for (Slayer s : Slayer.values())
            for (int tier = 1; tier <= s.maxTier; tier++) {
                long base = Rewards.slayerBase(s, tier);
                SlayerQuest q = quest(s, tier, base);
                assertNotNull(q, s + " T" + tier);
                assertTrue(q.classified(), s + " T" + tier + " " + q.classification.missing);
                assertEquals(s.key(tier), q.key());
                assertEquals(s.id, q.type);
                assertEquals(tier, q.tier);
                assertEquals(base, q.calc.observed, 1e-9);
                assertTrue(q.classification.confidence >= 0.95);
                assertEquals("observed", q.calc.kind);
            }
    }

    @Test
    void endermanT1IsNeverRecordedAsT4() {
        Progress.identify("SlayerSep", "acc-1", "chat");
        SlayerQuest t1 = quest(Slayer.ENDERMAN, 1, 5);
        SlayerQuest t4 = quest(Slayer.ENDERMAN, 4, 500);
        assertEquals("enderman:1", t1.key());
        assertEquals("enderman:4", t4.key());
        assertTrue(Progress.recordSlayerQuest(t1));
        assertTrue(Progress.recordSlayerQuest(t4));
        ActivityStats s1 = Progress.real().slayerStats.get("enderman:1"), s4 = Progress.real().slayerStats.get("enderman:4");
        assertEquals(1, s1.count);
        assertEquals(5, s1.average(), 1e-9);
        assertEquals(1, s4.count);
        assertEquals(500, s4.average(), 1e-9);
        assertNull(Progress.real().slayerStats.get("zombie:4"), "no other slayer type touched");
        assertFalse(Progress.recordSlayerQuest(t4), "the same quest is never stored twice");
    }

    @Test
    void tierIsNeverGuessedFromXp() {
        session.started(t += 1000);
        session.requirement(t += 100, "Endermen");           // type only
        assertTrue(session.completed(t += 60_000));
        SlayerQuest q = session.level(t += 500, "Enderman", 500);
        assertFalse(q.classified(), "no tier evidence → uncertain");
        assertNull(q.key());
        assertEquals("enderman", q.type);
        assertTrue(q.classification.missing.contains("evidence.no_tier"));
        assertEquals(java.util.List.of("enderman:4"), q.classification.candidates, "500 XP only lists T4 as a candidate");
        Progress.identify("SlayerGuess", "acc-1", "chat");
        Progress.recordSlayerQuest(q);
        assertTrue(Progress.real().slayerStats.isEmpty(), "uncertain quests are kept but never counted");
        assertEquals(1, Progress.real().slayerQuests.size());
    }

    @Test
    void conflictingEvidenceMakesTheQuestUncertain() {
        session.started(t += 1000);
        session.sidebar(t += 1000, "Voidgloom Seraph", "IV", "combat");
        session.ownBoss(t += 1000, "Voidgloom Seraph", "I");
        session.completed(t += 1000);
        SlayerQuest q = session.level(t += 500, "Enderman", 500);
        assertFalse(q.classified());
        assertTrue(q.classification.missing.contains("evidence.conflict_tier"));
        session.started(t += 1000);
        session.sidebar(t += 1000, "Voidgloom Seraph", "II", "combat");
        session.completed(t += 1000);
        SlayerQuest wrongType = session.level(t += 500, "Zombie", 25);
        assertFalse(wrongType.classified(), "sidebar says enderman, level message says zombie → uncertain");
        assertTrue(wrongType.classification.missing.contains("evidence.conflict_type"));
    }

    @Test
    void bossSpawnFailureAndDuplicatesAreNotCompletions() {
        session.started(t += 1000);
        session.sidebar(t += 1000, "Revenant Horror", "V", "boss");
        assertNull(session.tick(t += 60_000), "a spawned boss is not a completed quest");
        SlayerQuest failed = session.failed(t += 1000);
        assertEquals("failed", failed.status);
        // duplicate completion message
        session.started(t += 1000);
        session.sidebar(t += 1000, "Sven Packmaster", "III", "boss");
        assertTrue(session.completed(t += 1000));
        assertFalse(session.completed(t += 500), "repeated message within 3 s is ignored");
        SlayerQuest q = session.level(t += 500, "Wolf", 100);
        assertEquals("wolf:3", q.key());
        assertNull(session.level(t += 500, "Wolf", 100), "a second level message does not create another quest");
    }

    @Test
    void timeoutDisconnectAndLingeringSidebar() {
        session.started(t += 1000);
        session.sidebar(t += 1000, "Tarantula Broodfather", "II", "boss");
        session.completed(t += 1000);
        SlayerQuest noXp = session.tick(t += SlayerSession.XP_WAIT + 1);
        assertNotNull(noXp, "completed without a level message → finished without observed XP");
        assertEquals("spider:2", noXp.key());
        assertEquals(-1, noXp.calc.observed, 1e-9);
        assertEquals("estimated", noXp.calc.kind, "mayor unknown → table value is only an estimate");
        assertFalse(noXp.calc.complete);
        // "Boss slain!" still visible right after – must not start a phantom quest
        session.sidebar(t += 500, "Tarantula Broodfather", "II", "slain");
        assertFalse(session.active());
        // quest open while disconnecting → interrupted, not completed
        session.started(t += 10_000);
        session.sidebar(t += 1000, "Inferno Demonlord", "I", "combat");
        SlayerQuest cut = session.disconnect(t += 1000);
        assertEquals("interrupted", cut.status);
        // completed, then disconnect before the level message → still a completion (no XP)
        session.started(t += 1000);
        session.sidebar(t += 1000, "Riftstalker Bloodfiend", "V", "boss");
        session.completed(t += 1000);
        SlayerQuest done = session.disconnect(t += 1000);
        assertEquals("complete", done.status);
        assertEquals("vampire:5", done.key());
    }
}
