package dev.skycore.ui.pages;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.skycore.core.Lang;
import dev.skycore.data.Estimate;
import dev.skycore.data.Goals;
import dev.skycore.data.ProfileData.StatProgress;
import dev.skycore.data.Progress;
import dev.skycore.data.StatDef;
import dev.skycore.ui.Page;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.Widgets;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/** User-defined progression goals that update automatically from tracked data. */
public class GoalsPage implements Page {
    private enum Dialog { NONE, CREATE, EDIT, DELETE }

    private Dialog dialog = Dialog.NONE;
    private Goals.Goal editing;
    private int catIdx, statIdx, target = 1;
    private String goalName = "";
    private int filter;
    private static final String[] GOAL_CATS = {"dungeons", "slayer", "skills", "foraging", "bestiary", "mining"};

    @Override public String id() { return "goals"; }
    @Override public Icon icon() { return Icon.GOALS; }

    public static int goalColor(Theme t, StatDef def) {
        return switch (def.category()) {
            case "dungeons" -> t.chart(0);
            case "mining" -> t.success();
            case "bestiary" -> t.warning();
            case "slayer" -> t.danger();
            case "skills" -> def.id().equals("skill_mining") ? t.success() : t.chart(1);
            default -> t.chart(2);
        };
    }

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        Gfx g = ui.g;
        Theme t = ui.t;
        float y = area.y() + 6;
        g.text(Lang.t("goals.title"), area.x(), y + 4, t.text(), 1.3f, true);
        long active = Goals.activeCount();
        Widgets.badge(ui, area.x() + g.width(Lang.t("goals.title"), 1.3f, dev.skycore.ui.gfx.Gfx.Weight.SEMIBOLD) + 10, y + 2, Lang.t("goals.active_n", active), t.textDim());
        String nl = Lang.t("goals.new");
        float bw = g.width(nl) + 30;
        if (ui.button("goal#new", new Rect(area.right() - bw, y - 2, bw, 20), nl, Icon.PLUS, Ui.Style.PRIMARY)) openCreate(ui);
        y += 28;
        String[] filters = {Lang.t("goals.filter.all"), Lang.t("goals.filter.active"), Lang.t("goals.filter.paused"), Lang.t("goals.filter.completed")};
        filter = ui.segmented("goal#filter", new Rect(area.x(), y, Math.min(380, area.w()), 22), filters, filter);
        y += 32;

