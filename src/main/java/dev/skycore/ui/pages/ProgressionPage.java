package dev.skycore.ui.pages;

import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.data.Estimate;
import dev.skycore.data.ProfileData;
import dev.skycore.data.ProfileData.StatProgress;
import dev.skycore.data.Progress;
import dev.skycore.data.SessionLog;
import dev.skycore.data.StatDef;
import dev.skycore.data.XpTables;
import dev.skycore.ui.Page;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.Widgets;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Detailed progression per category (Dungeons, Slayer, Skills, Foraging, Bestiary, Mining, Collections). */
public class ProgressionPage implements Page {
    private int tab;
    private final CalibrateDialog calibrate = new CalibrateDialog();

    /** Dev screenshots / tests: open the calibration dialog (a null definition shows the unavailable state). */
    public void openCalibrate(Ui ui, StatDef def) { calibrate.show(ui, def); }
    private final ActivityViews views = new ActivityViews();
    /** Right edge available for extra header actions (left of the tracking badge). */
    private float headerActionsX;

    @Override public String id() { return "progression"; }

    /** Select a category tab programmatically (dev capture / deep links). */
    public void setTab(int i) { tab = Math.max(0, Math.min(StatDef.CATEGORIES.length - 1, i)); }

    /** Dev capture: open the details of the newest quest (slayer) or run (dungeons). */
    public void openFirstDetails(Ui ui, boolean slayer) {
        var d = Progress.view();
        if (slayer && !d.slayerQuests.isEmpty()) views.openQuest(ui, d.slayerQuests.get(0));
        else if (!slayer && !d.runs.isEmpty()) views.openRun(ui, d.runs.get(0));
    }
    @Override public Icon icon() { return Icon.PROGRESSION; }

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        String[] cats = StatDef.CATEGORIES;
        String[] labels = new String[cats.length];
        for (int i = 0; i < cats.length; i++) labels[i] = Lang.t("cat." + cats[i]);
        float y = area.y() + 8;
        int nt = ui.pills("prog#tabs", area.x(), y, labels, tab, area.w());
        if (nt != tab) tab = nt;
        y = ui.lastPillsBottom + 12;
        String cat = cats[tab];
        boolean tracking = Skycore.config().tracks(cat);
        Rect body = new Rect(area.x(), y, area.w(), 0);
        float h = switch (cat) {
            case "dungeons" -> dungeons(ui, body);
            case "slayer" -> slayer(ui, body, tracking);
            case "skills" -> skills(ui, body, tracking);
            case "foraging" -> singleSkill(ui, body, tracking, "foraging", "skill_foraging");
            case "bestiary" -> bestiary(ui, body);
            case "mining" -> mining(ui, body, tracking);
            default -> collections(ui, body, tracking);
        };
        calibrate.render(ui, screen);
        views.details(ui, screen);
        return y - area.y() + h + 8;
    }

    // ------------------------------------------------------------------ shared pieces
    /** Card header with title and the "Tracking / Paused" toggle badge. Returns content y. */
    private float cardHeader(Ui ui, Rect card, String title, String category, Icon icon) {
        Gfx g = ui.g;
        Theme t = ui.t;
        // title only (no icon tile); the tracking switch is a state mark + word, not a pill
        g.text(title, card.x() + 16, card.y() + 16, t.text(), 1.25f, Weight.SEMIBOLD);
        boolean on = Skycore.config().tracks(category);
        String label = Lang.t(on ? "progress.tracking" : "progress.paused");
        float bw = g.width(label, 0.9f, Weight.MEDIUM) + 14;
        Rect sw = new Rect(card.right() - 16 - bw, card.y() + 15, bw, 14);
        headerActionsX = sw.x() - 12;
        String id = "trk#" + category + title;
        boolean hov = ui.hover(sw);
        if (hov) ui.setCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.POINTING_HAND);
        if (on) g.circle(sw.x() + 3, sw.cy(), 2.6f, t.success()); else g.ringOutline(sw.x() + 3, sw.cy(), 2.6f, 1.2f, t.textMute());
        g.text(label, sw.x() + 11, sw.y() + 2.5f, hov ? t.text() : t.textDim(), 0.9f, Weight.MEDIUM);
        if (ui.pressedOn(sw)) ui.active = id;
        if (ui.releasedThisFrame() && id.equals(ui.active) && hov) {
            ui.active = null;
            Ui.clickSound();
            Skycore.config().tracking.put(category, !on);
            Skycore.markDirty();
        }
        if (hov) ui.tooltip(Lang.t("progress.tracking.tip"));
        return card.y() + 46;
    }

    /** Evidence word for a value ("Observed" / "Estimated" / "Unavailable"); the tooltip keeps the detailed record. */
    private static String sourceLabel(StatProgress p) {
        return p.known() ? Lang.t(dev.skycore.data.Evidence.of(p.status()).key()) : "";
    }

    private void row(Ui ui, Rect in, float y, String label, String value) {
        ui.g.text(label, in.x(), y, ui.t.textDim(), 0.9f);
        ui.g.textRight(value, in.right(), y, ui.t.text(), 0.9f, false);
    }

    private void trackingBanner(Ui ui, Rect area, String cat, boolean tracking) {
        if (tracking) return;
        Rect b = new Rect(area.x(), area.y(), area.w(), 26);
        ui.g.roundRect(b.x(), b.y(), b.w(), b.h(), ui.radius(), Col.alpha(ui.t.warning(), 0.12f));
        ui.g.icon(Icon.PAUSE, b.x() + 8, b.y() + 7, 12, ui.t.warning());
        ui.g.text(Lang.t("progress.paused_banner"), b.x() + 26, b.y() + 9, ui.t.warning());
        if (ui.link("trk#on" + cat, b.right() - ui.g.width(Lang.t("progress.resume")) - 20, b.y() + 9, Lang.t("progress.resume"), null)) {
            Skycore.config().tracking.put(cat, true);
            Skycore.markDirty();
        }
    }

    /** Recent gains as mini bars (last n), with exact values on hover. */
    private void recentBars(Ui ui, String id, Rect r, StatProgress p, int n, int color) {
        int k = Math.min(n, p.gains.size());
        if (k == 0) return;
        double[] v = new double[k];
        for (int i = 0; i < k; i++) v[i] = p.gains.get(p.gains.size() - k + i).amount;
        Widgets.bars(ui, id, r, v, color, Col.mix(color, ui.t.secondary(), 0.5f),
                i -> "+" + Lang.num(v[i]) + " XP\n" + Widgets.ago(p.gains.get(p.gains.size() - k + i).t));
    }

    // ------------------------------------------------------------------ dungeons
    private float dungeons(Ui ui, Rect area) {
        Gfx g = ui.g;
        Theme t = ui.t;
        StatDef def = StatDef.get("catacombs");
        ProfileData d = Progress.view();
        StatProgress p = d.peek("catacombs");
        Rect card = new Rect(area.x(), area.y(), area.w(), 190);
        ui.card(card);
        float y = cardHeader(ui, card, Lang.t("stat.catacombs"), "dungeons", Icon.DUNGEONS);
        Rect in = new Rect(card.x() + 16, y + 2, card.w() - 32, card.bottom() - y - 14);
        if (!p.known()) {
            if (Widgets.empty(ui, "cata#cal0", in, Icon.CALIBRATE, Lang.t("progress.not_calibrated"), Lang.t("progress.cata_calibrate"), Lang.t("action.calibrate")))
                calibrate.show(ui, def);
        } else {
            // the page's centrepiece: level, XP bar and four borderless figures (no nested tiles)
            Widgets.levelHero(ui, "cata", in, def, p, true);
            if (ui.link("cata#cal", headerActionsX - g.width(Lang.t("action.calibrate")) - 12, card.y() + 18, Lang.t("action.calibrate"), Icon.CALIBRATE))
                calibrate.show(ui, def);
        }
        // classes: four-to-five borderless columns on the card, thin neutral bars
        float y2 = card.bottom() + 12;
        Rect cc = new Rect(area.x(), y2, area.w(), 108);
        ui.card(cc);
        g.text(Lang.t("progress.classes"), cc.x() + 16, cc.y() + 16, t.text(), 1.15f, true);
        g.textRight(Lang.t("progress.classes_hint"), cc.right() - 16, cc.y() + 18, t.textMute(), 0.85f, false);
        Rect[] cls = Widgets.figureColumns(ui, new Rect(cc.x() + 16, cc.y() + 44, cc.w() - 32, 50), StatDef.CLASSES.length);
        for (int i = 0; i < StatDef.CLASSES.length; i++) {
            StatDef cd = StatDef.byClass(StatDef.CLASSES[i]);
            StatProgress cp = d.peek(cd.id());
            Rect r = cls[i];
            g.text(Lang.t(cd.nameKey()), r.x(), r.y(), t.textDim(), 0.9f, dev.skycore.ui.gfx.Gfx.Weight.MEDIUM);
            if (cp.known()) {
                g.text(String.valueOf(cp.level), r.x(), r.y() + 12, t.text(), 1.5f, dev.skycore.ui.gfx.Gfx.Weight.SEMIBOLD);
                Widgets.statusDot(ui, r.right() - 6, r.y() + 1, cp);
                ui.progress("cls" + i, new Rect(r.x(), r.bottom() - 6, r.w(), 3), cp.need > 0 ? cp.frac() : 1, t.listBar(), t.listBar());
                if (ui.hover(r)) ui.tooltip(Lang.t(cd.nameKey()) + " " + cp.level + "\n" + (cp.need > 0 ? Lang.num(cp.xp) + " / " + Lang.num(cp.need) + " XP" : Lang.t("progress.maxed")));
            } else {
                g.text("—", r.x(), r.y() + 14, t.textMute(), 1.3f, true);
                Widgets.evidenceMark(ui, r.right() - 3, r.y() + 3.5f, dev.skycore.data.Evidence.UNAVAILABLE);
                if (ui.hover(r)) ui.tooltip(Lang.t("progress.class_sync"));
            }
            if (ui.rightPressedThisFrame() && ui.hover(r) && !Progress.isSample()) calibrate.show(ui, cd);
        }
        // statistics and estimates per exact floor (normal and Master Mode separate)
        float y3 = cc.bottom() + 12;
        y3 += views.floors(ui, new Rect(area.x(), y3, area.w(), 0)) + 12;
        List<ProfileData.DungeonRun> runs = d.runs;
        float rowsH = Math.max(1, Math.min(10, runs.size())) * 22;
        Rect rc = new Rect(area.x(), y3, area.w(), 92 + rowsH + (runs.isEmpty() ? 60 : 0));
        ui.card(rc);
        g.text(Lang.t("progress.recent_runs"), rc.x() + 16, rc.y() + 16, t.text(), 1.3f, true);
        if (runs.isEmpty()) {
            Widgets.empty(ui, "runs#e", new Rect(rc.x(), rc.y() + 34, rc.w(), rc.h() - 40), Icon.DUNGEONS, Lang.t("progress.no_runs"), Lang.t("progress.no_runs_desc"), null);
            return rc.bottom() - area.y();
        }
        int n = Math.min(16, runs.size());
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) vals[i] = runs.get(n - 1 - i).xp;
        Widgets.bars(ui, "runs#bars", new Rect(rc.right() - 16 - Math.min(220, rc.w() * 0.4f), rc.y() + 12, Math.min(220, rc.w() * 0.4f), 26), vals,
                t.chart(0), t.chart(1), i -> Lang.t("progress.run_n", runs.size() - (n - 1 - i)) + "\n" + Lang.num(vals[i]) + " XP");
        float ty = rc.y() + 50;
        float c1 = rc.x() + 16, c2 = rc.x() + rc.w() * 0.14f, c3 = rc.x() + rc.w() * 0.27f, c4 = rc.x() + rc.w() * 0.40f, c5 = rc.right() - 16;
        g.text(Lang.t("progress.col.run"), c1, ty, t.text(), 1f, true);
        g.text(Lang.t("progress.col.floor"), c2, ty, t.text(), 1f, true);
        g.text(Lang.t("progress.col.score"), c3, ty, t.text(), 1f, true);
        g.text(Lang.t("progress.col.class"), c4, ty, t.text(), 1f, true);
        g.textRight(Lang.t("progress.col.xp"), c5, ty, t.text(), 1f, true);
        ty += 14;
        double best = runs.stream().mapToDouble(r -> r.xp).max().orElse(1);
        for (int i = 0; i < Math.min(10, runs.size()); i++) {
            ProfileData.DungeonRun r = runs.get(i);
            ui.separator(c1, ty, rc.w() - 32);
            Rect row = new Rect(c1 - 6, ty + 1, rc.w() - 20, 21);
            if (ui.hover(row)) {
                g.roundRect(row.x(), row.y(), row.w(), row.h(), 5, Col.alpha(t.surface(), 0.8f));
                ui.tooltip(Widgets.ago(r.t) + (r.scorePoints > 0 ? "\n" + Lang.t("progress.score_points", r.scorePoints) : "")
                        + (r.durationMs > 0 ? "\n" + Lang.t("progress.run_duration", Lang.duration(r.durationMs)) : "")
                        + (r.complete() ? "" : "\n" + Lang.t("progress.run_incomplete_tip"))
                        + (r.floor == null ? "\n" + Lang.t("floors.run_unclassified") : "") + "\n" + Lang.t("act.click_details"));
            }
            if (ui.pressedOn(row)) { Ui.clickSound(); views.openRun(ui, r); }
            g.text("#" + (runs.size() - i), c1, ty + 7, t.text());
            g.text(r.floor == null ? "?" : r.floor, c2, ty + 7, r.floor == null ? t.warning() : t.textDim());
            if (r.score != null) Widgets.badge(ui, c3, ty + 5, r.score, r.score.startsWith("S") ? t.success() : t.warning());
            else g.text("—", c3, ty + 7, t.textDim());
            g.text(r.dungeonClass == null ? "—" : Lang.t("stat.class_" + r.dungeonClass.toLowerCase()) + " +" + Lang.compact(r.classXp), c4, ty + 7, t.textDim());
            if (!r.complete()) {
                Widgets.badge(ui, c5 - g.width(Lang.t("progress.run_incomplete"), 0.85f, dev.skycore.ui.gfx.Gfx.Weight.MEDIUM) - 14, ty + 4, Lang.t("progress.run_incomplete"), t.textDim());
                ty += 22;
                continue;
            }
            String xs = Lang.num(r.xp);
            g.textRight(xs, c5, ty + 7, r.xp >= best ? t.xp() : t.text(), 1f, r.xp >= best);
            float bw = Math.min(70, rc.w() * 0.14f);
            ui.progress(null, new Rect(c5 - g.width(xs) - 10 - bw, ty + 9, bw, 3), (float) (r.xp / best), Col.alpha(t.restrained ? t.listBar() : t.primary(), 0.7f), t.progressEnd());
            ty += 22;
        }
        return rc.bottom() - area.y();
    }

    // ------------------------------------------------------------------ slayer
    private float slayer(Ui ui, Rect area, boolean tracking) {
        List<StatDef> defs = StatDef.inCategory("slayer");
        int cols = area.w() >= 560 ? 3 : area.w() >= 360 ? 2 : 1;
        float gap = 12, cw = (area.w() - gap * (cols - 1)) / cols, ch = 168;
        trackingBanner(ui, area, "slayer", tracking);
        float y0 = area.y() + (tracking ? 0 : 34);
        for (int i = 0; i < defs.size(); i++) {
            Rect r = new Rect(area.x() + (i % cols) * (cw + gap), y0 + (i / cols) * (ch + gap), cw, ch);
            statCard(ui, r, defs.get(i), i, true);
        }
        int rows = (defs.size() + cols - 1) / cols;
        float y = (y0 - area.y()) + rows * (ch + gap);
        return y + views.slayerTiers(ui, new Rect(area.x(), area.y() + y, area.w(), 0));
    }

    /** Level card used for slayer bosses, Mining, HOTM and Foraging. */
    private void statCard(Ui ui, Rect r, StatDef def, int idx, boolean withRecent) {
        Gfx g = ui.g;
        Theme t = ui.t;
        StatProgress p = Progress.view().peek(def.id());
        ui.card(r, false, "sc" + def.id());
        Rect in = r.inset(14, 12);
        g.text(g.ellipsize(Lang.t(def.nameKey()), in.w() - 24, 1.05f), in.x(), in.y() + 2, t.text(), 1.05f, true);
        if (ui.iconButton("sc#cal" + def.id(), new Rect(in.right() - 16, in.y() - 3, 18, 18), Icon.CALIBRATE, Lang.t("action.calibrate"))) calibrate.show(ui, def);
        if (!p.known()) {
            var lines = g.wrap(Lang.t(def.unit() == StatDef.Unit.BOSSES ? "progress.slayer_sync" : def.id().equals("hotm") ? "progress.hotm_sync" : "progress.skill_sync"), in.w(), 0.9f);
            for (int i = 0; i < lines.size(); i++) g.text(lines.get(i), in.x(), in.y() + 30 + i * 10, t.textDim(), 0.9f);
            return;
        }
        int c = t.listBar();
        g.text(String.valueOf(p.level), in.x(), in.y() + 18, t.text(), 2.2f, dev.skycore.ui.gfx.Gfx.Weight.BOLD);
        boolean maxed = p.need <= 0 && p.percent < 0;
        g.text(maxed ? Lang.t("progress.maxed") : Lang.t("progress.lvl_next", p.level + 1), in.x() + g.width(String.valueOf(p.level), 2.2f, dev.skycore.ui.gfx.Gfx.Weight.BOLD) + 6, in.y() + 26, t.textDim(), 0.9f);
        float sw = ui.g.width(sourceLabel(p), 0.8f) + 14;
        Widgets.statusLabel(ui, in.right() - sw, in.y() + 26, p, 0.8f);
        ui.progress("sc#p" + def.id(), new Rect(in.x(), in.y() + 46, in.w(), 6), maxed ? 1 : p.frac(), c, Col.mix(c, t.progressEnd(), 0.6f));
        Estimate.Range e = Estimate.forNextLevel(def, p);
        float ry = in.y() + 60;
        if (p.need > 0) row(ui, in, ry, Lang.t("progress.current_xp"), Lang.num(p.xp) + " / " + Lang.compact(p.need));
        else row(ui, in, ry, Lang.t("progress.current_xp"), maxed ? "—" : Lang.percent(p.frac()));
        row(ui, in, ry + 13, Lang.t("progress.xp_remaining"), maxed || p.need <= 0 ? "—" : Lang.num(p.remaining()));
        String estLabel = Lang.t(def.unit() == StatDef.Unit.BOSSES ? "progress.est_bosses" : "progress.est_time");
        row(ui, in, ry + 26, estLabel + (e != null && e.preliminary() ? " *" : ""), e == null ? "—" : e.format());
        if (e != null && e.preliminary() && ui.hover(new Rect(in.x(), ry + 24, in.w(), 12))) ui.tooltip(Lang.t("progress.preliminary", e.samples()));
        if (def.unit() == StatDef.Unit.BOSSES) row(ui, in, ry + 39, Lang.t("progress.bosses_recorded"), Lang.num(p.count));
        else if (!p.gains.isEmpty()) row(ui, in, ry + 39, Lang.t("progress.last_gain"), "+" + Lang.num(p.lastGain()));
        float barsH = Math.min(26, in.bottom() - (ry + 56));
        if (withRecent && barsH > 12) recentBars(ui, "sc#rb" + def.id(), new Rect(in.x(), in.bottom() - barsH, in.w(), barsH), p, 16, c);
    }

    // ------------------------------------------------------------------ skills
    private float skills(Ui ui, Rect area, boolean tracking) {
        Gfx g = ui.g;
        Theme t = ui.t;
        trackingBanner(ui, area, "skills", tracking);
        ProfileData d = Progress.view();
        List<StatDef> defs = new ArrayList<>(StatDef.inCategory("skills"));
        defs.sort(Comparator.comparing((StatDef x) -> !d.peek(x.id()).known()).thenComparing(x -> -d.peek(x.id()).updated));
        float y = area.y() + (tracking ? 0 : 34);
        Rect card = new Rect(area.x(), y, area.w(), 40 + defs.size() * 34);
        ui.card(card);
        g.text(Lang.t("cat.skills"), card.x() + 16, card.y() + 16, t.text(), 1.3f, true);
        g.textRight(Lang.t("progress.skills_hint"), card.right() - 16, card.y() + 18, t.textMute(), 0.85f, false);
        float ry = card.y() + 38;
        for (int i = 0; i < defs.size(); i++) {
            StatDef def = defs.get(i);
            StatProgress p = d.peek(def.id());
            Rect row = new Rect(card.x() + 8, ry, card.w() - 16, 32);
            boolean hov = ui.hover(row);
            float h = ui.hoverAnim("skr" + def.id(), hov);
            if (h > 0) g.roundRect(row.x(), row.y(), row.w(), row.h(), ui.radius(), Col.alpha(t.surface(), h));
            int c = t.listBar();
            Widgets.iconChip(ui, new Rect(row.x() + 8, row.y() + 7, 18, 18), def.icon(), p.known() ? t.textDim() : t.textMute());
            g.text(Lang.t(def.nameKey()), row.x() + 34, row.y() + 7, p.known() ? t.text() : t.textDim(), 1f, true);
            float barX = row.x() + Math.max(130, row.w() * 0.3f), barW = row.w() * 0.3f;
            if (p.known()) {
                boolean maxed = p.need <= 0 && p.percent < 0;
                Widgets.statusDot(ui, row.x() + 34, row.y() + 20.5f, p);
                g.text(Lang.t("progress.lvl", p.level) + (maxed ? "" : " → " + (p.level + 1)), row.x() + 44, row.y() + 19, t.textDim(), 0.85f);
                ui.progress("skp" + def.id(), new Rect(barX, row.cy() - 2.5f, barW, 5), maxed ? 1 : p.frac(), c, Col.mix(c, t.progressEnd(), 0.5f));
                g.text(maxed ? Lang.t("progress.maxed") : Lang.percent(p.frac()), barX + barW + 8, row.cy() - 4, t.text(), 0.95f);
                Estimate.Range e = Estimate.forNextLevel(def, p);
                String right = maxed ? "" : p.need > 0 ? Lang.num(p.xp) + " / " + Lang.compact(p.need) + " XP" : "";
                g.textRight(right, row.right() - 8, row.y() + 7, t.text(), 0.9f, false);
                String sub = p.gains.isEmpty() ? "" : Lang.t("progress.last_gain_short", Lang.dec(p.lastGain()));
                if (e != null) sub = (sub.isEmpty() ? "" : sub + " · ") + Lang.t("progress.eta", e.format());
                g.textRight(sub, row.right() - 8, row.y() + 19, t.textMute(), 0.85f, false);
                if (hov) ui.tooltip(Lang.t(def.nameKey()) + " " + p.level + "\n" + (p.need > 0 ? Lang.t("progress.xp_left_short", Lang.num(p.remaining())) : "")
                        + "\n" + sourceLabel(p) + (e == null && !maxed ? "\n" + Lang.t("progress.eta_tip") : ""));
            } else {
                g.text(Lang.t("progress.skill_sync"), row.x() + 34, row.y() + 19, t.textMute(), 0.85f);
            }
            if (hov && ui.iconButton("sk#cal" + def.id(), new Rect(row.right() - 26, row.y() + 6, 20, 20), Icon.CALIBRATE, Lang.t("action.calibrate"))) calibrate.show(ui, def);
            ry += 34;
        }
        return card.bottom() - area.y();
    }

    /** One skill with a big card + its 7-day XP chart (Foraging tab). */
    private float singleSkill(Ui ui, Rect area, boolean tracking, String cat, String statId) {
        trackingBanner(ui, area, cat, tracking);
        float y = area.y() + (tracking ? 0 : 34);
        statCard(ui, new Rect(area.x(), y, area.w(), 168), StatDef.get(statId), 3, true);
        y += 180;
        return chart7d(ui, new Rect(area.x(), y, area.w(), 190), cat, Lang.t(cat + ".chart"), 4) - area.y();
    }

    private float chart7d(Ui ui, Rect chart, String group, String title, int colorIdx) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ui.card(chart);
        g.text(title, chart.x() + 16, chart.y() + 16, t.text(), 1.3f, true);
        SessionLog.Range range = SessionLog.range(SessionLog.Period.WEEK);
        double[] s = SessionLog.series(range, group);
        boolean any = false;
        for (double v : s) any |= v > 0;
        Rect plot = new Rect(chart.x() + 16, chart.y() + 40, chart.w() - 32, chart.h() - 54);
        if (!any) Widgets.empty(ui, "c7#" + group, plot, StatDef.categoryIcon(group), Lang.t("chart.no_data"), Lang.t("chart.no_data_desc"), null);
        else Widgets.lineChart(ui, "c7#chart" + group, plot, new Widgets.Series[]{new Widgets.Series(s, t.chart(colorIdx), true, false, Lang.t("group." + group))},
                i -> SessionsPage.dayLabel(range, i), Lang::compact, 1);
        return chart.bottom();
    }

    // ------------------------------------------------------------------ bestiary
    private float bestiary(Ui ui, Rect area) {
        Gfx g = ui.g;
        Theme t = ui.t;
        StatDef def = StatDef.get("bestiary");
        StatProgress p = Progress.view().peek("bestiary");
        Rect card = new Rect(area.x(), area.y(), area.w(), 200);
        ui.card(card);
        float y = cardHeader(ui, card, Lang.t("stat.bestiary"), "bestiary", Icon.BESTIARY);
        Rect in = new Rect(card.x() + 16, y, card.w() - 32, card.bottom() - y - 14);
        if (!p.known()) {
            if (Widgets.empty(ui, "best#cal", in, Icon.BESTIARY, Lang.t("progress.not_calibrated"), Lang.t("bestiary.desc"), Lang.t("action.calibrate"))) calibrate.show(ui, def);
            return card.bottom() - area.y();
        }
        boolean knownNeed = p.need > 0;
        Widgets.levelHeader(ui, "best", in.withH(70), p.level, p.level + 1, knownNeed ? p.frac() : 0, false);
        float ty = in.y() + 76;
        Rect[] tiles = new Rect(in.x(), ty, in.w(), 50).columns(3, 10);
        Widgets.statTile(ui, tiles[0], Lang.t("bestiary.to_next"), knownNeed ? Lang.num(p.xp) + " / " + Lang.num(p.need) : "—",
                knownNeed ? Lang.t("bestiary.family_tiers") : Lang.t("bestiary.open_menu"), 0);
        Widgets.statTile(ui, tiles[1], Lang.t("bestiary.remaining"), knownNeed ? Lang.num(Math.max(0, p.need - p.xp)) : "—", Lang.t("bestiary.family_tiers"), 0);
        Widgets.statTile(ui, tiles[2], Lang.t("bestiary.tierups"), Lang.num(p.count), Lang.t("bestiary.recorded"), 0);
        if (ui.link("best#cal2", headerActionsX - g.width(Lang.t("action.calibrate")) - 12, card.y() + 20, Lang.t("action.calibrate"), Icon.CALIBRATE)) calibrate.show(ui, def);
        float y2 = card.bottom() + 8;
        for (String l : g.wrap(Lang.t("bestiary.limit"), area.w(), 0.85f)) { g.text(l, area.x(), y2, t.textMute(), 0.85f); y2 += 10; }
        return y2 - area.y();
    }

    // ------------------------------------------------------------------ mining
    private float mining(Ui ui, Rect area, boolean tracking) {
        Gfx g = ui.g;
        Theme t = ui.t;
        trackingBanner(ui, area, "mining", tracking);
        float y = area.y() + (tracking ? 0 : 34);
        Rect[] cols = new Rect(area.x(), y, area.w(), 168).columns(area.w() >= 460 ? 2 : 1, 12);
        statCard(ui, cols[0], StatDef.get("skill_mining"), 0, true);
        if (cols.length > 1) statCard(ui, cols[1], StatDef.get("hotm"), 1, false);
        y += 180;
        if (cols.length == 1) { statCard(ui, new Rect(area.x(), y, area.w(), 168), StatDef.get("hotm"), 1, false); y += 180; }
        // powder (read from the sidebar / tab list while in mining areas)
        String[] powders = {"mithril", "gemstone", "glacite"};
        Rect[] pt = new Rect(area.x(), y, area.w(), 52).columns(3, 10);
        for (int i = 0; i < powders.length; i++) {
            ProfileData.ValueRecord v = Progress.view().value("powder_" + powders[i]);
            Widgets.statTile(ui, pt[i], Lang.t("powder." + powders[i]), v == null ? "—" : Lang.num(v.value),
                    v == null ? Lang.t("powder.unseen") : Lang.t("powder.seen", Widgets.ago(v.updated)), 0);
        }
        y += 62;
        // HOTM tier ladder (requirements from the XP table)
        StatProgress h = Progress.view().peek("hotm");
        Rect lad = new Rect(area.x(), y, area.w(), 96);
        ui.card(lad);
        g.text(Lang.t("hotm.tiers"), lad.x() + 16, lad.y() + 14, t.text(), 1.15f, true);
        Rect[] tiers = new Rect(lad.x() + 16, lad.y() + 38, lad.w() - 32, 44).columns(10, 6);
        long cum = 0;
        for (int i = 0; i < 10; i++) {
            Rect r = tiers[i];
            boolean done = h.known() && h.level >= i + 1;
            boolean cur = h.known() && h.level == i;
            int c = done ? t.success() : cur ? t.primary() : t.textMute();
            g.roundRect(r.x(), r.y(), r.w(), r.h(), Math.min(ui.radius(), 6), Col.alpha(c, done ? 0.18f : cur ? 0.14f : 0.06f));
            if (cur) g.roundOutline(r.x(), r.y(), r.w(), r.h(), Math.min(ui.radius(), 6), 1f / g.s * 2, Col.alpha(t.primary(), 0.7f));
            g.textCentered(String.valueOf(i + 1), r.cx(), r.y() + 8, done || cur ? t.text() : t.textDim(), 1.1f, true);
            if (i > 0) cum += XpTables.HOTM[i - 1];
            g.textCentered(i == 0 ? "0" : Lang.compact(cum), r.cx(), r.y() + 28, t.textMute(), 0.75f, false);
            if (ui.hover(r)) ui.tooltip(Lang.t("hotm.tier", i + 1) + "\n" + Lang.t("hotm.total_xp", Lang.num(cum)));
        }
        y += 108;
        return chart7d(ui, new Rect(area.x(), y, area.w(), 190), "mining", Lang.t("mining.chart"), 3) - area.y();
    }

    // ------------------------------------------------------------------ collections
    private float collections(Ui ui, Rect area, boolean tracking) {
        Gfx g = ui.g;
        Theme t = ui.t;
        trackingBanner(ui, area, "collections", tracking);
        ProfileData d = Progress.view();
        var map = d.collections;
        float y = area.y() + (tracking ? 0 : 34);
        if (map.isEmpty()) {
            Rect card = new Rect(area.x(), y, area.w(), 170);
            ui.card(card);
            Widgets.empty(ui, "col#e", card, Icon.COLLECTIONS, Lang.t("collections.empty"), Lang.t("collections.empty_desc"), null);
            return card.bottom() - area.y();
        }
        int cols = area.w() >= 600 ? 3 : area.w() >= 380 ? 2 : 1;
        float gap = 10, cw = (area.w() - gap * (cols - 1)) / cols, ch = 66;
        int i = 0;
        List<String> names = new ArrayList<>(map.keySet());
        names.sort(Comparator.comparingLong((String n) -> { var cp = d.collectionProgress.get(n); return cp == null ? 0 : -cp.updated; }));
        for (String name : names) {
            int level = map.get(name);
            ProfileData.CollectionProgress cp = d.collectionProgress.get(name);
            Rect r = new Rect(area.x() + (i % cols) * (cw + gap), y + (i / cols) * (ch + gap), cw, ch);
            ui.card(r, false, "col" + name);
            int c = t.listBar();
            Widgets.iconChip(ui, new Rect(r.x() + 10, r.y() + 10, 16, 16), Icon.COLLECTIONS, t.textDim());
            g.text(g.ellipsize(name, cw - 90, 1f), r.x() + 32, r.y() + 14, t.text(), 1f, true);
            g.textRight(Lang.t("progress.lvl", level), r.right() - 10, r.y() + 14, t.textDim(), 0.9f, false);
            boolean exact = cp != null && cp.level == level && (cp.need > 0 || cp.percent >= 0);
            if (exact) {
                ui.progress("colp" + name, new Rect(r.x() + 10, r.y() + 34, r.w() - 20, 5), cp.frac(), c, Col.mix(c, t.progressEnd(), 0.5f));
                String txt = cp.need > 0 ? Lang.t("collections.to_next", Lang.compact(cp.current), Lang.compact(cp.need), level + 1) : Lang.percent(cp.frac());
                g.text(g.ellipsize(txt, r.w() - 20, 0.85f), r.x() + 10, r.y() + 46, t.textMute(), 0.85f);
                if (ui.hover(r) && cp.need > 0) ui.tooltip(Lang.t("collections.remaining", Lang.num(Math.max(0, cp.need - cp.current))) + "\n" + Widgets.ago(cp.updated));
            } else {
                g.text(Lang.t("collections.open_menu"), r.x() + 10, r.y() + 40, t.textMute(), 0.85f);
            }
            i++;
        }
        int rows = (names.size() + cols - 1) / cols;
        float ny = y + rows * (ch + gap) + 2;
        for (String l : g.wrap(Lang.t("collections.info"), area.w(), 0.85f)) { g.text(l, area.x(), ny, t.textMute(), 0.85f); ny += 10; }
        return ny - area.y() + 4;
    }
}
