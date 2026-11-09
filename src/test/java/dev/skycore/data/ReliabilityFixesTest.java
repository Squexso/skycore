package dev.skycore.data;

import com.google.gson.JsonParser;
import dev.skycore.core.Storage;
import dev.skycore.tracking.SlayerSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Per-profile sessions, assigning unassigned data, automatic reconciliation and the new integrity checks. */
class ReliabilityFixesTest {
    private static final String ACC = "aaaaaaaa-0000-0000-0000-000000000000";
    private static final StatDef MINING = StatDef.get("skill_mining");

    // ------------------------------------------------------------------ sessions per profile
    @Test
    void sessionStatisticsStaySeparatePerProfile() {
        Progress.identify("SesAlpha", ACC, "chat");
        SessionLog.addPlaySeconds(10);
        SessionLog.record(MINING, 100);
        Progress.identify("SesBeta", ACC, "tab"); // profile switch during play
        SessionLog.addPlaySeconds(5);
        SessionLog.record(MINING, 7);
        SessionLog.Data a = SessionLog.of("SesAlpha"), b = SessionLog.of("SesBeta");
        assertEquals(10, a.totalPlaySeconds);
        assertEquals(5, b.totalPlaySeconds);
        assertEquals(100, a.buckets.values().stream().mapToDouble(x -> x.xp.getOrDefault("skill_mining", 0d)).sum(), 1e-9);
        assertEquals(7, b.buckets.values().stream().mapToDouble(x -> x.xp.getOrDefault("skill_mining", 0d)).sum(), 1e-9);
        SessionLog.Span closed = a.spans.get(a.spans.size() - 1);
        assertEquals("profile_switch", closed.endReason, "the previous profile's session ends on a switch");
        assertEquals(10, closed.playSeconds);
        assertEquals(100, closed.totalXp(), 1e-9);
        assertEquals("open", b.spans.get(b.spans.size() - 1).endReason);
        assertEquals("SesBeta", SessionLog.openKey());
    }

    @Test
    void unknownProfileNeverReceivesGuessedActivity() {
        Progress.identify("SesGamma", ACC, "chat");
        SessionLog.addPlaySeconds(4);
        Progress.markUnknown(); // disconnect / detection failure
        assertEquals("profile_unknown", SessionLog.of("SesGamma").spans.get(SessionLog.of("SesGamma").spans.size() - 1).endReason);
        long unassignedBefore = SessionLog.of(SessionLog.UNASSIGNED).totalPlaySeconds;
        SessionLog.addPlaySeconds(3);              // held, not given to SesGamma
        assertEquals(4, SessionLog.of("SesGamma").totalPlaySeconds);
        Progress.identify("SesDelta", ACC, "chat"); // confirmed within the hold window → belongs to SesDelta
        assertEquals(3, SessionLog.of("SesDelta").totalPlaySeconds);
        Progress.markUnknown();
        SessionLog.addPlaySeconds(6);
        SessionLog.profileLost("profile_unknown", null); // never confirmed → unassigned, not the last profile
        assertEquals(unassignedBefore + 6, SessionLog.of(SessionLog.UNASSIGNED).totalPlaySeconds);
        assertEquals(3, SessionLog.of("SesDelta").totalPlaySeconds);
    }

    @Test
    void legacySessionsMigrateToUnassignedOnce() {
        String legacy = "{\"buckets\":{\"100\":{\"playSeconds\":300,\"xp\":{\"skill_mining\":50.0}},\"101\":{\"playSeconds\":120,\"xp\":{}}}}";
        SessionLog.Store s = SessionLog.parse(JsonParser.parseString(legacy));
        assertEquals(SessionLog.SCHEMA, s.schema);
        assertNull(s.buckets);
        SessionLog.Data u = s.profiles.get(SessionLog.UNASSIGNED);
        assertEquals(2, u.buckets.size(), "nothing lost");
        assertEquals(420, u.totalPlaySeconds);
        // running the migration again (e.g. after an interrupted save) changes nothing
        SessionLog.Store again = SessionLog.parse(Storage.GSON.toJsonTree(s));
        assertEquals(420, again.profiles.get(SessionLog.UNASSIGNED).totalPlaySeconds);
        assertEquals(1, again.profiles.size());
    }

