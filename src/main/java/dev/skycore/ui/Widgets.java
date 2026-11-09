package dev.skycore.ui;

import dev.skycore.core.Lang;
import dev.skycore.data.Evidence;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;

import java.util.function.DoubleFunction;
import java.util.function.IntFunction;

/** Composite, reusable visual components built on {@link Ui}. */
public final class Widgets {
    private Widgets() {}

    // ------------------------------------------------------------------ small pieces
    /**
     * Quiet text label (no pill); returns the width it reserves. Keeps the old badge geometry so call sites that
     * right-align with {@code width + 14} still line up.
     */
    public static float badge(Ui ui, float x, float y, String text, int color) {
        Gfx g = ui.g;
        float w = g.width(text, 0.85f, Weight.MEDIUM) + 14;
        g.text(text, x + 7, y + 3.4f, color, 0.85f, Weight.MEDIUM);
        return w;
    }

    /** Clickable filter chip (debugger filters). Keeps a quiet filled chip so the on/off state stays obvious. */
    public static boolean badgeButton(Ui ui, String id, float x, float y, String text, int color) {
        Gfx g = ui.g;
        float w = g.width(text, 0.85f, Weight.MEDIUM) + 14;
        Rect r = new Rect(x, y, w, 14);
        boolean hov = ui.hover(r);
        if (hov) { ui.setCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.POINTING_HAND); }
        int c = hov ? Col.mix(color, 0xFFFFFFFF, 0.2f) : color;
        g.roundRect(x, y, w, 14, 5, Col.alpha(c, 0.14f));
        g.text(text, x + 7, y + 3.4f, c, 0.85f, Weight.MEDIUM);
        if (ui.pressedOn(r)) ui.active = id;
        if (ui.releasedThisFrame() && id.equals(ui.active) && hov) { ui.active = null; Ui.clickSound(); return true; }
        return false;
    }

    /**
     * Icon without a container. (Formerly a tinted rounded square; the tile added decoration, not information.)
     * Icons always take a text role colour, never their own hue.
     */
    public static void iconChip(Ui ui, Rect r, Icon icon, int color) {
        float s = Math.min(16, Math.max(10, r.w() * 0.62f));
        ui.g.icon(icon, r.cx() - s / 2, r.cy() - s / 2, s, ui.t.textDim());
    }

    public static void sectionTitle(Ui ui, float x, float y, String text) {
        ui.g.text(text, x, y, ui.t.text(), 1.15f, Weight.SEMIBOLD);
    }

    // ------------------------------------------------------------------ evidence
    /** Draws the evidence mark centred on (cx, cy): solid dot = observed, hollow ring = estimated, dash = unavailable. */
    public static void evidenceMark(Ui ui, float cx, float cy, Evidence e) {
        Gfx g = ui.g;
        Theme t = ui.t;
        switch (e) {
            case OBSERVED -> g.circle(cx, cy, 2.6f, t.text());
            case ESTIMATED -> g.ringOutline(cx, cy, 2.6f, 1.2f, t.textDim());
            default -> g.roundRect(cx - 3, cy - 0.6f, 6, 1.2f, 0.6f, t.textMute());
        }
    }

    /** Evidence tooltip text: the coarse state first, then (when known) the detailed record behind it. */
    public static String evidenceTip(Evidence e, String detail) {
        String s = Lang.t(e.key()) + "\n" + Lang.t(e.key() + ".desc");
        return detail == null || detail.isEmpty() ? s : s + "\n" + detail;
    }

    /** Mark + word ("● Observed"). Returns the width used. The tooltip explains the state. */
    public static float evidenceLabel(Ui ui, float x, float y, Evidence e, String tip, float scale) {
        Gfx g = ui.g;
        evidenceMark(ui, x + 3, y + 4.2f * scale, e);
        String txt = Lang.t(e.key());
        g.text(txt, x + 10, y, ui.t.textDim(), scale, Weight.MEDIUM);
        float w = 10 + g.width(txt, scale, Weight.MEDIUM);
        if (ui.hover(new Rect(x - 2, y - 2, w + 4, 10 * scale + 4))) ui.tooltip(tip != null ? tip : evidenceTip(e, null));
        return w;
    }

    /**
     * Borderless figure: caption, value, optional sub line. Replaces the old boxed stat tile; groups of figures are
     * separated by whitespace and a hairline, not by nested containers.
     */
    public static void statTile(Ui ui, Rect r, String caption, String value, String sub, int subColor) {
        statTile(ui, r, caption, value, sub, subColor, null, null);
    }

    /** As above with an evidence mark after the caption (null = no mark). */
    public static void statTile(Ui ui, Rect r, String caption, String value, String sub, int subColor, Evidence ev, String evTip) {
        Gfx g = ui.g;
        Theme t = ui.t;
        g.text(g.ellipsize(caption, r.w() - (ev == null ? 0 : 12), 0.9f), r.x(), r.y(), t.textDim(), 0.9f, Weight.MEDIUM);
        if (ev != null) {
            float cw = g.width(g.ellipsize(caption, r.w() - 12, 0.9f), 0.9f, Weight.MEDIUM);
            evidenceMark(ui, r.x() + cw + 7, r.y() + 3.9f, ev);
            if (ui.hover(new Rect(r.x() + cw + 1, r.y() - 2, 12, 12))) ui.tooltip(evTip != null ? evTip : evidenceTip(ev, null));
        }
        float vs = r.h() >= 54 ? 1.7f : 1.5f;
        g.text(g.ellipsize(value, r.w() / vs, 1f), r.x(), r.y() + 12, t.text(), vs, Weight.SEMIBOLD);
        if (sub != null) g.text(g.ellipsize(sub, r.w(), 0.85f), r.x(), r.bottom() - 9, subColor == 0 ? t.textMute() : subColor, 0.85f);
    }

    /** Row of borderless figures with hairline dividers. Returns the rectangles for tooltips etc. */
    public static Rect[] figureColumns(Ui ui, Rect r, int n) {
        float gap = 20;
        Rect[] cols = r.columns(n, gap);
        for (int i = 1; i < n; i++) ui.vseparator(cols[i].x() - gap / 2, r.y() - 2, r.h() + 2);
        return cols;
    }

    /** Estimate tile text: range + "preliminary" marker when few samples exist. */
    public static String estimateSub(dev.skycore.data.Estimate.Range e, String unitKey) {
        if (e == null) return Lang.t("progress.need_" + unitKey);
        return e.preliminary() ? Lang.t("progress.preliminary", e.samples()) : Lang.t("progress.based_on_" + unitKey, e.samples());
    }

    /**
     * The Catacombs-style progression centrepiece: big level (the one place the XP colour is used large), the XP
     * bar, and a row of borderless figures. {@code full} adds the current-XP figure.
     *
     * @return height used
     */
    public static float levelHero(Ui ui, String id, Rect r, dev.skycore.data.StatDef def, dev.skycore.data.ProfileData.StatProgress p, boolean full) {
        Gfx g = ui.g;
        Theme t = ui.t;
        boolean maxed = p.need <= 0;
        float y = r.y();
        String lv = String.valueOf(Math.max(0, p.level));
        g.text(lv, r.x(), y, t.xp(), 3.0f, Weight.SEMIBOLD);
        float lw = g.width(lv, 3.0f, Weight.SEMIBOLD);
        if (!maxed) g.text("→ " + (p.level + 1), r.x() + lw + 10, y + 13, t.textDim(), 1.3f, Weight.MEDIUM);
        else g.text(Lang.t("progress.maxed"), r.x() + lw + 10, y + 13, t.success(), 1.2f, Weight.SEMIBOLD);
        g.textRight(Lang.percent(maxed ? 1 : p.frac()), r.right(), y + 6, t.text(), 1.6f, Weight.SEMIBOLD);

        Evidence ev = Evidence.of(p.status());
        String cap = Lang.t("progress.current_level");
        g.text(cap, r.x(), y + 31, t.textDim(), 0.9f, Weight.MEDIUM);
        evidenceLabel(ui, r.x() + g.width(cap, 0.9f, Weight.MEDIUM) + 10, y + 31, ev, evidenceTip(ev, statusTip(p)), 0.9f);

        Rect bar = new Rect(r.x(), y + 48, r.w(), 6);
        ui.progress(id, bar, maxed ? 1 : p.frac(), t.progress(), t.progressEnd());
        if (p.need > 0 && !maxed) g.text(Lang.num(p.xp) + " / " + Lang.num(p.need) + " XP", r.x(), y + 59, t.textMute(), 0.85f);

        ui.separator(r.x(), y + 76, r.w());
        int n = full ? 4 : 3;
        Rect[] c = figureColumns(ui, new Rect(r.x(), y + 86, r.w(), 38), n);
        dev.skycore.data.Estimate.Range e = dev.skycore.data.Estimate.forNextLevel(def, p);
        int i = 0;
        if (full) {
            statTile(ui, c[i++], Lang.t("progress.current_xp"), maxed ? "—" : Lang.num(p.xp), maxed ? null : Lang.t("progress.of_xp", Lang.compact(p.need)), 0, ev, evidenceTip(ev, statusTip(p)));
        }
        statTile(ui, c[i++], Lang.t("progress.xp_remaining"), maxed || p.need <= 0 ? "—" : Lang.compact(p.remaining()), null, 0,
                maxed || p.need <= 0 ? Evidence.UNAVAILABLE : ev, evidenceTip(maxed || p.need <= 0 ? Evidence.UNAVAILABLE : ev, statusTip(p)));
        Evidence eev = e == null ? Evidence.UNAVAILABLE : Evidence.ESTIMATED;
        statTile(ui, c[i], Lang.t("progress.est_runs"), e == null ? "—" : e.format(), estimateSub(e, "runs"), e != null && e.preliminary() ? t.warning() : 0, eev,
                evidenceTip(eev, Lang.t("progress.est_tip")));
        i++;
        double avg = dev.skycore.data.Estimate.averageGain(p);
        if (full) {
            Evidence aev = avg > 0 ? Evidence.ESTIMATED : Evidence.UNAVAILABLE;
            statTile(ui, c[i], Lang.t("progress.avg_run"), avg > 0 ? Lang.num(avg) : "—", Lang.t("progress.runs_recorded", p.count), 0, aev, evidenceTip(aev, null));
        } else {
            Evidence aev = avg > 0 ? Evidence.ESTIMATED : Evidence.UNAVAILABLE;
            statTile(ui, c[i], Lang.t("progress.avg_run"), avg > 0 ? Lang.compact(avg) : "—", null, 0, aev, evidenceTip(aev, null));
        }
        return 124;
    }

    /** Big "23 → 24" level header with bar (Bestiary and similar single-level cards). */
    public static void levelHeader(Ui ui, String id, Rect r, int level, int next, float frac, boolean maxed) {
        Gfx g = ui.g;
        Theme t = ui.t;
        g.text(String.valueOf(Math.max(0, level)), r.x(), r.y(), t.text(), 2.6f, Weight.SEMIBOLD);
        ui.dim(Lang.t("progress.current_level"), r.x(), r.y() + 28);
        if (!maxed) {
            g.textRight(String.valueOf(next), r.right(), r.y(), t.textMute(), 2.6f, Weight.SEMIBOLD);
            g.textRight(Lang.t("progress.next_level"), r.right(), r.y() + 28, t.textDim(), 1f, false);
        } else {
            g.textRight(Lang.t("progress.maxed"), r.right(), r.y() + 6, t.success(), 1.3f, true);
        }
        Rect bar = new Rect(r.x(), r.y() + 44, r.w(), 6);
        ui.progress(id, bar, maxed ? 1 : frac, t.listBar(), t.listBar());
        String pct = Lang.percent(maxed ? 1 : frac);
        float pw = g.width(pct, 0.85f);
        float px = Math.max(r.x(), Math.min(r.right() - pw, r.x() + r.w() * frac - pw / 2));
        g.text(pct, px, bar.bottom() + 4, t.textDim(), 0.85f);
    }

    /** Empty state with icon, title, body; optional action button (returns true when clicked). */
    public static boolean empty(Ui ui, String id, Rect r, Icon icon, String title, String body, String action) {
        Gfx g = ui.g;
        float cy = r.y() + Math.max(8, (r.h() - 86) / 2);
        Rect chip = new Rect(r.cx() - 15, cy, 30, 30);
        iconChip(ui, chip, icon, ui.t.textDim());
        g.textCentered(title, r.cx(), cy + 38, ui.t.text(), 1f, Weight.SEMIBOLD);
        var lines = g.wrap(body, Math.min(r.w() - 20, 280), 0.9f);
        for (int i = 0; i < lines.size(); i++) g.textCentered(lines.get(i), r.cx(), cy + 51 + i * 10, ui.t.textDim(), 0.9f, false);
        if (action != null) {
            float bw = g.width(action) + 30;
            return ui.button(id, new Rect(r.cx() - bw / 2, cy + 56 + lines.size() * 10, bw, 18), action, Icon.CALIBRATE, Ui.Style.SECONDARY);
        }
        return false;
    }

    public static void dashedH(Gfx g, float x, float y, float w, int c) {
        float seg = 3, gap = 3, h = 1f / g.s;
        for (float cx = x; cx < x + w; cx += seg + gap) g.rect(cx, y, Math.min(seg, x + w - cx), h, c);
    }

    // ------------------------------------------------------------------ charts
    public record Series(double[] values, int color, boolean area, boolean dashed, String name) {}

    /**
     * Line chart with grid, axis labels, gradient area and hover crosshair + tooltip.
     *
     * @param xLabel   label for x index (shown on axis and tooltip)
     * @param fmt      value formatter
     */
    public static void lineChart(Ui ui, String id, Rect r, Series[] series, IntFunction<String> xLabel, DoubleFunction<String> fmt, int labelEvery) {
        lineChart(ui, id, r, series, xLabel, xLabel, fmt, labelEvery);
    }

    /** @param tipLabel label used in the hover tooltip (e.g. full date + hour) */
    public static void lineChart(Ui ui, String id, Rect r, Series[] series, IntFunction<String> xLabel, IntFunction<String> tipLabel, DoubleFunction<String> fmt, int labelEvery) {
        Gfx g = ui.g;
        Theme t = ui.t;
        int n = series[0].values().length;
        double max = 0;
        for (Series s : series) for (double v : s.values()) max = Math.max(max, v);
        double top = niceCeil(max <= 0 ? 1 : max * 1.1);
        float left = Math.max(g.width(fmt.apply(top), 0.8f), g.width("0", 0.8f)) + 8;
        Rect plot = new Rect(r.x() + left, r.y() + 4, r.w() - left - 4, r.h() - 18);
        // grid
        for (int i = 0; i <= 4; i++) {
            float y = plot.y() + plot.h() * i / 4f;
            if (i == 4) g.rect(plot.x(), y, plot.w(), 1f / g.s, Col.alpha(t.border(), 0.9f));
            else dashedH(g, plot.x(), y, plot.w(), Col.alpha(t.border(), 0.45f));
            double v = top * (4 - i) / 4.0;
            g.textRight(fmt.apply(v), plot.x() - 5, y - 3, t.textMute(), 0.8f, false);
        }
        if (n < 2) return;
        float[] xs = new float[n];
        for (int i = 0; i < n; i++) xs[i] = plot.x() + plot.w() * i / (float) (n - 1);
        // x labels
        for (int i = 0; i < n; i += Math.max(1, labelEvery)) g.textCentered(xLabel.apply(i), xs[i], plot.bottom() + 5, t.textMute(), 0.8f, false);
        float reveal = revealOnChange(ui, id, series);
        float[][] ys = new float[series.length][];
        for (int si = series.length - 1; si >= 0; si--) {
            Series s = series[si];
            float[] y = new float[n];
            for (int i = 0; i < n; i++) y[i] = (float) (plot.bottom() - plot.h() * Math.min(1, s.values()[i] / top) * reveal);
            ys[si] = y;
            if (s.area() && t.gradients) g.area(xs, y, plot.y(), plot.bottom(), Col.alpha(s.color(), 0.26f), Col.alpha(s.color(), 0.0f));
            if (s.dashed()) {
                // dashed comparison line: draw short curve pieces
                for (int i = 0; i < n - 1; i++) {
                    float[] px = {xs[i], (xs[i] + xs[i + 1]) / 2};
                    float[] py = {y[i], (y[i] + y[i + 1]) / 2};
                    g.curve(px, py, 1.2f, Col.alpha(s.color(), 0.65f));
                }
            } else {
                g.curve(xs, y, 1.7f, s.color());
            }
        }
        // hover
        if (ui.hover(plot.expand(4))) {
            int idx = Math.round((ui.mx - plot.x()) / plot.w() * (n - 1));
            idx = Math.max(0, Math.min(n - 1, idx));
            float hx = xs[idx];
            g.rect(hx, plot.y(), 1f / g.s * Math.max(1, Math.round(g.s / 2)), plot.h(), Col.alpha(t.text(), 0.25f));
            StringBuilder tip = new StringBuilder(tipLabel.apply(idx));
            for (int si = 0; si < series.length; si++) {
                float py = ys[si][idx];
                g.circle(hx, py, 5, Col.alpha(series[si].color(), 0.25f));
                g.circle(hx, py, 3f, series[si].color());
                g.circle(hx, py, 1.5f, 0xFFFFFFFF);
                tip.append('\n').append(series[si].name()).append(": ").append(fmt.apply(series[si].values()[idx]));
            }
            ui.tooltipNow(tip.toString());
        }
    }

    /** Charts animate in only when their data (or the selected period) changes – not on every frame/visit. */
    static float revealOnChange(Ui ui, String id, Series[] series) {
        long h = 17;
        for (Series se : series) for (double v : se.values()) h = h * 31 + Double.hashCode(v);
        Long prev = ui.state(id + "#hash", Long.MIN_VALUE);
        if (prev != h) { ui.setState(id + "#hash", h); ui.setAnim(id + "#reveal", 0f); }
        return ui.anim(id + "#reveal", 1, 5f);
    }

    static float revealOnChange(Ui ui, String id, double[][] data) {
        long h = 17;
        for (double[] d : data) for (double v : d) h = h * 31 + Double.hashCode(v);
        Long prev = ui.state(id + "#hash", Long.MIN_VALUE);
        if (prev != h) { ui.setState(id + "#hash", h); ui.setAnim(id + "#rev", 0f); }
        return ui.anim(id + "#rev", 1, 5f);
    }

    public static double niceCeil(double v) {
        double exp = Math.pow(10, Math.floor(Math.log10(v)));
        double f = v / exp;
        double nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
        return nf * exp;
    }

    /**
     * Grouped bar chart with grid + value axis. series[s][i] = value of series s at index i.
     * Hovering a group shows every series' exact value.
     */
    public static void groupedBars(Ui ui, String id, Rect r, double[][] series, int[] colors, String[] names,
                                   IntFunction<String> xLabel, IntFunction<String> tipLabel, DoubleFunction<String> fmt, int labelEvery) {
        Gfx g = ui.g;
        Theme t = ui.t;
        int n = series[0].length;
        double max = 0;
        for (double[] s : series) for (double v : s) max = Math.max(max, v);
        double top = niceCeil(max <= 0 ? 1 : max * 1.1);
        float left = Math.max(g.width(fmt.apply(top), 0.8f), g.width("0", 0.8f)) + 8;
        Rect plot = new Rect(r.x() + left, r.y() + 4, r.w() - left - 4, r.h() - 18);
        for (int i = 0; i <= 4; i++) {
            float y = plot.y() + plot.h() * i / 4f;
            dashedH(g, plot.x(), y, plot.w(), Col.alpha(t.border(), 0.7f));
            g.textRight(fmt.apply(top * (4 - i) / 4.0), plot.x() - 5, y - 3, t.textMute(), 0.8f, false);
        }
        if (n == 0) return;
        float slot = plot.w() / n;
        float groupW = slot * 0.72f;
        float bw = groupW / series.length;
        float reveal = revealOnChange(ui, id, series);
        for (int i = 0; i < n; i++) {
            float gx = plot.x() + i * slot + (slot - groupW) / 2;
            Rect hit = new Rect(plot.x() + i * slot, plot.y(), slot, plot.h());
            boolean hov = ui.hover(hit);
            if (hov) g.roundRect(hit.x() + 1, hit.y(), hit.w() - 2, hit.h(), 4, Col.alpha(t.text(), 0.05f));
            for (int s = 0; s < series.length; s++) {
                float h = (float) (Math.min(1, series[s][i] / top) * plot.h() * reveal);
                if (h <= 0.01f) continue;
                float bx = gx + s * bw + bw * 0.08f, w = bw * 0.84f;
                int c = colors[s];
                int topC = hov ? Col.mix(c, 0xFFFFFFFF, 0.2f) : c;
                g.roundRectV(bx, plot.bottom() - Math.max(1.5f, h), w, Math.max(1.5f, h), Math.min(3, w / 2), topC, t.gradients ? Col.alpha(c, 0.45f) : topC);
            }
            if (i % Math.max(1, labelEvery) == 0) g.textCentered(xLabel.apply(i), plot.x() + i * slot + slot / 2, plot.bottom() + 5, t.textMute(), 0.8f, false);
            if (hov) {
                StringBuilder tip = new StringBuilder(tipLabel.apply(i));
                for (int s = 0; s < series.length; s++) tip.append('\n').append(names[s]).append(": ").append(fmt.apply(series[s][i]));
                ui.tooltipNow(tip.toString());
            }
        }
    }

    /** Vertical mini bars (e.g. XP of recent runs). */
    public static void bars(Ui ui, String id, Rect r, double[] values, int c1, int c2, IntFunction<String> tip) {
        Gfx g = ui.g;
        int n = values.length;
        if (n == 0) return;
        double max = 0;
        for (double v : values) max = Math.max(max, v);
        if (max <= 0) max = 1;
        float gap = Math.max(2, r.w() / n * 0.3f);
        float bw = (r.w() - gap * (n - 1)) / n;
        float reveal = revealOnChange(ui, id, new double[][]{values});
        for (int i = 0; i < n; i++) {
            float h = (float) (values[i] / max) * r.h() * reveal;
            Rect b = new Rect(r.x() + i * (bw + gap), r.bottom() - Math.max(2, h), bw, Math.max(2, h));
            boolean hov = ui.hover(new Rect(b.x(), r.y(), b.w(), r.h()));
            int top = hov ? Col.mix(c1, 0xFFFFFFFF, 0.25f) : c1;
            g.roundRectV(b.x(), b.y(), b.w(), b.h(), Math.min(3, bw / 2), top, ui.t.gradients ? Col.alpha(c2, 0.55f) : top);
            if (hov && tip != null) ui.tooltipNow(tip.apply(i));
        }
    }

    /** Donut with legend-ready colours and a centre label. */
    public static void donut(Ui ui, String id, float cx, float cy, float radius, double[] values, int[] colors, String center, String centerSub) {
        double sum = 0;
        for (double v : values) sum += v;
        float[] fr = new float[values.length];
        float reveal = ui.anim(id + "#rev", 1, 4f);
        for (int i = 0; i < values.length; i++) fr[i] = sum <= 0 ? 0 : (float) (values[i] / sum) * reveal;
        ui.g.donut(cx, cy, radius, radius * 0.72f, fr, colors, ui.t.track());
        ui.g.textCentered(center, cx, cy - (centerSub != null ? 8 : 4), ui.t.text(), 1.15f, true);
        if (centerSub != null) ui.g.textCentered(centerSub, cx, cy + 5, ui.t.textDim(), 0.8f, false);
    }

    /**
     * The XP-per-hour chart: a single line in the XP colour on a muted three-line grid. Steps without enough recorded
     * playtime are {@link Double#NaN}: they leave a gap and a small dash on the baseline (unknown is not zero), and the
     * line is never drawn across them. Hover shows the exact value. Cheap by design: no animation, no area fill.
     */
    public static void rateChart(Ui ui, String id, Rect r, double[] values, IntFunction<String> xLabel, IntFunction<String> tipLabel, DoubleFunction<String> fmt) {
        Gfx g = ui.g;
        Theme t = ui.t;
        int n = values.length;
        if (n < 2) return;
        double max = 0;
        for (double v : values) if (!Double.isNaN(v)) max = Math.max(max, v);
        double top = niceCeil(max <= 0 ? 1 : max * 1.1);
        float left = Math.max(g.width(fmt.apply(top), 0.8f), g.width("0", 0.8f)) + 8;
        Rect plot = new Rect(r.x() + left, r.y() + 4, r.w() - left - 4, r.h() - 20);
        for (int i = 0; i <= 2; i++) {
            float y = plot.y() + plot.h() * i / 2f;
            if (i == 2) g.rect(plot.x(), y, plot.w(), 1f / g.s, Col.alpha(t.border(), 0.9f));
            else dashedH(g, plot.x(), y, plot.w(), Col.alpha(t.border(), 0.5f));
            g.textRight(fmt.apply(top * (2 - i) / 2.0), plot.x() - 5, y - 3, t.textMute(), 0.8f, false);
        }
        float[] xs = new float[n], ys = new float[n];
        int every = Math.max(1, (int) Math.ceil(n / 8.0));
        for (int i = 0; i < n; i++) {
            xs[i] = plot.x() + 8 + (plot.w() - 16) * i / (float) (n - 1);
            ys[i] = Double.isNaN(values[i]) ? Float.NaN : (float) (plot.bottom() - plot.h() * Math.min(1, values[i] / top));
            if (i % every == 0) g.textCentered(xLabel.apply(i), xs[i], plot.bottom() + 5, t.textMute(), 0.8f, false);
        }
        int line = t.xp();
        int last = -1;
        for (int i = 0; i < n; i++) if (!Float.isNaN(ys[i])) last = i;
        int i = 0;
        while (i < n) {
            if (Float.isNaN(ys[i])) { // unknown: a dash on the baseline, no line through it
                g.roundRect(xs[i] - 3, plot.bottom() - 4, 6, 1.2f, 0.6f, t.textMute());
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < n && !Float.isNaN(ys[j + 1])) j++;
            if (j > i) g.curve(java.util.Arrays.copyOfRange(xs, i, j + 1), java.util.Arrays.copyOfRange(ys, i, j + 1), 1.8f, line);
            i = j + 1;
        }
        for (int k = 0; k < n; k++) {
            if (Float.isNaN(ys[k])) continue;
            g.circle(xs[k], ys[k], k == last ? 3.4f : 2.4f, line);
            g.circle(xs[k], ys[k], k == last ? 1.4f : 1.0f, t.card());
        }
        if (ui.hover(plot.expand(4))) {
            int idx = 0;
            float best = Float.MAX_VALUE;
            for (int k = 0; k < n; k++) if (Math.abs(xs[k] - ui.mx) < best) { best = Math.abs(xs[k] - ui.mx); idx = k; }
            g.rect(xs[idx], plot.y(), 1f / g.s * Math.max(1, Math.round(g.s / 2)), plot.h(), Col.alpha(t.text(), 0.2f));
            String val = Double.isNaN(values[idx]) ? Lang.t("evidence.unavailable") : fmt.apply(values[idx]);
            ui.tooltipNow(tipLabel.apply(idx) + "\n" + Lang.t("sessions.xp_hour") + ": " + val);
        }
    }

    // ------------------------------------------------------------------ color picker
    private static final int[] PALETTE = {0xFF3BC9F5, 0xFF22E4FF, 0xFF4F7BFF, 0xFF6B7BFF, 0xFF8B5CF6, 0xFFC86BFF, 0xFFFF6BD5,
            0xFFF2557A, 0xFFF5B13B, 0xFF3DF2A4, 0xFF34D399, 0xFFFFFFFF, 0xFF8D96B8, 0xFF141A30, 0xFF0A0D1C, 0xFF000000};

    /** Swatch + hex label; returns true when clicked. */
    public static boolean swatch(Ui ui, String id, Rect r, int color) {
        boolean hov = ui.hover(r);
        if (hov) ui.setCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.POINTING_HAND);
        float h = ui.hoverAnim(id, hov);
        checker(ui.g, r, Math.min(ui.radius(), 5));
        ui.g.roundRect(r.x(), r.y(), r.w(), r.h(), Math.min(ui.radius(), 5), color);
        ui.g.roundOutline(r.x(), r.y(), r.w(), r.h(), Math.min(ui.radius(), 5), (1 + h) / ui.g.s, Col.mix(Col.alpha(0xFFFFFFFF, 0.25f), ui.t.primary(), h));
        if (ui.pressedOn(r)) ui.active = id;
        if (ui.releasedThisFrame() && id.equals(ui.active) && hov) { ui.active = null; Ui.clickSound(); return true; }
        return false;
    }

    private static void checker(Gfx g, Rect r, float rad) {
        g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, 0xFFBFC4D0);
        float c = 4;
        for (float y = r.y(); y < r.bottom(); y += c)
            for (float x = r.x() + (((int) ((y - r.y()) / c)) % 2) * c; x < r.right(); x += c * 2)
                g.rect(x, y, Math.min(c, r.right() - x), Math.min(c, r.bottom() - y), 0xFF8A90A0);
    }

    /** Full picker inside r (no reset target). */
    public static int colorPicker(Ui ui, String id, Rect r, int color, boolean withAlpha) { return colorPicker(ui, id, r, color, withAlpha, color); }

    /**
     * Colour picker: saturation/value square + hue strip, preset swatches, hex and R/G/B inputs (validated),
     * before/after preview and a Reset button that restores {@code resetTo}. Returns the (possibly) new colour.
     */
    public static int colorPicker(Ui ui, String id, Rect r, int color, boolean withAlpha, int resetTo) {
        Gfx g = ui.g;
        Theme t = ui.t;
        int original = ui.state(id + "#orig", color);
        float[] hsv = ui.state(id + "#hsv", Col.toHsv(color));
        int alpha = Col.a(color);
        boolean dragging = (id + "#sv").equals(ui.active) || (id + "#hue").equals(ui.active);
        if (Col.fromHsv(hsv[0], hsv[1], hsv[2], alpha) != color && !dragging) {
            float[] n = Col.toHsv(color);
            if (n[1] == 0 || n[2] == 0) n[0] = hsv[0];
            hsv = n;
            ui.setState(id + "#hsv", hsv);
        }
        float rightW = 112;
        float sq = Math.min(r.w() - rightW - 32, r.h() - (withAlpha ? 70 : 52));
        Rect sv = new Rect(r.x(), r.y(), sq, sq);
        Rect hue = new Rect(sv.right() + 10, r.y(), 12, sq);
        int cols = Math.max(16, Math.round(sq * g.s / 3));
        float cw = sq / cols;
        for (int i = 0; i < cols; i++) {
            float sa = (i + 0.5f) / cols;
            g.gradientV(sv.x() + i * cw, sv.y(), cw + 0.01f, sv.h(), Col.fromHsv(hsv[0], sa, 1, 255), 0xFF000000);
        }
        g.roundOutline(sv.x(), sv.y(), sv.w(), sv.h(), 3, 1f / g.s, Col.alpha(0xFFFFFFFF, 0.15f));
        int steps = 48;
        for (int i = 0; i < steps; i++) g.gradientV(hue.x(), hue.y() + hue.h() * i / steps, hue.w(), hue.h() / steps + 0.01f,
                Col.fromHsv(i / (float) steps, 1, 1, 255), Col.fromHsv((i + 1) / (float) steps, 1, 1, 255));
        boolean changed = false;
        if (ui.pressedOn(sv)) ui.active = id + "#sv";
        if (ui.pressedOn(hue)) ui.active = id + "#hue";
        if ((id + "#sv").equals(ui.active) && ui.mouseDown()) {
            hsv[1] = clamp((ui.mx - sv.x()) / sv.w());
            hsv[2] = 1 - clamp((ui.my - sv.y()) / sv.h());
            changed = true;
        }
        if ((id + "#hue").equals(ui.active) && ui.mouseDown()) { hsv[0] = clamp((ui.my - hue.y()) / hue.h()) * 0.9999f; changed = true; }
        float kx = sv.x() + hsv[1] * sv.w(), ky = sv.y() + (1 - hsv[2]) * sv.h();
        g.ringOutline(kx, ky, 5f, 1.5f, 0xFFFFFFFF);
        float hy = hue.y() + hsv[0] * hue.h();
        g.roundRect(hue.x() - 2, hy - 2, hue.w() + 4, 4, 2, 0xFFFFFFFF);
        int out = changed ? Col.fromHsv(hsv[0], hsv[1], hsv[2], alpha) : color;

        // right column: preview, hex, rgb
        float rx = hue.right() + 14, rw = r.right() - rx;
        Rect before = new Rect(rx, r.y(), rw / 2 - 2, 22), after = new Rect(rx + rw / 2 + 2, r.y(), rw / 2 - 2, 22);
        checker(g, before, 5);
        checker(g, after, 5);
        g.roundRect(before.x(), before.y(), before.w(), before.h(), 5, original);
        g.roundRect(after.x(), after.y(), after.w(), after.h(), 5, out);
        g.text(Lang.t("picker.before"), before.x(), before.bottom() + 3, t.textMute(), 0.75f);
        g.textRight(Lang.t("picker.after"), after.right(), after.bottom() + 3, t.textMute(), 0.75f, Weight.REGULAR);
        if (ui.hover(before)) ui.tooltip(Lang.t("picker.before"));
        if (ui.hover(after)) ui.tooltip(Lang.t("picker.after"));
        float fy = r.y() + 38;
        g.text(Lang.t("picker.hex"), rx, fy, t.textDim(), 0.85f, Weight.MEDIUM);
        String hexKey = id + "#hex";
        String hex = ui.state(hexKey, "");
        if (!hexKey.equals(ui.focus)) hex = Theme.hex(out).substring(withAlpha ? 1 : 3);
        boolean hexValid = hex.length() == 6 || hex.length() == 8;
        String nh = ui.textField(hexKey, new Rect(rx, fy + 11, rw, 18), hex, withAlpha ? "AARRGGBB" : "RRGGBB", withAlpha ? 8 : 6,
                c -> Character.digit(c, 16) >= 0, !hexKey.equals(ui.focus) || hexValid);
        ui.setState(hexKey, nh);
        if (hexKey.equals(ui.focus) && (nh.length() == 6 || nh.length() == 8)) {
            try {
                int parsed = Theme.parse(nh);
                if (nh.length() == 6) parsed = Col.withAlpha(parsed, alpha);
                if (parsed != out) { out = parsed; ui.setState(id + "#hsv", Col.toHsv(out)); }
            } catch (Exception ignored) {}
        }
        fy += 36;
        String[] chan = {"R", "G", "B"};
        int[] vals = {Col.r(out), Col.g(out), Col.b(out)};
        float cwid = (rw - 8) / 3;
        for (int i = 0; i < 3; i++) {
            float cx = rx + i * (cwid + 4);
            g.text(chan[i], cx, fy, t.textDim(), 0.85f, Weight.MEDIUM);
            String k = id + "#c" + i;
            String v = ui.state(k, "");
            if (!k.equals(ui.focus)) v = String.valueOf(vals[i]);
            int parsed = -1;
            try { parsed = v.isEmpty() ? -1 : Integer.parseInt(v); } catch (NumberFormatException ignored) {}
            String nv = ui.textField(k, new Rect(cx, fy + 11, cwid, 18), v, "0", 3, Character::isDigit, !k.equals(ui.focus) || (parsed >= 0 && parsed <= 255));
            ui.setState(k, nv);
            if (k.equals(ui.focus)) {
                try {
                    int n = Integer.parseInt(nv);
                    if (n >= 0 && n <= 255 && n != vals[i]) {
                        vals[i] = n;
                        out = Col.argb(Col.a(out), vals[0], vals[1], vals[2]);
                        ui.setState(id + "#hsv", Col.toHsv(out));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        if (ui.button(id + "#reset", new Rect(rx, sv.bottom() - 20, rw, 20), Lang.t("action.reset"), Icon.RESET, Ui.Style.GHOST, out != resetTo)) {
            out = resetTo;
            ui.setState(id + "#hsv", Col.toHsv(out));
        }

        // below: alpha + presets
        float y = sv.bottom() + 10;
        if (withAlpha) {
            g.text(Lang.t("appearance.alpha"), r.x(), y + 2, t.textDim(), 0.9f, Weight.MEDIUM);
            float na = ui.slider(id + "#alpha", new Rect(r.x() + 46, y, r.w() - 46, 12), Col.a(out), 0, 255, 1);
            if ((int) na != Col.a(out)) out = Col.withAlpha(out, (int) na);
            y += 20;
        }
        g.text(Lang.t("picker.presets"), r.x(), y, t.textDim(), 0.85f, Weight.MEDIUM);
        y += 12;
        float sw = (r.w() - 15 * 4) / 16f;
        for (int i = 0; i < PALETTE.length; i++) {
            Rect sr = new Rect(r.x() + i * (sw + 4), y, sw, Math.min(sw, 16));
            if (swatch(ui, id + "#pal" + i, sr, PALETTE[i])) {
                out = Col.withAlpha(PALETTE[i], Col.a(out));
                ui.setState(id + "#hsv", Col.toHsv(out));
            }
        }
        return out;
    }

    /** Forget a picker's "before" colour (call when opening it for a new target). */
    public static void resetPicker(Ui ui, String id) {
        ui.setState(id + "#orig", null);
        ui.setState(id + "#hsv", null);
    }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }

    // ------------------------------------------------------------------ data status
    public static int statusColor(Theme t, dev.skycore.data.DataStatus s) {
        return switch (s) {
            case VERIFIED -> t.success();
            case EVENT_TRACKED -> t.primary();
            case MANUAL -> t.textDim();
            case ESTIMATED -> t.warning();
            default -> t.textMute();
        };
    }

    /**
     * Small evidence marker next to a value: solid dot = observed, hollow ring = estimated, dash = unavailable.
     * The shape carries the meaning (never colour alone). The tooltip names the state and keeps the detailed record.
     */
    public static void statusDot(Ui ui, float x, float y, dev.skycore.data.ProfileData.StatProgress p) {
        Evidence e = Evidence.of(p.status());
        evidenceMark(ui, x + 3, y + 3, e);
        if (ui.hover(new Rect(x - 3, y - 3, 12, 12))) ui.tooltip(evidenceTip(e, statusTip(p)));
    }

    public static String statusTip(dev.skycore.data.ProfileData.StatProgress p) {
        dev.skycore.data.DataStatus s = p.status();
        StringBuilder sb = new StringBuilder(Lang.t(s.key())).append('\n').append(Lang.t(s.key() + ".desc"));
        if (p.source != null) sb.append('\n').append(Lang.t("status.source", Lang.t("source." + p.source)));
        if (p.verifiedAt > 0) sb.append('\n').append(Lang.t("status.verified_at", ago(p.verifiedAt)));
        if (p.derivedEvents > 0) sb.append('\n').append(Lang.t("status.events_since", p.derivedEvents));
        return sb.toString();
    }

    /** Inline "● Observed" label (mark + word). Returns its width. */
    public static float statusLabel(Ui ui, float x, float y, dev.skycore.data.ProfileData.StatProgress p, float scale) {
        Evidence e = Evidence.of(p.status());
        return evidenceLabel(ui, x, y, e, evidenceTip(e, statusTip(p)), scale);
    }

    /** "5m ago" style relative time. */
    public static String ago(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < 60_000) return Lang.t("time.just_now");
        if (d < 3600_000) return Lang.t("time.min_ago", d / 60_000);
        if (d < 86_400_000) return Lang.t("time.hour_ago", d / 3600_000);
        return Lang.t("time.day_ago", d / 86_400_000);
    }
}
