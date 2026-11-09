package dev.skycore.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skycore.data.History;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    private static JsonObject progress(int marker) {
        return JsonParser.parseString("{\"schema\":3,\"current\":\"Apple\",\"profiles\":{\"Apple\":{\"bestRunXp\":" + marker + "}}}").getAsJsonObject();
    }

    private static int marker(JsonObject o) {
        return o.getAsJsonObject("profiles").getAsJsonObject("Apple").get("bestRunXp").getAsInt();
    }

    // ------------------------------------------------------------------ backup validation
    @Test
    void validBackupPassesAndAlterationsAreDetected() {
        JsonObject sections = new JsonObject();
        sections.add("progress", progress(1));
        String text = Storage.GSON.toJson(Backups.wrap(sections, 123));
        Backups.Validation v = Backups.validate(text);
        assertTrue(v.valid(), v.problem());
        assertTrue(v.checksummed());
        assertEquals(List.of("progress"), v.sections());
        assertEquals(123, v.created());

        assertFalse(Backups.validate(text.replace("\"bestRunXp\": 1", "\"bestRunXp\": 999")).valid(), "edited content → checksum mismatch");
        assertFalse(Backups.validate(text.substring(0, text.length() / 2)).valid(), "truncated file");
        assertFalse(Backups.validate("").valid());
        assertFalse(Backups.validate("{\"format\":2}").valid(), "no sections");
        assertFalse(Backups.validate("{\"format\":99,\"sections\":{\"goals\":[]}}").valid(), "newer format is refused");
        // format 1 (before checksums) is still accepted, flagged as unchecksummed
        Backups.Validation legacy = Backups.validate("{\"format\":1,\"created\":5,\"progress\":" + progress(2) + "}");
        assertTrue(legacy.valid(), legacy.problem());
        assertFalse(legacy.checksummed());
        // structurally damaged progress section
        JsonObject bad = new JsonObject();
        bad.add("progress", JsonParser.parseString("{\"current\":\"x\"}"));
        assertFalse(Backups.validate(Storage.GSON.toJson(Backups.wrap(bad, 1))).valid());
    }

    @Test
    void retentionNeverDeletesTheNewestValidBackup(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("auto-1.json"), "x"), b = Files.writeString(dir.resolve("auto-2.json"), "x"),
                c = Files.writeString(dir.resolve("auto-3.json"), "x");
        Backups.pruneList(List.of(a, b, c), 1, c); // keep 1, but c is the only valid one
        assertTrue(Files.exists(a));
        assertFalse(Files.exists(b));
        assertTrue(Files.exists(c), "protected");
    }

    // ------------------------------------------------------------------ storage recovery
    @Test
    void corruptFileIsRecoveredFromPreviousGeneration(@TempDir Path dir) throws Exception {
        Storage.useDir(dir);
        assertTrue(Storage.write(dir, "progress.json", Storage.GSON.toJson(progress(1))));
        assertTrue(Storage.write(dir, "progress.json", Storage.GSON.toJson(progress(2))));
        assertEquals(1, marker(JsonParser.parseString(Files.readString(dir.resolve("progress.json.bak"))).getAsJsonObject()), "previous generation kept");
        Files.writeString(dir.resolve("progress.json"), "{\"profiles\": {\"Apple\": "); // crash mid-write
        JsonObject loaded = Storage.load("progress.json", JsonObject.class, JsonObject::new);
        assertEquals(1, marker(loaded), "recovered instead of starting empty");
        assertEquals("recovered_previous", Storage.status("progress.json").state);
        try (var s = Files.list(dir)) { assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("progress.json.broken-")), "damaged copy kept"); }
        // a damaged main file is never rotated over the good previous generation
        Storage.write(dir, "progress.json", Storage.GSON.toJson(progress(3)));
        assertEquals(1, marker(JsonParser.parseString(Files.readString(dir.resolve("progress.json.bak"))).getAsJsonObject()));
    }

    @Test
    void corruptFileIsRecoveredFromNewestValidBackup(@TempDir Path dir) throws Exception {
        Storage.useDir(dir);
        Files.createDirectories(dir.resolve("backups"));
        JsonObject sections = new JsonObject();
        sections.add("progress", progress(7));
        Files.writeString(dir.resolve("backups").resolve("auto-a.json"), Storage.GSON.toJson(Backups.wrap(sections, 1)));
        // a newer but damaged backup must be skipped
        Thread.sleep(20);
        Files.writeString(dir.resolve("backups").resolve("auto-b.json"), "{\"format\":2,\"sections\":{\"progress\":");
        Files.writeString(dir.resolve("progress.json"), "garbage");
        JsonObject loaded = Storage.load("progress.json", JsonObject.class, JsonObject::new);
        assertEquals(7, marker(loaded));
        assertEquals("recovered_backup", Storage.status("progress.json").state);
        assertEquals("auto-a.json", Storage.status("progress.json").recoveredFrom);
    }

    @Test
    void nothingRecoverableFallsBackToDefaultsButKeepsTheDamagedFile(@TempDir Path dir) throws Exception {
        Storage.useDir(dir);
        Files.writeString(dir.resolve("goals.json"), "[{");
        List<?> loaded = Storage.load("goals.json", List.class, java.util.ArrayList::new);
        assertTrue(loaded.isEmpty());
        assertEquals("defaults", Storage.status("goals.json").state);
        try (var s = Files.list(dir)) { assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("goals.json.broken-"))); }
    }

    // ------------------------------------------------------------------ crash detection
    @Test
    void interruptedSessionEndsAtLastHeartbeat(@TempDir Path dir) throws Exception {
        assertNull(SessionGuard.begin(dir, 1_000), "first start: nothing interrupted");
        SessionGuard.heartbeat(5_000, "Apple");
        SessionGuard.disconnected();
        SessionGuard.heartbeat(6_000, "Apple");
        // the game dies here – no end()/release()
        History.SessionRecord r = SessionGuard.begin(dir, 90_000);
        assertNotNull(r);
        assertEquals("interrupted", r.endReason);
        assertEquals(1_000, r.start);
        assertEquals(6_000, r.end, "ends at the last save, never at the restart time");
        assertEquals("Apple", r.profile);
        assertEquals(1, r.disconnects);
        // clean shutdown leaves nothing behind
        History.SessionRecord clean = SessionGuard.end(95_000, "Apple");
        assertEquals("clean", clean.endReason);
        SessionGuard.release();
        assertFalse(Files.exists(dir.resolve(SessionGuard.FILE)));
        assertNull(SessionGuard.begin(dir, 100_000));
        SessionGuard.release();
    }

    @Test
    void unreadableLockStillReportsInterruptionWithoutInventingTimes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(SessionGuard.FILE), "\u0000\u0000garbage");
        History.SessionRecord r = SessionGuard.begin(dir, 5_000);
        assertNotNull(r);
        assertEquals(0, r.start);
        assertEquals(0, r.end);
        SessionGuard.release();
    }
}