    // ------------------------------------------------------------------ assign unassigned data
    private static ProfileData.DungeonRun run(String id, long t, String floor, double xp) {
        ProfileData.DungeonRun r = new ProfileData.DungeonRun();
        r.id = id; r.t = t; r.floor = floor; r.xp = xp;
        r.classification = new Activity.Classification();
        r.classification.key = floor; r.classification.confirmed = true; r.classification.confidence = 0.95;
        return r;
    }

    private record World(Progress.Saved saved, SessionLog.Store sessions, History.Data hist) {}

    private static World world() {
        Progress.Saved s = new Progress.Saved();
        ProfileData target = new ProfileData();
        target.name = "Apple";
        target.owner = ACC;
        ProfileData.StatProgress cata = target.stat("catacombs");
        cata.level = 30; cata.xp = 100; cata.need = 1000;
        target.runs.add(run("r-shared", 500, "F7", 20000));
        target.floorStats.computeIfAbsent("F7", k -> new ActivityStats()).add(500, 20000, 0, "r-shared");
        target.floorCompletions.put("F7", 1);
        s.profiles.put("Apple", target);
        ProfileData old = new ProfileData();
        ProfileData.StatProgress oc = old.stat("catacombs");
        oc.level = 25; oc.xp = 7; oc.need = 1000;                 // older, must not overwrite
        ProfileData.StatProgress om = old.stat("skill_mining");
        om.level = 40; om.xp = 5; om.need = 100;                   // unknown in target → added
        old.runs.add(run("r-old", 100, "F6", 4000));
        old.runs.add(run("r-shared", 500, "F7", 20000));           // duplicate
        old.floorStats.computeIfAbsent("F6", k -> new ActivityStats()).add(100, 4000, 0, "r-old");
        old.floorStats.computeIfAbsent("F7", k -> new ActivityStats()).add(500, 20000, 0, "r-shared");
        old.floorCompletions.put("F6", 1);
        old.floorCompletions.put("F7", 1);
        Activity.SlayerQuest q = new Activity.SlayerQuest();
        q.id = "q-old"; q.t = 90; q.type = "enderman"; q.tier = 4;
        q.classification.key = "enderman:4"; q.classification.confirmed = true; q.classification.confidence = 0.95;
        q.calc.observed = 500;
        old.slayerQuests.add(q);
        s.profiles.put(ProfileAssign.SOURCE, old);
        SessionLog.Store st = new SessionLog.Store();
        SessionLog.Data u = new SessionLog.Data();
        SessionLog.Bucket b = new SessionLog.Bucket();
        b.playSeconds = 300;
        u.buckets.put(10L, b);
        u.totalPlaySeconds = 300;
        st.profiles.put(SessionLog.UNASSIGNED, u);
        History.Data h = new History.Data();
        History.Event e = new History.Event();
        e.t = 50; e.kind = "xp"; e.stat = "skill_mining"; e.amount = 5; e.profile = "default"; e.confidence = 0.95; e.rule = "skill_abs";
        h.events.add(e);
        return new World(s, st, h);
    }

