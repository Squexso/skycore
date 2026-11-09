package dev.skycore.ui.pages;

import dev.skycore.core.Lang;
import dev.skycore.data.SessionLog;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;

/** Session analytics: XP over time, playtime, per-category breakdown and period comparison. */
public class SessionsPage implements Page {
    private int period;
    private boolean compare = true;
    private int chartType; // 0 = line, 1 = bars

    @Override public String id() { return "sessions"; }

    public void setView(int period, int chartType) { this.period = period; this.chartType = chartType; }
    @Override public Icon icon() { return Icon.SESSIONS; }

    public static int groupColor(Theme t, int i) {
        return switch (i) {
            case 0 -> t.chart(0);
            case 1 -> t.danger();
            case 2 -> t.chart(1);
            case 3 -> t.chart(2);
            case 4 -> t.success();
            default -> t.warning();
        };
    }

    public static String dayLabel(SessionLog.Range r, int i) {
        Instant at = Instant.ofEpochMilli(r.start() + i * r.step());
        var dt = at.atZone(ZoneId.systemDefault());
        if (r.step() < 86_400_000L) return String.format("%02d:00", dt.getHour());
        if (r.points() <= 7) return dt.getDayOfWeek().getDisplayName(TextStyle.SHORT, Lang.locale());
        return DateTimeFormatter.ofPattern("d.M.", Lang.locale()).format(dt);
    }

    private static String fullLabel(SessionLog.Range r, int i) {
        Instant at = Instant.ofEpochMilli(r.start() + i * r.step());
        var dt = at.atZone(ZoneId.systemDefault());
        String date = DateTimeFormatter.ofPattern(Lang.code().equals("de") ? "EEE, d. MMM" : "EEE, MMM d", Lang.locale()).format(dt);
        if (r.step() < 86_400_000L) return date + String.format(" · %02d:00–%02d:00", dt.getHour(), (dt.getHour() + 1) % 24);
        return date;
    }

