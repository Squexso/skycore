package dev.skycore.ui.pages;

import dev.skycore.core.Config;
import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
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
import net.minecraft.client.Minecraft;

import java.util.EnumMap;

/**
 * Themes, colours, typography and effects – organised in collapsible sections so only what the user is
 * working on is visible. Every change applies live to the whole interface (and the preview on the right).
 */
public class AppearancePage implements Page {
    private Theme.Slot picking;

    @Override public String id() { return "appearance"; }

    /** Open the colour picker for a slot (dev capture / deep links). */
    public void openPicker(Theme.Slot slot) { picking = slot; }
    @Override public Icon icon() { return Icon.APPEARANCE; }

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        Theme t = ui.t;
        Config.ThemeSettings ts = Skycore.config().theme;
        boolean wide = area.w() >= 660;
        float previewW = wide ? Math.min(250, area.w() * 0.36f) : 0;
        float mw = area.w() - (wide ? previewW + 16 : 0);
        float y = area.y() + 6;

        y = themeSection(ui, area.x(), y, mw, ts);
        y = colorsSection(ui, area.x(), y, mw, t);
        y = typographySection(ui, area.x(), y, mw);
        y = effectsSection(ui, area.x(), y, mw, ts);

        float bw = ui.g.width(Lang.t("appearance.reset"), 1f, Weight.MEDIUM) + 30;
        if (ui.button("ap#reset", new Rect(area.x(), y, bw, 20), Lang.t("appearance.reset"), Icon.RESET, Ui.Style.GHOST)) {
            Skycore.config().theme = new Config.ThemeSettings();
            Config.WindowState fresh = new Config.WindowState(), w = Skycore.config().window;
            w.opacity = fresh.opacity; w.backgroundDim = fresh.backgroundDim; w.blur = fresh.blur;
            Theme.rebuild();
            Skycore.markDirty();
        }
        if (ui.hover(new Rect(area.x(), y, bw, 20))) ui.tooltip(Lang.t("tip.appearance_reset"));
        y += 30;

        if (wide) preview(ui, new Rect(area.right() - previewW, area.y() + 26, previewW, 290));
        else { preview(ui, new Rect(area.x(), y + 20, area.w(), 290)); y += 330; }

