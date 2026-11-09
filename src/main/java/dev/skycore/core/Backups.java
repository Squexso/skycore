package dev.skycore.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skycore.data.Goals;
import dev.skycore.data.History;
import dev.skycore.data.ImportMerge;
import dev.skycore.data.Progress;
import dev.skycore.data.SessionLog;
import dev.skycore.hud.HudStore;
import dev.skycore.tracking.Health;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Full backups (config/skycore/backups): settings, progression, sessions, goals, HUD profiles and history in
 * one checksummed file.
 * <ul>
 *   <li>automatic: on start (daily) and every 6 hours of play, rotated to the configured count</li>
 *   <li>safety: before every destructive action, restore and import (kept: 10)</li>
 *   <li>migration: the raw file is copied before an older format is upgraded (backups/migrations)</li>
 *   <li>manual: "Back up now" / exports – never deleted automatically</li>
 * </ul>
 * Every file is validated (JSON, format, checksum) before it is used. A damaged backup is never restored,
 * and retention never deletes the newest valid backup.
 */
public final class Backups {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    public static final int FORMAT = 2;
    public static final String[] SECTIONS = {"config", "progress", "sessions", "goals", "hud", "history"};
    private static final long PERIODIC = 6 * 3600_000L, DAILY = 20 * 3600_000L;
    private static final int KEEP_SAFETY = 10, KEEP_MIGRATION = 10;

    public record Validation(boolean valid, int format, long created, List<String> sections, String problem, boolean checksummed) {}

    public record Info(Path path, String name, String kind, long created, long size, Validation validation) {}

    public record Recovered(String file, JsonElement section) {}

    private static final Map<Path, Object[]> VALIDATION_CACHE = new ConcurrentHashMap<>();
    private static volatile long lastPeriodicCheck;

    private Backups() {}

    public static Path dir() {
        Path d = Storage.dir().resolve("backups");
        try { Files.createDirectories(d); } catch (Exception ignored) {}
        return d;
    }

    // ------------------------------------------------------------------ building
    /** Snapshot of all data as one JSON tree (taken on the calling thread so it is consistent). */
    public static JsonObject build() {
        JsonObject sections = new JsonObject();
        sections.add("config", Storage.GSON.toJsonTree(Skycore.config()));
        sections.add("progress", Storage.GSON.toJsonTree(Progress.snapshot()));
        sections.add("sessions", Storage.GSON.toJsonTree(SessionLog.snapshot()));
        sections.add("goals", Storage.GSON.toJsonTree(Goals.snapshot()));
        sections.add("hud", Storage.GSON.toJsonTree(HudStore.snapshot()));
        sections.add("history", Storage.GSON.toJsonTree(History.data()));
        return wrap(sections, System.currentTimeMillis());
    }

