package dev.skycore.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.skycore.data.Integrity;
import dev.skycore.tracking.DebugLog;
import dev.skycore.tracking.Health;
import dev.skycore.tracking.HypixelTracker;
import dev.skycore.tracking.parse.CustomPatterns;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Diagnostic report for troubleshooting. Contains tracking health, pattern status, database status, the
 * integrity summary and (if the debugger was on) recognition decisions. It deliberately leaves out player
 * names, UUIDs, profile names (replaced by aliases), chat text, server addresses and file paths.
 */
public final class Diagnostics {
    private Diagnostics() {}

    public static JsonObject build() {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "skycore-diagnostics");
        root.addProperty("created", System.currentTimeMillis());
        root.addProperty("mod_version", net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("skycore")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        root.addProperty("tracking_enabled", Skycore.config().trackingEnabled);
        root.addProperty("language", Skycore.config().language);

        JsonObject health = new JsonObject();
        long now = System.currentTimeMillis();
        for (Health.Source s : Health.Source.values()) {
            Health.Stats st = Health.stats(s);
            JsonObject o = new JsonObject();
            o.addProperty("status", Health.statusOf(st, now).name().toLowerCase());
            o.addProperty("successes", st.successes);
            o.addProperty("failures", st.failures);
            o.addProperty("seconds_since_success", st.lastSuccess > 0 ? (now - st.lastSuccess) / 1000 : -1);
            o.addProperty("seconds_since_error", st.lastError > 0 ? (now - st.lastError) / 1000 : -1);
            if (st.lastErrorText != null) o.addProperty("last_error", st.lastErrorText);
            if (st.note != null) o.addProperty("note", st.note);
            health.add(s.name().toLowerCase(), o);
        }
        root.add("health", health);

        CustomPatterns.Result pr = HypixelTracker.customPatterns();
        JsonObject patterns = new JsonObject();
        patterns.addProperty("built_in", HypixelTracker.parser().builtInCount());
        patterns.addProperty("user_active", pr.loaded());
        JsonArray errs = new JsonArray();
        pr.errors().forEach(errs::add);
        patterns.add("user_errors", errs);
        JsonObject hits = new JsonObject();
        HypixelTracker.parser().hits().forEach(hits::addProperty);
        patterns.add("matches_per_rule", hits);
        JsonObject disabled = new JsonObject();
        HypixelTracker.parser().disabledRules().forEach(disabled::addProperty);
        patterns.add("disabled_at_runtime", disabled);
        root.add("patterns", patterns);

        JsonObject db = new JsonObject();
        Storage.status().forEach((file, st) -> {
            JsonObject o = new JsonObject();
            o.addProperty("state", st.state);
            if (st.error != null) o.addProperty("last_error", st.error);
            o.addProperty("seconds_since_write", st.lastWrite > 0 ? (now - st.lastWrite) / 1000 : -1);
            db.add(file, o);
        });
        root.add("database", db);

        Integrity.Report rep = Integrity.last();
        if (rep != null) {
            JsonObject ir = new JsonObject();
            ir.addProperty("errors", rep.count(Integrity.Severity.ERROR));
            ir.addProperty("warnings", rep.count(Integrity.Severity.WARNING));
            ir.addProperty("info", rep.count(Integrity.Severity.INFO));
            JsonObject byCheck = new JsonObject();
            for (Integrity.Issue i : rep.issues()) byCheck.addProperty(i.check(), byCheck.has(i.check()) ? byCheck.get(i.check()).getAsInt() + 1 : 1);
            ir.add("by_check", byCheck);
            root.add("integrity", ir);
        }
        root.addProperty("backups", Backups.list().size());
        root.addProperty("debugger_enabled", DebugLog.enabled());
        root.add("debugger", DebugLog.export());
        return root;
    }

    /** Writes the report on the storage thread. @return the target file */
    public static Path export() {
        String json = Storage.GSON.toJson(build());
        Path file = Storage.dir().resolve("diagnostics").resolve("diagnostics-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".json");
        Storage.async(() -> {
            try {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, json);
            } catch (Exception e) {
                Skycore.LOG.error("Diagnostics export failed", e);
            }
        });
        return file;
    }
}
