package dev.skycore.data;

import dev.skycore.tracking.DungeonClassifier;
import dev.skycore.tracking.parse.MessageParser;
import dev.skycore.tracking.parse.PanelParser;
import dev.skycore.tracking.parse.ParsedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DungeonFloorTest {
    private static String summary(String line) {
        var e = MessageParser.defaults().parse(line, MessageParser.Channel.CHAT);
        return e.isEmpty() ? "NO MATCH" : ((ParsedEvent.DungeonFloorSummary) e.get(0)).floor();
    }

    @Test
    void everyFloorFromTheSidebar() {
        for (String f : Activity.FLOORS) {
            String line = "⏣ The Catacombs (" + f + ")";
            PanelParser.Sidebar s = PanelParser.sidebar("SKYBLOCK", List.of(line), "Me");
            assertEquals(f, s.floor(), line);
            var c = DungeonClassifier.classify(List.of(s.floor()), null);
            assertTrue(c.confirmed);
            assertEquals(f, c.key);
        }
        assertEquals("master", Activity.mode("M1"));
        assertEquals("normal", Activity.mode("F6"));
        assertEquals("entrance", Activity.mode("E"));
        assertNull(Activity.mode("F8"));
    }

    @Test
    void runSummaryKeepsModesApart() {
        assertEquals("F3", summary("The Catacombs - Floor III"));
        assertEquals("M3", summary("Master Mode Catacombs - Floor III"));
        assertEquals("M3", summary("Master Mode The Catacombs - Floor III"));
        assertEquals("F6", summary("                The Catacombs - Floor VI"));
        assertEquals("M1", summary("Master Mode Catacombs - Floor I"));
        assertEquals("E", summary("The Catacombs - Entrance"));
        assertNull(summary("The Catacombs - Floor IX"), "not a real floor → no floor");
        assertEquals("NO MATCH", summary("[MVP+] Bob: The Catacombs - Floor VII"), "player chat is never evidence");
    }

    @Test
    void conflictsAndMissingEvidenceAreUncertain() {
        var agree = DungeonClassifier.classify(List.of("M3"), "M3");
        assertEquals("M3", agree.key);
        assertEquals(0.99, agree.confidence, 1e-9);
        var f3m3 = DungeonClassifier.classify(List.of("F3"), "M3");
        assertFalse(f3m3.confirmed, "F3 vs M3 disagreement is never resolved by guessing");
        assertNull(f3m3.key);
        assertTrue(f3m3.candidates.containsAll(List.of("F3", "M3")));
        var twoFloors = DungeonClassifier.classify(List.of("F6", "M1"), null);
        assertFalse(twoFloors.confirmed);
        var none = DungeonClassifier.classify(List.of(), null);
        assertFalse(none.confirmed);
        assertTrue(none.missing.contains("evidence.no_floor"));
        var summaryOnly = DungeonClassifier.classify(List.of(), "M1");
        assertTrue(summaryOnly.confirmed);
        assertEquals(0.85, summaryOnly.confidence, 1e-9);
    }

    @Test
    void floorStatisticsAreSeparateAndDuplicatesIgnored() {
        Progress.identify("FloorSep", "acc-1", "chat");
        var f6 = Progress.recordRun(4200, DungeonClassifier.classify(List.of("F6"), null), "S+", 300, 0, "Mage");
        assertNotNull(f6);
        assertNull(Progress.recordRun(4200, DungeonClassifier.classify(List.of("F6"), null), "S+", 300, 0, "Mage"), "same XP within 10 s = duplicate");
        Progress.recordRun(11000, DungeonClassifier.classify(List.of("M1"), "M1"), "S", 280, 0, "Mage");
        Progress.recordRun(9000, DungeonClassifier.classify(List.of("F3"), "M3"), "S", 280, 0, "Mage"); // conflicting → uncertain
        Progress.recordIncompleteRun(DungeonClassifier.classify(List.of("F7"), null), 0, "Mage");
        ProfileData d = Progress.real();
        assertEquals(1, d.floorStats.get("F6").count);
        assertEquals(4200, d.floorStats.get("F6").average(), 1e-9);
        assertEquals(1, d.floorStats.get("M1").count);
        assertEquals(11000, d.floorStats.get("M1").average(), 1e-9);
        assertNull(d.floorStats.get("F3"));
        assertNull(d.floorStats.get("M3"));
        assertNull(d.floorStats.get("F7"), "an incomplete run is never a completion");
        assertEquals(1, d.floorCompletions.get("F6"));
        assertNull(d.floorCompletions.get("F7"));
        assertEquals(4, d.runs.size(), "uncertain and incomplete runs are kept for inspection");
        assertEquals(1, d.runs.stream().filter(r -> r.complete() && r.floor == null).count());
        // the user corrects the uncertain run → it moves into M3
        var unknown = d.runs.stream().filter(r -> r.complete() && r.floor == null).findFirst().orElseThrow();
        assertTrue(Progress.reclassifyRun(unknown.id, "M3"));
        assertEquals(1, d.floorStats.get("M3").count);
        assertTrue(unknown.classification.manual);
        assertTrue(Progress.reclassifyRun(unknown.id, "F3"));
        assertEquals(0, d.floorStats.get("M3").count, "moving again removes it from M3");
        assertEquals(1, d.floorStats.get("F3").count);
    }

    @Test
    void detailedRunsAreCappedPerFloorButLifetimeIsKept() {
        Progress.identify("FloorCap", "acc-1", "chat");
        ProfileData d = Progress.real();
        ActivityStats st = new ActivityStats();
        for (int i = 0; i < 60; i++) {
            ProfileData.DungeonRun r = new ProfileData.DungeonRun();
            r.t = 1000L * i;
            r.xp = 20000 + i;
            r.floor = "F7";
            r.id = "r" + i;
            d.runs.add(0, r);
            st.add(r.t, r.xp, 0, r.id);
        }
        Progress.pruneRuns(d);
        assertEquals(50, d.runs.stream().filter(r -> "F7".equals(r.floor)).count());
        assertEquals(59 * 1000L, d.runs.get(0).t, "the newest runs are kept");
        assertEquals(60, st.count, "lifetime count survives");
        assertEquals(50, st.recent.size());
        assertEquals(20029.5, st.average(), 1e-6, "lifetime average over all 60 runs");
        assertEquals(20000, st.min, 1e-9);
        assertEquals(20059, st.max, 1e-9);
    }
}
