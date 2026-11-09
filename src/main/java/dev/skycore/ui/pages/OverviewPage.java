package dev.skycore.ui.pages;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.skycore.core.Config;
import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.data.Evidence;
import dev.skycore.data.Goals;
import dev.skycore.data.ProfileData;
import dev.skycore.data.ProfileData.StatProgress;
import dev.skycore.data.Progress;
import dev.skycore.data.Rates;
import dev.skycore.data.SessionLog;
import dev.skycore.data.StatDef;
import dev.skycore.tracking.HypixelTracker;
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

/**
 * Central hub: a modular grid of cards the user can rearrange, hide, add and resize.
 * <p>
 * Hierarchy of the default layout: the Catacombs level and XP bar are the centrepiece (the only large use of the XP
 * colour besides the chart), XP per hour is the one analytics chart, everything else is compact and neutral.
 * Cards in one row share one height; card titles are quiet captions, not icon-and-heading pairs.
 */
public class OverviewPage implements Page {
    private boolean editing;
    private String dragging;

    @Override public String id() { return "overview"; }
    @Override public Icon icon() { return Icon.OVERVIEW; }

    private static float cardHeight(String id) {
        return switch (id) {
            case "catacombs" -> 176;
            case "xp_chart" -> 208;
            case "categories" -> 176;
            case "activity", "skills", "slayer" -> 168;
            default -> 148;
        };
    }

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        Gfx g = ui.g;
        Theme t = ui.t;
        List<Config.CardState> cards = Skycore.config().overview;
        float y = area.y() + 6;
        // toolbar: profile + tracking state on the left, layout controls on the right
        String name = Progress.playerName();
        g.text(name, area.x(), y + 5, t.text(), 1.1f, Weight.SEMIBOLD);
        trackingState(ui, area.x() + g.width(name, 1.1f, Weight.SEMIBOLD) + 14, y + 6, screen);
        float bx = area.right();
        float bw = g.width(Lang.t(editing ? "overview.done" : "overview.customize")) + 30;
        bx -= bw;
        if (ui.button("ov#edit", new Rect(bx, y, bw, 20), Lang.t(editing ? "overview.done" : "overview.customize"), editing ? Icon.CHECK : Icon.EDIT,
                editing ? Ui.Style.PRIMARY : Ui.Style.SECONDARY)) { editing = !editing; dragging = null; }
        if (editing) {
            List<String> hidden = new ArrayList<>();
            for (Config.CardState c : cards) if (!c.visible) hidden.add(c.id);
            float rw = g.width(Lang.t("overview.reset")) + 30;
            bx -= rw + 6;
            if (ui.button("ov#reset", new Rect(bx, y, rw, 20), Lang.t("overview.reset"), Icon.RESET, Ui.Style.GHOST)) {
                cards.clear();
                Skycore.config().normalize();
                Skycore.markDirty();
            }
            if (!hidden.isEmpty()) {
                String[] opts = new String[hidden.size() + 1];
                opts[0] = Lang.t("overview.add_card");
                for (int i = 0; i < hidden.size(); i++) opts[i + 1] = Lang.t("card." + hidden.get(i));
                bx -= 156;
                int sel = ui.dropdown("ov#add", new Rect(bx, y, 150, 20), opts, 0);
                if (sel > 0) {
                    for (Config.CardState c : cards) if (c.id.equals(hidden.get(sel - 1))) c.visible = true;
                    Skycore.markDirty();
                }
            }
        }
        y += 32;
        if (editing) {
            g.text(Lang.t("overview.edit_hint"), area.x(), y, t.textDim(), 0.9f);
            y += 14;
        }
        // grid layout
        int cols = area.w() >= 660 ? 3 : area.w() >= 400 ? 2 : 1;
        float gap = 12;
        float cw = (area.w() - gap * (cols - 1)) / cols;
        // dense packing: when a card doesn't fit the rest of a row, pull up the next card that does (no holes)
        List<Config.CardState> queue = new ArrayList<>(cards.stream().filter(c -> c.visible).toList());
        List<Config.CardState> visible = new ArrayList<>();
        int fill = 0;
        while (!queue.isEmpty()) {
            Config.CardState pick = null;
            for (Config.CardState q : queue) if (fill + Math.min(cols, Math.max(1, q.span)) <= cols) { pick = q; break; }
            if (pick == null) { fill = 0; continue; }
            queue.remove(pick);
            visible.add(pick);
            fill += Math.min(cols, Math.max(1, pick.span));
            if (fill >= cols) fill = 0;
        }
        // rows: every card in a row gets the row's height so edges line up
        List<List<Config.CardState>> rows = new ArrayList<>();
        List<Config.CardState> row = new ArrayList<>();
        int used = 0;
        for (Config.CardState c : visible) {
            int span = Math.min(cols, Math.max(1, c.span));
            if (used + span > cols) { rows.add(row); row = new ArrayList<>(); used = 0; }
            row.add(c);
            used += span;
        }
        if (!row.isEmpty()) rows.add(row);