    /** Which profile's sessions are shown, and which profile is being tracked right now. */
    private float profileBar(Ui ui, Rect area, float y) {
        Gfx g = ui.g;
        Theme t = ui.t;
        if (dev.skycore.data.Progress.isSample()) return y;
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String k : dev.skycore.data.Progress.profileKeys()) if (!k.equals(dev.skycore.data.ProfileAssign.SOURCE)) keys.add(k);
        for (String k : SessionLog.keys()) if (!keys.contains(k)) keys.add(k);
        String sel = SessionLog.selectedKey();
        if (sel != null && !keys.contains(sel)) keys.add(0, sel);
        if (keys.isEmpty()) return y;
        String[] names = new String[keys.size()];
        int idx = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = label(keys.get(i));
            if (keys.get(i).equals(sel)) idx = i;
        }
        g.text(Lang.t("sessions.profile"), area.x(), y + 6, t.textDim(), 0.9f);
        float lw = g.width(Lang.t("sessions.profile"), 0.9f) + 8;
        int ni = ui.dropdown("ses#profile", new Rect(area.x() + lw, y, 190, 22), names, idx);
        if (ni != idx) SessionLog.select(keys.get(ni));
        // tracking indicator
        boolean confirmed = dev.skycore.data.Progress.isIdentified();
        String now = confirmed ? Lang.t("sessions.tracking_now", label(dev.skycore.data.Progress.profileKey())) : Lang.t("sessions.tracking_paused");
        int c = confirmed ? t.success() : t.warning();
        float w = g.width(now, 0.85f, dev.skycore.ui.gfx.Gfx.Weight.MEDIUM);
        g.circle(area.right() - w - 10, y + 11, 2.5f, c);
        g.text(now, area.right() - w, y + 7, c, 0.85f, dev.skycore.ui.gfx.Gfx.Weight.MEDIUM);
        if (ui.hover(new Rect(area.right() - w - 16, y, w + 16, 22))) ui.tooltip(Lang.t(confirmed ? "sessions.tracking_tip" : "sessions.paused_tip"));
        return y + 32;
    }

    static String label(String key) {
        if (key == null) return "—";
        return key.equals(SessionLog.UNASSIGNED) ? Lang.t("sessions.unassigned") : ReliabilitySections.label(key);
    }

    /** Recent play sessions of the shown profile with duration, XP and level changes. */
    private float history(Ui ui, Rect area, float y) {
        Gfx g = ui.g;
        Theme t = ui.t;
        SessionLog.Data d = SessionLog.view();
        java.util.List<SessionLog.Span> spans = new java.util.ArrayList<>(d.spans);
        java.util.Collections.reverse(spans);
        int n = Math.min(8, spans.size());
        Rect card = new Rect(area.x(), y, area.w(), 52 + Math.max(1, n) * 22 + 6);
        ui.card(card);
        g.text(Lang.t("sessions.history"), card.x() + 16, card.y() + 16, t.text(), 1.3f, true);
        g.textRight(Lang.t("sessions.lifetime", Lang.duration(d.totalPlaySeconds * 1000L)), card.right() - 16, card.y() + 18, t.textDim(), 0.85f, false);
        float ry = card.y() + 44;
        if (n == 0) g.text(Lang.t("sessions.no_history"), card.x() + 16, ry + 6, t.textMute(), 0.9f);
        DateTimeFormatter f = DateTimeFormatter.ofPattern(Lang.code().equals("de") ? "EEE d.M. HH:mm" : "EEE MMM d, HH:mm", Lang.locale());
        for (int i = 0; i < n; i++) {
            SessionLog.Span sp = spans.get(i);
            Rect row = new Rect(card.x() + 10, ry, card.w() - 20, 21);
            ui.separator(card.x() + 16, ry, card.w() - 32);
            int ups = 0;
            StringBuilder changes = new StringBuilder();
            for (var e : sp.levelsEnd.entrySet()) {
                Double before = sp.levelsStart.get(e.getKey());
                if (before != null && Math.floor(e.getValue()) > Math.floor(before)) {
                    ups++;
                    StatDef def = StatDef.get(e.getKey());
                    changes.append('\n').append(def == null ? e.getKey() : Lang.t(def.nameKey())).append(' ').append((int) Math.floor(before)).append(" → ").append((int) Math.floor(e.getValue()));
                }
            }
            if (ui.hover(row)) ui.tooltip(Lang.t("sessions.span_tip", Lang.t("sessions.end." + sp.endReason)) + changes);
            g.text(f.format(Instant.ofEpochMilli(sp.start).atZone(ZoneId.systemDefault())), card.x() + 16, ry + 7, t.text(), 0.9f);
            g.text(Lang.duration(sp.playSeconds * 1000L), card.x() + card.w() * 0.34f, ry + 7, t.textDim(), 0.9f);
            g.text(sp.totalXp() > 0 ? Lang.compact(sp.totalXp()) + " XP" : "—", card.x() + card.w() * 0.52f, ry + 7, t.text(), 0.9f);
            g.text(ups > 0 ? Lang.t("sessions.level_ups", ups) : "", card.x() + card.w() * 0.68f, ry + 7, t.success(), 0.9f);
            g.textRight(Lang.t("sessions.end." + sp.endReason), card.right() - 16, ry + 7, sp.endReason.equals("open") ? t.success() : t.textMute(), 0.85f, false);
            ry += 22;
        }
        return card.bottom();
    }

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        Gfx g = ui.g;
        Theme t = ui.t;
        float y = area.y() + 8;
        y = profileBar(ui, area, y);
        String[] labels = {Lang.t("period.today"), Lang.t("period.yesterday"), Lang.t("period.week"), Lang.t("period.month")};
        period = ui.pills("ses#period", area.x(), y, labels, period, area.w() - 170);
        compare = ui.toggleRow("ses#cmp", new Rect(area.right() - 168, y - 4, 168, 26), Lang.t("sessions.compare"), null, compare);
        y = ui.lastPillsBottom + 12;

        SessionLog.Period p = SessionLog.Period.values()[period];
        SessionLog.Range range = SessionLog.range(p);
        SessionLog.Range prev = SessionLog.previous(range);
        SessionLog.Totals tt = SessionLog.totals(range);
        SessionLog.Totals pt = SessionLog.totals(prev);

        // stat tiles
        int cols = area.w() >= 560 ? 4 : 2;
        Rect[] tiles = new Rect(area.x(), y, area.w(), 66).columns(cols, 10);
        if (cols == 2) {
            Rect[] second = new Rect(area.x(), y + 76, area.w(), 66).columns(2, 10);
            tiles = new Rect[]{tiles[0], tiles[1], second[0], second[1]};
        }
        double hours = tt.playMillis() / 3600_000.0;
        Widgets.statTile(ui, tiles[0], Lang.t("sessions.total_xp"), Lang.compact(tt.xp()), compare ? delta(tt.xp(), pt.xp()) : null, compare ? deltaColor(t, tt.xp(), pt.xp()) : 0);
        Widgets.statTile(ui, tiles[1], Lang.t("sessions.playtime"), Lang.duration(tt.playMillis()),
                compare ? delta(tt.playMillis(), pt.playMillis()) : Lang.t("sessions.afk_excluded"), compare ? deltaColor(t, tt.playMillis(), pt.playMillis()) : 0);
        double pHours = pt.playMillis() / 3600_000.0;
        double rate = hours > 0.05 ? tt.xp() / hours : 0, pRate = pHours > 0.05 ? pt.xp() / pHours : 0;
        Widgets.statTile(ui, tiles[2], Lang.t("sessions.xp_hour"), rate > 0 ? Lang.compact(rate) : "—", compare && rate > 0 ? delta(rate, pRate) : null, deltaColor(t, rate, pRate));
        String top = null;
        double topV = 0;
        for (var e : tt.byGroup().entrySet()) if (e.getValue() > topV) { topV = e.getValue(); top = e.getKey(); }
        Widgets.statTile(ui, tiles[3], Lang.t("sessions.top_category"), top == null ? "—" : Lang.t("group." + top),
                top == null ? null : Lang.percent(tt.xp() > 0 ? topV / tt.xp() : 0) + " " + Lang.t("sessions.of_xp"), 0);
        y = tiles[tiles.length - 1].bottom() + 12;

        // chart
        Rect chart = new Rect(area.x(), y, area.w(), 230);
        ui.card(chart);
        g.text(Lang.t("sessions.xp_dev"), chart.x() + 16, chart.y() + 16, t.text(), 1.3f, true);
        g.text(Lang.t(p == SessionLog.Period.TODAY || p == SessionLog.Period.YESTERDAY ? "sessions.per_hour" : "sessions.per_day"), chart.x() + 16, chart.y() + 33, t.textDim(), 0.9f);
        double[] s = SessionLog.series(range, null);
        boolean any = false;
        for (double v : s) any |= v > 0;
        boolean played = SessionLog.played(range);
        Rect plot = new Rect(chart.x() + 16, chart.y() + 52, chart.w() - 32, chart.h() - 64);
        String[] types = {Lang.t("chart.line"), Lang.t("chart.bars")};
        chartType = ui.segmented("ses#type", new Rect(chart.right() - 16 - 120, chart.y() + 30, 120, 16), types, chartType);
        if (!any) {
            Widgets.empty(ui, "ses#e", plot, Icon.SESSIONS, Lang.t(played ? "sessions.no_xp" : "sessions.empty"),
                    Lang.t(played ? "sessions.no_xp_desc" : "sessions.empty_desc"), null);
        } else {
            // legend
            float lx = chart.right() - 16;
            if (compare) {
                String l2 = Lang.t("sessions.previous");
                lx -= g.width(l2, 0.85f);
                g.text(l2, lx, chart.y() + 18, t.textDim(), 0.85f);
                g.rect(lx - 14, chart.y() + 21, 10, 1.5f, t.textMute());
                lx -= 24;
            }
            String l1 = Lang.t("sessions.current");
            lx -= g.width(l1, 0.85f);
            g.text(l1, lx, chart.y() + 18, t.textDim(), 0.85f);
            g.rect(lx - 14, chart.y() + 21, 10, 2, t.chart(0));
            Widgets.Series cur = new Widgets.Series(s, t.chart(0), true, false, Lang.t("sessions.current"));
            Widgets.Series[] series = compare
                    ? new Widgets.Series[]{cur, new Widgets.Series(SessionLog.series(prev, null), t.textMute(), false, true, Lang.t("sessions.previous"))}
                    : new Widgets.Series[]{cur};
            int every = s.length > 12 ? (int) Math.ceil(s.length / 8.0) : 1;
            if (s.length == 24) every = 3;
            if (chartType == 0) Widgets.lineChart(ui, "ses#chart" + period, plot, series, i -> dayLabel(range, i), i -> fullLabel(range, i), Lang::compact, every);
            else {
                double[][] data = compare ? new double[][]{s, SessionLog.series(prev, null)} : new double[][]{s};
                int[] cols2 = compare ? new int[]{t.chart(0), Col.alpha(t.textMute(), 0.6f)} : new int[]{t.chart(0)};
                String[] names = compare ? new String[]{Lang.t("sessions.current"), Lang.t("sessions.previous")} : new String[]{Lang.t("sessions.current")};
                Widgets.groupedBars(ui, "ses#bars" + period, plot, data, cols2, names, i -> dayLabel(range, i), i -> fullLabel(range, i), Lang::compact, every);
            }
        }
        y = chart.bottom() + 12;

        // category comparison (current vs previous period)
        Rect cmp = new Rect(area.x(), y, area.w(), 200);
        ui.card(cmp);
        g.text(Lang.t("sessions.cat_compare"), cmp.x() + 16, cmp.y() + 16, t.text(), 1.3f, true);
        Rect cplot = new Rect(cmp.x() + 16, cmp.y() + 40, cmp.w() - 32, cmp.h() - 52);
        if (!any && pt.xp() <= 0) {
            Widgets.empty(ui, "ses#ce", cplot, Icon.CHART_BAR, Lang.t("sessions.empty"), Lang.t("sessions.empty_desc"), null);
        } else {
            double[] cur = new double[StatDef.GROUPS.length], before = new double[StatDef.GROUPS.length];
            for (int i = 0; i < cur.length; i++) {
                cur[i] = tt.byGroup().getOrDefault(StatDef.GROUPS[i], 0d);
                before[i] = pt.byGroup().getOrDefault(StatDef.GROUPS[i], 0d);
            }
            Widgets.groupedBars(ui, "ses#cmp" + period, cplot, new double[][]{cur, before}, new int[]{t.chart(0), Col.alpha(t.textMute(), 0.6f)},
                    new String[]{Lang.t("sessions.current"), Lang.t("sessions.previous")},
                    i -> Lang.t("group." + StatDef.GROUPS[i]), i -> Lang.t("group." + StatDef.GROUPS[i]), Lang::compact, 1);
        }
        y = cmp.bottom() + 12;

        // categories
        Rect cat = new Rect(area.x(), y, area.w(), 52 + StatDef.GROUPS.length * 30);
        ui.card(cat);
        g.text(Lang.t("sessions.categories"), cat.x() + 16, cat.y() + 16, t.text(), 1.3f, true);
        double max = 0;
        for (double v : tt.byGroup().values()) max = Math.max(max, v);
        float ry = cat.y() + 40;
        for (int i = 0; i < StatDef.GROUPS.length; i++) {
            String grp = StatDef.GROUPS[i];
            double v = tt.byGroup().getOrDefault(grp, 0d), pv = pt.byGroup().getOrDefault(grp, 0d);
            long last = SessionLog.lastActivity(grp);
            // "inactive" only when you actually played in this period but made no progress here – no data ≠ zero
            boolean inactive = played && v <= 0 && last > 0;
            Rect row = new Rect(cat.x() + 8, ry, cat.w() - 16, 28);
            boolean hov = ui.hover(row);
            if (hov) g.roundRect(row.x(), row.y(), row.w(), row.h(), ui.radius(), Col.alpha(t.surface(), 0.8f));
            int c = groupColor(t, i);
            Widgets.iconChip(ui, new Rect(row.x() + 8, row.y() + 6, 16, 16), StatDef.categoryIcon(grp), inactive ? t.warning() : c);
            g.text(Lang.t("group." + grp), row.x() + 32, row.y() + 10, t.text(), 1f, true);
            float bx = row.x() + Math.max(120, row.w() * 0.25f), bw = row.w() * 0.42f;
            if (inactive) {
                g.icon(Icon.WARNING, bx, row.y() + 8, 11, t.warning());
                g.text(Lang.t("sessions.inactive", Widgets.ago(last)), bx + 16, row.y() + 10, t.warning(), 0.9f);
                if (hov) ui.tooltip(Lang.t("sessions.inactive_tip"));
            } else if (v > 0) {
                ui.progress("ses#g" + i + period, new Rect(bx, row.cy() - 2.5f, bw, 5), (float) (v / Math.max(1, max)), c, Col.mix(c, t.progressEnd(), 0.4f));
            } else {
                g.text(Lang.t(played ? "sessions.no_data" : "sessions.not_played"), bx, row.y() + 10, t.textMute(), 0.9f);
            }
            g.textRight(v > 0 ? Lang.compact(v) + " XP" : "—", row.right() - (compare ? 64 : 8), row.y() + 10, t.text(), 1f, false);
            if (compare && (v > 0 || pv > 0)) {
                String d = delta(v, pv);
                if (d != null) g.textRight(d, row.right() - 8, row.y() + 10, deltaColor(t, v, pv), 0.85f, false);
            }
            ry += 30;
        }
        g.text(Lang.t("sessions.honest"), cat.x() + 16, cat.bottom() + 6, t.textMute(), 0.85f);
        float hy = history(ui, area, cat.bottom() + 12);
        return hy + 18 - area.y();
    }

    private static String delta(double cur, double prev) {
        if (prev <= 0) return cur > 0 ? Lang.t("sessions.new") : null;
        double pct = (cur - prev) / prev * 100;
        return (pct >= 0 ? "+" : "−") + Lang.dec(Math.abs(pct)) + "% " + Lang.t("sessions.vs_prev");
    }

    private static int deltaColor(Theme t, double cur, double prev) {
        if (prev <= 0) return t.textDim();
        return cur >= prev ? t.success() : t.danger();
    }
}
