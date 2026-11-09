package dev.skycore.hud;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.skycore.ui.Rect;
import dev.skycore.ui.Ui;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;

/**
 * Interactive HUD layout surface. Coordinates: the canvas rect is in Ui units; the HUD is laid out in GUI
 * pixels (sw × sh) and mapped onto the canvas with factor k. Used at full size by the editor screen and as a
 * miniature in the HUD Studio page.
 */
public final class HudCanvas {
    public String selected = "catacombs";
    private String dragging, resizing;
    private float grabX, grabY, startScale, startW;
    public boolean grid = true;
    private float guideX = -1, guideY = -1;

    public void render(Ui ui, String id, Rect canvas, float sw, float sh, boolean miniature) {
        Gfx g = ui.g;
        Theme t = ui.t;
        float k = canvas.w() / sw;
        if (miniature) {
            g.roundRectV(canvas.x(), canvas.y(), canvas.w(), canvas.h(), 8, Col.mix(t.bg(), 0xFF2A4A6A, 0.35f), Col.mix(t.bg(), 0xFF1B3A2A, 0.35f));
            g.roundOutline(canvas.x(), canvas.y(), canvas.w(), canvas.h(), 8, 1f / g.s, t.border());
            // hotbar silhouette for orientation
            float hw = 182 * k, hh = 22 * k;
            g.roundRect(canvas.cx() - hw / 2, canvas.bottom() - hh - 2 * k, hw, hh, 3 * k, Col.alpha(0xFF000000, 0.35f));
        }
        ui.pushClip(canvas);
        if (grid) {
            float step = 20 * k;
            int gc = Col.alpha(t.text(), miniature ? 0.04f : 0.05f);
            for (float x = canvas.x() + step; x < canvas.right(); x += step) g.rect(x, canvas.y(), 1f / g.s, canvas.h(), gc);
            for (float y = canvas.y() + step; y < canvas.bottom(); y += step) g.rect(canvas.x(), y, canvas.w(), 1f / g.s, gc);
        }
        // guides
        if (dragging != null) {
            if (guideX >= 0) g.rect(canvas.x() + guideX * k, canvas.y(), 1f / g.s * 2, canvas.h(), Col.alpha(t.primary(), 0.7f));
            if (guideY >= 0) g.rect(canvas.x(), canvas.y() + guideY * k, canvas.w(), 1f / g.s * 2, Col.alpha(t.primary(), 0.7f));
        }
        String hovered = null;
        for (HudStore.WidgetState w : HudStore.widgets()) {
            HudWidgets.Content c = HudWidgets.cached(w.id);
            Rect sz = HudWidgets.measure(w, c);
            float x = Math.max(0, Math.min(sw - sz.w(), w.x * sw)), y = Math.max(0, Math.min(sh - sz.h(), w.y * sh));
            Rect onCanvas = new Rect(canvas.x() + x * k, canvas.y() + y * k, sz.w() * k, sz.h() * k);
            float prev = g.alpha;
            if (!w.enabled) g.alpha *= 0.35f;
            g.push();
            g.translate(canvas.x(), canvas.y());
            g.g.pose().scale(k, k);
            HudWidgets.draw(g, w, c, x, y);
            g.pop();
            g.alpha = prev;
            boolean hov = ui.hover(onCanvas);
            if (hov) hovered = w.id;
            boolean sel = w.id.equals(selected);
            if (hov || sel) {
                int oc = sel ? t.primary() : Col.alpha(t.text(), 0.6f);
                g.roundOutline(onCanvas.x() - 2, onCanvas.y() - 2, onCanvas.w() + 4, onCanvas.h() + 4, 4, (sel ? 2f : 1f) / g.s * (miniature ? 1 : 2), oc);
            }
            if (sel) {
                Rect handle = new Rect(onCanvas.right() - 3, onCanvas.bottom() - 3, 7, 7);
                g.roundRect(handle.x(), handle.y(), handle.w(), handle.h(), 2, t.primary());
                if (ui.hover(handle.expand(2))) ui.setCursor(CursorTypes.RESIZE_ALL);
                if (ui.pressedOn(handle.expand(2))) { resizing = w.id; startScale = w.scale; startW = ui.mx - onCanvas.x(); }
            }
            if (hov && dragging == null && resizing == null) ui.setCursor(CursorTypes.RESIZE_ALL);
        }
        ui.popClip();
        // start drag (topmost = last drawn wins)
        if (hovered != null && ui.pressedThisFrame() && !ui.clickConsumed() && dragging == null && resizing == null) {
            ui.consumeClick();
            selected = hovered;
            dragging = hovered;
            HudStore.WidgetState w = HudStore.widget(hovered);
            grabX = (ui.mx - canvas.x()) / k - w.x * sw;
            grabY = (ui.my - canvas.y()) / k - w.y * sh;
            Ui.clickSound();
        }
        if (dragging != null) {
            HudStore.WidgetState w = HudStore.widget(dragging);
            if (!ui.mouseDown() || w == null) { dragging = null; HudStore.changed(); }
            else {
                Rect sz = HudWidgets.measure(w, HudWidgets.cached(w.id));
                float nx = (ui.mx - canvas.x()) / k - grabX, ny = (ui.my - canvas.y()) / k - grabY;
                guideX = guideY = -1;
                if (!freeMoveHeld(ui)) { // snapping (hold Shift – or Alt when Shift resizes – to move freely)
                    float snap = 5;
                    float cxw = nx + sz.w() / 2, cyw = ny + sz.h() / 2;
                    if (Math.abs(cxw - sw / 2) < snap) { nx = sw / 2 - sz.w() / 2; guideX = sw / 2; }
                    if (Math.abs(cyw - sh / 2) < snap) { ny = sh / 2 - sz.h() / 2; guideY = sh / 2; }
                    if (Math.abs(nx - 4) < snap) { nx = 4; guideX = 4; }
                    if (Math.abs(ny - 4) < snap) { ny = 4; guideY = 4; }
                    if (Math.abs(nx + sz.w() - (sw - 4)) < snap) { nx = sw - 4 - sz.w(); guideX = sw - 4; }
                    if (Math.abs(ny + sz.h() - (sh - 4)) < snap) { ny = sh - 4 - sz.h(); guideY = sh - 4; }
                }
                nx = Math.max(0, Math.min(sw - sz.w(), nx));
                ny = Math.max(0, Math.min(sh - sz.h(), ny));
                w.x = nx / sw;
                w.y = ny / sh;
            }
        }
        if (resizing != null) {
            HudStore.WidgetState w = HudStore.widget(resizing);
            if (!ui.mouseDown() || w == null) { resizing = null; HudStore.changed(); }
            else {
                float px = canvas.x() + w.x * sw * k;
                float nw = ui.mx - px;
                w.scale = Math.max(0.5f, Math.min(3f, Math.round(startScale * nw / Math.max(1, startW) * 20) / 20f));
            }
        }
        // <modifier> + scroll resizes the hovered (or selected) widget
        if (resizeHeld(ui)) {
            String target = hovered != null ? hovered : selected;
            float s = ui.takeScroll(canvas);
            HudStore.WidgetState w = target == null ? null : HudStore.widget(target);
            if (s != 0 && w != null) {
                w.scale = Math.max(0.5f, Math.min(3f, Math.round((w.scale + (s > 0 ? 0.05f : -0.05f)) * 20) / 20f));
                selected = target;
                HudStore.changed();
            }
        }
    }

    public boolean busy() { return dragging != null || resizing != null; }

    /** Configurable modifier for scroll-resizing (Settings → HUD). */
    public static boolean resizeHeld(Ui ui) {
        return switch (dev.skycore.core.Skycore.config().hudResizeModifier) {
            case "alt" -> ui.alt();
            case "shift" -> ui.shift();
            default -> ui.ctrl();
        };
    }

    private static boolean freeMoveHeld(Ui ui) {
        return "shift".equals(dev.skycore.core.Skycore.config().hudResizeModifier) ? ui.alt() : ui.shift();
    }

    /** Localised name of the resize modifier for hints. */
    public static String modifierName() {
        return dev.skycore.core.Lang.t("key." + dev.skycore.core.Skycore.config().hudResizeModifier);
    }
}
