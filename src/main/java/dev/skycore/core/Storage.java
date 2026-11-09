package dev.skycore.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import dev.skycore.tracking.Health;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * JSON persistence. Writes are atomic (temp file + move) and run on one background thread, so the game never
 * waits for the disk. The previous generation of every file is kept as {@code <file>.bak}. When a file cannot
 * be read it is preserved as {@code <file>.broken-<time>} and recovered – first from the previous generation,
 * then from the newest valid backup – instead of silently starting empty.
 */
public final class Storage {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
    private static Path dir;

    /** What happened to each data file in this session (shown as "database status"). */
    public static final class FileStatus {
        /** "ok", "new", "recovered_previous", "recovered_backup", "defaults" (unreadable, nothing to recover) */
        public volatile String state = "ok";
        public volatile long lastWrite, lastError;
        public volatile String error;
        public volatile String recoveredFrom;
    }

    private static final Map<String, FileStatus> STATUS = new ConcurrentHashMap<>();

    private Storage() {}

    public static Path dir() {
        if (dir == null) {
            dir = FabricLoader.getInstance().getConfigDir().resolve("skycore");
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
        }
        return dir;
    }

    /** Tests only. */
    public static void useDir(Path d) { dir = d; }

    public static Map<String, FileStatus> status() { return STATUS; }

    public static FileStatus status(String file) { return STATUS.computeIfAbsent(file, k -> new FileStatus()); }

    public static <T> T load(String file, Type type, Supplier<T> def) {
        Path p = dir().resolve(file);
        FileStatus st = status(file);
        if (!Files.exists(p)) {
            // the main file vanished but a previous generation exists → use it rather than starting empty
            T prev = readOrNull(dir().resolve(file + ".bak"), type);
            if (prev != null) { st.state = "recovered_previous"; st.recoveredFrom = file + ".bak"; Skycore.LOG.warn("{} missing – restored previous generation", file); return prev; }
            st.state = "new";
            return def.get();
        }
        T t = readOrNull(p, type);
        if (t != null) { st.state = "ok"; return t; }
        // unreadable: keep the damaged file, then recover
        Skycore.LOG.error("Could not read {} – keeping the damaged copy and recovering", file);
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        try { Files.copy(p, dir().resolve(file + ".broken-" + stamp), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
        st.error = "unreadable";
        st.lastError = System.currentTimeMillis();
        Health.failure(Health.Source.PERSISTENCE, file + " unreadable");
        T prev = readOrNull(dir().resolve(file + ".bak"), type);
        if (prev != null) { st.state = "recovered_previous"; st.recoveredFrom = file + ".bak"; Skycore.LOG.warn("{} restored from its previous generation", file); return prev; }
        try {
            Backups.Recovered r = Backups.recoverSection(file);
            if (r != null) {
                T fromBackup = GSON.fromJson(r.section(), type);
                if (fromBackup != null) { st.state = "recovered_backup"; st.recoveredFrom = r.file(); return fromBackup; }
            }
        } catch (Exception e) {
            Skycore.LOG.error("Recovery of {} from backups failed", file, e);
        }
        st.state = "defaults";
        return def.get();
    }

    /** null if missing, empty or unreadable. */
    static <T> T readOrNull(Path p, Type type) {
        try {
            if (!Files.exists(p) || Files.size(p) == 0) return null;
            String s = Files.readString(p, StandardCharsets.UTF_8);
            if (s.isBlank()) return null;
            JsonElement el = com.google.gson.JsonParser.parseString(s);
            if (el.isJsonNull()) return null;
            return GSON.fromJson(el, type);
        } catch (Exception | StackOverflowError e) {
            return null;
        }
    }

    /** Single background writer: serialisation happens on the caller's thread (a consistent snapshot), disk I/O does not. */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SKYCORE-storage");
        t.setDaemon(true);
        return t;
    });

    /** Run disk work on the storage thread (in order with all saves). */
    public static void async(Runnable r) {
        WRITER.execute(() -> {
            try { r.run(); } catch (Exception e) { Skycore.LOG.error("Background storage task failed", e); Health.failure(Health.Source.PERSISTENCE, e.getClass().getSimpleName()); }
        });
    }

    public static void save(String file, Object value) {
        if (DevCapture.blockSaving()) return; // screenshot runs must not touch real data
        String json;
        try {
            json = GSON.toJson(value);
        } catch (Exception e) {
            Skycore.LOG.error("Could not serialise {}", file, e);
            Health.failure(Health.Source.PERSISTENCE, "serialise " + file);
            return;
        }
        WRITER.execute(() -> write(dir(), file, json));
    }

    /** Wait for pending writes (game shutdown). */
    public static void flush() {
        try {
            WRITER.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Skycore.LOG.warn("Storage flush did not complete", e);
        }
    }

    /** Atomic write that keeps the previous generation. Package-visible for tests. */
    static boolean write(Path directory, String file, String json) {
        Path p = directory.resolve(file);
        Path tmp = directory.resolve(file + ".tmp");
        FileStatus st = status(file);
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(json);
        } catch (Exception e) {
            fail(st, file, e);
            return false;
        }
        try {
            // keep the last good generation (only if it is readable – never rotate a damaged file over a good .bak)
            if (Files.exists(p) && Files.size(p) > 0 && readOrNull(p, JsonElement.class) != null)
                Files.copy(p, directory.resolve(file + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Skycore.LOG.warn("Could not keep previous generation of {}", file, e);
        }
        try {
            try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception e) { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e2) {
            fail(st, file, e2);
            return false;
        }
        st.lastWrite = System.currentTimeMillis();
        st.error = null;
        if (st.state.equals("new")) st.state = "ok";
        Health.success(Health.Source.PERSISTENCE);
        return true;
    }

    private static void fail(FileStatus st, String file, Exception e) {
        Skycore.LOG.error("Could not write {}", file, e);
        dev.skycore.tracking.DebugLog.add(new dev.skycore.tracking.DebugLog.Entry(System.currentTimeMillis(), dev.skycore.tracking.DebugLog.Filter.STORAGE,
                "write_failed", null, 0, java.util.Map.of("file", file, "error", e.getClass().getSimpleName()), null, "-", false, "io_error", "-"));
        st.error = e.getClass().getSimpleName();
        st.lastError = System.currentTimeMillis();
        Health.failure(Health.Source.PERSISTENCE, "write " + file + ": " + e.getClass().getSimpleName());
    }
}
