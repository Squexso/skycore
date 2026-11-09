package dev.skycore.data;

import com.google.gson.JsonObject;
import dev.skycore.core.Storage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfileAndImportTest {
    private static final String ACC_A = "aaaaaaaa-1111-2222-3333-444444444444";
    private static final String ACC_B = "bbbbbbbb-1111-2222-3333-444444444444";

    // ------------------------------------------------------------------ identity & isolation
    @Test
    void sameProfileNameOnTwoAccountsStaysSeparate() {
        Map<String, ProfileData> profiles = new LinkedHashMap<>();
        assertEquals("Apple", Progress.resolveKey(profiles, "Apple", ACC_A));
        ProfileData a = new ProfileData();
        a.owner = ACC_A;
        profiles.put("Apple", a);
        assertEquals("Apple", Progress.resolveKey(profiles, "Apple", ACC_A), "same account → same profile");
        String bKey = Progress.resolveKey(profiles, "Apple", ACC_B);
        assertEquals("Apple#bbbbbbbb", bKey, "other account → separate storage");
        assertEquals("apple", Progress.resolveKey(profiles, "apple", ACC_B), "names are exact – 'apple' is not 'Apple'");
        // legacy profile without owner is claimed by the first account that plays it
        ProfileData legacy = new ProfileData();
        profiles.put("Banana", legacy);
        assertEquals("Banana", Progress.resolveKey(profiles, "Banana", ACC_B));
    }

    @Test
    void unknownProfileStateAndSwitching() {
        String key = Progress.identify("IsoAlpha", ACC_A, "chat");
        assertEquals("IsoAlpha", key);
        assertTrue(Progress.isIdentified());
        assertEquals("chat", Progress.identitySource());
        Progress.markUnknown(); // disconnect
        assertFalse(Progress.isIdentified(), "after reconnecting the profile must be confirmed again");
        assertEquals("IsoAlpha", Progress.profileKey(), "the last profile is remembered but not trusted");
        Progress.identify("IsoBeta", ACC_A, "tab");
        assertEquals("IsoBeta", Progress.profileKey());
        Progress.gain(StatDef.get("catacombs"), 1); // unknown level: only recorded as a gain
        assertTrue(Progress.stored("IsoAlpha").stat("catacombs").gains.isEmpty(), "data stays with the active profile");
        assertFalse(Progress.deleteProfile("IsoBeta"), "the active profile cannot be deleted");
        assertTrue(Progress.deleteProfile("IsoAlpha"));
        assertNull(Progress.stored("IsoAlpha"));
        assertNull(Progress.identify("  ", ACC_A, "chat"), "blank names are ignored");
        assertNull(Progress.identify("x".repeat(40), ACC_A, "chat"));
    }

    @Test
    void reconnectDoesNotDoubleCount() {
        Progress.identify("IsoReconnect", ACC_A, "chat");
        StatDef mining = StatDef.get("skill_mining");
        long need = XpTables.need(mining.table(), 20);
        double d1 = Progress.observe(mining, 20, 1000, need, "action_bar");
        assertEquals(-1, d1, "first observation has nothing to compare with");
        Progress.markUnknown();
        Progress.identify("IsoReconnect", ACC_A, "chat");
        // the same action-bar state seen again after reconnecting → zero delta, no gain to record
        assertEquals(0, Progress.observe(mining, 20, 1000, need, "action_bar"), 1e-9);
        assertEquals(30, Progress.observe(mining, 20, 1030, need, "action_bar"), 1e-9, "only the real difference");
    }

    // ------------------------------------------------------------------ import merge
    private static Progress.Saved current() {
        Progress.Saved s = new Progress.Saved();
        ProfileData apple = new ProfileData();
        apple.owner = ACC_A;
        apple.name = "Apple";
        ProfileData.StatProgress c = apple.stat("catacombs");
        c.level = 35; c.xp = 100; c.need = 1000;
        apple.runs.add(run(1000, "F7", 300));
        s.profiles.put("Apple", apple);
        return s;
    }

    private static ProfileData.DungeonRun run(long t, String floor, double xp) {
        ProfileData.DungeonRun r = new ProfileData.DungeonRun();
        r.t = t; r.floor = floor; r.xp = xp;
        return r;
    }

    private static JsonObject backup() {
        Progress.Saved in = new Progress.Saved();
        ProfileData apple = new ProfileData();
        apple.owner = ACC_A;
        apple.name = "Apple";
        ProfileData.StatProgress c = apple.stat("catacombs");
        c.level = 30; c.xp = 5; c.need = 1000;          // older, lower – must not overwrite
        ProfileData.StatProgress m = apple.stat("skill_mining");
        m.level = 12; m.xp = 50; m.need = 500;         // unknown in current → filled in
        apple.runs.add(run(1000, "F7", 300));            // duplicate
        apple.runs.add(run(500, "F6", 200));             // new
        in.profiles.put("Apple", apple);
        ProfileData other = new ProfileData();
        other.owner = ACC_B;
        other.name = "Apple";
        in.profiles.put("Apple#x", other);               // name clash from another account
        ProfileData kiwi = new ProfileData();
        kiwi.name = "Kiwi";
        in.profiles.put("Kiwi", kiwi);
        JsonObject sections = new JsonObject();
        sections.add("progress", Storage.GSON.toJsonTree(in));
        SessionLog.Data ses = new SessionLog.Data();
        ses.buckets.put(1L, new SessionLog.Bucket());
        ses.buckets.put(2L, new SessionLog.Bucket());
        sections.add("sessions", Storage.GSON.toJsonTree(ses));
        return sections;
    }

    @Test
    void mergeAddsOnlyWhatIsMissing() {
        Progress.Saved cur = current();
        SessionLog.Store ses = new SessionLog.Store();
        ses.profiles.put(SessionLog.UNASSIGNED, new SessionLog.Data());
        SessionLog.Bucket existing = new SessionLog.Bucket();
        existing.playSeconds = 123;
        ses.profiles.get(SessionLog.UNASSIGNED).buckets.put(1L, existing);
        List<Goals.Goal> goals = new ArrayList<>();
        History.Data hist = new History.Data();

        ImportMerge.Preview p = ImportMerge.preview(backup(), cur, ses, goals, hist);
        assertTrue(p.valid());
        assertEquals(3, p.profiles().size());
        ImportMerge.ProfilePlan apple = p.profiles().stream().filter(x -> x.key().equals("Apple")).findFirst().orElseThrow();
        assertEquals(ImportMerge.ProfileAction.MERGE, apple.action());
        assertEquals(1, apple.newRuns());
        assertEquals(1, apple.duplicateRuns());
        assertEquals(1, p.newSessionBlocks());
        assertEquals(1, p.existingSessionBlocks());
        assertEquals(1, cur.profiles.get("Apple").runs.size(), "preview changes nothing");

        ImportMerge.apply(backup(), cur, ses, goals, hist);
        ProfileData merged = cur.profiles.get("Apple");
        assertEquals(35, merged.peek("catacombs").level, "current value wins over the older backup");
        assertEquals(12, merged.peek("skill_mining").level, "gaps are filled");
        assertEquals(2, merged.runs.size(), "duplicate run skipped, new run added");
        assertEquals(1000, merged.runs.get(0).t, "runs sorted newest first");
        assertTrue(cur.profiles.containsKey("Kiwi"));
        assertEquals(3, cur.profiles.size(), "Apple, Kiwi and the other account's Apple stored separately");
        // a backup in the old single-log format can only be imported as unassigned – its profile is unknown
        assertEquals(123, ses.profiles.get(SessionLog.UNASSIGNED).buckets.get(1L).playSeconds, "existing session block untouched");
        assertEquals(2, ses.profiles.get(SessionLog.UNASSIGNED).buckets.size());
        assertTrue(cur.profiles.containsKey("Apple#bbbbbbbb"));
        // importing the same file again adds nothing – not even the separately stored profile
        assertEquals(0, ImportMerge.apply(backup(), cur, ses, goals, hist), "second import is a no-op");
        assertEquals(3, cur.profiles.size());
    }

    @Test
    void secondMergeIsIdempotentForRunsAndSessions() {
        Progress.Saved cur = current();
        SessionLog.Store ses = new SessionLog.Store();
        List<Goals.Goal> goals = new ArrayList<>();
        History.Data hist = new History.Data();
        ImportMerge.apply(backup(), cur, ses, goals, hist);
        int runs = cur.profiles.get("Apple").runs.size();
        int blocks = ses.profiles.get(SessionLog.UNASSIGNED).buckets.size();
        ImportMerge.Preview again = ImportMerge.preview(backup(), cur, ses, goals, hist);
        ImportMerge.ProfilePlan apple = again.profiles().stream().filter(x -> x.key().equals("Apple")).findFirst().orElseThrow();
        assertEquals(0, apple.newRuns());
        assertEquals(0, again.newSessionBlocks());
        ImportMerge.apply(backup(), cur, ses, goals, hist);
        assertEquals(runs, cur.profiles.get("Apple").runs.size());
        assertEquals(blocks, ses.profiles.get(SessionLog.UNASSIGNED).buckets.size());
    }

    // ------------------------------------------------------------------ statistics export
    @Test
    void statisticsExportIsSafeCsv() {
        Map<String, ProfileData> profiles = new LinkedHashMap<>();
        ProfileData d = new ProfileData();
        d.name = "=cmd|calc";
        d.owner = ACC_A;
        ProfileData.StatProgress c = d.stat("catacombs");
        c.level = 30; c.xp = 1234.5; c.need = 5000; c.source = "menu"; c.verifiedAt = 1;
        ProfileData.StatProgress m = d.stat("mining");
        m.level = 20; m.xpUnknown = true; m.source = "tab";
        d.runs.add(run(1000, "F7", 300));
        profiles.put("p", d);
        SessionLog.Store ses = new SessionLog.Store();
        ses.profiles.put("p", new SessionLog.Data());
        SessionLog.Bucket b = new SessionLog.Bucket();
        b.playSeconds = 600;
        b.xp.put("mining", 1500.0);
        ses.profiles.get("p").buckets.put(3L, b);
        Map<String, String> out = StatsExport.build(profiles, ses);
        String stats = out.get("stats.csv");
        assertTrue(stats.startsWith("profile,stat,level,xp,need,status,source,verified_at"));
        assertTrue(stats.contains("'=cmd|calc,catacombs,30,1234.5,5000,verified,menu"), stats);
        assertTrue(stats.contains(",mining,20,,"), "unknown XP is exported empty, not as 0: " + stats);
        assertTrue(stats.contains("estimated"));
        assertTrue(out.get("runs.csv").contains(",F7,complete,300,"));
        assertTrue(out.get("sessions.csv").contains(",600,mining,1500"));
        for (String v : out.values()) assertFalse(v.contains(ACC_A), "no account UUIDs in exports");
        assertEquals("\"a,b\",\"say \"\"hi\"\"\",-5", StatsExport.csv("a,b", "say \"hi\"", "-5"));
    }
}
