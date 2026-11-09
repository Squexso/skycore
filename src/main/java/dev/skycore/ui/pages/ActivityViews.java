package dev.skycore.ui.pages;

import dev.skycore.core.Lang;
import dev.skycore.data.Activity;
import dev.skycore.data.Activity.Slayer;
import dev.skycore.data.ActivityStats;
import dev.skycore.data.Mayors;
import dev.skycore.data.ProfileData;
import dev.skycore.data.Progress;
import dev.skycore.data.Projection;
import dev.skycore.data.StatDef;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.Widgets;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Slayer tiers, dungeon floors and the activity details dialog (Progression page). Every number shows where it
 * comes from: observed, calculated from the reward table, or an approximate fallback – with a confidence rating.
 */
final class ActivityViews {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private int slayerIdx = 3;           // Voidgloom by default
    private int floorMode;               // 0 = normal (incl. Entrance), 1 = Master Mode
    private final Map<String, Integer> targets = new HashMap<>();
    private ProfileData.DungeonRun detailRun;
    private Activity.SlayerQuest detailQuest;
    private int correctionIdx;

    /** Whole numbers as such, fractional rewards (e.g. 6.25 with a bonus) with decimals – never silently rounded. */
    static String xp(double v) { return v == Math.rint(v) ? Lang.num(v) : Lang.dec2(v); }

    static Activity.MayorSnapshot mayorNow() {
        return Progress.mayorNow();
    }

    static int confColor(Theme t, Projection.Confidence c) {
        return switch (c) { case HIGH -> t.success(); case MEDIUM -> t.text(); case LOW -> t.warning(); default -> t.textMute(); };
    }

    private int target(String key, int level, int max) {
        int v = targets.getOrDefault(key, Math.min(max, Math.max(1, level + 1)));
        return Math.max(Math.max(0, level), Math.min(max, v));
    }