    @Test
    void assignPreviewChangesNothingAndApplyMovesEverythingOnce() {
        World w = world();
        String before = Storage.GSON.toJson(w.saved());
        ProfileAssign.Preview p = ProfileAssign.preview(w.saved(), w.sessions(), w.hist(), "Apple", false);
        assertTrue(p.possible());
        assertEquals(1, p.runs());
        assertEquals(1, p.runsDuplicate());
        assertEquals(1, p.quests());
        assertEquals(1, p.statsKept(), "catacombs exists in the target and is kept");
        assertEquals(1, p.historyEvents());
        assertEquals(before, Storage.GSON.toJson(w.saved()), "a preview (or cancelling) changes nothing");

        int n = ProfileAssign.apply(w.saved(), w.sessions(), w.hist(), "Apple");
        assertTrue(n > 0);
        ProfileData apple = w.saved().profiles.get("Apple");
        assertEquals(30, apple.peek("catacombs").level, "current level kept");
        assertEquals(40, apple.peek("skill_mining").level, "missing level added");
        assertEquals(2, apple.runs.size(), "duplicate run skipped");
        assertEquals(1, apple.floorStats.get("F7").count, "duplicate not counted twice");
        assertEquals(1, apple.floorStats.get("F6").count);
        assertEquals(1, apple.floorCompletions.get("F7"));
        assertEquals("r-old", apple.runs.get(1).id, "ids and timestamps preserved");
        assertEquals(1, apple.slayerQuests.size());
        assertEquals(0.95, apple.slayerQuests.get(0).classification.confidence, 1e-9);
        assertNull(w.saved().profiles.get(ProfileAssign.SOURCE), "unassigned data is gone after the move");
        assertEquals(300, w.sessions().profiles.get("Apple").totalPlaySeconds);
        assertNull(w.sessions().profiles.get(SessionLog.UNASSIGNED));
        assertEquals("Apple", w.hist().events.get(0).profile);
        assertEquals(0.95, w.hist().events.get(0).confidence, 1e-9);
        assertEquals("assigned_unassigned", w.hist().repairs.get(w.hist().repairs.size() - 1).check, "recorded in the correction log");
        assertEquals(-1, ProfileAssign.apply(w.saved(), w.sessions(), w.hist(), "Apple"), "cannot be assigned twice");
    }

    @Test
    void assignRefusesInvalidTargetsAndCorruptingMerges() {
        World w = world();
        assertEquals(-1, ProfileAssign.apply(w.saved(), w.sessions(), w.hist(), "Nope"));
        assertEquals(-1, ProfileAssign.apply(w.saved(), w.sessions(), w.hist(), ProfileAssign.SOURCE));
        assertNotNull(w.saved().profiles.get(ProfileAssign.SOURCE), "nothing moved");
        ProfileData orig = w.saved().profiles.get("Apple");
        ProfileData broken = Storage.GSON.fromJson(Storage.GSON.toJson(orig), ProfileData.class);
        broken.stat("catacombs").level = 12; // a merge that would change the target's verified level
        assertFalse(ProfileAssign.valid(broken, orig));
        assertTrue(ProfileAssign.valid(Storage.GSON.fromJson(Storage.GSON.toJson(orig), ProfileData.class), orig));
    }

    // ------------------------------------------------------------------ reconciliation by counters
    @Test
    void countersResolveOnlyWhenUnambiguous() {
        var u = List.of(new Reconciler.Uncertain("q1", 5, List.of()));
        var ok = Reconciler.byCounts(Map.of("enderman:1", 5L, "enderman:4", 10L), Map.of("enderman:1", 5L, "enderman:4", 12L), Map.of("enderman:4", 1L), u);
        assertEquals("resolved", ok.reason());
        assertEquals(Map.of("q1", "enderman:4"), ok.resolved());
        var conflict = Reconciler.byCounts(Map.of("enderman:4", 10L), Map.of("enderman:4", 11L), Map.of("enderman:4", 2L), u);
        assertEquals("conflict", conflict.reason(), "a confirmed record the counter does not support");
        assertTrue(conflict.resolved().isEmpty());
        var twoTiers = Reconciler.byCounts(Map.of("enderman:1", 5L, "enderman:4", 10L), Map.of("enderman:1", 6L, "enderman:4", 11L), Map.of(), u);
        assertEquals("ambiguous", twoTiers.reason());
        var unseen = Reconciler.byCounts(Map.of("enderman:4", 10L), Map.of("enderman:4", 13L), Map.of(), u);
        assertEquals("ambiguous", unseen.reason(), "more kills than recorded quests (e.g. played elsewhere) → no guess");
        var vetoed = Reconciler.byCounts(Map.of("enderman:4", 10L), Map.of("enderman:4", 11L), Map.of(),
                List.of(new Reconciler.Uncertain("q1", 5, List.of("enderman:3"))));
        assertEquals("conflict", vetoed.reason(), "XP candidates contradict the counter");
    }