        if (picking != null) pickerModal(ui, screen, picking);
        return Math.max(y, area.y() + (wide ? 340 : 0)) - area.y();
    }

    // ------------------------------------------------------------------ theme
    private float themeSection(Ui ui, float x, float y, float w, Config.ThemeSettings ts) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("ap.theme", x, y, w, Lang.t("appearance.themes"), Lang.t("appearance.themes_desc"), Icon.APPEARANCE, true);
        float cy = s.contentY();
        if (s.visible()) {
            String[] names = new String[Theme.PRESETS.length];
            for (int i = 0; i < names.length; i++) names[i] = Lang.t("theme." + Theme.PRESETS[i]);
            int cur = java.util.Arrays.asList(Theme.PRESETS).indexOf(ts.preset);
            g.text(Lang.t("appearance.theme"), s.x(), cy + 7, t.text(), 1f, Weight.MEDIUM);
            float ddw = Math.min(240, s.w() * 0.6f);
            Rect dd = new Rect(s.x() + s.w() - ddw, cy, ddw, 24);
            int sel = ui.dropdown("ap#theme", dd, names, Math.max(0, cur), (r, i, selected, hovered) -> {
                EnumMap<Theme.Slot, Integer> c = paletteFor(Theme.PRESETS[i]);
                float sx = r.x() + 6, sy = r.cy() - 6;
                g.roundRect(sx, sy, 34, 12, 4, c.get(Theme.Slot.BACKGROUND));
                g.circle(sx + 9, sy + 6, 3.2f, c.get(Theme.Slot.PRIMARY));
                g.circle(sx + 17, sy + 6, 3.2f, c.get(Theme.Slot.SECONDARY));
                g.circle(sx + 25, sy + 6, 3.2f, c.get(Theme.Slot.CARD));
                g.text(names[i], sx + 42, r.cy() - 4.5f, selected || hovered ? t.text() : Col.mix(t.textDim(), t.text(), 0.5f), 1f, Weight.MEDIUM);
            }, 22);
            if (sel != cur) {
                if (Theme.PRESETS[sel].equals("custom") && ts.custom.isEmpty()) Theme.setCustomColor(Theme.Slot.PRIMARY, t.primary());
                else { ts.preset = Theme.PRESETS[sel]; Theme.rebuild(); Skycore.markDirty(); }
            }
            cy += 32;
            for (String l : g.wrap(Lang.t("theme.desc." + ts.preset), s.w(), 0.9f)) { g.text(l, s.x(), cy, t.textDim(), 0.9f); cy += 10.5f; }
        }
        return ui.endSection(s, cy);
    }

    private static EnumMap<Theme.Slot, Integer> paletteFor(String preset) {
        if (!preset.equals("custom")) return Theme.presetColors(preset);
        Config.ThemeSettings ts = Skycore.config().theme;
        if (ts.custom.isEmpty()) return Theme.presetColors("midnight");
        Config.ThemeSettings c = new Config.ThemeSettings();
        c.preset = "custom";
        c.custom = ts.custom;
        return Theme.resolveColors(c);
    }

    // ------------------------------------------------------------------ colours
    private float colorsSection(Ui ui, float x, float y, float w, Theme t) {
        Gfx g = ui.g;
        Ui.Section s = ui.beginSection("ap.colors", x, y, w, Lang.t("appearance.colors"), Lang.t("appearance.colors_hint"), Icon.CHART_BAR, false);
        float cy = s.contentY();
        if (s.visible()) {
            int cols = s.w() >= 420 ? 2 : 1;
            float cw = (s.w() - 12 * (cols - 1)) / cols;
            for (int i = 0; i < Theme.EDITABLE.length; i++) {
                Theme.Slot slot = Theme.EDITABLE[i];
                Rect r = new Rect(s.x() + (i % cols) * (cw + 12), cy + (i / cols) * 28, cw, 24);
                boolean hov = ui.hover(r);
                if (hov) g.roundRect(r.x() - 6, r.y(), r.w() + 6, r.h(), ui.radius(), Col.alpha(t.surface(), 0.7f));
                g.text(Lang.t("color." + slot.name().toLowerCase()), r.x(), r.cy() - 4.5f, t.text(), 1f, Weight.MEDIUM);
                g.textRight(Theme.hex(t.c(slot)).substring(3), r.right() - 34, r.cy() - 4.5f, t.textMute(), 0.85f, Weight.REGULAR);
                if (Widgets.swatch(ui, "sw" + slot, new Rect(r.right() - 28, r.y() + 3, 24, 18), t.c(slot))) {
                    picking = slot;
                    Widgets.resetPicker(ui, "picker" + slot);
                    ui.resetModalAnim();
                }
            }
            cy += ((Theme.EDITABLE.length + cols - 1) / cols) * 28;
        }
        return ui.endSection(s, cy);
    }

    private void pickerModal(Ui ui, SkycoreScreen screen, Theme.Slot slot) {
        Config.ThemeSettings ts = Skycore.config().theme;
        String base = ts.basePreset == null || ts.basePreset.isEmpty() ? "midnight" : ts.basePreset;
        int resetTo = Theme.presetColors("custom".equals(ts.preset) ? base : ts.preset).get(slot);
        ui.modal(screen.screenRect(), 400, 330, Lang.t("appearance.pick", Lang.t("color." + slot.name().toLowerCase())), body -> {
            int cur = Theme.get().c(slot);
            int nc = Widgets.colorPicker(ui, "picker" + slot, body.withH(body.h() - 28), cur, false, resetTo);
            if (nc != cur) Theme.setCustomColor(slot, nc);
            if (ui.button("pick#done", new Rect(body.right() - 90, body.bottom() - 20, 90, 20), Lang.t("action.done"), Icon.CHECK, Ui.Style.PRIMARY)) picking = null;
        }, () -> picking = null);
    }

    // ------------------------------------------------------------------ typography
    private float typographySection(Ui ui, float x, float y, float w) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("ap.type", x, y, w, Lang.t("appearance.typography"), Lang.t("appearance.typography_desc"), Icon.EDIT, false);
        float cy = s.contentY();
        if (s.visible()) {
            Rect box = new Rect(s.x(), cy, s.w(), 92);
            ui.tile(box);
            g.text(Lang.t("appearance.font_sample_title"), box.x() + 12, box.y() + 10, t.text(), 1.3f, Weight.SEMIBOLD);
            g.text(Lang.t("appearance.type_label"), box.x() + 12, box.y() + 32, t.text(), 1f, Weight.MEDIUM);
            g.text(Lang.t("appearance.font_sample_body"), box.x() + 12, box.y() + 46, t.textDim(), 1f, Weight.REGULAR);
            g.text("33,600", box.x() + 12, box.y() + 62, t.text(), 1.6f, Weight.BOLD);
            String[] ws = {"Regular", "Medium", "SemiBold", "Bold"};
            Weight[] wt = {Weight.REGULAR, Weight.MEDIUM, Weight.SEMIBOLD, Weight.BOLD};
            for (int i = 0; i < ws.length; i++) g.textRight(ws[i], box.right() - 12, box.y() + 12 + i * 18, t.textDim(), 0.9f, wt[i]);
            cy += 100;
            Config cfg = Skycore.config();
            String sv = cfg.uiScale <= 0 ? Lang.t("settings.scale_auto", Minecraft.getInstance().getWindow().getGuiScale()) : Lang.dec(cfg.uiScale) + "×";
            float ns = ui.sliderRow("ap#scale", new Rect(s.x(), cy, s.w(), 30), Lang.t("settings.scale"), cfg.uiScale, 0, 4, 0.5f, sv, Lang.t("tip.scale"));
            if (ns != cfg.uiScale) { cfg.uiScale = ns < 1 ? 0 : ns; Skycore.markDirty(); }
            cy += 36;
            for (String l : g.wrap(Lang.t("appearance.font_note"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textMute(), 0.85f); cy += 10; }
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ effects
    private float effectsSection(Ui ui, float x, float y, float w, Config.ThemeSettings ts) {
        Ui.Section s = ui.beginSection("ap.effects", x, y, w, Lang.t("appearance.effects"), Lang.t("appearance.effects_desc"), Icon.SPARK, false);
        float cy = s.contentY();
        if (s.visible()) {
            Config.WindowState win = Skycore.config().window;
            Rect[] two = new Rect(s.x(), cy, s.w(), 30).columns(2, 18);
            float glow = ui.sliderRow("ap#glow", two[0], Lang.t("appearance.glow"), ts.glow, 0, 1, 0.05f, Lang.percent(ts.glow), Lang.t("tip.glow"));
            float rad = ui.sliderRow("ap#rad", two[1], Lang.t("appearance.radius"), ts.radius, 0, 14, 1, Lang.num(ts.radius) + " px", Lang.t("tip.radius"));
            cy += 38;
            two = new Rect(s.x(), cy, s.w(), 30).columns(2, 18);
            float op = ui.sliderRow("ap#op", two[0], Lang.t("appearance.opacity"), win.opacity, 0.5f, 1, 0.01f, Lang.percent(win.opacity), Lang.t("tip.opacity"));
            float dim = ui.sliderRow("ap#dim", two[1], Lang.t("appearance.dim"), win.backgroundDim, 0, 0.9f, 0.05f, Lang.percent(win.backgroundDim), Lang.t("tip.dim"));
            cy += 40;
            Rect[] tg = new Rect(s.x() - 8, cy, s.w() + 8, 26).columns(2, 10);
            boolean borders = ui.toggleRow("ap#borders", tg[0], Lang.t("appearance.borders"), null, ts.borders, Lang.t("tip.borders"));
            boolean grad = ui.toggleRow("ap#grad", tg[1], Lang.t("appearance.gradients"), null, ts.gradients, Lang.t("tip.gradients"));
            cy += 28;
            tg = new Rect(s.x() - 8, cy, s.w() + 8, 26).columns(2, 10);
            boolean hover = ui.toggleRow("ap#hover", tg[0], Lang.t("appearance.hover"), null, ts.hoverEffects, Lang.t("tip.hover"));
            boolean blur = ui.toggleRow("ap#blur", tg[1], Lang.t("appearance.blur"), null, win.blur, Lang.t("tip.blur"));
            cy += 34;
            ui.separator(s.x(), cy, s.w());
            cy += 8;
            tg = new Rect(s.x() - 8, cy, s.w() + 8, 30).columns(2, 10);
            boolean reduce = ui.toggleRow("ap#reduce", tg[0], Lang.t("appearance.reduce_motion"), null, !ts.animations, Lang.t("tip.reduce_motion"));
            float ai = ts.animationIntensity;
            if (!reduce) ai = ui.sliderRow("ap#ai", new Rect(tg[1].x() + 8, tg[1].y(), tg[1].w() - 8, 30), Lang.t("appearance.anim_intensity"),
                    ts.animationIntensity, 0.3f, 2, 0.1f, Lang.percent(ts.animationIntensity), Lang.t("tip.anim_intensity"));
            else ui.g.text(Lang.t("appearance.motion_off"), tg[1].x() + 8, tg[1].cy() - 4.5f, ui.t.textMute(), 0.9f);
            cy += 34;
            if (glow != ts.glow || rad != ts.radius || borders != ts.borders || grad != ts.gradients || hover != ts.hoverEffects
                    || op != win.opacity || dim != win.backgroundDim || blur != win.blur || reduce == ts.animations || ai != ts.animationIntensity) {
                ts.glow = glow; ts.radius = rad; ts.borders = borders; ts.gradients = grad; ts.hoverEffects = hover;
                ts.animations = !reduce; ts.animationIntensity = ai;
                win.opacity = op; win.backgroundDim = dim; win.blur = blur;
                Theme.rebuild();
                Skycore.markDirty();
            }
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ preview
    /** Live preview of core components using the current theme. */
    private void preview(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        g.text(Lang.t("appearance.preview"), r.x(), r.y() - 20, t.text(), 1.1f, Weight.SEMIBOLD);
        ui.card(r, false, "ap#preview");
        Rect in = r.inset(14);
        Widgets.iconChip(ui, new Rect(in.x(), in.y(), 18, 18), Icon.DUNGEONS, t.textDim());
        g.text(Lang.t("stat.catacombs"), in.x() + 25, in.y() + 5, t.text(), 1f, Weight.SEMIBOLD);
        Widgets.badge(ui, in.right() - g.width(Lang.t("progress.tracking"), 0.85f, Weight.MEDIUM) - 14, in.y() + 2, Lang.t("progress.tracking"), t.success());
        g.text("23", in.x(), in.y() + 28, t.primary(), 2.2f, Weight.BOLD);
        g.textRight("24", in.right(), in.y() + 28, t.textDim(), 2.2f, Weight.BOLD);
        ui.progress(null, new Rect(in.x(), in.y() + 54, in.w(), 7), 0.72f, t.progress(), t.progressEnd());
        Rect[] tiles = new Rect(in.x(), in.y() + 70, in.w(), 46).columns(2, 8);
        Widgets.statTile(ui, tiles[0], Lang.t("progress.xp_remaining"), "34,000", null, 0);
        Widgets.statTile(ui, tiles[1], Lang.t("progress.est_runs"), "3–5", null, 0);
        double[] demo = {3, 5, 4, 7, 6, 9, 8};
        Widgets.lineChart(ui, "ap#chart", new Rect(in.x(), in.y() + 126, in.w(), 90),
                new Widgets.Series[]{new Widgets.Series(demo, t.chart(0), true, false, "XP")}, i -> String.valueOf(i + 1), v -> Lang.num(v), 2);
        Rect[] btn = new Rect(in.x(), in.bottom() - 22, in.w(), 20).columns(2, 8);
        ui.button("ap#pv1", btn[0], Lang.t("appearance.primary_btn"), Icon.CHECK, Ui.Style.PRIMARY);
        ui.button("ap#pv2", btn[1], Lang.t("appearance.secondary_btn"), null, Ui.Style.SECONDARY);
        g.text(g.ellipsize(Lang.t("appearance.preview_note"), r.w() / 0.8f, 1f), r.x(), r.bottom() + 6, t.textMute(), 0.8f);
    }
}