        Rect dragRect = null;
        Config.CardState dropTarget = null;
        for (List<Config.CardState> rw : rows) {
            float rowH = 0;
            for (Config.CardState c : rw) rowH = Math.max(rowH, cardHeight(c.id));
            int col = 0;
            for (Config.CardState c : rw) {
                int span = Math.min(cols, Math.max(1, c.span));
                Rect r = new Rect(area.x() + col * (cw + gap), y, cw * span + gap * (span - 1), rowH);
                if (c.id.equals(dragging)) {
                    g.roundOutline(r.x(), r.y(), r.w(), r.h(), t.radius + 2, 1.5f / g.s * 2, Col.alpha(t.text(), 0.5f));
                    g.roundRect(r.x(), r.y(), r.w(), r.h(), t.radius + 2, Col.alpha(t.text(), 0.04f));
                    dragRect = r;
                } else {
                    if (dragging != null && ui.hover(r)) dropTarget = c;
                    drawCard(ui, c, r, dragging != null && dropTarget == c);
                }
                col += span;
            }
            y += rowH + gap;
        }
        if (!rows.isEmpty()) y -= gap;
        // drag & drop
        if (dragging != null) {
            Config.CardState dragged = cards.stream().filter(c -> c.id.equals(dragging)).findFirst().orElse(null);
            if (dragged != null && dragRect != null) {
                Rect ghost = new Rect(ui.mx - 40, ui.my - 12, Math.min(dragRect.w(), 260), 60);
                final Rect gr = ghost;
                ui.overlay(() -> {
                    ui.g.glow(gr.x(), gr.y(), gr.w(), gr.h(), 14, Col.alpha(0xFF000000, 0.4f));
                    ui.card(gr, true, null);
                    ui.g.icon(cardIcon(dragged.id), gr.x() + 12, gr.y() + 12, 12, ui.t.textDim());
                    ui.g.text(Lang.t("card." + dragged.id), gr.x() + 30, gr.y() + 14, ui.t.text(), 1f, true);
                    ui.g.text(Lang.t("overview.drop_hint"), gr.x() + 12, gr.y() + 36, ui.t.textDim(), 0.85f);
                });
                ui.setCursor(CursorTypes.RESIZE_ALL);
            }
            if (!ui.mouseDown()) {
                if (dropTarget != null && dragged != null) {
                    int to = cards.indexOf(dropTarget);
                    cards.remove(dragged);
                    cards.add(Math.max(0, Math.min(cards.size(), to)), dragged);
                    Skycore.markDirty();
                }
                dragging = null;
            }
        }
        return y - area.y() + 8;
    }

    /** Compact data-collection status as plain text with a state mark: what SKYCORE is doing and when it last recognised something. */
    private void trackingState(Ui ui, float x, float y, SkycoreScreen screen) {
        Gfx g = ui.g;
        Theme t = ui.t;
        var cfg = Skycore.config();
        dev.skycore.data.History.Event last = dev.skycore.data.History.lastEvent();
        String state;
        boolean live = false;
        if (Progress.isSample()) state = Lang.t("track.sample");
        else if (!cfg.trackingEnabled) state = Lang.t("track.paused");
        else if (HypixelTracker.onSkyBlock()) { state = Lang.t("track.active"); live = true; }
        else state = Lang.t("track.waiting");
        String detail = last == null || Progress.isSample() ? "" : " · " + Widgets.ago(last.t);
        String label = state + detail;
        float w = g.width(label, 0.9f, Weight.MEDIUM) + 14;
        Rect r = new Rect(x, y - 3, w, 14);
        boolean hov = ui.hover(r);
        if (live) g.circle(r.x() + 3, r.cy(), 2.6f, t.success()); else g.ringOutline(r.x() + 3, r.cy(), 2.6f, 1.2f, t.textMute());
        g.text(label, r.x() + 11, r.y() + 2, hov ? t.text() : t.textDim(), 0.9f, Weight.MEDIUM);
        if (hov) {
            ui.setCursor(CursorTypes.POINTING_HAND);
            ui.tooltip(Lang.t("track.tip") + (last == null ? "" : "\n" + Lang.t("track.last", Lang.t("event." + last.kind), Widgets.ago(last.t))));
        }
        if (ui.pressedOn(r)) {
            Ui.clickSound();
            cfg.expanded.put("set.datatrack", true);
            screen.openPage(screen.pageIndex(SettingsPage.class));
        }
    }

    private static Icon cardIcon(String id) {
        return switch (id) {
            case "profile" -> Icon.PROFILE;
            case "catacombs" -> Icon.DUNGEONS;
            case "session" -> Icon.CLOCK;
            case "xp_chart" -> Icon.SESSIONS;
            case "categories" -> Icon.CHART_BAR;
            case "skills" -> Icon.SKILLS;
            case "slayer" -> Icon.SLAYER;
            case "goals" -> Icon.GOALS;
            case "mining" -> Icon.MINING;
            case "foraging" -> Icon.FORAGING;
            case "bestiary" -> Icon.BESTIARY;
            default -> Icon.LIST;
        };
    }

    private void drawCard(Ui ui, Config.CardState c, Rect r, boolean dropHighlight) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ui.card(r, dropHighlight, "ovc" + c.id);
        Rect in = r.inset(16, 14);
        // the card title is a quiet caption; the figure underneath is what the eye should land on
        g.text(Lang.t("card." + c.id), in.x(), in.y(), t.textDim(), 0.9f, Weight.MEDIUM);
        if (editing) {
            float bx = r.right() - 30;
            if (ui.iconButton("ovc#hide" + c.id, new Rect(bx, r.y() + 8, 20, 20), Icon.EYE_OFF, Lang.t("overview.hide"))) { c.visible = false; Skycore.markDirty(); }
            bx -= 22;
            if (ui.iconButton("ovc#size" + c.id, new Rect(bx, r.y() + 8, 20, 20), c.span > 1 ? Icon.MINUS : Icon.PLUS, Lang.t("overview.resize"))) {
                c.span = c.span > 1 ? 1 : 2;
                Skycore.markDirty();
            }
            bx -= 22;
            Rect handle = new Rect(bx, r.y() + 8, 20, 20);
            boolean hh = ui.hover(handle);
            if (hh) { ui.setCursor(CursorTypes.RESIZE_ALL); ui.tooltip(Lang.t("overview.drag")); }
            g.roundRect(handle.x(), handle.y(), handle.w(), handle.h(), 5, hh ? t.surfaceHover() : Col.alpha(t.surface(), 0.6f));
            g.icon(Icon.DRAG, handle.x() + 4, handle.y() + 4, 12, hh ? t.text() : t.textDim());
            if (ui.pressedOn(handle)) dragging = c.id;
            return;
        }
        if (c.id.equals("xp_chart") || c.id.equals("categories"))
            g.textRight(Lang.t("chart.seven_days"), in.right(), in.y(), t.textMute(), 0.85f, Weight.REGULAR);
        Rect body = new Rect(in.x(), in.y() + 22, in.w(), in.h() - 22);
        switch (c.id) {
            case "profile" -> profile(ui, body);
            case "catacombs" -> catacombs(ui, body);
            case "session" -> session(ui, body);
            case "xp_chart" -> xpChart(ui, body);
            case "categories" -> categories(ui, body);
            case "skills" -> statList(ui, body, "skills", "ov#sk", 5);
            case "slayer" -> statList(ui, body, "slayer", "ov#sl", 4);
            case "goals" -> goals(ui, body);
            case "mining" -> mining(ui, body);
            case "foraging" -> foraging(ui, body);
            case "bestiary" -> bestiary(ui, body);
            case "activity" -> activity(ui, body);
            default -> {}
        }
    }

    // ------------------------------------------------------------------ card bodies
    private void profile(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ProfileData d = Progress.view();
        g.text(Progress.profileName(), r.x(), r.y(), t.text(), 1.5f, Weight.SEMIBOLD);
        boolean on = HypixelTracker.onSkyBlock();
        if (on) g.circle(r.x() + 3, r.y() + 25, 2.6f, t.success()); else g.ringOutline(r.x() + 3, r.y() + 25, 2.6f, 1.2f, t.textMute());
        g.text(Lang.t(on ? "status.online" : "status.offline"), r.x() + 11, r.y() + 21.5f, t.textDim(), 0.9f);
        long known = d.stats.values().stream().filter(StatProgress::known).count();
        Rect figs = new Rect(r.x(), r.bottom() - 34, r.w(), 34);
        ui.separator(r.x(), figs.y() - 10, r.w());
        Rect[] c = Widgets.figureColumns(ui, figs, 2);
        Widgets.statTile(ui, c[0], Lang.t("overview.tracked_stats"), String.valueOf(known), null, 0);
        Widgets.statTile(ui, c[1], Lang.t("overview.active_goals"), String.valueOf(Goals.activeCount()), null, 0);
    }

    private void catacombs(Ui ui, Rect r) {
        StatDef def = StatDef.get("catacombs");
        StatProgress p = Progress.view().peek("catacombs");
        if (!p.known()) {
            emptySmall(ui, r, Lang.t("empty.catacombs"));
            return;
        }
        Widgets.levelHero(ui, "ov#cata", r, def, p, false);
    }

    private void session(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        SessionLog.Range today = SessionLog.range(SessionLog.Period.TODAY);
        SessionLog.Totals tt = SessionLog.totals(today);
        SessionLog.Totals yt = SessionLog.totals(SessionLog.range(SessionLog.Period.YESTERDAY));
        g.text(Lang.compact(tt.xp()), r.x(), r.y(), t.text(), 2.2f, Weight.SEMIBOLD);
        String cap = Lang.t("overview.xp_today");
        g.text(cap, r.x(), r.y() + 26, t.textDim(), 0.9f, Weight.MEDIUM);
        float cx = r.x() + g.width(cap, 0.9f, Weight.MEDIUM) + 8;
        // XP gains are summed from recorded gain events, not read as a game total: estimated, never "observed"
        Widgets.evidenceMark(ui, cx, r.y() + 29.8f, Evidence.ESTIMATED);
        if (ui.hover(new Rect(cx - 4, r.y() + 24, 10, 12))) ui.tooltip(Widgets.evidenceTip(Evidence.ESTIMATED, Lang.t("overview.xp_today_tip")));
        if (yt.xp() > 0) {
            double pct = (tt.xp() - yt.xp()) / yt.xp() * 100;
            String s = (pct >= 0 ? "+" : "") + Lang.dec(pct) + "%";
            g.textRight(Lang.t("overview.vs_yesterday", s), r.right(), r.y() + 6, t.textDim(), 0.9f, Weight.MEDIUM);
        }
        double rate = Rates.perHour(tt.xp(), tt.playMillis() / 1000.0);
        Rect figs = new Rect(r.x(), r.bottom() - 34, r.w(), 34);
        ui.separator(r.x(), figs.y() - 10, r.w());
        Rect[] c = Widgets.figureColumns(ui, figs, 2);
        Widgets.statTile(ui, c[0], Lang.t("sessions.playtime"), Lang.duration(tt.playMillis()), null, 0, Evidence.ESTIMATED, Widgets.evidenceTip(Evidence.ESTIMATED, Lang.t("overview.playtime_tip")));
        Evidence rev = Double.isNaN(rate) ? Evidence.UNAVAILABLE : Evidence.ESTIMATED;
        Widgets.statTile(ui, c[1], Lang.t("sessions.xp_hour"), Double.isNaN(rate) ? "—" : Lang.compact(rate), null, 0, rev, Widgets.evidenceTip(rev, Lang.t("overview.rate_tip")));
    }

    private void xpChart(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        SessionLog.Range range = SessionLog.range(SessionLog.Period.WEEK);
        double[] s = SessionLog.rateSeries(range);
        boolean any = false;
        for (double v : s) any |= !Double.isNaN(v);
        if (!any) { emptySmall(ui, r, Lang.t("empty.sessions")); return; }
        SessionLog.Totals tt = SessionLog.totals(range);
        double avg = Rates.perHour(tt.xp(), tt.playMillis() / 1000.0);
        String value = Double.isNaN(avg) ? "—" : Lang.compact(avg);
        g.text(value, r.x(), r.y(), t.xp(), 2.0f, Weight.SEMIBOLD);
        String cap = Lang.t("overview.avg_per_hour");
        float cx = r.x() + g.width(value, 2.0f, Weight.SEMIBOLD) + 10;
        g.text(cap, cx, r.y() + 9, t.textDim(), 0.9f, Weight.MEDIUM);
        float mx = cx + g.width(cap, 0.9f, Weight.MEDIUM) + 8;
        Evidence ev = Double.isNaN(avg) ? Evidence.UNAVAILABLE : Evidence.ESTIMATED;
        Widgets.evidenceMark(ui, mx, r.y() + 12.8f, ev);
        if (ui.hover(new Rect(mx - 4, r.y() + 7, 10, 12))) ui.tooltip(Widgets.evidenceTip(ev, Lang.t("overview.rate_tip")));
        Rect plot = new Rect(r.x(), r.y() + 30, r.w(), r.h() - 30);
        Widgets.rateChart(ui, "ov#rate", plot, s, i -> SessionsPage.dayLabel(range, i), i -> SessionsPage.dayLabel(range, i), Lang::compact);
    }

    private void categories(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        SessionLog.Totals tt = SessionLog.totals(SessionLog.range(SessionLog.Period.WEEK));
        int n = StatDef.GROUPS.length;
        double[] vals = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) { vals[i] = tt.byGroup().getOrDefault(StatDef.GROUPS[i], 0d); sum += vals[i]; }
        if (sum <= 0) { emptySmall(ui, r, Lang.t("empty.sessions")); return; }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(vals[b], vals[a]));
        double max = vals[order[0]];
        float pitch = 24, y = r.y();
        int rows = (int) Math.min(5, Math.max(1, r.h() / pitch));
        for (int k = 0; k < rows && k < n; k++) {
            int idx = order[k];
            if (vals[idx] <= 0) break;
            g.text(Lang.t("group." + StatDef.GROUPS[idx]), r.x(), y, t.text(), 0.95f);
            g.textRight(Lang.percent(vals[idx] / sum), r.right(), y, t.textDim(), 0.9f, Weight.MEDIUM);
            // the leading category carries the XP colour, the rest stay neutral (other presets keep category colours)
            int c = k == 0 ? t.xp() : t.restrained ? Col.alpha(t.textDim(), 0.55f) : SessionsPage.groupColor(t, idx);
            ui.progress(null, new Rect(r.x(), y + 13, r.w(), 3), (float) (vals[idx] / max), c, c);
            y += pitch;
        }
    }

    private void statList(Ui ui, Rect r, String category, String id, int max) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ProfileData d = Progress.view();
        List<StatDef> defs = new ArrayList<>(StatDef.inCategory(category));
        defs.removeIf(def -> !d.peek(def.id()).known());
        defs.sort(Comparator.comparingLong((StatDef def) -> d.peek(def.id()).updated).reversed());
        if (defs.isEmpty()) { emptySmall(ui, r, Lang.t("empty." + category)); return; }
        float y = r.y();
        for (int i = 0; i < Math.min(max, defs.size()); i++) {
            StatDef def = defs.get(i);
            StatProgress p = d.peek(def.id());
            g.text(g.ellipsize(Lang.t(def.nameKey()), r.w() - 40, 0.95f), r.x(), y, t.text(), 0.95f);
            g.textRight(String.valueOf(p.level), r.right(), y, t.text(), 0.95f, Weight.SEMIBOLD);
            ui.progress(id + def.id(), new Rect(r.x(), y + 13, r.w(), 3), p.frac(), t.listBar(), t.listBar());
            y += 24;
        }
    }

    private void goals(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        List<Goals.Goal> list = Goals.all().stream().filter(x -> !x.paused && x.completed == 0).limit(4).toList();
        if (list.isEmpty()) { emptySmall(ui, r, Lang.t("empty.goals")); return; }
        float y = r.y();
        for (Goals.Goal goal : list) {
            StatDef def = StatDef.get(goal.stat);
            float f = Goals.fraction(goal);
            g.text(g.ellipsize(Lang.t("goals.reach", Lang.t(def.nameKey()), goal.target), r.w() - 40, 0.95f), r.x(), y, t.text(), 0.95f);
            g.textRight(Lang.percent(f), r.right(), y, t.textDim(), 0.9f, Weight.MEDIUM);
            int c = t.restrained ? t.listBar() : GoalsPage.goalColor(t, def);
            ui.progress("ov#g" + goal.id, new Rect(r.x(), y + 13, r.w(), 3), f, c, c);
            y += 24;
        }
    }

    private void mining(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ProfileData d = Progress.view();
        StatProgress m = d.peek("skill_mining"), h = d.peek("hotm");
        if (!m.known() && !h.known()) { emptySmall(ui, r, Lang.t("empty.mining")); return; }
        float y = r.y();
        String[] ids = {"skill_mining", "hotm"};
        for (int i = 0; i < 2; i++) {
            StatProgress p = d.peek(ids[i]);
            g.text(Lang.t("stat." + ids[i]), r.x(), y, t.text(), 0.95f);
            g.textRight(p.known() ? String.valueOf(p.level) : "—", r.right(), y, t.text(), 0.95f, Weight.SEMIBOLD);
            ui.progress("ov#m" + i, new Rect(r.x(), y + 13, r.w(), 3), p.frac(), t.listBar(), t.listBar());
            y += 30;
        }
    }

    private void foraging(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        StatProgress p = Progress.view().peek("skill_foraging");
        if (!p.known()) { emptySmall(ui, r, Lang.t("empty.foraging")); return; }
        g.text(String.valueOf(p.level), r.x(), r.y(), t.text(), 2.0f, Weight.SEMIBOLD);
        g.textRight(Lang.percent(p.frac()), r.right(), r.y() + 5, t.textDim(), 1.1f, Weight.MEDIUM);
        ui.progress("ov#for", new Rect(r.x(), r.y() + 25, r.w(), 4), p.frac(), t.listBar(), t.listBar());
        SessionLog.Totals wk = SessionLog.totals(SessionLog.range(SessionLog.Period.WEEK));
        Rect figs = new Rect(r.x(), r.bottom() - 34, r.w(), 34);
        ui.separator(r.x(), figs.y() - 10, r.w());
        Rect[] c = Widgets.figureColumns(ui, figs, 2);
        Widgets.statTile(ui, c[0], Lang.t("progress.xp_remaining"), p.need > 0 ? Lang.compact(p.remaining()) : "—", null, 0);
        Widgets.statTile(ui, c[1], Lang.t("overview.xp_week"), Lang.compact(wk.byGroup().getOrDefault("foraging", 0d)), null, 0);
    }

    private void bestiary(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        StatProgress p = Progress.view().peek("bestiary");
        if (!p.known()) { emptySmall(ui, r, Lang.t("empty.bestiary")); return; }
        g.text(String.valueOf(p.level), r.x(), r.y(), t.text(), 2.2f, Weight.SEMIBOLD);
        g.text(Lang.t("bestiary.milestone"), r.x(), r.y() + 25, t.textDim(), 0.9f);
        g.text(Lang.t("bestiary.next", p.level + 1), r.x(), r.bottom() - 12, t.textMute(), 0.85f);
    }

    private void activity(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        List<ProfileData.Activity> list = Progress.view().activity;
        if (list.isEmpty()) { emptySmall(ui, r, Lang.t("empty.activity")); return; }
        float y = r.y();
        int rows = (int) (r.h() / 22);
        for (int i = 0; i < Math.min(rows, list.size()); i++) {
            ProfileData.Activity a = list.get(i);
            Icon ic;
            try { ic = Icon.valueOf(a.icon); } catch (Exception e) { ic = Icon.SPARK; }
            g.icon(ic, r.x(), y + 2, 11, t.textMute());
            g.text(g.ellipsize(ActivityText.of(a), r.w() - 90, 0.95f), r.x() + 20, y + 4, t.text(), 0.95f);
            g.textRight(Widgets.ago(a.t), r.right(), y + 4, t.textMute(), 0.85f, false);
            y += 22;
        }
    }

    private void emptySmall(Ui ui, Rect r, String text) {
        var lines = ui.g.wrap(text, r.w() - 10, 0.9f);
        float y = r.cy() - lines.size() * 5 - 6;
        ui.g.icon(Icon.INFO, r.cx() - 6, y - 8, 12, ui.t.textMute());
        for (int i = 0; i < lines.size(); i++) ui.g.textCentered(lines.get(i), r.cx(), y + 10 + i * 10, ui.t.textDim(), 0.9f, false);
    }

    /** Localised activity line. */
    public static final class ActivityText {
        public static String of(ProfileData.Activity a) {
            Object[] args = new Object[a.args == null ? 0 : a.args.length];
            for (int i = 0; i < args.length; i++) args[i] = a.args[i] != null && a.args[i].startsWith("stat.") ? Lang.t(a.args[i]) : a.args[i];
            return Lang.t(a.key, args);
        }
    }
}