    private static Activity.SlayerQuest uncertainQuest(String id, long t, String type, double observed) {
        Activity.SlayerQuest q = new Activity.SlayerQuest();
        q.id = id; q.t = t; q.type = type;
        q.classification.missing.add("evidence.no_tier");
        q.calc = Rewards.slayer(null, -1, null, observed);
        return q;
    }

    @Test
    void uncertainQuestResolvedFromMenuCountersWithAuditTrail() {
        Rewards.load(null);
        Progress.identify("ReconSlayer", ACC, "chat");
        ProfileData d = Progress.real();
        assertEquals("baseline", Progress.observeSlayerCounts("enderman", Map.of("enderman:1", 3L, "enderman:4", 20L), 1000).reason());
        Activity.SlayerQuest q = uncertainQuest("rq1", 2000, "enderman", 500);
        q.classification.candidates.add("enderman:4");
        Activity.SlayerQuest zombie = uncertainQuest("rq2", 2100, "zombie", 100);
        Progress.recordSlayerQuest(q);
        Progress.recordSlayerQuest(zombie);
        var o = Progress.observeSlayerCounts("enderman", Map.of("enderman:1", 3L, "enderman:4", 21L), 3000);
        assertEquals("resolved", o.reason());
        assertTrue(q.classified());
        assertEquals("enderman:4", q.key());
        assertTrue(q.classification.autoResolved);
        assertEquals(1, q.classification.history.size(), "original classification kept in the audit trail");
        assertTrue(q.classification.history.get(0).contains("uncertain"));
        assertEquals(1, d.slayerStats.get("enderman:4").count);
        assertFalse(zombie.classified(), "a quest of another slayer type is never touched");
        assertTrue(History.data().repairs.stream().anyMatch(r -> "auto_resolved".equals(r.check) && "rq1".equals(r.target)));
    }

    @Test
    void verifiedEventsAreNeverChangedAndConflictsKeepUncertainty() {
        Progress.identify("ReconSafe", ACC, "chat");
        ProfileData d = Progress.real();
        Activity.SlayerQuest confirmed = new Activity.SlayerQuest();
        confirmed.id = "c1"; confirmed.t = 10; confirmed.type = "enderman"; confirmed.tier = 1;
        confirmed.classification.key = "enderman:1"; confirmed.classification.confirmed = true; confirmed.classification.confidence = 0.95;
        Progress.recordSlayerQuest(confirmed);
        assertFalse(Reconciler.resolveQuest(d, "c1", "enderman:4", 0.9, "evidence.menu_counts", "menu_counts"));
        assertEquals("enderman:1", confirmed.key());
        // the counters say T4 rose although the only confirmed quest was T1 → conflict, nothing changes
        Progress.observeSlayerCounts("enderman", Map.of("enderman:1", 1L, "enderman:4", 1L), 5);
        Activity.SlayerQuest open = uncertainQuest("u1", 20, "enderman", -1);
        Progress.recordSlayerQuest(open);
        var o = Progress.observeSlayerCounts("enderman", Map.of("enderman:1", 1L, "enderman:4", 3L), 30);
        assertEquals("conflict", o.reason());
        assertFalse(open.classified());
        // wrong slayer type through late evidence is refused
        assertFalse(Reconciler.resolveQuest(d, "u1", "zombie:4", 0.9, "evidence.sidebar", "late_evidence"));
    }

    @Test
    void dungeonRunsResolveFromCountersOrALateSummary() {
        Progress.identify("ReconDungeon", ACC, "chat");
        ProfileData d = Progress.real();
        Progress.observeFloorCounts(Map.of("F6", 10L, "M1", 2L), 1000);
        ProfileData.DungeonRun r = Progress.recordRun(4100, dev.skycore.tracking.DungeonClassifier.classify(List.of(), null), "S", 300, 0, "Mage");
        assertNotNull(r);
        assertNull(r.floor);
        r.t = 2000; // inside the counter window
        var o = Progress.observeFloorCounts(Map.of("F6", 11L, "M1", 2L), 3000);
        assertEquals("resolved", o.reason());
        assertEquals("F6", r.floor, "F6 rose by one, M1 did not – never M1");
        assertEquals(1, d.floorStats.get("F6").count);
        assertEquals(1, d.floorCompletions.get("F6"));
        ProfileData.DungeonRun r2 = Progress.recordRun(11000, dev.skycore.tracking.DungeonClassifier.classify(List.of(), null), "S", 300, 0, "Mage");
        assertTrue(Progress.resolveRunLate(r2.id, "M1", "evidence.run_summary"));
        assertEquals("M1", r2.floor);
        assertFalse(Progress.resolveRunLate(r2.id, "F6", "evidence.run_summary"), "a resolved run is not changed again");
        // a run whose evidence conflicted (F3 vs M3) only accepts one of its candidates
        ProfileData.DungeonRun r3 = Progress.recordRun(9000, dev.skycore.tracking.DungeonClassifier.classify(List.of("F3"), "M3"), "S", 300, 0, "Mage");
        assertFalse(Progress.resolveRunLate(r3.id, "F6", "evidence.run_summary"));
        assertNull(r3.floor);
    }

