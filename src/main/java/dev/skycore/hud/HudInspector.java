package dev.skycore.hud;

import dev.skycore.core.Lang;
import dev.skycore.ui.Rect;
import dev.skycore.ui.Ui;
import dev.skycore.ui.Widgets;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.Minecraft;

/**
 * Property editor for one HUD widget, shared by HUD Studio and the fullscreen editor.
 * Organised as General · Widget appearance · Position (collapsible, state remembered).
 */
public final class HudInspector {
    private String picking; // "background" | "text" | "accent"

    /** @return height used */
    public float render(Ui ui, Rect r, HudStore.WidgetState w, Rect modalArea) {
        Gfx g = ui.g;
        Theme t = ui.t;
        float y = r.y();
        g.icon(HudWidgets.icon(w.id), r.x(), y + 1, 12, t.textDim());
        g.text(Lang.t(HudWidgets.nameKey(w.id)), r.x() + 18, y + 2, t.text(), 1.15f, Weight.SEMIBOLD);
        y += 22;
        y = general(ui, r, y, w);
        y = look(ui, r, y, w);
        y = position(ui, r, y, w);
        if (picking != null) pickerModal(ui, w, modalArea);
        return y - r.y();
    }

    // ------------------------------------------------------------------ general
    private float general(Ui ui, Rect r, float y, HudStore.WidgetState w) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("hud.general", r.x(), y, r.w(), Lang.t("hud.section.general"), null, null, true);
        float cy = s.contentY();
        if (s.visible()) {
            boolean en = ui.toggleRow("hi#en" + w.id, new Rect(s.x() - 8, cy, s.w() + 8, 24), Lang.t("hud.visible"), null, w.enabled, Lang.t("tip.hud_visible"));
            if (en != w.enabled) { w.enabled = en; HudStore.changed(); }
            cy += 30;
            ui.caption(Lang.t("hud.title_text"), s.x(), cy);
            String nt = ui.textField("hi#title" + w.id, new Rect(s.x(), cy + 13, s.w(), 20), w.title, Lang.t("hud.title_auto"), 32, c -> c >= 32);
            if (!nt.equals(w.title)) { w.title = nt; HudStore.changed(); }
            cy += 40;
            ui.caption(Lang.t("hud.visibility"), s.x(), cy);
            String[] vis = {"auto", "always", "dungeon", "mining"};
            String[] visL = {Lang.t("hud.vis.auto"), Lang.t("hud.vis.always"), Lang.t("hud.vis.dungeon"), Lang.t("hud.vis.mining")};
            int vi = Math.max(0, java.util.Arrays.asList(vis).indexOf(w.visibility));
            Rect seg = new Rect(s.x(), cy + 13, s.w(), 20);
            if (ui.hover(seg)) ui.tooltip(Lang.t("tip.hud_visibility"));
            int nvi = ui.segmented("hi#vis", seg, visL, vi);
            if (nvi != vi) { w.visibility = vis[nvi]; HudStore.changed(); }
            cy += 42;
            ui.caption(Lang.t("hud.info"), s.x(), cy);
            cy += 12;
            for (String k : HudWidgets.lineKeys(w.id)) {
                boolean v = ui.toggleRow("hi#l" + k, new Rect(s.x() - 8, cy, s.w() + 8, 22), Lang.t("hud.line." + k), null, w.line(k));
                if (v != w.line(k)) { w.lines.put(k, v); HudStore.changed(); }
                cy += 22;
            }
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ appearance
    private float look(Ui ui, Rect r, float y, HudStore.WidgetState w) {
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("hud.look", r.x(), y, r.w(), Lang.t("hud.section.look"), null, null, false);
        float cy = s.contentY();
        if (s.visible()) {
            boolean changed = false;
            float sc = ui.sliderRow("hi#scale", new Rect(s.x(), cy, s.w(), 30), Lang.t("hud.scale"), w.scale, 0.5f, 3f, 0.05f, Lang.percent(w.scale), Lang.t("tip.hud_scale"));
            cy += 34;
            float tsc = ui.sliderRow("hi#ts", new Rect(s.x(), cy, s.w(), 30), Lang.t("hud.text_size"), w.textScale, 0.6f, 1.8f, 0.05f, Lang.percent(w.textScale), Lang.t("tip.hud_text"));
            cy += 34;
            float op = ui.sliderRow("hi#op", new Rect(s.x(), cy, s.w(), 30), Lang.t("hud.opacity"), w.opacity, 0f, 1f, 0.05f, Lang.percent(w.opacity), Lang.t("tip.hud_opacity"));
            cy += 34;
            Rect[] two = new Rect(s.x(), cy, s.w(), 30).columns(2, 10);
            float pad = ui.sliderRow("hi#pad", two[0], Lang.t("hud.padding"), w.padding, 2, 14, 1, Lang.num(w.padding));
            float sp = ui.sliderRow("hi#sp", two[1], Lang.t("hud.spacing"), w.spacing, 0, 8, 1, Lang.num(w.spacing));
            cy += 36;
            if (sc != w.scale || tsc != w.textScale || op != w.opacity || pad != w.padding || sp != w.spacing) {
                w.scale = sc; w.textScale = tsc; w.opacity = op; w.padding = pad; w.spacing = sp;
                changed = true;
            }
            boolean st = ui.toggleRow("hi#title", new Rect(s.x() - 8, cy, s.w() + 8, 22), Lang.t("hud.show_title"), null, w.showTitle);
            boolean si = ui.toggleRow("hi#icon", new Rect(s.x() - 8, cy + 22, s.w() + 8, 22), Lang.t("hud.show_icon"), null, w.showIcon);
            boolean sb = ui.toggleRow("hi#bar", new Rect(s.x() - 8, cy + 44, s.w() + 8, 22), Lang.t("hud.show_bar"), null, w.showBar);
            if (st != w.showTitle || si != w.showIcon || sb != w.showBar) { w.showTitle = st; w.showIcon = si; w.showBar = sb; changed = true; }
            cy += 72;
            ui.caption(Lang.t("hud.colors"), s.x(), cy);
            cy += 13;
            String[] keys = {"background", "text", "accent"};
            Rect[] cols = new Rect(s.x(), cy, s.w(), 32).columns(3, 8);
            for (int i = 0; i < 3; i++) {
                int c = color(w, keys[i], t);
                ui.g.text(Lang.t("hud.color." + keys[i]), cols[i].x(), cols[i].y(), t.textDim(), 0.85f);
                if (Widgets.swatch(ui, "hi#sw" + keys[i], new Rect(cols[i].x(), cols[i].y() + 11, cols[i].w(), 16), c)) {
                    picking = keys[i];
                    Widgets.resetPicker(ui, "hpick" + keys[i]);
                    ui.resetModalAnim();
                }
            }
            cy += 36;
            boolean themeAccent = w.accent == null || w.accent.isEmpty();
            boolean ta = ui.toggleRow("hi#ta", new Rect(s.x() - 8, cy, s.w() + 8, 22), Lang.t("hud.theme_accent"), null, themeAccent, Lang.t("tip.hud_theme_accent"));
            if (ta != themeAccent) { w.accent = ta ? "" : Theme.hex(t.primary()); changed = true; }
            cy += 26;
            if (changed) HudStore.changed();
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ position
    private float position(Ui ui, Rect r, float y, HudStore.WidgetState w) {
        Ui.Section s = ui.beginSection("hud.pos", r.x(), y, r.w(), Lang.t("hud.section.position"), null, null, false);
        float cy = s.contentY();
        if (s.visible()) {
            var win = Minecraft.getInstance().getWindow();
            float sw = win.getGuiScaledWidth(), sh = win.getGuiScaledHeight();
            HudWidgets.Content c = HudWidgets.cached(w.id);
            Rect size = HudWidgets.measure(w, c);
            float maxX = Math.max(0, 1 - size.w() / sw), maxY = Math.max(0, 1 - size.h() / sh);
            float nx = ui.sliderRow("hi#x", new Rect(s.x(), cy, s.w(), 30), Lang.t("hud.pos_x"), Math.min(w.x, maxX), 0, maxX, 0.002f, Lang.percent(w.x / Math.max(0.001f, maxX)));
            cy += 34;
            float ny = ui.sliderRow("hi#y", new Rect(s.x(), cy, s.w(), 30), Lang.t("hud.pos_y"), Math.min(w.y, maxY), 0, maxY, 0.002f, Lang.percent(w.y / Math.max(0.001f, maxY)));
            cy += 36;
            if (nx != w.x || ny != w.y) { w.x = nx; w.y = ny; HudStore.changed(); }
            ui.caption(Lang.t("hud.snap_to"), s.x(), cy);
            cy += 13;
            float[][] anchors = {{0, 0}, {0.5f, 0}, {1, 0}, {0, 0.5f}, {0.5f, 0.5f}, {1, 0.5f}, {0, 1}, {0.5f, 1}, {1, 1}};
            float cell = Math.min(22, (s.w() - 16) / 9);
            Rect grid = new Rect(s.x(), cy, cell * 3 + 8, cell * 3 + 8);
            ui.g.roundRect(grid.x(), grid.y(), grid.w(), grid.h(), 6, ui.t.surface());
            for (int i = 0; i < 9; i++) {
                Rect b = new Rect(grid.x() + 4 + (i % 3) * cell, grid.y() + 4 + (i / 3) * cell, cell, cell);
                if (ui.iconButton("hi#anchor" + i, b.inset(2), Icon.MOVE, Lang.t("hud.anchor." + i))) {
                    float margin = 4 / sw, marginY = 4 / sh;
                    w.x = Math.max(0, Math.min(maxX, anchors[i][0] * maxX + (anchors[i][0] == 0 ? margin : anchors[i][0] == 1 ? -margin : 0)));
                    w.y = Math.max(0, Math.min(maxY, anchors[i][1] * maxY + (anchors[i][1] == 0 ? marginY : anchors[i][1] == 1 ? -marginY : 0)));
                    HudStore.changed();
                }
            }
            ui.g.text(ui.g.ellipsize(Lang.t("hud.pos_hint"), (s.w() - grid.w() - 10) / 0.85f, 1f), grid.right() + 10, grid.y() + 4, ui.t.textMute(), 0.85f);
            cy += grid.h() + 6;
        }
        return ui.endSection(s, cy);
    }

    private void pickerModal(Ui ui, HudStore.WidgetState w, Rect modalArea) {
        String key = picking;
        Theme t = Theme.get();
        HudStore.WidgetState def = new HudStore.WidgetState();
        int resetTo = switch (key) {
            case "background" -> Theme.parse(def.background);
            case "text" -> Theme.parse(def.text);
            default -> t.primary();
        };
        ui.modal(modalArea, 400, 350, Lang.t("hud.pick", Lang.t("hud.color." + key)), body -> {
            int cur = color(w, key, t);
            int nc = Widgets.colorPicker(ui, "hpick" + key, body.withH(body.h() - 28), cur, true, resetTo);
            if (nc != cur) { set(w, key, nc); HudStore.changed(); }
            if (ui.button("hpick#done", new Rect(body.right() - 90, body.bottom() - 20, 90, 20), Lang.t("action.done"), Icon.CHECK, Ui.Style.PRIMARY)) picking = null;
        }, () -> picking = null);
    }

    private static int color(HudStore.WidgetState w, String key, Theme t) {
        return switch (key) {
            case "background" -> Theme.parse(w.background);
            case "text" -> Theme.parse(w.text);
            default -> w.accent == null || w.accent.isEmpty() ? t.primary() : Theme.parse(w.accent);
        };
    }

    private static void set(HudStore.WidgetState w, String key, int c) {
        switch (key) {
            case "background" -> w.background = Theme.hex(c);
            case "text" -> w.text = Theme.hex(c);
            default -> w.accent = Theme.hex(c);
        }
    }
}
