package dev.skycore.ui.pages;

import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.data.DataStatus;
import dev.skycore.data.Progress;
import dev.skycore.data.StatDef;
import dev.skycore.share.Destination;
import dev.skycore.share.Inbox;
import dev.skycore.share.Kind;
import dev.skycore.share.MessageBuilder;
import dev.skycore.share.Prepared;
import dev.skycore.share.ShareService;
import dev.skycore.share.ShareSettings;
import dev.skycore.share.Template;
import dev.skycore.share.Template.Value;
import dev.skycore.ui.Page;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Milestone sharing: prepared messages, routing, milestones, checkpoints, template editor, signature, clipboard
 * Everything here only prepares text; the player sends it themselves.
 */
public class SharePage implements Page {
    @Override public String id() { return "share"; }
    @Override public Icon icon() { return Icon.EXPORT; }

    private static final Destination[] DESTS = {Destination.PARTY, Destination.ALL, Destination.CLIPBOARD, Destination.MANUAL};

    private Kind editKind = Kind.COMPLETED;
    private boolean previewMissing;
    private int reportIdx;
    private final Map<String, String> drafts = new LinkedHashMap<>();
    private int ruleCat, ruleStat, ruleDest;
    private final Random previewRnd = new Random(7);
    private Prepared removeRequest;

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        ShareSettings s = Skycore.config().share;
        float y = area.y() + 6;
        y = general(ui, area, y, s);
        float prev = ui.g.alpha;
        if (!s.enabled) ui.g.alpha *= 0.5f;
        y = inbox(ui, area, y, s);
        y = routing(ui, area, y, s);
        y = milestones(ui, area, y, s);
        y = checkpoints(ui, area, y, s);
        y = templates(ui, area, y, s);
        y = signature(ui, area, y, s);
        y = clipboard(ui, area, y, s);
        ui.g.alpha = prev;
        return y - area.y();
    }

    private static void changed() { Skycore.markDirty(); }

    private static String[] names(String prefix, String[] ids) {
        String[] out = new String[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = Lang.t(prefix + ids[i]);
        return out;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    private static int accentFor(Theme t, String kind) {
        return switch (kind) {
            case "completed", "major" -> t.progress();       // gold: achievements
            case "nearly", "checkpoint", "report" -> ShareService.cyan(t); // cyan: progress updates
            default -> ShareService.cyan(t);
        };
    }

    private static int catAccent(Theme t, Prepared p) {
        if (p.category.equals("goal_completed")) return t.success(); // green: completed goals
        return accentFor(t, p.kind);
    }

    // ------------------------------------------------------------------ general
    private float general(Ui ui, Rect area, float y, ShareSettings s) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section sec = ui.beginSection("sh.general", area.x(), y, area.w(), Lang.t("share.section.general"), Lang.t("share.section.general_desc"), Icon.EXPORT, true);
        float cy = sec.contentY();
        if (sec.visible()) {
            boolean en = ui.toggleRow("sh#en", new Rect(sec.x() - 8, cy, sec.w() + 8, 30), Lang.t("share.enabled"), Lang.t("share.enabled_desc"), s.enabled);
            if (en != s.enabled) { s.enabled = en; changed(); }
            cy += 34;
            boolean ts = ui.toggleRow("sh#toast", new Rect(sec.x() - 8, cy, sec.w() + 8, 26), Lang.t("share.toast"), null, s.showToast);
            if (ts != s.showToast) { s.showToast = ts; changed(); }
            cy += 30;
            for (String l : g.wrap(Lang.t("share.manual_note"), sec.w(), 0.85f)) { g.text(l, sec.x(), cy, t.textMute(), 0.85f); cy += 10; }
        }
        return ui.endSection(sec, cy);
    }

    // ------------------------------------------------------------------ inbox
    private float inbox(Ui ui, Rect area, float y, ShareSettings s) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Inbox box = ShareService.inbox();
        List<Prepared> list = ShareService.state().inbox;
        Ui.Section sec = ui.beginSection("sh.inbox", area.x(), y, area.w(), Lang.t("share.section.inbox"), Lang.t("share.section.inbox_desc"), Icon.TROPHY, true);
        float cy = sec.contentY();
        if (sec.visible()) {
            if (list.isEmpty()) { g.text(Lang.t("share.inbox_empty"), sec.x(), cy + 2, t.textMute(), 0.95f); cy += 22; }
            removeRequest = null;
            int shown = 0;
            for (Prepared p : new ArrayList<>(list)) {
                if (shown++ >= 8) break;
                cy = inboxRow(ui, sec, cy, p, box);
            }
            if (removeRequest != null) { box.remove(removeRequest); ShareService.markDirty(); removeRequest = null; }
            cy += 4;
            cy = report(ui, sec, cy);
        }
        return ui.endSection(sec, cy);
    }

    /** One prepared message: accent bar, headline, text, evidence note and the copy buttons. Returns the next y. */
    private float inboxRow(Ui ui, Ui.Section sec, float y, Prepared p, Inbox box) {
        Gfx g = ui.g;
        Theme t = ui.t;
        float w = sec.w();
        List<String> lines = g.wrap(p.text, w - 24, 1f);
        float h = 22 + lines.size() * 11 + 30;
        g.roundRect(sec.x(), y, w, h, Math.min(t.radius, 8), t.surface());
        g.roundRect(sec.x(), y + 6, 2, h - 12, 1, catAccent(t, p));
        g.text(g.ellipsize(p.title, w - 170, 1f), sec.x() + 12, y + 7, t.text(), 1f, Weight.SEMIBOLD);
        if (ui.iconButton("sh#del" + p.id, new Rect(sec.x() + w - 24, y + 4, 18, 18), Icon.TRASH, Lang.t("share.dismiss"))) removeRequest = p;
        Destination d = p.destination();
        String tag = Lang.t("share.dest." + d.id()) + (p.estimated ? " · " + Lang.t("share.estimated_tag") : "");
        g.textRight(tag, sec.x() + w - 30, y + 8, p.estimated ? t.warning() : t.textDim(), 0.85f, Weight.MEDIUM);
        float ty = y + 22;
        for (String l : lines) { g.text(l, sec.x() + 12, ty, t.textDim(), 1f); ty += 11; }
        float by = y + h - 26;
        float bx = sec.x() + 12;
        Destination[] targets = d == Destination.MANUAL ? new Destination[]{Destination.PARTY, Destination.ALL, Destination.CLIPBOARD} : new Destination[]{d};
        for (Destination target : targets) {
            boolean done = box.justCopied(p);
            String label = done ? Lang.t("share.copied") : Lang.t("share.copy_for." + target.id());
            float bw = g.width(label, 1f, Weight.MEDIUM) + 32;
            if (ui.button("sh#cp" + p.id + target.id(), new Rect(bx, by, bw, 20), label, done ? Icon.CHECK : Icon.COPY,
                    d == target || targets.length == 1 ? Ui.Style.PRIMARY : Ui.Style.SECONDARY)) box.copy(p, target);
            bx += bw + 6;
        }
        return y + h + 8;
    }

    private float report(Ui ui, Ui.Section sec, float cy) {
        Gfx g = ui.g;
        Theme t = ui.t;
        g.text(Lang.t("share.report"), sec.x(), cy + 6, t.text(), 1f, Weight.MEDIUM);
        List<StatDef> defs = new ArrayList<>();
        for (StatDef d : StatDef.ALL.values()) {
            if (d.isClass() || d.id().equals("bestiary")) continue;
            if (Progress.real().peek(d.id()).known()) defs.add(d);
        }
        if (Progress.isSample() || defs.isEmpty()) {
            g.text(Lang.t(Progress.isSample() ? "share.report_sample" : "share.report_none"), sec.x() + 130, cy + 6, t.textMute(), 0.9f);
            return cy + 26;
        }
        String[] opts = new String[defs.size()];
        for (int i = 0; i < opts.length; i++) opts[i] = Lang.t(defs.get(i).nameKey());
        reportIdx = Math.max(0, Math.min(opts.length - 1, reportIdx));
        float dx = sec.x() + 130;
        reportIdx = ui.dropdown("sh#reportstat", new Rect(dx, cy, 170, 22), opts, reportIdx);
        String label = Lang.t("share.report_create");
        if (ui.button("sh#report", new Rect(dx + 178, cy, g.width(label, 1f, Weight.MEDIUM) + 32, 22), label, Icon.PLUS, Ui.Style.SECONDARY)) {
            Prepared p = ShareService.report(defs.get(reportIdx).id());
            drafts.put("report_note", p == null ? Lang.t("share.report_failed") : "");
        }
        String note = drafts.getOrDefault("report_note", "");
        if (!note.isEmpty()) g.text(note, sec.x(), cy + 30, t.warning(), 0.9f);
        return cy + (note.isEmpty() ? 30 : 44);
    }

    // ------------------------------------------------------------------ routing
    private float routing(Ui ui, Rect area, float y, ShareSettings s) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section sec = ui.beginSection("sh.routing", area.x(), y, area.w(), Lang.t("share.section.routing"), Lang.t("share.section.routing_desc"), Icon.GLOBE, false);
        float cy = sec.contentY();
        if (sec.visible()) {
            float ddW = Math.min(150, sec.w() * 0.3f);
            float wmX = sec.x() + sec.w() - 34, ddX = wmX - 14 - ddW;
            ui.caption(Lang.t("share.destination"), ddX, cy);
            ui.caption(Lang.t("share.signature_short"), wmX - 18, cy);
            cy += 14;
            String[] destNames = names("share.dest.", new String[]{"party", "all", "clipboard", "manual"});
            for (String cat : ShareSettings.CATEGORIES) {
                ShareSettings.Category c = s.category(cat);
                boolean on = ui.toggle("sh#ce" + cat, new Rect(sec.x(), cy + 2, 28, 16), c.enabled);
                if (on != c.enabled) { c.enabled = on; changed(); }
                g.text(Lang.t("share.cat." + cat), sec.x() + 38, cy + 5, c.enabled ? t.text() : t.textMute(), 1f, Weight.MEDIUM);
                int di = ui.dropdown("sh#cd" + cat, new Rect(ddX, cy, ddW, 20), destNames, indexOf(new String[]{"party", "all", "clipboard", "manual"}, c.destination));
                String nd = DESTS[di].id();
                if (!nd.equals(c.destination)) { c.destination = nd; changed(); }
                boolean wm = ui.toggle("sh#cw" + cat, new Rect(wmX, cy + 2, 28, 16), c.watermark);
                if (wm != c.watermark) { c.watermark = wm; changed(); }
                cy += 26;
            }
            cy += 6;
            ui.separator(sec.x(), cy, sec.w());
            cy += 10;
            g.text(Lang.t("share.rules"), sec.x(), cy, t.text(), 1f, Weight.MEDIUM);
            cy += 14;
            for (String l : g.wrap(Lang.t("share.rules_desc"), sec.w(), 0.85f)) { g.text(l, sec.x(), cy, t.textMute(), 0.85f); cy += 10; }
            cy += 4;
            ShareSettings.RouteRule gone = null;
            for (int i = 0; i < s.rules.size(); i++) {
                ShareSettings.RouteRule r = s.rules.get(i);
                String stat = r.stat.isEmpty() ? Lang.t("share.any_stat") : (StatDef.get(r.stat) == null ? r.stat : Lang.t(StatDef.get(r.stat).nameKey()));
                g.text(Lang.t("share.rule_line", Lang.t("share.cat." + r.category), stat, Lang.t("share.dest." + r.destination)), sec.x(), cy + 5, t.text(), 1f);
                if (ui.iconButton("sh#rr" + i, new Rect(sec.x() + sec.w() - 20, cy + 1, 18, 18), Icon.CLOSE, Lang.t("notify.remove"))) gone = r;
                cy += 24;
            }
            if (gone != null) { s.rules.remove(gone); changed(); }
            if (s.rules.size() < 10) {
                List<String> statIds = new ArrayList<>(List.of(""));
                for (StatDef d : StatDef.ALL.values()) if (!d.isClass() && !d.id().equals("bestiary")) statIds.add(d.id());
                String[] statNames = new String[statIds.size()];
                for (int i = 0; i < statNames.length; i++) statNames[i] = statIds.get(i).isEmpty() ? Lang.t("share.any_stat") : Lang.t(StatDef.get(statIds.get(i)).nameKey());
                String[] catNames = names("share.cat.", ShareSettings.CATEGORIES);
                float cw = (sec.w() - 3 * 8 - 60) / 3f;
                float x = sec.x();
                ruleCat = ui.dropdown("sh#rcat", new Rect(x, cy, cw, 20), catNames, Math.min(ruleCat, catNames.length - 1));
                x += cw + 8;
                ruleStat = ui.dropdown("sh#rstat", new Rect(x, cy, cw, 20), statNames, Math.min(ruleStat, statNames.length - 1));
                x += cw + 8;
                ruleDest = ui.dropdown("sh#rdest", new Rect(x, cy, cw, 20), destNames, Math.min(ruleDest, destNames.length - 1));
                x += cw + 8;
                if (ui.button("sh#radd", new Rect(x, cy, 52, 20), Lang.t("notify.add"), Icon.PLUS, Ui.Style.SECONDARY)) {
                    s.rules.add(new ShareSettings.RouteRule(ShareSettings.CATEGORIES[ruleCat], statIds.get(ruleStat), DESTS[ruleDest].id()));
                    changed();
                }
                cy += 28;
            }
        }
        return ui.endSection(sec, cy);
    }

    // ------------------------------------------------------------------ milestones
    private float milestones(Ui ui, Rect area, float y, ShareSettings s) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section sec = ui.beginSection("sh.milestones", area.x(), y, area.w(), Lang.t("share.section.milestones"), Lang.t("share.section.milestones_desc"), Icon.TROPHY, false);
        float cy = sec.contentY();
        if (sec.visible()) {
            cy = chips(ui, sec, cy, "cat", Lang.t("share.ms.catacombs"), s.catacombsLevels, 1, 50, v -> Lang.t("share.level_n", v));
            cy = chips(ui, sec, cy, "sla", Lang.t("share.ms.slayer"), s.slayerLevels, 1, 9, v -> Lang.t("share.level_n", v));
            int mj = ui.stepper("sh#majsl", new Rect(sec.x() + sec.w() - 96, cy - 4, 96, 20), s.majorSlayerLevel, 1, 9);
            g.text(Lang.t("share.ms.major_slayer"), sec.x(), cy, t.textDim(), 0.95f);
            if (mj != s.majorSlayerLevel) { s.majorSlayerLevel = mj; changed(); }
            cy += 28;
            cy = chips(ui, sec, cy, "run", Lang.t("share.ms.runs"), s.runMilestones, 1, 100_000, v -> Lang.num(v));
            cy = chips(ui, sec, cy, "hot", Lang.t("share.ms.hotm"), s.hotmTiers, 1, 10, v -> Lang.t("share.tier_n", v));
            g.text(Lang.t("share.ms.collection_min"), sec.x(), cy + 2, t.textDim(), 0.95f);
            int cm = ui.stepper("sh#colmin", new Rect(sec.x() + sec.w() - 96, cy - 2, 96, 20), s.collectionMinLevel, 1, 100);
            if (cm != s.collectionMinLevel) { s.collectionMinLevel = cm; changed(); }
            cy += 28;
            boolean sm = ui.toggleRow("sh#skillmax", new Rect(sec.x() - 8, cy, sec.w() + 8, 28), Lang.t("share.ms.skill_max"), Lang.t("share.ms.skill_max_desc"), s.skillMax);
            if (sm != s.skillMax) { s.skillMax = sm; changed(); }
            cy += 32;
        }
        return ui.endSection(sec, cy);
    }

    private float checkpoints(Ui ui, Rect area, float y, ShareSettings s) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section sec = ui.beginSection("sh.checkpoints", area.x(), y, area.w(), Lang.t("share.section.checkpoints"), Lang.t("share.section.checkpoints_desc"), Icon.GOALS, false);
        float cy = sec.contentY();
        if (sec.visible()) {
            cy = chips(ui, sec, cy, "pct", Lang.t("share.cp.percent"), s.checkpointPercents, 1, 99, v -> v + "%");
            cy = chips(ui, sec, cy, "rem", Lang.t("share.cp.remaining"), s.checkpointRemaining, 1, 100, v -> Lang.t("share.cp.left_n", v));
            boolean nr = ui.toggleRow("sh#nearly", new Rect(sec.x() - 8, cy, sec.w() + 8, 28), Lang.t("share.cp.nearly"), Lang.t("share.cp.nearly_desc"), s.nearlyReached);
            if (nr != s.nearlyReached) { s.nearlyReached = nr; changed(); }
            cy += 34;
            for (String l : g.wrap(Lang.t("share.cp.note"), sec.w(), 0.85f)) { g.text(l, sec.x(), cy, t.textMute(), 0.85f); cy += 10; }
        }
        return ui.endSection(sec, cy);
    }

    /** A labelled row of removable value chips with an input to add one. */
    private float chips(Ui ui, Ui.Section sec, float y, String id, String label, List<Integer> list, int min, int max, java.util.function.IntFunction<String> fmt) {
        Gfx g = ui.g;
        Theme t = ui.t;
        g.text(label, sec.x(), y, t.text(), 1f, Weight.MEDIUM);
        float cy = y + 15, cx = sec.x();
        if (list.isEmpty()) { g.text(Lang.t("share.none"), cx, cy + 5, t.textMute(), 0.9f); cx += 60; }
        Integer gone = null;
        for (int v : new ArrayList<>(list)) {
            String text = fmt.apply(v);
            float w = g.width(text, 1f, Weight.MEDIUM) + 32;
            if (cx + w > sec.x() + sec.w() && cx > sec.x()) { cx = sec.x(); cy += 26; }
            Rect chip = new Rect(cx, cy, w, 22);
            g.roundRect(chip.x(), chip.y(), chip.w(), chip.h(), 11, t.surface());
            g.text(text, chip.x() + 10, chip.cy() - 4.5f, t.text(), 1f, Weight.MEDIUM);
            if (ui.iconButton("sh#x" + id + v, new Rect(chip.right() - 20, chip.y() + 3, 16, 16), Icon.CLOSE, Lang.t("notify.remove"))) gone = v;
            cx += w + 6;
        }
        if (gone != null) { list.remove(gone); changed(); }
        cy += 30;
        String key = "draft_" + id;
        String cur = drafts.getOrDefault(key, "");
        String nv = ui.textField("sh#in" + id, new Rect(sec.x(), cy, 90, 22), cur, Lang.t("share.add_value"), 6, c -> c >= '0' && c <= '9');
        drafts.put(key, nv);
        int parsed = -1;
        try { parsed = Integer.parseInt(nv); } catch (NumberFormatException ignored) { }
        boolean can = parsed >= min && parsed <= max && !list.contains(parsed) && list.size() < ShareSettings.MAX_LIST;
        if (ui.button("sh#add" + id, new Rect(sec.x() + 98, cy, 64, 22), Lang.t("notify.add"), Icon.PLUS, Ui.Style.SECONDARY, can)) {
            list.add(parsed);
            list.sort(Integer::compare);
            drafts.put(key, "");
            changed();
        }
        return cy + 34;
    }

    // ------------------------------------------------------------------ templates
    /** Example values for the live preview. Clearly labelled as examples, never the player's data. */
    private Map<String, Value> exampleVars(boolean missing) {
        Map<String, Value> v = new LinkedHashMap<>();
        v.put("player", Value.of("Player"));
        v.put("category", Value.of(Lang.t("share.catname.catacombs")));
        v.put("milestone", Value.of(Lang.t("stat.catacombs") + " 40"));
        v.put("previous_level", Value.of("39"));
        v.put("current_level", Value.of("40"));
        v.put("remaining", Value.estimated(Lang.num(2) + " " + Lang.t("unit.runs")));
        if (!missing) {
            v.put("runs_total", Value.tracked("1,250"));
            v.put("runs_since", Value.tracked("87"));
            v.put("next_goal", Value.of(Lang.t("stat.catacombs") + " 50"));
        }
        return v;
    }

    private float templates(Ui ui, Rect area, float y, ShareSettings s) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section sec = ui.beginSection("sh.templates", area.x(), y, area.w(), Lang.t("share.section.templates"), Lang.t("share.section.templates_desc"), Icon.EDIT, false);
        float cy = sec.contentY();
        if (sec.visible()) {
            String[] kinds = new String[Kind.values().length];
            for (int i = 0; i < kinds.length; i++) kinds[i] = Lang.t("share.kind." + Kind.values()[i].id());
            int ki = ui.segmented("sh#kind", new Rect(sec.x(), cy, Math.min(sec.w(), 560), 22), kinds, editKind.ordinal());
            editKind = Kind.values()[ki];
            cy += 32;
            List<String> own = s.templates.get(editKind.id());
            boolean custom = own != null && !own.isEmpty();
            if (!custom) {
                g.text(Lang.t("share.tpl_default"), sec.x(), cy + 3, t.textMute(), 0.9f);
                String cl = Lang.t("share.tpl_customize");
                if (ui.button("sh#cust", new Rect(sec.x() + sec.w() - g.width(cl, 1f, Weight.MEDIUM) - 32, cy - 2, g.width(cl, 1f, Weight.MEDIUM) + 32, 22), cl, Icon.EDIT, Ui.Style.SECONDARY)) {
                    s.templates.put(editKind.id(), new ArrayList<>(ShareService.defaults(editKind)));
                    changed();
                }
                cy += 28;
                for (String d : ShareService.defaults(editKind)) {
                    for (String l : g.wrap(d, sec.w() - 12, 0.95f)) { g.text(l, sec.x() + 6, cy, t.textDim(), 0.95f); cy += 11; }
                    cy += 5;
                }
            } else {
                int remove = -1;
                for (int i = 0; i < own.size(); i++) {
                    String cur = own.get(i);
                    boolean bad = !Template.unknown(cur).isEmpty();
                    String nv = ui.textField("sh#tpl" + editKind.id() + i, new Rect(sec.x(), cy, sec.w() - 26, 22), cur, Lang.t("share.tpl_placeholder"), 200, c -> c >= 32, !bad);
                    if (!nv.equals(cur)) { own.set(i, nv); changed(); }
                    if (ui.iconButton("sh#tpd" + editKind.id() + i, new Rect(sec.x() + sec.w() - 22, cy + 2, 18, 18), Icon.TRASH, Lang.t("share.tpl_remove"))) remove = i;
                    cy += 26;
                    if (bad) { g.text(Lang.t("share.unknown_ph", String.join(", ", Template.unknown(cur).stream().map(x -> "{" + x + "}").toList())), sec.x(), cy - 2, t.warning(), 0.85f); cy += 11; }
                }
                if (remove >= 0) { own.remove(remove); if (own.isEmpty()) s.templates.remove(editKind.id()); changed(); }
                float bx = sec.x();
                if (own.size() < 6) {
                    String al = Lang.t("share.tpl_add");
                    float bw = g.width(al, 1f, Weight.MEDIUM) + 32;
                    if (ui.button("sh#tpadd", new Rect(bx, cy, bw, 22), al, Icon.PLUS, Ui.Style.SECONDARY)) { own.add(""); changed(); }
                    bx += bw + 8;
                }
                String rl = Lang.t("share.tpl_reset");
                if (ui.button("sh#tpreset", new Rect(bx, cy, g.width(rl, 1f, Weight.MEDIUM) + 32, 22), rl, Icon.RESET, Ui.Style.GHOST)) { s.templates.remove(editKind.id()); changed(); }
                cy += 30;
            }
            StringBuilder ph = new StringBuilder();
            for (String p : Template.PLACEHOLDERS) ph.append('{').append(p).append("} ");
            for (String l : g.wrap(Lang.t("share.placeholders", ph.toString().trim()), sec.w(), 0.85f)) { g.text(l, sec.x(), cy, t.textMute(), 0.85f); cy += 10; }
            cy += 6;
            cy = preview(ui, sec, cy, s);
        }
        return ui.endSection(sec, cy);
    }

    /** Live preview with example values (never the player's data) of every variant of the selected kind. */
    private float preview(Ui ui, Ui.Section sec, float y, ShareSettings s) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ui.caption(Lang.t("share.preview"), sec.x(), y);
        boolean pm = ui.toggleRow("sh#pvmiss", new Rect(sec.x() + sec.w() - 230, y - 6, 230, 22), Lang.t("share.preview_missing"), null, previewMissing);
        previewMissing = pm;
        y += 16;
        Map<String, Value> vars = exampleVars(previewMissing);
        List<String> variants = ShareService.variants(editKind);
        ShareSettings tmp = s;
        int accent = accentFor(t, editKind == Kind.MAJOR || editKind == Kind.COMPLETED ? "completed" : "nearly");
        Destination dest = ShareSettings.defaultDestination("catacombs");
        for (int i = 0; i < variants.size(); i++) {
            MessageBuilder.Built b = MessageBuilder.build(tmp, "catacombs", dest, List.of(variants.get(i)), -1, vars, previewRnd);
            String text = b == null ? Lang.t("share.preview_empty") : b.text();
            List<String> lines = g.wrap(text, sec.w() - 24, 1f);
            float h = 12 + lines.size() * 11 + 14;
            g.roundRect(sec.x(), y, sec.w(), h, Math.min(t.radius, 8), t.surface());
            g.roundRect(sec.x(), y + 5, 2, h - 10, 1, accent);
            float ty = y + 7;
            for (String l : lines) { g.text(l, sec.x() + 12, ty, b == null ? t.textMute() : t.text(), 1f); ty += 11; }
            g.textRight((b == null ? 0 : b.text().length()) + "/" + (MessageBuilder.CHAT_LIMIT - dest.prefix.length()), sec.x() + sec.w() - 8, y + h - 11, t.textMute(), 0.8f, Weight.REGULAR);
            y += h + 6;
        }
        g.text(Lang.t("share.preview_note"), sec.x(), y, t.textMute(), 0.85f);
        return y + 14;
    }

    // ------------------------------------------------------------------ signature
    private float signature(Ui ui, Rect area, float y, ShareSettings s) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section sec = ui.beginSection("sh.signature", area.x(), y, area.w(), Lang.t("share.section.signature"), Lang.t("share.section.signature_desc"), Icon.SPARK, false);
        float cy = sec.contentY();
        if (sec.visible()) {
            String[] presets = ShareSettings.SIGNATURE_PRESETS;
            String[] opts = new String[presets.length + 1];
            System.arraycopy(presets, 0, opts, 0, presets.length);
            opts[presets.length] = Lang.t("share.sig_custom");
            int cur = presets.length;
            for (int i = 0; i < presets.length; i++) if (presets[i].equals(s.signature)) cur = i;
            g.text(Lang.t("share.signature"), sec.x(), cy + 5, t.text(), 1f, Weight.MEDIUM);
            int sel = ui.dropdown("sh#sigpreset", new Rect(sec.x() + 150, cy, Math.min(240, sec.w() - 150), 22), opts, cur);
            if (sel != cur) { s.signature = sel < presets.length ? presets[sel] : ""; changed(); }
            cy += 30;
            if (cur == presets.length || sel == presets.length) {
                g.text(Lang.t("share.sig_text"), sec.x(), cy + 5, t.text(), 1f, Weight.MEDIUM);
                String nv = ui.textField("sh#sigtext", new Rect(sec.x() + 150, cy, Math.min(240, sec.w() - 150), 22), s.signature, Lang.t("share.sig_placeholder"), 40, c -> c >= 32);
                if (!nv.equals(s.signature)) { s.signature = nv; changed(); }
                cy += 30;
            }
            for (String l : g.wrap(Lang.t("share.sig_note"), sec.w(), 0.85f)) { g.text(l, sec.x(), cy, t.textMute(), 0.85f); cy += 10; }
        }
        return ui.endSection(sec, cy);
    }

    // ------------------------------------------------------------------ clipboard
    private float clipboard(Ui ui, Rect area, float y, ShareSettings s) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section sec = ui.beginSection("sh.clipboard", area.x(), y, area.w(), Lang.t("share.section.clipboard"), Lang.t("share.section.clipboard_desc"), Icon.COPY, false);
        float cy = sec.contentY();
        if (sec.visible()) {
            boolean ac = ui.toggleRow("sh#autocopy", new Rect(sec.x() - 8, cy, sec.w() + 8, 30), Lang.t("share.autocopy"), Lang.t("share.autocopy_desc"), s.autoCopy);
            if (ac != s.autoCopy) { s.autoCopy = ac; changed(); }
            cy += 36;
            for (String l : g.wrap(Lang.t("share.clipboard_note"), sec.w(), 0.85f)) { g.text(l, sec.x(), cy, t.textMute(), 0.85f); cy += 10; }
        }
        return ui.endSection(sec, cy);
    }
}