    @Test
    void lateSidebarEvidenceBelongsOnlyToTheQuestThatJustEnded() {
        SlayerSession s = new SlayerSession(t -> null);
        s.started(1000);
        s.requirement(1100, "Endermen");
        s.completed(60_000);
        Activity.SlayerQuest q = s.level(60_500, "Enderman", 500);
        assertFalse(q.classified());
        s.sidebar(62_000, "Voidgloom Seraph", "IV", "slain");
        SlayerSession.Late late = s.takeLate();
        assertNotNull(late);
        assertEquals(q.id, late.questId());
        assertEquals("enderman:4", late.key());
        assertFalse(late.conflict());
        // too late
        s.sidebar(90_000, "Voidgloom Seraph", "IV", "slain");
        assertNull(s.takeLate());
        // a new quest started: nothing carries over
        s.started(100_000);
        s.sidebar(100_500, "Voidgloom Seraph", "II", "combat");
        assertNull(s.takeLate());
        // a confirmed quest is never changed by late evidence; a different tier is only reported
        s.completed(130_000);
        Activity.SlayerQuest c = s.level(130_500, "Enderman", 25);
        assertTrue(c.classified());
        s.sidebar(131_000, "Voidgloom Seraph", "IV", "slain");
        SlayerSession.Late conflict = s.takeLate();
        assertTrue(conflict.conflict());
        assertEquals("enderman:2", c.key());
    }

    // ------------------------------------------------------------------ integrity
    @Test
    void newIntegrityChecks() {
        Progress.Saved s = new Progress.Saved();
        ProfileData a = new ProfileData(), b = new ProfileData();
        a.runs.add(run("same", 10, "F7", 100));
        b.runs.add(run("same", 10, "F7", 100));
        ActivityStats broken = new ActivityStats();
        broken.add(10, 100, 0, "same");
        broken.totalXp = 5; // impossible: below its own runs
        a.floorStats.put("F7", broken);
        s.profiles.put("A", a);
        s.profiles.put("B", b);
        SessionLog.Store st = new SessionLog.Store();
        SessionLog.Data ses = new SessionLog.Data();
        SessionLog.Bucket bk = new SessionLog.Bucket();
        bk.playSeconds = 400;
        ses.buckets.put(1L, bk);
        ses.totalPlaySeconds = 100;
        st.profiles.put("A", ses);
        History.Data h = new History.Data();
        Integrity.Report r = Integrity.check(s, h, st);
        List<String> checks = r.issues().stream().map(Integrity.Issue::check).toList();
        assertTrue(checks.contains("profile_conflict"), checks.toString());
        assertTrue(checks.contains("aggregate_broken"), checks.toString());
        assertTrue(checks.contains("session_totals"), checks.toString());
        assertTrue(checks.contains("run_counts") || checks.contains("aggregate_broken"));
        int n = Integrity.repair(s, h, st);
        assertTrue(n >= 2);
        assertEquals(100, a.floorStats.get("F7").totalXp, 1e-9, "rebuilt from the detailed record");
        assertEquals(400, ses.totalPlaySeconds);
        assertEquals(2, s.profiles.size(), "nothing deleted");
        assertEquals(1, b.runs.size(), "the conflicting record is kept and only reported");
    }
}
