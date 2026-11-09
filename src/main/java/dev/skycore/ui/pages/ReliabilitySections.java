package dev.skycore.ui.pages;

import dev.skycore.core.Backups;
import dev.skycore.core.Diagnostics;
import dev.skycore.core.Lang;
import dev.skycore.core.SessionGuard;
import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;
import dev.skycore.data.History;
import dev.skycore.data.ImportMerge;
import dev.skycore.data.Integrity;
import dev.skycore.data.KnownCollections;
import dev.skycore.data.ProfileData;
import dev.skycore.data.Progress;
import dev.skycore.data.StatsExport;
import dev.skycore.notify.Notifier;
import dev.skycore.tracking.DebugLog;
import dev.skycore.tracking.Health;
import dev.skycore.tracking.HypixelTracker;
import dev.skycore.tracking.parse.CustomPatterns;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.Widgets;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Settings sections for reliability: tracking health, profiles, backups & recovery, data integrity,
 * recognition patterns and the optional live debugger. Each is a collapsible section; heavy data
 * (backup validation, integrity checks) is cached and refreshed at most every few seconds, never per frame.
 */
final class ReliabilitySections {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Set<DebugLog.Filter> filters = EnumSet.noneOf(DebugLog.Filter.class);
    private volatile List<Backups.Info> infos = List.of();
    private volatile long infosAt;
    private volatile boolean refreshing;
    private int backupIdx, confirmProfileIdx;
    /** "restore" | "merge" | "delete_profile" | "repair" */
    String dialog;
    private String dialogProfile;
    private ImportMerge.Preview preview;
    private Path previewFile;
    private int assignIdx;
    private String assignTarget;
    private dev.skycore.data.ProfileAssign.Preview assignPreview;
    private boolean unassignedCached;
    private long unassignedAt;

    private boolean hasUnassigned() {
        if (System.currentTimeMillis() - unassignedAt > 2000) { unassignedCached = !Progress.isSample() && dev.skycore.data.ProfileAssign.hasUnassigned(); unassignedAt = System.currentTimeMillis(); }
        return unassignedCached;
    }

    /** Listing + validation (reads and hashes files) runs on the storage thread; the UI shows the last result. */
    private List<Backups.Info> infos() {
        if (!refreshing && System.currentTimeMillis() - infosAt > 3000) {
            refreshing = true;
            Storage.async(() -> {
                try { infos = Backups.infos(); } finally { infosAt = System.currentTimeMillis(); refreshing = false; }
            });
        }
        return infos;
    }

    private static String when(long t) { return t <= 0 ? Lang.t("settings.never") : Widgets.ago(t); }

    private static float btnW(Gfx g, String label) { return g.width(label, 1f, Weight.MEDIUM) + 30; }

    // ------------------------------------------------------------------ health
    private static final Health.Source[] SHOWN = {Health.Source.CHAT, Health.Source.ACTION_BAR, Health.Source.SIDEBAR, Health.Source.TAB,
            Health.Source.MENU, Health.Source.PROFILE, Health.Source.DUNGEON, Health.Source.SLAYER, Health.Source.PERSISTENCE};

    static int statusColor(Theme t, Health.Status s) {
        return switch (s) {
            case OPERATIONAL -> t.success();
            case ERROR -> t.danger();
            case WAITING -> t.textDim();
            default -> t.textMute();
        };
    }