    // ------------------------------------------------------------------ mayor chip
    /** "Aatrox · Slayer XP Buff +25 %" with the conditions in the tooltip. Returns the chip's width. */
    float mayorChip(Ui ui, float right, float y) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Activity.MayorSnapshot m = mayorNow();
        String text;
        int c;
        if (m == null) { text = Lang.t("mayor.unknown"); c = t.warning(); }
        else if (m.slayerPerk != null && m.slayerXpPercent > 0) { text = Lang.t("mayor.with_bonus", m.mayor, m.slayerPerk, Lang.dec(m.slayerXpPercent)); c = t.success(); }
        else if (m.slayerPerk != null) { text = Lang.t("mayor.bonus_unknown", m.mayor, m.slayerPerk); c = t.warning(); }
        else if (!m.ministerKnown) { text = Lang.t("mayor.minister_unknown", m.mayor); c = t.warning(); }
        else { text = Lang.t("mayor.no_bonus", m.mayor); c = t.textDim(); }
        float w = g.width(text, 0.85f, Weight.MEDIUM) + 22;
        Rect r = new Rect(right - w, y, w, 16);
        g.roundRect(r.x(), r.y(), r.w(), r.h(), 8, Col.alpha(c, 0.12f));
        g.circle(r.x() + 8, r.cy(), 2.5f, c);
        g.text(text, r.x() + 15, r.y() + 4, c, 0.85f, Weight.MEDIUM);
        if (ui.hover(r)) ui.tooltip(mayorTip(m));
        return w;
    }

    private static String mayorTip(Activity.MayorSnapshot m) {
        long now = System.currentTimeMillis();
        String term = Lang.t("mayor.term_ends", Lang.duration(Mayors.termEnd(now) - now));
        if (m == null) return Lang.t("mayor.unknown_tip") + "\n" + term;
        StringBuilder sb = new StringBuilder(Lang.t("mayor.tip_mayor", m.mayor));
        if (m.minister != null) sb.append('\n').append(Lang.t("mayor.tip_minister", m.minister));
        if (!m.perks.isEmpty()) sb.append('\n').append(Lang.t("mayor.tip_perks", String.join(", ", m.perks)));
        sb.append('\n').append(Lang.t("mayor.tip_seen", Widgets.ago(m.observedAt))).append('\n').append(term);
        if (Progress.isSample()) sb.append('\n').append(Lang.t("badge.sample"));
        return sb.toString();
    }

    // ------------------------------------------------------------------ slayer tiers
    float slayerTiers(Ui ui, Rect area) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Slayer s = Slayer.values()[slayerIdx];
        ProfileData d = Progress.view();
        List<Activity.SlayerQuest> quests = new ArrayList<>();
        for (Activity.SlayerQuest q : d.slayerQuests) if (s.id.equals(q.type) || q.type == null) quests.add(q);
        int recentN = Math.min(6, quests.size());
        float h = 46 + 30 + 26 + 18 + s.maxTier * 22 + 16 + 20 + Math.max(1, recentN) * 20 + 14;
        Rect card = new Rect(area.x(), area.y(), area.w(), h);
        ui.card(card);
        Rect in = card.inset(16, 0);
        g.text(Lang.t("slayer.tiers"), in.x(), card.y() + 18, t.text(), 1.3f, true);
        mayorChip(ui, in.right(), card.y() + 16);
        float y = card.y() + 44;

        // type selector + target level
        String[] names = new String[Slayer.values().length];
        for (int i = 0; i < names.length; i++) names[i] = Lang.t("slayer.short." + Slayer.values()[i].id);
        int ns = ui.segmented("sl#type", new Rect(in.x(), y, in.w(), 22), names, slayerIdx);
        if (ns != slayerIdx) { slayerIdx = ns; s = Slayer.values()[ns]; }
        StatDef def = StatDef.get(s.statId());
        ProfileData.StatProgress p = d.peek(def.id());
        y += 30;
        g.text(Lang.t("progress.target_level"), in.right() - 150, y + 4, t.textDim(), 0.85f);
        int target = target(s.id, p.level, def.maxLevel());
        int nt = ui.stepper("sl#target" + s.id, new Rect(in.right() - 74, y - 2, 74, 22), target, Math.max(1, p.level + 1), def.maxLevel());
        if (nt != target) targets.put(s.id, nt);

        double remaining = Projection.remaining(def, p, nt);
        String summary = !p.known() ? Lang.t("slayer.level_unknown")
                : remaining <= 0 ? Lang.t("slayer.target_reached", nt)
                : Lang.t("slayer.remaining", p.level, Lang.num(remaining), nt) + (p.xpUnknown ? " " + Lang.t("slayer.upper_bound") : "");
        g.text(summary, in.x(), y + 4, p.known() ? t.text() : t.textMute(), 0.95f, Weight.MEDIUM);
        y += 24;

        // tier table
        float[] cx = {0, .10f, .23f, .36f, .50f, .62f, .76f};
        String[] heads = {"slayer.col.tier", "slayer.col.base", "slayer.col.bonus", "slayer.col.expected", "slayer.col.done", "slayer.col.observed", "slayer.col.to_target"};
        for (int i = 0; i < heads.length; i++) g.text(Lang.t(heads[i]), in.x() + in.w() * cx[i], y, t.textDim(), 0.85f, Weight.MEDIUM);
        g.textRight(Lang.t("slayer.col.confidence"), in.right(), y, t.textDim(), 0.85f, Weight.MEDIUM);
        y += 14;
        Activity.MayorSnapshot m = mayorNow();
        List<Projection.Option> opts = Projection.slayer(s, p.known() ? Math.max(0, remaining) : -1, d.slayerStats, m, System.currentTimeMillis());
        for (int tier = 1; tier <= s.maxTier; tier++) {
            ui.separator(in.x(), y, in.w());
            Rect row = new Rect(in.x() - 6, y + 1, in.w() + 12, 21);
            ActivityStats st = d.slayerStats.get(s.key(tier));
            Activity.XpCalc calc = dev.skycore.data.Rewards.slayer(s, tier, m, -1);
            Projection.Option o = null;
            for (Projection.Option x : opts) if (x.key().equals(s.key(tier))) o = x;
            if (ui.hover(row)) {
                g.roundRect(row.x(), row.y(), row.w(), row.h(), 5, Col.alpha(t.surface(), 0.8f));
                ui.tooltip(tierTip(s, tier, calc, st, o));
            }
            float ty = y + 7;
            g.text("T" + tier, in.x(), ty, t.text(), 1f, Weight.SEMIBOLD);
            g.text(calc.base > 0 ? xp(calc.base) : "—", in.x() + in.w() * cx[1], ty, t.text(), 0.9f);
            Activity.Bonus b = calc.bonuses.isEmpty() ? null : calc.bonuses.get(0);
            String bonus = b != null && b.applied ? "+" + Lang.dec(b.percent) + "%" : b != null && b.confirmed ? "0%" : "?";
            g.text(bonus, in.x() + in.w() * cx[2], ty, b != null && b.applied ? t.success() : b != null && b.confirmed ? t.textDim() : t.warning(), 0.9f);
            g.text(calc.expected > 0 ? xp(calc.expected) + (calc.complete ? "" : " *") : "—", in.x() + in.w() * cx[3], ty, calc.complete ? t.text() : t.warning(), 0.9f);
            g.text(st == null ? "0" : Lang.num(st.count), in.x() + in.w() * cx[4], ty, t.text(), 0.9f);
            double avg = st == null ? -1 : st.average();
            g.text(avg > 0 ? xp(avg) : "—", in.x() + in.w() * cx[5], ty, t.text(), 0.9f);
            String need = o == null || remaining < 0 ? "—" : Lang.num(o.activities()) + (o.millis() > 0 ? " · " + Lang.duration(o.millis()) : "");
            g.text(need, in.x() + in.w() * cx[6], ty, o == null ? t.textMute() : t.text(), 0.9f, Weight.MEDIUM);
            if (o != null) g.textRight(Lang.t(o.confidence().key()), in.right(), ty, confColor(t, o.confidence()), 0.85f, Weight.MEDIUM);
            y += 22;
        }
        y += 12;

        // recent quests (including uncertain ones)
        g.text(Lang.t("slayer.recent"), in.x(), y, t.text(), 1f, Weight.SEMIBOLD);
        long open = quests.stream().filter(q -> "complete".equals(q.status) && !q.classified()).count();
        if (open > 0) g.textRight(Lang.t("slayer.unresolved", open), in.right(), y + 1, t.warning(), 0.85f, Weight.MEDIUM);
        y += 18;
        if (quests.isEmpty()) { g.text(Lang.t("slayer.no_quests"), in.x(), y + 3, t.textMute(), 0.9f); }
        for (int i = 0; i < recentN; i++) {
            Activity.SlayerQuest q = quests.get(i);
            Rect row = new Rect(in.x() - 6, y, in.w() + 12, 19);
            boolean hov = ui.hover(row);
            if (hov) { g.roundRect(row.x(), row.y(), row.w(), row.h(), 5, Col.alpha(t.surface(), 0.8f)); ui.tooltip(Lang.t("act.click_details")); }
            if (ui.pressedOn(row)) { Ui.clickSound(); openQuest(ui, q); }
            g.text(Widgets.ago(q.t), in.x(), y + 5, t.textDim(), 0.85f);
            String label = q.classified() ? Activity.slayerLabel(q.key()) : Lang.t("act.uncertain") + (q.type == null ? "" : " · " + Activity.slayerLabel(q.type));
            if (q.classification.autoResolved) label += " · " + Lang.t("act.auto");
            else if (q.classification.manual) label += " · " + Lang.t("act.manual");
            g.text(label, in.x() + in.w() * 0.16f, y + 5, q.classified() ? t.text() : t.warning(), 0.9f, Weight.MEDIUM);
            String status = Lang.t("act.status." + q.status);
            g.text(status, in.x() + in.w() * 0.5f, y + 5, "complete".equals(q.status) ? t.textDim() : t.warning(), 0.85f);
            String xp = q.calc.observed >= 0 ? Lang.t("act.observed_xp", Lang.num(q.calc.observed)) : q.calc.expected >= 0 ? Lang.t("act.calculated_xp", Lang.num(q.calc.expected)) : "—";
            g.textRight(xp, in.right(), y + 5, t.text(), 0.9f, Weight.REGULAR);
            y += 20;
        }
        return h;
    }

    private static String tierTip(Slayer s, int tier, Activity.XpCalc calc, ActivityStats st, Projection.Option o) {
        StringBuilder sb = new StringBuilder(s.boss + " T" + tier);
        sb.append('\n').append(Lang.t("calc.base", calc.base > 0 ? Lang.num(calc.base) : "—", calc.rewardSource));
        for (Activity.Bonus b : calc.bonuses) sb.append('\n').append(bonusLine(b));
        sb.append('\n').append(Lang.t("calc.expected", calc.expected > 0 ? Lang.num(calc.expected) : "—"));
        if (!calc.complete) sb.append('\n').append(Lang.t("calc.note.incomplete"));
        if (st != null) {
            sb.append('\n').append(Lang.t("slayer.tip_observed", st.count, st.xpCount, st.min > 0 ? Lang.num(st.min) : "—", st.max > 0 ? Lang.num(st.max) : "—"));
            if (st.tableMatches + st.tableMismatches > 0) sb.append('\n').append(Lang.t("slayer.tip_table", st.tableMatches, st.tableMismatches));
            if (st.tableMismatches > 0) sb.append('\n').append(Lang.t("slayer.tip_mismatch", Lang.num(st.lastMismatchObserved), Lang.num(st.lastMismatchExpected)));
        }
        if (o != null) sb.append('\n').append(Lang.t("proj.basis." + o.basis()));
        return sb.toString();
    }

    static String bonusLine(Activity.Bonus b) {
        String who = b.source == null ? Lang.t("mayor.unknown") : b.source.replace("mayor:", "").replace("minister:", "");
        String perk = b.perk == null ? "" : " · " + b.perk;
        String pct = b.percent > 0 ? " · +" + Lang.dec(b.percent) + "%" : "";
        return Lang.t("calc.bonus_line", who + perk + pct, Lang.t(b.reason == null ? "calc.bonus.mayor_unknown" : b.reason));
    }

    // ------------------------------------------------------------------ dungeon floors
    float floors(Ui ui, Rect area) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ProfileData d = Progress.view();
        List<String> floors = new ArrayList<>();
        for (String f : Activity.FLOORS) if ((floorMode == 1) == f.startsWith("M")) floors.add(f);
        float h = 46 + 30 + 24 + 16 + floors.size() * 22 + 30;
        Rect card = new Rect(area.x(), area.y(), area.w(), h);
        ui.card(card);
        Rect in = card.inset(16, 0);
        g.text(Lang.t("floors.title"), in.x(), card.y() + 18, t.text(), 1.3f, true);
        int nm = ui.segmented("fl#mode", new Rect(in.right() - 200, card.y() + 14, 200, 22), new String[]{Lang.t("floors.normal"), Lang.t("floors.master")}, floorMode);
        if (nm != floorMode) floorMode = nm;
        float y = card.y() + 46;
        StatDef def = StatDef.get("catacombs");
        ProfileData.StatProgress p = d.peek("catacombs");
        int target = target("catacombs", p.level, def.maxLevel());
        g.text(Lang.t("progress.target_level"), in.right() - 150, y + 6, t.textDim(), 0.85f);
        int nt = ui.stepper("fl#target", new Rect(in.right() - 74, y, 74, 22), target, Math.max(1, p.level + 1), def.maxLevel());
        if (nt != target) targets.put("catacombs", nt);
        double remaining = Projection.remaining(def, p, nt);
        String summary = !p.known() ? Lang.t("floors.level_unknown") : remaining <= 0 ? Lang.t("slayer.target_reached", nt)
                : Lang.t("floors.remaining", p.level, Lang.num(remaining), nt);
        g.text(summary, in.x(), y + 6, p.known() ? t.text() : t.textMute(), 0.95f, Weight.MEDIUM);
        y += 30;

        float[] cx = {0, .12f, .21f, .32f, .43f, .54f, .64f, .76f};
        String[] heads = {"floors.col.floor", "floors.col.runs", "floors.col.avg", "floors.col.median", "floors.col.last10", "floors.col.best", "floors.col.trend", "floors.col.to_target"};
        for (int i = 0; i < heads.length; i++) g.text(Lang.t(heads[i]), in.x() + in.w() * cx[i], y, t.textDim(), 0.85f, Weight.MEDIUM);
        g.textRight(Lang.t("slayer.col.confidence"), in.right(), y, t.textDim(), 0.85f, Weight.MEDIUM);
        y += 14;
        long now = System.currentTimeMillis();
        for (String f : floors) {
            ui.separator(in.x(), y, in.w());
            ActivityStats st = d.floorStats.get(f);
            Projection.Option o = remaining < 0 ? null : Projection.floor(f, Math.max(0, remaining), st, now);
            Rect row = new Rect(in.x() - 6, y + 1, in.w() + 12, 21);
            if (ui.hover(row)) {
                g.roundRect(row.x(), row.y(), row.w(), row.h(), 5, Col.alpha(t.surface(), 0.8f));
                ui.tooltip(floorTip(f, st, o));
            }
            float ty = y + 7;
            boolean any = st != null && st.count > 0;
            g.text(f.equals("E") ? Lang.t("floors.entrance") : f, in.x(), ty, any ? t.text() : t.textDim(), 1f, Weight.SEMIBOLD);
            g.text(any ? Lang.num(st.count) : "0", in.x() + in.w() * cx[1], ty, t.text(), 0.9f);
            double avg = st == null ? -1 : st.average(), med = st == null ? -1 : st.median(), l10 = st == null ? -1 : st.averageLast(10);
            g.text(avg > 0 ? Lang.num(avg) : "—", in.x() + in.w() * cx[2], ty, t.text(), 0.9f);
            g.text(med > 0 ? (st.medianApproximate() ? "≈" : "") + Lang.num(med) : "—", in.x() + in.w() * cx[3], ty, t.text(), 0.9f);
            g.text(l10 > 0 ? Lang.num(l10) : "—", in.x() + in.w() * cx[4], ty, t.text(), 0.9f);
            g.text(st != null && st.max > 0 ? Lang.num(st.max) : "—", in.x() + in.w() * cx[5], ty, t.text(), 0.9f);
            double tr = st == null ? Double.NaN : st.trend();
            g.text(Double.isNaN(tr) ? "—" : (tr >= 0 ? "+" : "−") + Lang.dec(Math.abs(tr) * 100) + "%", in.x() + in.w() * cx[6], ty,
                    Double.isNaN(tr) ? t.textMute() : tr >= 0 ? t.success() : t.warning(), 0.9f);
            if (o != null) {
                boolean fb = o.basis().equals("fallback");
                String need = (fb ? "≈" : "") + Lang.num(o.activities()) + (o.millis() > 0 ? " · " + Lang.duration(o.millis()) : "");
                g.text(need, in.x() + in.w() * cx[7], ty, fb ? t.textMute() : t.text(), 0.9f, Weight.MEDIUM);
                g.textRight(Lang.t(o.confidence().key()), in.right(), ty, confColor(t, o.confidence()), 0.85f, Weight.MEDIUM);
            } else g.text("—", in.x() + in.w() * cx[7], ty, t.textMute(), 0.9f);
            y += 22;
        }
        long unclassified = d.runs.stream().filter(r -> r.complete() && r.floor == null).count();
        g.text(g.ellipsize(unclassified > 0 ? Lang.t("floors.unclassified", unclassified) : Lang.t("floors.note"), in.w() / 0.85f, 1f),
                in.x(), y + 10, unclassified > 0 ? t.warning() : t.textMute(), 0.85f);
        return h;
    }

    private static String floorTip(String f, ActivityStats st, Projection.Option o) {
        StringBuilder sb = new StringBuilder(Lang.t("floors.tip_title", f.equals("E") ? Lang.t("floors.entrance") : f, Lang.t("floors.mode." + Activity.mode(f))));
        if (st != null && st.count > 0) {
            sb.append('\n').append(Lang.t("floors.tip_lifetime", st.count, st.xpCount, Lang.num(st.totalXp)));
            if (st.min > 0) sb.append('\n').append(Lang.t("floors.tip_range", Lang.num(st.min), Lang.num(st.max)));
            double l50 = st.averageLast(50);
            if (l50 > 0) sb.append('\n').append(Lang.t("floors.tip_last50", Lang.num(l50)));
            double xph = st.xpPerHour();
            sb.append('\n').append(xph > 0 ? Lang.t("floors.tip_xph", Lang.num(xph)) : Lang.t("floors.tip_xph_none"));
            if (st.medianApproximate()) sb.append('\n').append(Lang.t("floors.tip_median_approx"));
        } else sb.append('\n').append(Lang.t("floors.tip_no_runs"));
        if (o != null) sb.append('\n').append(Lang.t("proj.basis." + o.basis())).append(o.samples() > 0 ? " · " + Lang.t("proj.samples", o.samples()) : "");
        return sb.toString();
    }

    // ------------------------------------------------------------------ details dialog
    void openRun(Ui ui, ProfileData.DungeonRun r) { detailRun = r; detailQuest = null; correctionIdx = 0; ui.resetModalAnim(); }

    void openQuest(Ui ui, Activity.SlayerQuest q) { detailQuest = q; detailRun = null; correctionIdx = 0; ui.resetModalAnim(); }

    void details(Ui ui, SkycoreScreen screen) {
        if (detailRun == null && detailQuest == null) return;
        Gfx g = ui.g;
        Theme t = ui.t;
        int extra = (detailQuest != null ? detailQuest.classification.history.size() : detailRun != null && detailRun.classification != null ? detailRun.classification.history.size() : 0) * 11;
        ui.modal(screen.screenRect(), 480, (detailQuest != null ? 320 : 250) + extra, Lang.t("act.details"), body -> {
            float y = body.y();
            Activity.Classification c;
            Activity.XpCalc calc;
            long time;
            String title, status;
            Activity.MayorSnapshot mayor = null;
            if (detailRun != null) {
                ProfileData.DungeonRun r = detailRun;
                c = r.classification;
                calc = r.calc;
                time = r.t;
                title = r.floor != null ? Lang.t("act.run_title", r.floor, Lang.t("floors.mode." + Activity.mode(r.floor))) : Lang.t("act.run_unknown");
                status = Lang.t(r.complete() ? "act.status.complete" : "act.status.incomplete");
            } else {
                Activity.SlayerQuest q = detailQuest;
                c = q.classification;
                calc = q.calc;
                time = q.t;
                mayor = q.mayor;
                title = q.classified() ? Activity.slayerLabel(q.key()) : Lang.t("act.uncertain") + (q.type == null ? "" : " · " + Activity.slayerLabel(q.type));
                status = Lang.t("act.status." + q.status);
            }
            boolean confirmed = c != null && c.confirmed;
            g.text(title, body.x(), y, confirmed ? t.text() : t.warning(), 1.1f, Weight.SEMIBOLD);
            g.textRight(status + " · " + WHEN.format(Instant.ofEpochMilli(time)), body.right(), y + 1, t.textDim(), 0.85f, Weight.REGULAR);
            y += 18;
            // classification
            String conf = c == null ? "—" : Math.round(c.confidence * 100) + "%";
            line(ui, body, y, Lang.t("act.classification"), confirmed ? Lang.t(c.manual ? "act.confirmed_manual" : "act.confirmed", conf) : Lang.t("act.not_confirmed", conf), confirmed ? t.success() : t.warning());
            y += 13;
            if (c != null) {
                for (String e : c.evidence) { line(ui, body, y, "  " + evidenceLabel(e), "", t.textDim()); y += 11; }
                for (String m : c.missing) { line(ui, body, y, "  " + Lang.t(m), "", t.warning()); y += 11; }
                for (String h : c.history) { line(ui, body, y, "  " + historyLabel(h), "", t.primary()); y += 11; }
                if (!c.candidates.isEmpty()) {
                    List<String> labels = new ArrayList<>();
                    for (String k : c.candidates) labels.add(k.contains(":") ? Activity.slayerLabel(k) : k);
                    line(ui, body, y, "  " + Lang.t("act.candidates", String.join(", ", labels)), "", t.textDim());
                    y += 11;
                }
            }
            y += 6;
            // calculation
            if (calc != null) {
                line(ui, body, y, Lang.t("act.observed"), calc.observed >= 0 ? xp(calc.observed) + " XP" : Lang.t("act.not_observed"), t.text()); y += 13;
                if (detailQuest != null) {
                    line(ui, body, y, Lang.t("act.base"), calc.base > 0 ? xp(calc.base) + " XP · " + calc.rewardSource : "—", t.text()); y += 13;
                    for (Activity.Bonus b : calc.bonuses) { line(ui, body, y, "  " + bonusLine(b), "", b.applied ? t.success() : t.textDim()); y += 11; }
                    line(ui, body, y, Lang.t("act.expected"), calc.expected > 0 ? xp(calc.expected) + " XP" + (calc.complete ? "" : " *") : "—", calc.complete ? t.text() : t.warning()); y += 13;
                } else {
                    line(ui, body, y, Lang.t("act.modifiers"), Lang.t("act.modifiers_none"), t.textDim()); y += 13;
                }
                line(ui, body, y, Lang.t("act.recorded_as"), Lang.t("act.kind." + calc.kind), t.text()); y += 13;
                if (calc.note != null) { for (String l : g.wrap(Lang.t(calc.note), body.w(), 0.85f)) { g.text(l, body.x(), y, t.textMute(), 0.85f); y += 10; } }
            }
            if (detailQuest != null) {
                y += 4;
                line(ui, body, y, Lang.t("act.mayor"), mayor == null ? Lang.t("mayor.unknown") : mayor.mayor + (mayor.minister != null ? " · " + Lang.t("mayor.tip_minister", mayor.minister) : ""), mayor == null ? t.warning() : t.text());
                y += 13;
            }
            // correction (not in sample mode)
            if (!Progress.isSample()) {
                List<String> keys = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                if (detailRun != null) {
                    if (detailRun.complete()) for (String f : Activity.FLOORS) { keys.add(f); labels.add(f.equals("E") ? Lang.t("floors.entrance") : f); }
                } else for (Slayer s : Slayer.values()) for (int tier = 1; tier <= s.maxTier; tier++) { keys.add(s.key(tier)); labels.add(s.boss + " T" + tier); }
                if (!keys.isEmpty()) {
                    Rect row = new Rect(body.x(), body.bottom() - 22, body.w(), 22);
                    g.text(Lang.t("act.correct"), row.x(), row.y() + 6, t.textDim(), 0.9f);
                    correctionIdx = Math.min(correctionIdx, keys.size() - 1);
                    Rect dd = new Rect(row.right() - 100 - 8 - 170, row.y(), 170, 22);
                    correctionIdx = ui.dropdown("act#fix", dd, labels.toArray(String[]::new), correctionIdx);
                    if (ui.button("act#apply", new Rect(row.right() - 100, row.y(), 100, 22), Lang.t("act.apply"), Icon.CHECK, Ui.Style.SECONDARY)) {
                        String k = keys.get(correctionIdx);
                        boolean ok;
                        if (detailRun != null) ok = Progress.reclassifyRun(detailRun.id, k);
                        else { int i = k.indexOf(':'); ok = Progress.reclassifyQuest(detailQuest.id, k.substring(0, i), Integer.parseInt(k.substring(i + 1))); }
                        dev.skycore.notify.Notifier.push(null, Lang.t(ok ? "act.corrected" : "act.unchanged"), ok ? labels.get(correctionIdx) : "", Icon.CHECK, ok ? t.success() : t.textDim());
                    }
                    if (ui.hover(new Rect(row.right() - 100, row.y(), 100, 22))) ui.tooltip(Lang.t("act.correct_tip"));
                }
            }
        }, () -> { detailRun = null; detailQuest = null; });
    }

    private static void line(Ui ui, Rect body, float y, String label, String value, int color) {
        ui.g.text(ui.g.ellipsize(label, body.w() * (value.isEmpty() ? 1 : 0.5f) / 0.9f, 1f), body.x(), y, value.isEmpty() ? color : ui.t.textDim(), 0.9f);
        if (!value.isEmpty()) ui.g.textRight(ui.g.ellipsize(value, body.w() * 0.5f / 0.9f, 1f), body.right(), y, color, 0.9f, Weight.REGULAR);
    }

    /** "time|before|after|reason" → "Changed 5m ago: uncertain → Voidgloom Seraph T4 (menu counters)". */
    static String historyLabel(String h) {
        String[] p = h.split("\\|", 4);
        if (p.length < 4) return h;
        String after = p[2].contains(":") ? Activity.slayerLabel(p[2]) : p[2];
        String before = p[1].equals("uncertain") ? Lang.t("act.uncertain") : p[1].contains(":") ? Activity.slayerLabel(p[1].split(" ")[0]) : p[1];
        long t = 0;
        try { t = Long.parseLong(p[0]); } catch (NumberFormatException ignored) {}
        return Lang.t("act.history_line", Widgets.ago(t), before, after, Lang.t("act.why." + p[3]));
    }

    /** "evidence.sidebar|enderman:4" → "Sidebar quest line: Voidgloom Seraph T4". */
    static String evidenceLabel(String e) {
        int i = e.indexOf('|');
        String key = i > 0 ? e.substring(0, i) : e;
        String v = i > 0 ? e.substring(i + 1) : "";
        String value = v.contains(":") ? Activity.slayerLabel(v) : Activity.Slayer.byId(v) != null ? Activity.Slayer.byId(v).boss : v;
        return Lang.t(key) + (value.isEmpty() ? "" : ": " + value);
    }
}