    /** Wrap sections in the versioned, checksummed envelope. */
    public static JsonObject wrap(JsonObject sections, long created) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("created", created);
        root.addProperty("mod", "skycore");
        root.add("sections", sections);
        root.addProperty("checksum", checksum(sections));
        return root;
    }

    private static final com.google.gson.Gson COMPACT = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().disableHtmlEscaping().create();

    static String checksum(JsonElement sections) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(COMPACT.toJson(sections).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return "";
        }
    }

    /** Write a full backup synchronously. Used for safety backups, which must exist before anything is changed. */
    public static Path exportNow(String prefix) {
        if (DevCapture.blockSaving()) return null;
        JsonObject root = build();
        Path file = uniqueFile(dir(), prefix);
        return writeFile(file, Storage.GSON.toJson(root)) ? file : null;
    }

    /** Write a full backup on the storage thread (periodic backups, "Back up now"). Returns the target file. */
    public static Path exportAsync(String prefix, Runnable after) {
        if (DevCapture.blockSaving()) return null;
        String json = Storage.GSON.toJson(build());
        Path file = uniqueFile(dir(), prefix);
        Storage.async(() -> {
            writeFile(file, json);
            prune();
            if (after != null) after.run();
        });
        return file;
    }

    /** A safety backup before something destructive. @return false = do not proceed. */
    public static boolean safety(String reason) {
        if (DevCapture.blockSaving()) return true;
        Storage.flush(); // pending saves first, so the backup contains them
        Path p = exportNow("before-" + reason);
        if (p == null) { Skycore.LOG.error("Safety backup before {} failed – action cancelled", reason); return false; }
        Storage.async(Backups::prune);
        return true;
    }

    private static Path uniqueFile(Path d, String prefix) {
        String base = prefix + "-" + LocalDateTime.now().format(STAMP);
        Path f = d.resolve(base + ".json");
        for (int i = 2; Files.exists(f); i++) f = d.resolve(base + "-" + i + ".json");
        return f;
    }

    private static boolean writeFile(Path file, String json) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            // verify what is on disk before trusting it
            Validation v = validate(file);
            if (!v.valid()) {
                Skycore.LOG.error("Backup {} failed verification: {}", file.getFileName(), v.problem());
                Health.failure(Health.Source.PERSISTENCE, "backup verification failed");
                return false;
            }
            Health.success(Health.Source.PERSISTENCE);
            return true;
        } catch (Exception e) {
            Skycore.LOG.error("Backup failed", e);
            Health.failure(Health.Source.PERSISTENCE, "backup " + e.getClass().getSimpleName());
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            return false;
        }
    }

    // ------------------------------------------------------------------ validation
    /** Reads and checks a backup file: JSON, envelope, sections and checksum. */
    public static Validation validate(Path file) {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            Object[] c = VALIDATION_CACHE.get(file);
            if (c != null && (long) c[0] == mtime) return (Validation) c[1];
            Validation v = validate(Files.readString(file, StandardCharsets.UTF_8));
            VALIDATION_CACHE.put(file, new Object[]{mtime, v});
            return v;
        } catch (Exception e) {
            return new Validation(false, 0, 0, List.of(), "unreadable file", false);
        }
    }

    /** Pure validation of backup text (unit-tested). */
    public static Validation validate(String text) {
        JsonObject root;
        try {
            if (text == null || text.isBlank()) return new Validation(false, 0, 0, List.of(), "empty file", false);
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception | StackOverflowError e) {
            return new Validation(false, 0, 0, List.of(), "not valid JSON (damaged or truncated)", false);
        }
        int format = root.has("format") && root.get("format").isJsonPrimitive() ? root.get("format").getAsInt() : 0;
        long created = root.has("created") && root.get("created").isJsonPrimitive() ? root.get("created").getAsLong() : 0;
        JsonObject sections = sections(root);
        if (sections == null) return new Validation(false, format, created, List.of(), "no data sections", false);
        List<String> present = new ArrayList<>();
        for (String s : SECTIONS) if (sections.has(s) && !sections.get(s).isJsonNull()) present.add(s);
        if (present.isEmpty()) return new Validation(false, format, created, present, "no data sections", false);
        if (format > FORMAT) return new Validation(false, format, created, present, "made by a newer SKYCORE version", false);
        boolean checksummed = format >= 2;
        if (checksummed) {
            String expected = root.has("checksum") ? root.get("checksum").getAsString() : "";
            if (!expected.equals(checksum(sections))) return new Validation(false, format, created, present, "checksum mismatch (file was altered or damaged)", true);
        }
        if (sections.has("progress")) {
            JsonElement p = sections.get("progress");
            if (!p.isJsonObject() || !p.getAsJsonObject().has("profiles") || !p.getAsJsonObject().get("profiles").isJsonObject())
                return new Validation(false, format, created, present, "progress section is damaged", checksummed);
        }
        return new Validation(true, format, created, present, null, checksummed);
    }

    /** Format 2 keeps data under "sections"; format 1 had them at the top level. */
    public static JsonObject sections(JsonObject root) {
        if (root.has("sections") && root.get("sections").isJsonObject()) return root.getAsJsonObject("sections");
        for (String s : SECTIONS) if (root.has(s)) return root;
        return null;
    }

    // ------------------------------------------------------------------ listing
    /** Full backups, newest first. */
    public static List<Path> list() {
        try (Stream<Path> s = Files.list(dir())) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted(Comparator.comparing(Backups::modified).reversed()).toList();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<Info> infos() {
        List<Info> out = new ArrayList<>();
        for (Path p : list()) {
            String n = p.getFileName().toString();
            int dash = n.indexOf('-');
            String kind = n.startsWith("before-") ? "safety" : dash > 0 ? n.substring(0, dash) : "manual";
            long size = 0;
            try { size = Files.size(p); } catch (Exception ignored) {}
            Validation v = validate(p);
            out.add(new Info(p, n, kind, v.created() > 0 ? v.created() : modified(p), size, v));
        }
        return out;
    }

    /** Newest backup that passes validation, or null. */
    public static Info latestValid() {
        for (Info i : infos()) if (i.validation().valid()) return i;
        return null;
    }

    private static long modified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------ restore / import
    public static JsonObject read(Path file) {
        try { return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception e) { return null; }
    }

    /** What a merge import would do – nothing is changed. */
    public static ImportMerge.Preview preview(Path file) {
        Validation v = validate(file);
        if (!v.valid()) return ImportMerge.Preview.invalid(v.problem());
        JsonObject root = read(file);
        return root == null ? ImportMerge.Preview.invalid("unreadable file") : ImportMerge.preview(sections(root));
    }

    /** Merge: adds what is missing, never overwrites existing values. @return number of added items, or -1 if refused. */
    public static int merge(Path file) {
        Validation v = validate(file);
        if (!v.valid()) return -1;
        JsonObject root = read(file);
        if (root == null || !safety("import")) return -1;
        int n = ImportMerge.apply(sections(root));
        Skycore.saveAll();
        return n;
    }

    /**
     * Full restore (replace). Refused when the file fails validation or the safety backup of the current data
     * cannot be written – so the current database is never replaced by a damaged file, and never lost.
     *
     * @return number of restored sections; -1 refused
     */
    public static int restore(Path file) {
        Validation v = validate(file);
        if (!v.valid()) { Skycore.LOG.warn("Restore refused – {}: {}", file.getFileName(), v.problem()); return -1; }
        JsonObject root = read(file);
        if (root == null) return -1;
        if (!safety("restore")) return -1;
        JsonObject s = sections(root);
        int n = 0;
        try {
            if (s.has("config")) {
                Config c = Storage.GSON.fromJson(s.get("config"), Config.class);
                if (c != null) { Skycore.replaceConfig(c); n++; }
            }
        } catch (Exception e) { Skycore.LOG.warn("Skipped config section", e); }
        try { if (s.has("progress")) { Progress.restore(s.get("progress")); n++; } } catch (Exception e) { Skycore.LOG.warn("Skipped progress", e); }
        try { if (s.has("sessions")) { SessionLog.restore(s.get("sessions")); n++; } } catch (Exception e) { Skycore.LOG.warn("Skipped sessions", e); }
        try { if (s.has("goals")) { Goals.restore(s.get("goals")); n++; } } catch (Exception e) { Skycore.LOG.warn("Skipped goals", e); }
        try { if (s.has("hud")) { HudStore.restore(s.get("hud")); n++; } } catch (Exception e) { Skycore.LOG.warn("Skipped hud", e); }
        try { if (s.has("history")) { History.restore(s.get("history")); n++; } } catch (Exception e) { Skycore.LOG.warn("Skipped history", e); }
        Skycore.saveAll();
        return n;
    }

    // ------------------------------------------------------------------ crash recovery
    private static final Map<String, String> FILE_SECTION = Map.of("config.json", "config", "progress.json", "progress",
            "sessions.json", "sessions", "goals.json", "goals", "hud.json", "hud", "history.json", "history");

    /** The newest valid backup's copy of a data file's section. */
    public static Recovered recoverSection(String file) {
        String section = FILE_SECTION.get(file);
        if (section == null) return null;
        for (Path p : list()) {
            if (!validate(p).valid()) continue;
            JsonObject root = read(p);
            JsonObject s = root == null ? null : sections(root);
            if (s != null && s.has(section) && !s.get(section).isJsonNull()) {
                Skycore.LOG.warn("Recovered {} from backup {}", file, p.getFileName());
                return new Recovered(p.getFileName().toString(), s.get(section));
            }
        }
        return null;
    }

    /** Copy a data file before its format is upgraded (synchronous; runs at startup). */
    public static void beforeMigration(String file, int fromSchema) {
        if (DevCapture.blockSaving()) return;
        try {
            Path src = Storage.dir().resolve(file);
            if (!Files.exists(src)) return;
            Path d = dir().resolve("migrations");
            Files.createDirectories(d);
            Path dst = d.resolve(file.replace(".json", "") + "-schema" + fromSchema + "-" + LocalDateTime.now().format(STAMP) + ".json");
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            Skycore.LOG.info("Saved {} (schema {}) before migrating it", file, fromSchema);
            pruneDir(d, KEEP_MIGRATION, p -> true);
        } catch (Exception e) {
            Skycore.LOG.warn("Could not back up {} before migration", file, e);
        }
    }

    // ------------------------------------------------------------------ automatic & retention
    /** Startup: daily automatic backup (on the storage thread). */
    public static void autoBackup() {
        if (Skycore.config().backupsToKeep <= 0) return;
        long newest = newestOf("auto-");
        if (System.currentTimeMillis() - newest < DAILY) return;
        exportAsync("auto", null);
    }

    /** Called from the client tick; cheap – only compares timestamps every minute. */
    public static void periodic() {
        long now = System.currentTimeMillis();
        if (now - lastPeriodicCheck < 60_000) return;
        lastPeriodicCheck = now;
        if (Skycore.config().backupsToKeep <= 0) return;
        if (now - newestOf("auto-") >= PERIODIC) exportAsync("auto", null);
    }

    private static long newestOf(String prefix) {
        for (Path p : list()) if (p.getFileName().toString().startsWith(prefix)) return modified(p);
        return 0;
    }

    /** Rotation. Manual exports are never deleted; the newest valid backup is never deleted. */
    public static void prune() {
        Info newestValid = latestValid();
        Path protect = newestValid == null ? null : newestValid.path();
        int keep = Math.max(1, Skycore.config().backupsToKeep);
        pruneList(list().stream().filter(p -> p.getFileName().toString().startsWith("auto-")).toList(), keep, protect);
        pruneList(list().stream().filter(p -> p.getFileName().toString().startsWith("before-")).toList(), KEEP_SAFETY, protect);
    }

    static void pruneList(List<Path> newestFirst, int keep, Path protect) {
        for (int i = keep; i < newestFirst.size(); i++) {
            Path p = newestFirst.get(i);
            if (p.equals(protect)) continue;
            try { Files.deleteIfExists(p); VALIDATION_CACHE.remove(p); } catch (Exception ignored) {}
        }
    }

    private static void pruneDir(Path d, int keep, java.util.function.Predicate<Path> filter) {
        try (Stream<Path> s = Files.list(d)) {
            List<Path> files = s.filter(filter).sorted(Comparator.comparing(Backups::modified).reversed()).toList();
            for (int i = keep; i < files.size(); i++) Files.deleteIfExists(files.get(i));
        } catch (Exception ignored) {}
    }
}