    /** Compact health overview (inside the data tracking section). */
    float health(Ui ui, Ui.Section s, float cy) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Health.Status overall = Health.overall();
        int errors = Health.errorCount();
        ui.caption(Lang.t("health.title"), s.x(), cy);
        String summary = errors > 0 ? Lang.t("health.summary_errors", errors) : Lang.t("health.summary." + overall.name().toLowerCase());
        g.textRight(summary, s.x() + s.w(), cy, errors > 0 ? t.danger() : statusColor(t, overall), 0.85f, Weight.MEDIUM);
        cy += 14;
        long now = System.currentTimeMillis();
        int cols = s.w() >= 460 ? 2 : 1;
        float colW = (s.w() - (cols - 1) * 16) / cols;
        for (int i = 0; i < SHOWN.length; i++) {
            Health.Source src = SHOWN[i];
            Health.Stats st = Health.stats(src);
            Health.Status status = Health.statusOf(st, now);
            float x = s.x() + (i % cols) * (colW + 16), y = cy + (i / cols) * 20;
            Rect row = new Rect(x, y, colW, 18);
            int c = statusColor(t, status);
            if (status == Health.Status.OPERATIONAL || status == Health.Status.ERROR) g.circle(x + 3, y + 7, 3, c);
            else g.ringOutline(x + 3, y + 7, 3, 1.1f, c);
            g.text(Lang.t("health.src." + src.name().toLowerCase()), x + 12, y + 3, t.text(), 0.9f, Weight.MEDIUM);
            String right = Lang.t(status.key()) + (st.lastSuccess > 0 && status != Health.Status.ERROR ? " · " + Widgets.ago(st.lastSuccess) : "");
            g.textRight(g.ellipsize(right, colW * 0.55f / 0.85f, 1f), x + colW, y + 3.5f, c, 0.85f, Weight.REGULAR);
            if (ui.hover(row)) ui.tooltip(healthTip(src, st, status));
        }
        cy += ((SHOWN.length + cols - 1) / cols) * 20 + 4;
        // events still waiting for a classification, recent recognition problems, diagnostics
        int unresolved = Progress.unresolvedCount();
        g.text(Lang.t("health.unresolved"), s.x(), cy + 3, t.textDim(), 0.9f);
        g.textRight(unresolved == 0 ? Lang.t("health.unresolved_none") : Lang.t("health.unresolved_n", unresolved), s.x() + s.w(), cy + 3,
                unresolved == 0 ? t.text() : t.warning(), 0.9f, Weight.REGULAR);
        if (ui.hover(new Rect(s.x(), cy, s.w(), 16))) ui.tooltip(Lang.t("health.unresolved_tip"));
        cy += 18;
        List<DebugLog.Problem> problems = DebugLog.problems();
        g.text(Lang.t("health.problems"), s.x(), cy + 3, t.textDim(), 0.9f);
        g.textRight(problems.isEmpty() ? Lang.t("health.problems_none") : Lang.t("health.problems_n", problems.size()), s.x() + s.w(), cy + 3,
                problems.isEmpty() ? t.text() : t.warning(), 0.9f, Weight.REGULAR);
        cy += 16;
        for (int i = 0; i < Math.min(4, problems.size()); i++) {
            DebugLog.Problem pr = problems.get(i);
            g.text(g.ellipsize(Widgets.ago(pr.t()) + " · " + Lang.t("debug.reason." + pr.reason()) + (pr.detail() == null || pr.detail().isEmpty() ? "" : " · " + problemDetail(pr.detail())), s.w() / 0.85f, 1f),
                    s.x() + 10, cy + 2, t.textMute(), 0.85f);
            cy += 12;
        }
        String le = Lang.t("debug.export");
        if (ui.button("dt#export", new Rect(s.x(), cy + 4, btnW(g, le), 22), le, Icon.EXPORT, Ui.Style.GHOST)) {
            Path f = Diagnostics.export();
            Notifier.push(null, Lang.t("debug.exported"), Lang.t("debug.exported_body", "diagnostics/" + f.getFileName()), Icon.EXPORT, t.success());
        }
        cy += 32;
        return cy;
    }

    /** "evidence.no_tier,evidence.conflict_tier" / "slayer:enderman" / rule ids → readable text. */
    private static String problemDetail(String d) {
        List<String> out = new ArrayList<>();
        for (String part : d.split(",")) {
            String x = part.trim();
            if (Lang.exists(x)) out.add(Lang.t(x));
            else if (x.startsWith("slayer:")) out.add(dev.skycore.data.Activity.slayerLabel(x.substring(7)));
            else if (x.equals("dungeon")) out.add(Lang.t("cat.dungeons"));
            else out.add(x);
        }
        return String.join(", ", out);
    }

    private static String healthTip(Health.Source src, Health.Stats st, Health.Status status) {
        StringBuilder sb = new StringBuilder();
        sb.append(Lang.t("health.src." + src.name().toLowerCase())).append(": ").append(Lang.t(status.key())).append('\n');
        sb.append(Lang.t(status.key() + ".desc"));
        if (st.note != null && status != Health.Status.OPERATIONAL) sb.append('\n').append(Lang.t(st.note));
        sb.append('\n').append(Lang.t("health.last_success", when(st.lastSuccess)));
        sb.append('\n').append(Lang.t("health.last_activity", when(st.lastActivity)));
        sb.append('\n').append(Lang.t("health.counts", st.successes, st.failures));
        if (st.lastError > 0) sb.append('\n').append(Lang.t("health.last_error", Widgets.ago(st.lastError), st.lastErrorText));
        return sb.toString();
    }

    // ------------------------------------------------------------------ coverage of other areas
    private static final String[][] OTHER = {
            {"garden_level", "coverage.garden", "coverage.howto.garden"},
            {"rift_motes", "coverage.rift_motes", "coverage.howto.rift"},
            {"rift_enigma_souls", "coverage.rift_souls", "coverage.howto.rift_souls"},
            {"museum_donated", "coverage.museum", "coverage.howto.museum"},
            {"community_fame", "coverage.fame", "coverage.howto.fame"},
            {"bits", "coverage.bits", "coverage.howto.bits"},
    };

    float otherCoverage(Ui ui, Ui.Section s, float cy) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ProfileData d = Progress.view();
        for (String[] o : OTHER) {
            ProfileData.ValueRecord v = d.value(o[0]);
            Rect row = new Rect(s.x(), cy, s.w(), 18);
            g.text(Lang.t(o[1]), s.x(), cy + 3, t.text(), 1f, Weight.MEDIUM);
            String val = v == null ? Lang.t("coverage.not_seen") : formatValue(o[0], v, d);
            g.textRight(val, s.x() + s.w(), cy + 3, v == null ? t.textMute() : t.text(), 0.9f, Weight.REGULAR);
            if (ui.hover(row)) ui.tooltip(v == null ? Lang.t(o[2]) : Lang.t("status.source", Lang.t("source." + v.source)) + "\n" + Lang.t("status.verified_at", Widgets.ago(v.updated)));
            cy += 18;
        }
        // kuudra: completions per tier from menus, completions counted by SKYCORE from chat
        int tiers = 0;
        double counted = 0;
        for (var e : d.values.entrySet()) {
            if (e.getKey().startsWith("kuudra_completions_")) tiers++;
            else if (e.getKey().startsWith("kuudra_")) counted += e.getValue().value;
        }
        g.text(Lang.t("coverage.kuudra"), s.x(), cy + 3, t.text(), 1f, Weight.MEDIUM);
        String kv = tiers > 0 ? Lang.t("coverage.kuudra_tiers", tiers) : counted > 0 ? Lang.t("coverage.kuudra_counted", Lang.num(counted)) : Lang.t("coverage.not_seen");
        g.textRight(kv, s.x() + s.w(), cy + 3, tiers + counted > 0 ? t.text() : t.textMute(), 0.9f, Weight.REGULAR);
        if (ui.hover(new Rect(s.x(), cy, s.w(), 18))) ui.tooltip(Lang.t("coverage.howto.kuudra"));
        cy += 18;
        // collections against the reference list, bestiary families
        List<String> missing = KnownCollections.missing(d.collections);
        int seen = KnownCollections.ALL.size() - missing.size();
        g.text(Lang.t("coverage.collections"), s.x(), cy + 3, t.text(), 1f, Weight.MEDIUM);
        g.textRight(Lang.t("settings.known_of", seen, KnownCollections.ALL.size()), s.x() + s.w(), cy + 3, missing.isEmpty() ? t.success() : t.textDim(), 0.9f, Weight.REGULAR);
        if (ui.hover(new Rect(s.x(), cy, s.w(), 18)))
            ui.tooltip(Lang.t("coverage.collections_tip") + (missing.isEmpty() ? "" : "\n" + Lang.t("settings.missing", String.join(", ", missing.subList(0, Math.min(14, missing.size()))) + (missing.size() > 14 ? " …" : ""))));
        cy += 18;
        long families = d.values.keySet().stream().filter(k -> k.startsWith("bestiary_family:")).count();
        g.text(Lang.t("coverage.bestiary_families"), s.x(), cy + 3, t.text(), 1f, Weight.MEDIUM);
        g.textRight(families > 0 ? Lang.num(families) : Lang.t("coverage.not_seen"), s.x() + s.w(), cy + 3, families > 0 ? t.text() : t.textMute(), 0.9f, Weight.REGULAR);
        if (ui.hover(new Rect(s.x(), cy, s.w(), 18))) ui.tooltip(Lang.t("coverage.howto.bestiary"));
        cy += 18;
        g.text(g.ellipsize(Lang.t("coverage.unavailable_note"), s.w() / 0.85f, 1f), s.x(), cy + 4, t.textMute(), 0.85f);
        return cy + 18;
    }

    private static String formatValue(String key, ProfileData.ValueRecord v, ProfileData d) {
        if (key.equals("museum_donated")) {
            ProfileData.ValueRecord tot = d.value("museum_total");
            return tot == null ? Lang.num(v.value) : Lang.t("settings.known_of", Lang.num(v.value), Lang.num(tot.value));
        }
        if (key.equals("rift_enigma_souls")) {
            ProfileData.ValueRecord tot = d.value("rift_enigma_total");
            return tot == null ? Lang.num(v.value) : Lang.t("settings.known_of", Lang.num(v.value), Lang.num(tot.value));
        }
        return Lang.num(v.value);
    }

    // ------------------------------------------------------------------ profiles
    float profiles(Ui ui, Rect area, float y) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("set.profiles", area.x(), y, area.w(), Lang.t("profiles.title"), Lang.t("profiles.desc"), Icon.PROFILE, false);
        float cy = s.contentY();
        if (s.visible()) {
            boolean ok = Progress.isIdentified();
            int c = ok ? t.success() : t.warning();
            g.circle(s.x() + 4, cy + 7, 3.5f, c);
            g.text(Lang.t("profiles.active", label(Progress.profileKey())), s.x() + 14, cy + 2, t.text(), 1f, Weight.SEMIBOLD);
            String st = ok ? Lang.t("profiles.identified." + (Progress.identitySource() == null ? "chat" : Progress.identitySource())) : Lang.t("profiles.unknown");
            g.textRight(st, s.x() + s.w(), cy + 3, c, 0.9f, Weight.MEDIUM);
            cy += 18;
            for (String l : g.wrap(Lang.t(ok ? "profiles.identified_desc" : "profiles.unknown_desc"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textDim(), 0.85f); cy += 10; }
            if (HypixelTracker.pendingCount() > 0) {
                g.text(Lang.t("profiles.pending", HypixelTracker.pendingCount()), s.x(), cy + 2, t.warning(), 0.85f, Weight.MEDIUM);
                cy += 12;
            }
            cy += 6;
            List<String> keys = Progress.profileKeys();
            // manual confirmation (only when the game has not identified the profile)
            if (!ok && !keys.isEmpty() && dev.skycore.tracking.HypixelTracker.onSkyBlock()) {
                String[] names = keys.stream().map(ReliabilitySections::label).toArray(String[]::new);
                confirmProfileIdx = Math.min(confirmProfileIdx, names.length - 1);
                g.text(Lang.t("profiles.confirm_label"), s.x(), cy + 6, t.text(), 1f, Weight.MEDIUM);
                String lb = Lang.t("profiles.confirm");
                float bw = btnW(g, lb);
                Rect dd = new Rect(s.x() + s.w() - bw - 8 - 160, cy, 160, 22);
                confirmProfileIdx = ui.dropdown("prof#pick", dd, names, confirmProfileIdx);
                if (ui.button("prof#confirm", new Rect(dd.right() + 8, cy, bw, 22), lb, Icon.CHECK, Ui.Style.PRIMARY)) {
                    HypixelTracker.confirmProfile(keys.get(confirmProfileIdx));
                    Notifier.push(null, Lang.t("profiles.confirmed"), Progress.profileName(), Icon.PROFILE, t.success());
                }
                if (ui.hover(new Rect(dd.right() + 8, cy, bw, 22))) ui.tooltip(Lang.t("profiles.confirm_tip"));
                cy += 32;
            }
            ui.caption(Lang.t("profiles.stored"), s.x(), cy);
            cy += 14;
            String owner = currentOwner();
            for (String key : keys) {
                ProfileData d = Progress.stored(key);
                boolean active = key.equals(Progress.profileKey());
                Rect row = new Rect(s.x(), cy, s.w(), 22);
                g.text(g.ellipsize(label(key), s.w() * 0.3f, 1f), s.x(), cy + 6, active ? t.primary() : t.text(), 1f, active ? Weight.SEMIBOLD : Weight.MEDIUM);
                // which account a profile belongs to can only be said while a player is connected
                String acct = d.owner == null ? Lang.t("profiles.owner_unknown") : owner == null ? null : d.owner.equals(owner) ? Lang.t("profiles.owner_this") : Lang.t("profiles.owner_other");
                String info = (acct == null ? "" : acct + " · ") + Lang.t("profiles.runs", d.runs.size()) + " · " + Lang.t("profiles.seen", when(d.lastSeen));
                g.text(g.ellipsize(info, (s.w() * 0.6f - 30) / 0.85f, 1f), s.x() + s.w() * 0.32f, cy + 6.5f, t.textDim(), 0.85f);
                if (!active && ui.iconButton("prof#del" + key, new Rect(s.x() + s.w() - 22, cy + 1, 20, 20), Icon.TRASH, Lang.t("profiles.delete"))) {
                    dialog = "delete_profile";
                    dialogProfile = key;
                    ui.resetModalAnim();
                }
                if (ui.hover(row.withW(s.w() - 26))) {
                    dev.skycore.data.SessionLog.Data ses = dev.skycore.data.SessionLog.of(key);
                    long quests = d.slayerQuests.stream().filter(q -> "complete".equals(q.status)).count();
                    ui.tooltip(Lang.t("profiles.key_tip", key, d.stats.size(), d.runs.size()) + "\n"
                            + Lang.t("profiles.stats_tip", Lang.duration(ses.totalPlaySeconds * 1000L), ses.spans.size(), quests));
                }
                cy += 24;
            }
            if (keys.isEmpty()) { g.text(Lang.t("profiles.none"), s.x(), cy + 2, t.textMute(), 0.9f); cy += 16; }
            cy += 4;
            // unassigned historical data → a profile the user chooses (never automatic)
            if (hasUnassigned()) {
                ui.separator(s.x(), cy, s.w());
                cy += 10;
                ui.caption(Lang.t("assign.title"), s.x(), cy);
                cy += 14;
                for (String l : g.wrap(Lang.t("assign.desc"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textDim(), 0.85f); cy += 10; }
                cy += 4;
                List<String> targets = new ArrayList<>();
                for (String k : keys) if (!k.equals(dev.skycore.data.ProfileAssign.SOURCE)) targets.add(k);
                if (targets.isEmpty()) {
                    g.text(Lang.t("assign.no_target"), s.x(), cy + 2, t.textMute(), 0.9f);
                    cy += 16;
                } else {
                    String[] names = targets.stream().map(ReliabilitySections::label).toArray(String[]::new);
                    assignIdx = Math.min(assignIdx, names.length - 1);
                    g.text(Lang.t("assign.to"), s.x(), cy + 6, t.text(), 1f, Weight.MEDIUM);
                    String lb = Lang.t("assign.preview");
                    float bw = btnW(g, lb);
                    Rect dd = new Rect(s.x() + s.w() - bw - 8 - 170, cy, 170, 22);
                    assignIdx = ui.dropdown("assign#to", dd, names, assignIdx);
                    if (ui.button("assign#preview", new Rect(dd.right() + 8, cy, bw, 22), lb, Icon.EYE, Ui.Style.SECONDARY)) {
                        assignTarget = targets.get(assignIdx);
                        assignPreview = dev.skycore.data.ProfileAssign.preview(assignTarget);
                        dialog = "assign";
                        ui.resetModalAnim();
                    }
                    cy += 30;
                }
            }
        }
        return ui.endSection(s, cy);
    }

    /** Display name of a stored profile; the pre-identification key "default" is shown as unassigned data. */
    static String label(String key) {
        return "default".equals(key) ? Lang.t("profiles.default") : Progress.displayName(key);
    }

    private static String currentOwner() {
        var p = net.minecraft.client.Minecraft.getInstance().player;
        return p == null ? null : p.getUUID().toString();
    }

    // ------------------------------------------------------------------ backups & recovery
    float backups(Ui ui, Rect area, float y, SettingsPage page) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("set.backup", area.x(), y, area.w(), Lang.t("backup.title"), Lang.t("backup.desc"), Icon.SAVE, false);
        float cy = s.contentY();
        if (s.visible()) {
            List<Backups.Info> list = infos();
            Backups.Info latest = null;
            int invalid = 0;
            for (Backups.Info i : list) { if (i.validation().valid()) { if (latest == null) latest = i; } else invalid++; }
            // status lines
            g.text(Lang.t("backup.latest"), s.x(), cy + 3, t.textDim(), 0.9f);
            g.textRight(latest == null ? Lang.t("backup.none") : DATE.format(Instant.ofEpochMilli(latest.created())) + " · " + Widgets.ago(latest.created()),
                    s.x() + s.w(), cy + 3, latest == null ? t.warning() : t.text(), 0.9f, Weight.REGULAR);
            cy += 18;
            g.text(Lang.t("backup.count"), s.x(), cy + 3, t.textDim(), 0.9f);
            g.textRight(invalid > 0 ? Lang.t("backup.count_invalid", list.size(), invalid) : Lang.t("backup.count_n", list.size()),
                    s.x() + s.w(), cy + 3, invalid > 0 ? t.warning() : t.text(), 0.9f, Weight.REGULAR);
            cy += 18;
            // database status
            g.text(Lang.t("backup.db"), s.x(), cy + 3, t.textDim(), 0.9f);
            String dbState = dbSummary();
            boolean dbOk = dbState == null;
            g.textRight(dbOk ? Lang.t("backup.db_ok") : dbState, s.x() + s.w(), cy + 3, dbOk ? t.success() : t.warning(), 0.9f, Weight.REGULAR);
            if (ui.hover(new Rect(s.x(), cy, s.w(), 16))) ui.tooltip(dbTip());
            cy += 18;
            // recovery
            History.SessionRecord inter = SessionGuard.lastInterrupted();
            g.text(Lang.t("backup.last_session"), s.x(), cy + 3, t.textDim(), 0.9f);
            String rs = inter == null ? Lang.t("backup.clean_shutdown") : inter.end > 0 ? Lang.t("backup.interrupted", DATE.format(Instant.ofEpochMilli(inter.end))) : Lang.t("backup.interrupted_unknown");
            g.textRight(rs, s.x() + s.w(), cy + 3, inter == null ? t.text() : t.warning(), 0.9f, Weight.REGULAR);
            if (inter != null && ui.hover(new Rect(s.x(), cy, s.w(), 16))) ui.tooltip(Lang.t("backup.interrupted_tip"));
            cy += 24;

            // actions
            String l1 = Lang.t("backup.now"), l2 = Lang.t("backup.export_stats");
            float w1 = btnW(g, l1), w2 = btnW(g, l2);
            if (ui.button("bk#now", new Rect(s.x(), cy, w1, 22), l1, Icon.SAVE, Ui.Style.SECONDARY)) {
                Path f = Backups.exportAsync("manual", () -> infosAt = 0);
                if (f != null) Notifier.push(null, Lang.t("backup.started"), Lang.t("settings.exported_body", f.getFileName()), Icon.SAVE, t.success());
            }
            if (ui.hover(new Rect(s.x(), cy, w1, 22))) ui.tooltip(Lang.t("backup.now_tip"));
            if (ui.button("bk#stats", new Rect(s.x() + w1 + 8, cy, w2, 22), l2, Icon.EXPORT, Ui.Style.SECONDARY)) exportStats(t);
            if (ui.hover(new Rect(s.x() + w1 + 8, cy, w2, 22))) ui.tooltip(Lang.t("backup.export_stats_tip"));
            cy += 32;

            // restore / import
            ui.caption(Lang.t("backup.restore_title"), s.x(), cy);
            cy += 14;
            if (list.isEmpty()) { g.text(Lang.t("settings.no_backups"), s.x(), cy + 4, t.textMute(), 0.9f); cy += 20; }
            else {
                backupIdx = Math.min(backupIdx, list.size() - 1);
                String[] names = new String[list.size()];
                for (int i = 0; i < names.length; i++) {
                    Backups.Info in = list.get(i);
                    names[i] = (in.validation().valid() ? "" : Lang.t("backup.damaged_prefix") + " ") + in.name();
                }
                String lp = Lang.t("backup.preview");
                float wp = btnW(g, lp);
                Rect dd = new Rect(s.x(), cy, Math.max(140, Math.min(320, s.w() - wp - 8)), 22);
                backupIdx = ui.dropdown("bk#list", dd, names, backupIdx);
                Backups.Info sel = list.get(backupIdx);
                if (ui.button("bk#preview", new Rect(dd.right() + 8, cy, wp, 22), lp, Icon.EYE, Ui.Style.SECONDARY, sel.validation().valid())) {
                    previewFile = sel.path();
                    preview = Backups.preview(previewFile);
                    dialog = "import";
                    ui.resetModalAnim();
                }
                cy += 28;
                Backups.Validation v = sel.validation();
                String vs = v.valid() ? Lang.t(v.checksummed() ? "backup.valid" : "backup.valid_legacy", String.join(", ", v.sections()))
                        : Lang.t("backup.invalid", v.problem());
                g.icon(v.valid() ? Icon.CHECK : Icon.WARNING, s.x(), cy, 10, v.valid() ? t.success() : t.danger());
                g.text(g.ellipsize(vs, (s.w() - 16) / 0.85f, 1f), s.x() + 15, cy + 1, v.valid() ? t.textDim() : t.danger(), 0.85f);
                cy += 16;
            }
            cy += 4;
            for (String l : g.wrap(Lang.t("backup.policy"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textMute(), 0.85f); cy += 10; }
            cy += 6;
            cy = page.keepSlider(ui, s, cy);
        }
        return ui.endSection(s, cy);
    }

    private static String dbSummary() {
        for (String f : new String[]{"progress.json", "history.json", "sessions.json", "goals.json", "hud.json", "config.json"}) {
            Storage.FileStatus st = Storage.status().get(f);
            if (st == null) continue;
            if (st.error != null) return Lang.t("backup.db_write_error", f);
            if (!st.state.equals("ok") && !st.state.equals("new")) return Lang.t("backup.db_state." + st.state, f);
        }
        return null;
    }

    private static String dbTip() {
        StringBuilder sb = new StringBuilder(Lang.t("backup.db_tip"));
        Storage.status().forEach((f, st) -> {
            sb.append('\n').append(f).append(": ").append(Lang.t("backup.db_file." + st.state));
            if (st.lastWrite > 0) sb.append(" · ").append(Lang.t("backup.saved", Widgets.ago(st.lastWrite)));
            if (st.recoveredFrom != null) sb.append(" · ").append(st.recoveredFrom);
        });
        return sb.toString();
    }

    private void exportStats(Theme t) {
        var contents = StatsExport.build();
        String base = "statistics-" + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path dir = Storage.dir().resolve("exports");
        Storage.async(() -> {
            try { StatsExport.write(dir, base, contents); }
            catch (Exception e) { Skycore.LOG.error("Statistics export failed", e); Health.failure(Health.Source.PERSISTENCE, "statistics export"); }
        });
        Notifier.push(null, Lang.t("backup.stats_done"), Lang.t("backup.stats_done_body", "exports/" + base), Icon.EXPORT, t.success());
    }

    // ------------------------------------------------------------------ integrity
    float integrity(Ui ui, Rect area, float y) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("set.integrity", area.x(), y, area.w(), Lang.t("integrity.title"), Lang.t("integrity.desc"), Icon.CHECK, false);
        float cy = s.contentY();
        if (s.visible()) {
            Integrity.Report r = Integrity.last();
            if (r == null) { g.text(Lang.t("integrity.not_run"), s.x(), cy + 3, t.textMute(), 0.9f); cy += 20; }
            else {
                long e = r.count(Integrity.Severity.ERROR), w = r.count(Integrity.Severity.WARNING), i = r.count(Integrity.Severity.INFO);
                int c = e > 0 ? t.danger() : w > 0 ? t.warning() : t.success();
                g.circle(s.x() + 4, cy + 7, 3.5f, c);
                g.text(r.clean() ? Lang.t("integrity.clean") : Lang.t("integrity.found", e, w, i), s.x() + 14, cy + 2, t.text(), 1f, Weight.SEMIBOLD);
                g.textRight(Lang.t("integrity.checked", Widgets.ago(r.t()), r.profiles(), r.runs(), r.events()), s.x() + s.w(), cy + 3, t.textDim(), 0.85f, Weight.REGULAR);
                cy += 20;
                int shown = 0;
                for (Integrity.Issue is : r.issues()) {
                    if (shown++ >= 7) { g.text(Lang.t("integrity.more", r.issues().size() - 7), s.x() + 12, cy + 2, t.textMute(), 0.85f); cy += 14; break; }
                    int ic = is.severity() == Integrity.Severity.ERROR ? t.danger() : is.severity() == Integrity.Severity.WARNING ? t.warning() : t.textDim();
                    g.circle(s.x() + 4, cy + 6, 2.5f, ic);
                    String head = Lang.t("integrity.check." + is.check()) + (is.target() == null ? "" : " · " + targetName(is.target())) + (is.profile() == null ? "" : " · " + Progress.displayName(is.profile()));
                    g.text(g.ellipsize(head, s.w() * 0.42f / 0.9f, 1f), s.x() + 12, cy + 2, t.text(), 0.9f, Weight.MEDIUM);
                    g.text(g.ellipsize(Lang.t("integrity.d." + is.detail(), is.args()), (s.w() * 0.56f) / 0.85f, 1f), s.x() + s.w() * 0.44f, cy + 2.5f, t.textDim(), 0.85f);
                    if (ui.hover(new Rect(s.x(), cy, s.w(), 14)))
                        ui.tooltip(Lang.t("integrity.d." + is.detail(), is.args()) + "\n" + Lang.t(is.repairable() ? "integrity.repairable" : "integrity.not_repairable"));
                    cy += 15;
                }
                cy += 4;
            }
            String l1 = Lang.t("integrity.run"), l2 = Lang.t("integrity.repair");
            float w1 = btnW(g, l1), w2 = btnW(g, l2);
            if (ui.button("int#run", new Rect(s.x(), cy, w1, 22), l1, Icon.RESET, Ui.Style.SECONDARY)) Integrity.checkNow();
            boolean canRepair = r != null && r.repairable() > 0;
            if (ui.button("int#repair", new Rect(s.x() + w1 + 8, cy, w2, 22), l2, Icon.CALIBRATE, Ui.Style.SECONDARY, canRepair)) { dialog = "repair"; ui.resetModalAnim(); }
            if (ui.hover(new Rect(s.x() + w1 + 8, cy, w2, 22))) ui.tooltip(Lang.t("integrity.repair_tip"));
            var repairs = History.data().repairs;
            String rl = repairs.isEmpty() ? Lang.t("integrity.no_repairs") : Lang.t("integrity.repairs", repairs.size(), Widgets.ago(repairs.get(repairs.size() - 1).t));
            g.textRight(rl, s.x() + s.w(), cy + 7, t.textMute(), 0.85f, Weight.REGULAR);
            cy += 30;
            for (String l : g.wrap(Lang.t("integrity.policy"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textMute(), 0.85f); cy += 10; }
            cy += 4;
        }
        return ui.endSection(s, cy);
    }

    private static String targetName(String target) {
        if (Lang.exists("stat." + target)) return Lang.t("stat." + target);
        if (Lang.exists("integrity.target." + target)) return Lang.t("integrity.target." + target);
        return target;
    }

    // ------------------------------------------------------------------ patterns
    float patterns(Ui ui, Rect area, float y) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("set.patterns", area.x(), y, area.w(), Lang.t("patterns.title"), Lang.t("patterns.desc"), Icon.LIST, false);
        float cy = s.contentY();
        if (s.visible()) {
            var parser = HypixelTracker.parser();
            CustomPatterns.Result pr = HypixelTracker.customPatterns();
            long builtIn = parser.builtInCount();
            long hits = parser.hits().values().stream().mapToLong(Long::longValue).sum();
            row(ui, s, cy, Lang.t("patterns.builtin"), Lang.t("patterns.builtin_n", builtIn, Lang.num(hits)), t.text()); cy += 18;
            int errs = pr.errors().size();
            row(ui, s, cy, Lang.t("patterns.user"), errs == 0 ? Lang.t("patterns.user_n", pr.loaded()) : Lang.t("patterns.user_err", pr.loaded(), errs), errs == 0 ? t.text() : t.warning()); cy += 18;
            row(ui, s, cy, Lang.t("patterns.reloaded"), HypixelTracker.patternsLoadedAt() > 0 ? Widgets.ago(HypixelTracker.patternsLoadedAt()) : Lang.t("settings.never"), t.text()); cy += 18;
            if (!pr.jsonValid()) {
                g.icon(Icon.WARNING, s.x(), cy, 10, t.danger());
                g.text(g.ellipsize(Lang.t("patterns.json_invalid"), (s.w() - 16) / 0.85f, 1f), s.x() + 15, cy + 1, t.danger(), 0.85f);
                cy += 16;
            }
            // per-entry status
            for (CustomPatterns.EntryStatus e : pr.entries().subList(0, Math.min(8, pr.entries().size()))) {
                int c = e.active() ? t.success() : t.danger();
                g.circle(s.x() + 4, cy + 6, 2.5f, c);
                String head = e.id() + (e.kind() == null ? "" : " · " + e.kind()) + (e.language() == null ? "" : " · " + e.language());
                g.text(g.ellipsize(head, s.w() * 0.45f / 0.9f, 1f), s.x() + 12, cy + 2, t.text(), 0.9f, Weight.MEDIUM);
                long h = parser.hits().getOrDefault("custom:" + e.id(), 0L);
                String right = e.active() ? (parser.disabledRules().containsKey("custom:" + e.id()) ? Lang.t("patterns.disabled_runtime") : Lang.t("patterns.matches", h)) : e.error();
                g.text(g.ellipsize(right, s.w() * 0.5f / 0.85f, 1f), s.x() + s.w() * 0.48f, cy + 2.5f, e.active() ? t.textDim() : t.danger(), 0.85f);
                if (!e.active() && ui.hover(new Rect(s.x(), cy, s.w(), 14))) ui.tooltip(e.error());
                cy += 15;
            }
            if (pr.entries().isEmpty() && pr.errors().isEmpty()) { g.text(Lang.t("patterns.none"), s.x(), cy + 2, t.textMute(), 0.85f); cy += 14; }
            cy += 6;
            String l1 = Lang.t("settings.reload_patterns");
            if (ui.button("pat#reload", new Rect(s.x(), cy, btnW(g, l1), 22), l1, Icon.RESET, Ui.Style.SECONDARY)) {
                HypixelTracker.loadPatterns();
                CustomPatterns.Result r = HypixelTracker.customPatterns();
                Notifier.push(null, Lang.t("patterns.reload_done"), r.errors().isEmpty() ? Lang.t("patterns.user_n", r.loaded()) : Lang.t("patterns.user_err", r.loaded(), r.errors().size()),
                        Icon.LIST, r.errors().isEmpty() ? t.success() : t.warning());
            }
            cy += 30;
            for (String l : g.wrap(Lang.t("patterns.help"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textMute(), 0.85f); cy += 10; }
            cy += 4;
        }
        return ui.endSection(s, cy);
    }

    private static void row(Ui ui, Ui.Section s, float cy, String label, String value, int color) {
        ui.g.text(label, s.x(), cy + 3, ui.t.textDim(), 0.9f);
        ui.g.textRight(value, s.x() + s.w(), cy + 3, color, 0.9f, Weight.REGULAR);
    }

    // ------------------------------------------------------------------ live debugger
    float debugger(Ui ui, Rect area, float y) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("set.debug", area.x(), y, area.w(), Lang.t("debug.title"), Lang.t("debug.desc"), Icon.INFO, false);
        float cy = s.contentY();
        if (s.visible()) {
            boolean on = ui.toggleRow("dbg#on", new Rect(s.x() - 8, cy, s.w() + 8, 32), Lang.t("debug.enable"), Lang.t("debug.enable_desc"), DebugLog.enabled(), Lang.t("debug.privacy"));
            if (on != DebugLog.enabled()) DebugLog.setEnabled(on);
            cy += 38;
            if (on) {
                // filter chips (none selected = everything)
                float fx = s.x();
                for (DebugLog.Filter f : DebugLog.Filter.values()) {
                    String label = Lang.t(f.key());
                    float w = g.width(label, 0.85f, Weight.MEDIUM) + 14;
                    if (fx + w > s.x() + s.w()) { fx = s.x(); cy += 18; }
                    boolean sel = filters.contains(f);
                    if (Widgets.badgeButton(ui, "dbg#f" + f, fx, cy, label, sel ? t.primary() : t.textMute())) { if (sel) filters.remove(f); else filters.add(f); }
                    fx += w + 4;
                }
                cy += 22;
                List<DebugLog.Entry> entries = DebugLog.recent(filters, 10);
                if (entries.isEmpty()) { g.text(Lang.t("debug.empty"), s.x(), cy + 2, t.textMute(), 0.85f); cy += 16; }
                for (DebugLog.Entry e : entries) {
                    Rect row = new Rect(s.x(), cy, s.w(), 15);
                    g.text(TIME.format(Instant.ofEpochMilli(e.t)), s.x(), cy + 2, t.textMute(), 0.85f);
                    g.icon(e.accepted ? Icon.CHECK : Icon.CLOSE, s.x() + 48, cy + 1, 9, e.accepted ? t.success() : t.textDim());
                    String head = e.type + (e.rule == null ? "" : " · " + e.rule) + (e.confidence > 0 ? " · " + Math.round(e.confidence * 100) + "%" : "");
                    g.text(g.ellipsize(head, s.w() * 0.42f / 0.85f, 1f), s.x() + 62, cy + 2, t.text(), 0.85f, Weight.MEDIUM);
                    String tail = Lang.t("debug.reason." + e.reason) + (e.values.isEmpty() ? "" : " · " + compact(e));
                    g.text(g.ellipsize(tail, (s.w() * 0.58f - 66) / 0.85f, 1f), s.x() + s.w() * 0.42f + 66, cy + 2, e.accepted ? t.textDim() : t.textMute(), 0.85f);
                    if (ui.hover(row)) ui.tooltip(debugTip(e));
                    cy += 15;
                }
                cy += 4;
                g.text(Lang.t("debug.counts", DebugLog.size(), DebugLog.unmatchedCount(), DebugLog.playerChatCount()), s.x(), cy + 2, t.textMute(), 0.85f);
                cy += 16;
            }
            String l1 = Lang.t("debug.export"), l2 = Lang.t("debug.clear");
            float w1 = btnW(g, l1), w2 = btnW(g, l2);
            if (ui.button("dbg#export", new Rect(s.x(), cy, w1, 22), l1, Icon.EXPORT, Ui.Style.SECONDARY)) {
                Path f = Diagnostics.export();
                Notifier.push(null, Lang.t("debug.exported"), Lang.t("debug.exported_body", "diagnostics/" + f.getFileName()), Icon.EXPORT, t.success());
            }
            if (ui.hover(new Rect(s.x(), cy, w1, 22))) ui.tooltip(Lang.t("debug.export_tip"));
            if (ui.button("dbg#clear", new Rect(s.x() + w1 + 8, cy, w2, 22), l2, Icon.TRASH, Ui.Style.GHOST, DebugLog.size() > 0)) DebugLog.clear();
            cy += 30;
            for (String l : g.wrap(Lang.t("debug.privacy"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textMute(), 0.85f); cy += 10; }
            cy += 4;
        }
        return ui.endSection(s, cy);
    }

    private static String compact(DebugLog.Entry e) {
        List<String> parts = new ArrayList<>();
        e.values.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join(" ", parts);
    }

    private static String debugTip(DebugLog.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(Lang.t("debug.tip.type", e.type, Lang.t(e.filter.key())));
        if (e.rule != null) sb.append('\n').append(Lang.t("debug.tip.rule", e.rule, Math.round(e.confidence * 100)));
        if (!e.values.isEmpty()) sb.append('\n').append(Lang.t("debug.tip.values", compact(e)));
        sb.append('\n').append(Lang.t("debug.tip.profile", e.profile == null ? Lang.t("profiles.unknown") : e.profile));
        sb.append('\n').append(Lang.t("debug.tip.dedupe", e.dedupe));
        sb.append('\n').append(Lang.t(e.accepted ? "debug.tip.accepted" : "debug.tip.rejected", Lang.t("debug.reason." + e.reason)));
        sb.append('\n').append(Lang.t("debug.tip.persisted", e.persisted));
        return sb.toString();
    }

    // ------------------------------------------------------------------ dialogs
    void dialogs(Ui ui, SkycoreScreen screen) {
        if (dialog == null) return;
        Gfx g = ui.g;
        Theme t = ui.t;
        String d = dialog;
        if (d.equals("import")) { importDialog(ui, screen); return; }
        if (d.equals("assign")) { assignDialog(ui, screen); return; }
        ui.modal(screen.screenRect(), 360, 160, Lang.t("settings.confirm_title"), body -> {
            float ly = body.y();
            String text = d.equals("delete_profile") ? Lang.t("profiles.confirm_delete", label(dialogProfile)) : Lang.t("integrity.confirm_repair");
            for (String l : g.wrap(text, body.w(), 1f)) { g.text(l, body.x(), ly, t.textDim()); ly += 11; }
            Rect ok = new Rect(body.right() - 120, body.bottom() - 22, 120, 22);
            boolean del = d.equals("delete_profile");
            if (ui.button("rel#ok", ok, Lang.t(del ? "profiles.delete" : "integrity.repair"), del ? Icon.TRASH : Icon.CALIBRATE, del ? Ui.Style.DANGER : Ui.Style.PRIMARY)) {
                if (!Backups.safety(del ? "delete-profile" : "repair")) {
                    Notifier.push(null, Lang.t("backup.safety_failed"), Lang.t("backup.safety_failed_body"), Icon.WARNING, t.danger());
                } else if (del) {
                    boolean okDel = Progress.deleteProfile(dialogProfile);
                    if (okDel) History.repair("profile_deleted", dialogProfile, "profile", "stored", "deleted by user");
                } else {
                    int n = Integrity.repairNow();
                    Notifier.push(null, Lang.t("integrity.repaired"), Lang.t("integrity.repaired_body", n), Icon.CHECK, t.success());
                }
                infosAt = 0;
                dialog = null;
            }
            if (ui.button("rel#cancel", new Rect(ok.x() - 88, ok.y(), 80, 22), Lang.t("action.cancel"), null, Ui.Style.GHOST)) dialog = null;
        }, () -> dialog = null);
    }

    private void assignDialog(Ui ui, SkycoreScreen screen) {
        Gfx g = ui.g;
        Theme t = ui.t;
        dev.skycore.data.ProfileAssign.Preview p = assignPreview;
        ui.modal(screen.screenRect(), 420, 250, Lang.t("assign.title"), body -> {
            float ly = body.y();
            if (p == null || !p.possible()) {
                g.text(Lang.t(p == null ? "assign.problem.no_target" : p.problem()), body.x(), ly, t.danger());
            } else {
                g.text(Lang.t("assign.target", label(assignTarget)), body.x(), ly, t.text(), 1f, Weight.SEMIBOLD);
                ly += 16;
                String[] lines = {
                        Lang.t("assign.line.stats", p.stats(), p.statsKept()),
                        Lang.t("assign.line.runs", p.runs(), p.runsDuplicate()),
                        Lang.t("assign.line.quests", p.quests(), p.questsDuplicate()),
                        Lang.t("assign.line.values", p.values() + p.collections()),
                        Lang.t("assign.line.sessions", p.sessionBlocks(), p.sessionSpans(), Lang.duration(p.playSeconds() * 1000L)),
                        Lang.t("assign.line.events", p.historyEvents())};
                for (String l : lines) { g.text(l, body.x(), ly, t.textDim(), 0.9f); ly += 12; }
                ly += 4;
                for (String l : g.wrap(Lang.t("assign.rules"), body.w(), 0.85f)) { g.text(l, body.x(), ly, t.textMute(), 0.85f); ly += 10; }
            }
            Rect ok = new Rect(body.right() - 120, body.bottom() - 22, 120, 22);
            boolean can = p != null && p.possible() && !p.empty();
            if (ui.button("assign#ok", ok, Lang.t("assign.confirm"), Icon.CHECK, Ui.Style.PRIMARY, can)) {
                int n = -1;
                if (Backups.safety("assign")) n = dev.skycore.data.ProfileAssign.applyNow(assignTarget);
                else Notifier.push(null, Lang.t("backup.safety_failed"), Lang.t("backup.safety_failed_body"), Icon.WARNING, t.danger());
                if (n >= 0) Notifier.push(null, Lang.t("assign.done"), Lang.t("assign.done_body", n, label(assignTarget)), Icon.CHECK, t.success());
                else Notifier.push(null, Lang.t("assign.failed"), Lang.t("assign.failed_body"), Icon.WARNING, t.danger());
                unassignedAt = 0;
                dialog = null;
            }
            if (ui.button("assign#cancel", new Rect(ok.x() - 88, ok.y(), 80, 22), Lang.t("action.cancel"), null, Ui.Style.GHOST)) dialog = null;
        }, () -> dialog = null);
    }

    private void importDialog(Ui ui, SkycoreScreen screen) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ImportMerge.Preview p = preview;
        ui.modal(screen.screenRect(), 440, 250, Lang.t("backup.preview_title"), body -> {
            float ly = body.y();
            if (p == null || !p.valid()) {
                g.text(Lang.t("backup.invalid", p == null ? "?" : p.problem()), body.x(), ly, t.danger());
            } else {
                g.text(g.ellipsize(previewFile.getFileName().toString(), body.w(), 1f), body.x(), ly, t.text(), 1f, Weight.SEMIBOLD);
                ly += 16;
                int shown = 0;
                for (ImportMerge.ProfilePlan pl : p.profiles()) {
                    if (shown++ >= 5) { g.text(Lang.t("integrity.more", p.profiles().size() - 5), body.x(), ly, t.textMute(), 0.85f); ly += 12; break; }
                    String line = switch (pl.action()) {
                        case ADD -> Lang.t("backup.plan.add", pl.name(), pl.runsInFile());
                        case ADD_SEPARATE -> Lang.t("backup.plan.separate", pl.name());
                        case MERGE -> Lang.t("backup.plan.merge", pl.name(), pl.newRuns(), pl.duplicateRuns());
                    };
                    g.text(g.ellipsize(line, body.w() / 0.9f, 1f), body.x(), ly, pl.action() == ImportMerge.ProfileAction.ADD_SEPARATE ? t.warning() : t.textDim(), 0.9f);
                    ly += 12;
                }
                g.text(Lang.t("backup.plan.sessions", p.newSessionBlocks(), p.existingSessionBlocks()), body.x(), ly, t.textDim(), 0.9f); ly += 12;
                g.text(Lang.t("backup.plan.goals_events", p.newGoals(), p.newEvents(), p.duplicateEvents()), body.x(), ly, t.textDim(), 0.9f); ly += 16;
                for (String l : g.wrap(Lang.t("backup.merge_vs_restore"), body.w(), 0.85f)) { g.text(l, body.x(), ly, t.textMute(), 0.85f); ly += 10; }
            }
            Rect merge = new Rect(body.right() - 110, body.bottom() - 22, 110, 22);
            boolean valid = p != null && p.valid();
            if (ui.button("imp#merge", merge, Lang.t("backup.merge"), Icon.IMPORT, Ui.Style.PRIMARY, valid)) {
                int n = Backups.merge(previewFile);
                Notifier.push(null, Lang.t(n >= 0 ? "backup.merged" : "settings.import_failed"),
                        n >= 0 ? Lang.t("backup.merged_body", n) : Lang.t("backup.refused"), n >= 0 ? Icon.IMPORT : Icon.WARNING, n >= 0 ? t.success() : t.danger());
                infosAt = 0;
                dialog = null;
            }
            if (ui.hover(merge)) ui.tooltip(Lang.t("backup.merge_tip"));
            Rect restore = new Rect(merge.x() - 118, merge.y(), 110, 22);
            if (ui.button("imp#restore", restore, Lang.t("backup.restore"), Icon.RESET, Ui.Style.DANGER, valid)) {
                int n = Backups.restore(previewFile);
                Notifier.push(null, Lang.t(n > 0 ? "settings.imported" : "settings.import_failed"),
                        n > 0 ? Lang.t("settings.imported_body", n) : Lang.t("backup.refused"), n > 0 ? Icon.IMPORT : Icon.WARNING, n > 0 ? t.success() : t.danger());
                infosAt = 0;
                dialog = null;
            }
            if (ui.hover(restore)) ui.tooltip(Lang.t("backup.restore_tip"));
            if (ui.button("imp#cancel", new Rect(restore.x() - 88, merge.y(), 80, 22), Lang.t("action.cancel"), null, Ui.Style.GHOST)) dialog = null;
        }, () -> dialog = null);
    }
}
