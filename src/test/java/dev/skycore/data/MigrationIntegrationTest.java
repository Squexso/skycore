package dev.skycore.data;

import dev.skycore.core.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real files in older formats (as earlier SKYCORE versions wrote them) are loaded through the normal startup path,
 * migrated, saved and loaded again – in a temporary copy, never the user's data.
 */
class MigrationIntegrationTest {
    @Test
    void olderFilesMigrateWithBackupsAndWithoutLoss(@TempDir Path dir) throws Exception {
        Storage.useDir(dir);
        // progress.json schema 3: unassigned "default" profile + an identified one
        Files.writeString(dir.resolve("progress.json"), """
                {"schema":3,"current":"Apple","profiles":{
                  "default":{"stats":{"catacombs":{"level":20,"xp":10,"need":1000,"gains":[{"t":5,"amount":300}]}},
                             "runs":[{"t":100,"xp":4000,"floor":"F6","status":"complete"}],"floorCompletions":{"F6":3}},
                  "Apple":{"name":"Apple","stats":{"skill_mining":{"level":40,"xp":5,"need":100,"gains":[]}},
                           "runs":[{"t":200,"xp":20000,"floor":"F7","status":"complete"},{"t":300,"xp":0,"floor":null,"status":"incomplete"}],
                           "floorCompletions":{"F7":12}}}}""");
        // sessions.json schema 1: one shared log
        Files.writeString(dir.resolve("sessions.json"), "{\"buckets\":{\"100\":{\"playSeconds\":300,\"xp\":{\"catacombs\":4000.0}}}}");
        // history.json schema 2
        Files.writeString(dir.resolve("history.json"), "{\"schema\":2,\"events\":[{\"t\":5,\"kind\":\"xp\",\"stat\":\"catacombs\",\"amount\":300,\"profile\":\"default\",\"confidence\":0.9}]}");

        Progress.load();
        SessionLog.load();
        History.load();

        // nothing lost, nothing guessed
        ProfileData apple = Progress.stored("Apple"), unassigned = Progress.stored("default");
        assertNotNull(unassigned, "unassigned data is kept, not merged automatically");
        assertEquals(12, apple.floorStats.get("F7").count, "lifetime completions preserved");
        assertEquals(3, unassigned.floorStats.get("F6").count);
        assertEquals(2, apple.runs.size());
        assertEquals(300, SessionLog.of(SessionLog.UNASSIGNED).totalPlaySeconds, "old shared sessions → unassigned");
        assertEquals(0, SessionLog.of("Apple").totalPlaySeconds, "not attributed to a guessed profile");
        assertEquals(1, History.data().events.size());
        assertEquals(History.SCHEMA, History.data().schema);
        // raw copies were taken before the migrations
        try (var s = Files.list(dir.resolve("backups").resolve("migrations"))) {
            var names = s.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(n -> n.startsWith("progress-schema3")), names.toString());
            assertTrue(names.stream().anyMatch(n -> n.startsWith("sessions-schema1")), names.toString());
            assertTrue(names.stream().anyMatch(n -> n.startsWith("history-schema2")), names.toString());
        }

        // save, reload: the migration does not run twice and the data is identical
        Progress.markChanged();
        Progress.saveIfDirty();
        SessionLog.markDirty();
        SessionLog.saveIfDirty();
        History.markDirty();
        History.saveIfDirty();
        Storage.flush();
        String progressAfterFirst = Files.readString(dir.resolve("progress.json"));
        Progress.load();
        SessionLog.load();
        assertEquals(12, Progress.stored("Apple").floorStats.get("F7").count, "no double counting on a second load");
        assertEquals(300, SessionLog.of(SessionLog.UNASSIGNED).totalPlaySeconds);
        Progress.markChanged();
        Progress.saveIfDirty();
        Storage.flush();
        assertEquals(progressAfterFirst.replaceAll("\"lastSeen\": \\d+", ""), Files.readString(dir.resolve("progress.json")).replaceAll("\"lastSeen\": \\d+", ""),
                "second load+save produces the same file");

        // the user assigns the old data – then it is gone from "unassigned" and lives in Apple
        assertTrue(ProfileAssign.hasUnassigned());
        assertTrue(ProfileAssign.applyNow("Apple") > 0);
        assertNull(Progress.stored("default"));
        assertEquals(3, Progress.stored("Apple").floorStats.get("F6").count);
        assertEquals(300, SessionLog.of("Apple").totalPlaySeconds);
        assertEquals("Apple", History.data().events.get(0).profile);
        assertFalse(ProfileAssign.hasUnassigned());
    }
}
