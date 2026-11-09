package dev.skycore.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.skycore.core.Storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Statistics export for analysis (separate from full backups): one JSON file plus CSV tables for skills/levels,
 * dungeon runs and session blocks. Contains SkyBlock profile names and numbers only – no account names, UUIDs,
 * chat or server data. Every value carries its data status so estimates are never mistaken for verified values.
 */
public final class StatsExport {
    private StatsExport() {}

    /** Build all contents on the calling thread (consistent snapshot). */
    public static Map<String, String> build() {
        return build(Progress.saved().profiles, SessionLog.raw());
    }

    static Map<String, String> build(Map<String, ProfileData> profiles, SessionLog.Store sessions) {
        return Map.of("json", json(profiles, sessions), "stats.csv", statsCsv(profiles), "runs.csv", runsCsv(profiles), "sessions.csv", sessionsCsv(sessions));
    }

    public static void write(Path dir, String base, Map<String, String> contents) throws java.io.IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(base + ".json"), contents.get("json"), StandardCharsets.UTF_8);
        for (String k : new String[]{"stats.csv", "runs.csv", "sessions.csv"})
            Files.writeString(dir.resolve(base + "-" + k), contents.get(k), StandardCharsets.UTF_8);
    }

    static String json(Map<String, ProfileData> profiles, SessionLog.Store sessions) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "skycore-statistics");
        root.addProperty("format", 1);
        root.addProperty("exported", Instant.now().toString());
        JsonArray arr = new JsonArray();
        for (var e : profiles.entrySet()) {
            ProfileData d = e.getValue();
            if (d == null) continue;
            JsonObject p = new JsonObject();
            p.addProperty("profile", d.name != null ? d.name : e.getKey());
            JsonObject stats = new JsonObject();
            for (var s : d.stats.entrySet()) {
                ProfileData.StatProgress sp = s.getValue();
                if (sp == null || !sp.known()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("level", sp.level);
                if (!sp.xpUnknown) o.addProperty("xp", sp.xp);
                if (sp.need > 0) o.addProperty("need", sp.need);
                o.addProperty("status", sp.status().name().toLowerCase());
                if (sp.source != null) o.addProperty("source", sp.source);
                if (sp.verifiedAt > 0) o.addProperty("verified_at", Instant.ofEpochMilli(sp.verifiedAt).toString());
                o.addProperty("count", sp.count);
                stats.add(s.getKey(), o);
            }
            p.add("stats", stats);
            p.add("values", Storage.GSON.toJsonTree(d.values));
            p.add("collections", Storage.GSON.toJsonTree(d.collections));
            p.add("floor_completions", Storage.GSON.toJsonTree(d.floorCompletions));
            p.addProperty("runs", d.runs.size());
            SessionLog.Data ses = sessions.profiles.get(e.getKey());
            p.addProperty("tracked_playtime_seconds", ses == null ? 0 : ses.totalPlaySeconds);
            p.addProperty("sessions", ses == null ? 0 : ses.spans.size());
            arr.add(p);
        }
        root.add("profiles", arr);
        SessionLog.Data un = sessions.profiles.get(SessionLog.UNASSIGNED);
        root.addProperty("unassigned_playtime_seconds", un == null ? 0 : un.totalPlaySeconds);
        return Storage.GSON.toJson(root);
    }

    static String statsCsv(Map<String, ProfileData> profiles) {
        List<String> rows = new ArrayList<>();
        rows.add("profile,stat,level,xp,need,status,source,verified_at");
        for (var e : profiles.entrySet()) {
            ProfileData d = e.getValue();
            if (d == null) continue;
            String name = d.name != null ? d.name : e.getKey();
            for (var s : d.stats.entrySet()) {
                ProfileData.StatProgress sp = s.getValue();
                if (sp == null || !sp.known()) continue;
                rows.add(csv(name, s.getKey(), sp.level, sp.xpUnknown ? "" : num(sp.xp), sp.need > 0 ? num(sp.need) : "",
                        sp.status().name().toLowerCase(), sp.source, sp.verifiedAt > 0 ? Instant.ofEpochMilli(sp.verifiedAt).toString() : ""));
            }
        }
        return String.join("\n", rows) + "\n";
    }

    static String runsCsv(Map<String, ProfileData> profiles) {
        List<String> rows = new ArrayList<>();
        rows.add("profile,time,floor,status,xp,score,score_points,class,class_xp,duration_s");
        for (var e : profiles.entrySet()) {
            ProfileData d = e.getValue();
            if (d == null) continue;
            String name = d.name != null ? d.name : e.getKey();
            for (ProfileData.DungeonRun r : d.runs) {
                if (r == null) continue;
                rows.add(csv(name, r.t > 0 ? Instant.ofEpochMilli(r.t).toString() : "", r.floor, r.complete() ? "complete" : "incomplete",
                        num(r.xp), r.score, r.scorePoints > 0 ? String.valueOf(r.scorePoints) : "", r.dungeonClass,
                        r.classXp > 0 ? num(r.classXp) : "", r.durationMs > 0 ? String.valueOf(r.durationMs / 1000) : ""));
            }
        }
        return String.join("\n", rows) + "\n";
    }

    static String sessionsCsv(SessionLog.Store store) {
        List<String> rows = new ArrayList<>();
        rows.add("profile,block_start,play_seconds,stat,xp");
        for (var pe : store.profiles.entrySet()) {
            String profile = pe.getKey().equals(SessionLog.UNASSIGNED) ? "unassigned" : Progress.displayName(pe.getKey());
            for (var b : pe.getValue().buckets.entrySet()) {
                SessionLog.Bucket bk = b.getValue();
                if (bk == null) continue;
                String start = Instant.ofEpochMilli(b.getKey() * SessionLog.BUCKET).toString();
                if (bk.xp == null || bk.xp.isEmpty()) rows.add(csv(profile, start, bk.playSeconds, "", ""));
                else for (var x : bk.xp.entrySet()) rows.add(csv(profile, start, bk.playSeconds, x.getKey(), num(x.getValue() == null ? 0 : x.getValue())));
            }
        }
        return String.join("\n", rows) + "\n";
    }

    private static String num(double v) {
        if (!Double.isFinite(v)) return "";
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0);
    }

    /** RFC 4180 quoting; cells starting with = + - @ are prefixed so spreadsheets never execute them. */
    static String csv(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            String c = cells[i] == null ? "" : String.valueOf(cells[i]);
            if (!c.isEmpty() && "=+@".indexOf(c.charAt(0)) >= 0) c = "'" + c;
            if (c.startsWith("-") && !c.matches("-\\d+(\\.\\d+)?")) c = "'" + c;
            if (c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r")) c = "\"" + c.replace("\"", "\"\"") + "\"";
            sb.append(c);
        }
        return sb.toString();
    }
}
