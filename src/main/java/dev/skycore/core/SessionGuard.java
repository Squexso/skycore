package dev.skycore.core;

import dev.skycore.data.History;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash / interruption detection. A small lock file ({@code session.lock}) exists while the game runs and is
 * refreshed with every periodic save. If it is still there at the next start, the previous session did not
 * shut down cleanly: it is recorded as <i>interrupted</i>, ending at its last heartbeat – the time up to which
 * data was saved. No activity is invented for the gap.
 */
public final class SessionGuard {
    public static final String FILE = "session.lock";

    public static final class Lock {
        public int version = 1;
        public long start, heartbeat;
        public String profile;
        public int disconnects;
    }

    private static Lock current;
    private static History.SessionRecord lastInterrupted;
    private static Path dir;
    /** Where heartbeat writes run – the storage writer thread in game, inline in tests. */
    public static java.util.function.Consumer<Runnable> io = Runnable::run;

    private SessionGuard() {}

    /** Game start. @return the interrupted previous session, or null if the last shutdown was clean. */
    public static History.SessionRecord begin(Path directory, long now) {
        dir = directory;
        History.SessionRecord interrupted = null;
        Path f = directory.resolve(FILE);
        if (Files.exists(f)) {
            Lock old = null;
            try { old = Storage.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Lock.class); } catch (Exception ignored) {}
            interrupted = new History.SessionRecord();
            interrupted.endReason = "interrupted";
            if (old != null && old.start > 0) {
                interrupted.start = old.start;
                // the last heartbeat is the last moment data was saved – never "now"
                interrupted.end = Math.max(old.start, old.heartbeat);
                interrupted.profile = old.profile;
                interrupted.disconnects = old.disconnects;
            } else {
                // unreadable lock: we know a session was interrupted, but not when – leave times unknown (0)
                interrupted.start = interrupted.end = 0;
            }
        }
        current = new Lock();
        current.start = current.heartbeat = now;
        write();
        lastInterrupted = interrupted;
        return interrupted;
    }

    /** Called with every periodic save. Cheap: a few bytes, written atomically. */
    public static void heartbeat(long now, String profile) {
        if (current == null) return;
        current.heartbeat = now;
        current.profile = profile;
        String json = Storage.GSON.toJson(current);
        io.accept(() -> writeJson(json));
    }

    public static void disconnected() { if (current != null) current.disconnects++; }

    /** Clean shutdown, step 1: the record of this session (saved together with the other data). */
    public static History.SessionRecord end(long now, String profile) {
        if (current == null) return null;
        History.SessionRecord r = new History.SessionRecord();
        r.start = current.start;
        r.end = now;
        r.profile = profile;
        r.endReason = "clean";
        r.disconnects = current.disconnects;
        return r;
    }

    /** Clean shutdown, step 2 – only after all data has been flushed to disk: remove the lock. */
    public static void release() {
        current = null;
        try { if (dir != null) Files.deleteIfExists(dir.resolve(FILE)); } catch (Exception e) { Skycore.LOG.warn("Could not remove session lock", e); }
    }

    public static History.SessionRecord lastInterrupted() { return lastInterrupted; }

    public static long sessionStart() { return current == null ? 0 : current.start; }

    /** Synchronous tiny write (the lock must be on disk before anything else can go wrong). */
    private static void write() {
        if (current != null) writeJson(Storage.GSON.toJson(current));
    }

    private static void writeJson(String json) {
        if (dir == null || DevCapture.blockSaving()) return;
        try {
            Path tmp = dir.resolve(FILE + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try { Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception e) { Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            Skycore.LOG.warn("Could not write session lock", e);
        }
    }
}
