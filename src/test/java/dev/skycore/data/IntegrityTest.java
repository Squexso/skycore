package dev.skycore.data;

import dev.skycore.data.ProfileData.DungeonRun;
import dev.skycore.data.ProfileData.StatProgress;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntegrityTest {
    private static DungeonRun run(long t, String floor, double xp, String status) {
        DungeonRun r = new DungeonRun();
        r.t = t;
        r.floor = floor;
        r.xp = xp;
        r.status = status;
        return r;
    }

    private static Progress.Saved damaged() {
        Progress.Saved s = new Progress.Saved();
        s.current = "Apple";
        ProfileData d = new ProfileData();
        StatProgress cata = d.stat("catacombs");
        cata.level = 30; cata.xp = -50; cata.need = 1000; cata.updated = 1;
        StatProgress mining = d.stat("skill_mining");
        mining.level = 20; mining.xp = Double.NaN; mining.need = 500; mining.updated = 1;
        StatProgress farming = d.stat("skill_farming");
        farming.level = 10; farming.xp = 5000; farming.need = 1000; farming.updated = 1; // conflict – reported, not changed
        cata.gains.add(new ProfileData.Gain(100, 300));
        cata.gains.add(new ProfileData.Gain(100, 300));   // duplicate
        cata.gains.add(new ProfileData.Gain(200, -5));    // impossible
        d.runs.add(run(1000, "F7", 300, "complete"));
        d.runs.add(run(1000, "F7", 300, "complete"));     // duplicate
        d.runs.add(run(2000, "F6", 0, "complete"));       // completed without XP – reported only
        d.floorCompletions.put("F7", 1);
        s.profiles.put("Apple", d);
        return s;
    }

    private static History.Data history() {
        History.Data h = new History.Data();
        History.Event ok = new History.Event();
        ok.t = 5; ok.kind = "xp"; ok.stat = "mining"; ok.amount = 10; ok.confidence = 0.9; ok.profile = "Apple";
        History.Event broken = new History.Event();
        broken.t = 0; broken.kind = null;
        History.Event conf = new History.Event();
        conf.t = 6; conf.kind = "xp"; conf.amount = 1; conf.confidence = 7; conf.profile = "Apple";
        h.events.add(ok); h.events.add(broken); h.events.add(conf);
        // level decrease between days
        h.snapshots.put("2026-11-01", new LinkedHashMap<>(Map.of("Apple", new LinkedHashMap<>(Map.of("catacombs", 31.5)))));
        h.snapshots.put("2026-11-02", new LinkedHashMap<>(Map.of("Apple", new LinkedHashMap<>(Map.of("catacombs", 30.2)))));
        return h;
    }

    private static SessionLog.Store sessions() {
        SessionLog.Store st = new SessionLog.Store();
        SessionLog.Data s = new SessionLog.Data();
        st.profiles.put("Apple", s);
        SessionLog.Bucket b = new SessionLog.Bucket();
        b.playSeconds = 900;
        b.xp.put("mining", -20.0);
        s.buckets.put(1L, b);
        SessionLog.Bucket fine = new SessionLog.Bucket();
        fine.playSeconds = 300;
        fine.xp.put("mining", 100.0);
        s.buckets.put(2L, fine);
        return st;
    }

    private static boolean has(Integrity.Report r, String check) { return r.issues().stream().anyMatch(i -> i.check().equals(check)); }

    @Test
    void detectsEveryKindOfProblem() {
        Integrity.Report r = Integrity.check(damaged(), history(), sessions());
        for (String c : new String[]{"negative_xp", "invalid_number", "xp_conflict", "duplicate_gain", "invalid_gain", "duplicate_run",
                "run_inconsistent", "corrupt_history", "level_decrease", "session_totals"})
            assertTrue(has(r, c), "missing check " + c + " in " + r.issues());
        assertTrue(r.count(Integrity.Severity.ERROR) > 0);
        assertTrue(r.repairable() > 0);
    }

    @Test
    void cleanDataHasNoIssues() {
        Progress.Saved s = new Progress.Saved();
        s.current = "Apple";
        ProfileData d = new ProfileData();
        StatProgress p = d.stat("catacombs");
        p.level = 20; p.xp = 100; p.need = 1000; p.updated = 5;
        d.runs.add(run(10, "F7", 200, "complete"));
        d.floorCompletions.put("F7", 3);
        s.profiles.put("Apple", d);
        assertTrue(Integrity.check(s, new History.Data(), new SessionLog.Store()).clean());
    }

    @Test
    void repairFixesOnlyProvablyInvalidValuesAndLogsEverything() {
        Progress.Saved s = damaged();
        History.Data h = history();
        SessionLog.Store store = sessions();
        SessionLog.Data ses = store.profiles.get("Apple");
        int n = Integrity.repair(s, h, store);
        assertTrue(n > 0);
        assertEquals(n, h.repairs.size(), "every change is logged");
        ProfileData d = s.profiles.get("Apple");
        StatProgress cata = d.peek("catacombs");
        assertEquals(30, cata.level, "the level is never touched");
        assertEquals(0, cata.xp);
        assertTrue(cata.xpUnknown, "unknown XP is marked, not invented");
        assertEquals(DataStatus.ESTIMATED, cata.status());
        assertTrue(d.peek("skill_mining").xpUnknown);
        assertEquals(5000, d.peek("skill_farming").xp, "plausible-but-odd values are left for the next verified reading");
        assertEquals(1, cata.gains.size(), "duplicate and impossible gains removed");
        assertEquals(2, d.runs.size(), "only the exact duplicate run removed");
        assertEquals(2, h.events.size(), "damaged event removed");
        assertTrue(h.events.stream().allMatch(e -> e.confidence >= 0 && e.confidence <= 1));
        assertEquals(600, ses.buckets.get(1L).playSeconds);
        assertTrue(ses.buckets.get(1L).xp.isEmpty());
        assertEquals(100.0, ses.buckets.get(2L).xp.get("mining"), "valid data untouched");
        // after repair only non-repairable findings remain
        Integrity.Report after = Integrity.check(s, h, store);
        assertEquals(0, after.repairable(), after.issues().toString());
        assertTrue(has(after, "level_decrease"), "a level decrease is reported, never 'fixed'");
        assertTrue(has(after, "xp_conflict"));
        // a second repair changes nothing
        assertEquals(0, Integrity.repair(s, h, store));
    }
}