        List<Goals.Goal> open = new ArrayList<>(), done = new ArrayList<>();
        for (Goals.Goal goal : Goals.all()) {
            boolean completed = goal.completed != 0;
            if (completed && (filter == 0 || filter == 3)) done.add(goal);
            else if (!completed && (filter == 0 || (filter == 1 && !goal.paused) || (filter == 2 && goal.paused))) open.add(goal);
        }
        int cols = area.w() >= 520 ? 2 : 1;
        float gap = 12, cw = (area.w() - gap * (cols - 1)) / cols, ch = 132;
        int i = 0;
        for (Goals.Goal goal : open) {
            Rect r = new Rect(area.x() + (i % cols) * (cw + gap), y + (i / cols) * (ch + gap), cw, ch);
            goalCard(ui, r, goal);
            i++;
        }
        if (filter <= 1) { // the create card belongs with active goals
            Rect cr = new Rect(area.x() + (i % cols) * (cw + gap), y + (i / cols) * (ch + gap), cw, ch);
            createCard(ui, cr);
            i++;
        } else if (open.isEmpty() && done.isEmpty()) {
            Rect er = new Rect(area.x(), y, area.w(), 130);
            ui.card(er);
            Widgets.empty(ui, "goal#empty", er, filter == 2 ? Icon.PAUSE : Icon.TROPHY, Lang.t(filter == 2 ? "goals.none_paused" : "goals.none_completed"),
                    Lang.t(filter == 2 ? "goals.none_paused_desc" : "goals.none_completed_desc"), null);
            y += 142;
        }
        y += ((i + cols - 1) / cols) * (ch + gap);
        if (!done.isEmpty()) {
            y += 6;
            Widgets.sectionTitle(ui, area.x(), y, Lang.t("goals.completed"));
            y += 20;
            for (Goals.Goal goal : done) {
                Rect r = new Rect(area.x(), y, area.w(), 30);
                ui.tile(r);
                StatDef def = StatDef.get(goal.stat);
                Widgets.iconChip(ui, new Rect(r.x() + 8, r.y() + 7, 16, 16), Icon.TROPHY, t.success());
                g.text(g.ellipsize(Goals.displayName(goal), r.w() - 160, 1f), r.x() + 32, r.y() + 11, t.text());
                g.textRight(Widgets.ago(goal.completed), r.right() - 34, r.y() + 11, t.textMute(), 0.9f, false);
                if (!Progress.isSample() && ui.iconButton("goal#del" + goal.id, new Rect(r.right() - 26, r.y() + 5, 20, 20), Icon.TRASH, Lang.t("action.delete"))) Goals.delete(goal);
                y += 34;
            }
        }
        dialogs(ui, screen);
        return y - area.y();
    }

    private void goalCard(Ui ui, Rect r, Goals.Goal goal) {
        Gfx g = ui.g;
        Theme t = ui.t;
        StatDef def = StatDef.get(goal.stat);
        StatProgress p = Progress.view().peek(goal.stat);
        int c = goalColor(t, def);
        ui.card(r, false, "gc" + goal.id);
        float a = goal.paused ? 0.55f : 1f;
        float prev = g.alpha;
        g.alpha *= a;
        Rect in = r.inset(16, 14);
        g.text(g.ellipsize(Goals.displayName(goal), (in.w() - 80) / 1.15f, 1f), in.x(), in.y() + 2, t.text(), 1.15f, true);
        double cur = Goals.current(goal);
        String curS = cur < 0 ? "?" : (def.table() == null ? String.valueOf((int) cur) : Lang.dec(Math.floor(cur * 10) / 10));
        g.text(curS + " / " + goal.target, in.x(), in.y() + 24, t.text(), 2f, true);
        float f = Goals.fraction(goal);
        ui.progress("gp" + goal.id, new Rect(in.x(), in.y() + 52, in.w(), 7), f, c, Col.mix(c, t.progressEnd(), 0.35f));
        g.text(Lang.t("goals.pct_done", Math.round(f * 100)), in.x(), in.y() + 66, t.textDim(), 0.9f);
        double rem = Goals.xpRemaining(goal);
        String info;
        if (cur < 0) info = Lang.t("goals.unknown");
        else if (rem > 0) {
            Estimate.Range e = Estimate.forXp(def, p, rem);
            info = Lang.t("goals.xp_to_go", Lang.compact(rem)) + (e != null ? " · " + e.format() + " " + e.unitLabel() : "");
        } else info = "";
        g.text(g.ellipsize(info, in.w() * 0.65f, 0.85f), in.x(), in.y() + 80, t.textMute(), 0.85f);
        if (!Progress.isSample()) g.textRight(Lang.t("goals.created", Widgets.ago(goal.created)), in.right(), in.y() + 80, t.textMute(), 0.85f, false);
        if (cur >= 0 && f >= 1) Widgets.badge(ui, in.right() - g.width(Lang.t("goals.reached"), 0.85f) - 12, in.y() + 62, Lang.t("goals.reached"), t.success());
        if (goal.paused) Widgets.badge(ui, in.right() - g.width(Lang.t("goals.paused"), 0.85f) - 12, in.y() + 24, Lang.t("goals.paused"), t.textDim());
        g.alpha = prev;
        // icon & actions
        float bx = r.right() - 30;
        if (!Progress.isSample()) {
            if (ui.iconButton("goal#d" + goal.id, new Rect(bx, r.y() + 10, 20, 20), Icon.TRASH, Lang.t("action.delete"))) { editing = goal; dialog = Dialog.DELETE; ui.resetModalAnim(); }
            bx -= 22;
            if (ui.iconButton("goal#e" + goal.id, new Rect(bx, r.y() + 10, 20, 20), Icon.EDIT, Lang.t("action.edit"))) { editing = goal; target = goal.target; goalName = goal.name; dialog = Dialog.EDIT; ui.resetModalAnim(); }
            bx -= 22;
            if (ui.iconButton("goal#p" + goal.id, new Rect(bx, r.y() + 10, 20, 20), goal.paused ? Icon.PLAY : Icon.PAUSE, Lang.t(goal.paused ? "action.resume" : "action.pause"))) {
                goal.paused = !goal.paused;
                Goals.changed();
            }
        } else g.icon(def.icon(), r.right() - 30, r.y() + 14, 14, c);
    }

    private void createCard(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        boolean hov = ui.hover(r);
        float h = ui.hoverAnim("goal#create", hov);
        if (hov) ui.setCursor(CursorTypes.POINTING_HAND);
        float rad = t.radius + 2;
        g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, Col.alpha(t.surface(), 0.25f + 0.35f * h));
        // dashed border
        int bc = Col.mix(t.border(), t.primary(), h);
        float d = 1f / g.s * Math.max(1, Math.round(g.s / 2));
        for (float x = r.x() + rad; x < r.right() - rad; x += 8) { g.rect(x, r.y(), 4, d, bc); g.rect(x, r.bottom() - d, 4, d, bc); }
        for (float y = r.y() + rad; y < r.bottom() - rad; y += 8) { g.rect(r.x(), y, d, 4, bc); g.rect(r.right() - d, y, d, 4, bc); }
        g.ringOutline(r.cx(), r.cy() - 18, 9, 1.4f, Col.mix(t.textDim(), t.primary(), h));
        g.icon(Icon.PLUS, r.cx() - 6, r.cy() - 24, 12, Col.mix(t.textDim(), t.primary(), h));
        g.textCentered(Lang.t("goals.create"), r.cx(), r.cy() + 2, t.text(), 1.15f, true);
        g.textCentered(Lang.t("goals.create_sub"), r.cx(), r.cy() + 18, t.textDim(), 0.9f, false);
        if (ui.pressedOn(r)) ui.active = "goal#create";
        if (ui.releasedThisFrame() && "goal#create".equals(ui.active) && hov) { ui.active = null; Ui.clickSound(); openCreate(ui); }
    }

    private void openCreate(Ui ui) {
        if (Progress.isSample()) { dialog = Dialog.NONE; return; }
        dialog = Dialog.CREATE;
        catIdx = 0;
        statIdx = 0;
        goalName = "";
        target = suggestedTarget(currentStat());
        ui.resetModalAnim();
    }

    private StatDef currentStat() {
        List<StatDef> defs = goalStats(GOAL_CATS[catIdx]);
        return defs.get(Math.max(0, Math.min(defs.size() - 1, statIdx)));
    }

    private static List<StatDef> goalStats(String cat) {
        List<StatDef> out = new ArrayList<>(StatDef.inCategory(cat));
        if (cat.equals("collections")) out.clear();
        if (cat.equals("mining")) out.removeIf(d -> d.id().equals("skill_mining")); // listed under skills already
        return out;
    }

    private static int suggestedTarget(StatDef def) {
        StatProgress p = Progress.real().peek(def.id());
        return Math.min(def.maxLevel(), Math.max(1, p.known() ? p.level + 1 : 1));
    }

    private void dialogs(Ui ui, SkycoreScreen screen) {
        Gfx g = ui.g;
        Theme t = ui.t;
        switch (dialog) {
            case CREATE -> ui.modal(screen.screenRect(), 380, 272, Lang.t("goals.create"), body -> {
                String[] cats = GOAL_CATS;
                String[] cl = new String[cats.length];
                for (int i = 0; i < cats.length; i++) cl[i] = Lang.t("cat." + cats[i]);
                g.text(Lang.t("goals.category"), body.x(), body.y(), t.textDim(), 0.9f);
                int nc = ui.segmented("gd#cat", new Rect(body.x(), body.y() + 12, body.w(), 20), cl, catIdx);
                if (nc != catIdx) { catIdx = nc; statIdx = 0; target = suggestedTarget(currentStat()); }
                List<StatDef> defs = goalStats(cats[catIdx]);
                String[] names = new String[defs.size()];
                for (int i = 0; i < defs.size(); i++) names[i] = Lang.t(defs.get(i).nameKey());
                g.text(Lang.t("goals.stat"), body.x(), body.y() + 44, t.textDim(), 0.9f);
                g.text(Lang.t("goals.target"), body.x() + body.w() - 120, body.y() + 44, t.textDim(), 0.9f);
                StatDef def = currentStat();
                target = ui.stepper("gd#target", new Rect(body.right() - 120, body.y() + 56, 120, 20), target, 1, def.maxLevel());
                int ns = ui.dropdown("gd#stat", new Rect(body.x(), body.y() + 56, body.w() - 132, 20), names, statIdx);
                if (ns != statIdx) { statIdx = ns; target = suggestedTarget(currentStat()); }
                StatProgress p = Progress.real().peek(def.id());
                String hint = p.known() ? Lang.t("goals.current", p.level) : Lang.t("goals.unknown");
                g.text(hint, body.x(), body.y() + 82, t.textMute(), 0.85f);
                g.text(Lang.t("goals.name"), body.x(), body.y() + 96, t.textDim(), 0.9f);
                goalName = ui.textField("gd#name", new Rect(body.x(), body.y() + 108, body.w(), 20), goalName,
                        Lang.t("goals.reach", Lang.t(def.nameKey()), target), 40, c -> c >= 32);
                // preview
                Rect pv = new Rect(body.x(), body.y() + 136, body.w(), 46);
                ui.tile(pv);
                g.text(g.ellipsize(goalName.isBlank() ? Lang.t("goals.reach", Lang.t(def.nameKey()), target) : goalName, pv.w() - 20, 1f), pv.x() + 10, pv.y() + 9, t.text(), 1f, true);
                float f = p.known() ? (float) Math.min(1, p.fractionalLevel() / target) : 0;
                ui.progress("gd#pv", new Rect(pv.x() + 10, pv.y() + 26, pv.w() - 60, 5), f, goalColor(t, def), t.progressEnd());
                g.textRight(Lang.percent(f), pv.right() - 10, pv.y() + 24, t.textDim(), 0.9f, false);
                Rect ok = new Rect(body.right() - 110, body.bottom() - 20, 110, 20);
                if (ui.button("gd#ok", ok, Lang.t("goals.create_btn"), Icon.CHECK, Ui.Style.PRIMARY)) {
                    Goals.create(def.id(), target, goalName);
                    dialog = Dialog.NONE;
                }
                if (ui.button("gd#cancel", new Rect(ok.x() - 86, ok.y(), 80, 20), Lang.t("action.cancel"), null, Ui.Style.GHOST)) dialog = Dialog.NONE;
            }, () -> dialog = Dialog.NONE);
            case EDIT -> ui.modal(screen.screenRect(), 320, 190, Lang.t("goals.edit"), body -> {
                StatDef def = StatDef.get(editing.stat);
                g.text(Lang.t(def.nameKey()), body.x(), body.y() + 5, t.text(), 1f, true);
                target = ui.stepper("ge#target", new Rect(body.right() - 120, body.y(), 120, 20), target, 1, def.maxLevel());
                g.text(Lang.t("goals.name"), body.x(), body.y() + 30, t.textDim(), 0.9f);
                goalName = ui.textField("ge#name", new Rect(body.x(), body.y() + 42, body.w(), 20), goalName,
                        Lang.t("goals.reach", Lang.t(def.nameKey()), target), 40, c -> c >= 32);
                Rect ok = new Rect(body.right() - 100, body.bottom() - 20, 100, 20);
                if (ui.button("ge#ok", ok, Lang.t("action.save"), Icon.CHECK, Ui.Style.PRIMARY)) {
                    editing.target = target;
                    editing.name = goalName.trim();
                    editing.completed = 0;
                    Goals.changed();
                    Goals.check();
                    dialog = Dialog.NONE;
                }
                if (ui.button("ge#cancel", new Rect(ok.x() - 86, ok.y(), 80, 20), Lang.t("action.cancel"), null, Ui.Style.GHOST)) dialog = Dialog.NONE;
            }, () -> dialog = Dialog.NONE);
            case DELETE -> ui.modal(screen.screenRect(), 300, 130, Lang.t("goals.delete"), body -> {
                StatDef def = StatDef.get(editing.stat);
                for (String l : g.wrap(Lang.t("goals.delete_confirm", Goals.displayName(editing)), body.w(), 1f)) {
                    g.text(l, body.x(), body.y(), t.textDim());
                    body = body.cutTop(11);
                }
                Rect ok = new Rect(body.right() - 100, body.bottom() - 20, 100, 20);
                if (ui.button("gx#ok", ok, Lang.t("action.delete"), Icon.TRASH, Ui.Style.DANGER)) {
                    Goals.delete(editing);
                    dialog = Dialog.NONE;
                }
                if (ui.button("gx#cancel", new Rect(ok.x() - 86, ok.y(), 80, 20), Lang.t("action.cancel"), null, Ui.Style.GHOST)) dialog = Dialog.NONE;
            }, () -> dialog = Dialog.NONE);
            default -> {}
        }
    }
}
