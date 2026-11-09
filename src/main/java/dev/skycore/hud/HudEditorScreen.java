package dev.skycore.hud;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.skycore.core.Lang;
import dev.skycore.data.Progress;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Fullscreen HUD layout editor over the live game. Drag widgets, drag the corner handle or Ctrl+scroll to
 * resize, Shift disables snapping. A floating inspector edits the selected widget.
 */
public class HudEditorScreen extends Screen {
    private final Screen parent;
    private final Ui ui = new Ui();
    private final HudCanvas canvas = new HudCanvas();
    private final HudInspector inspector = new HudInspector();
    private boolean sample = true;
    private float px = -1, py = 8;
    private boolean panelOpen = true;
    private float dragDX, dragDY;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("SKYCORE HUD Editor"));
        this.parent = parent;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float d) {
        if (minecraft.level == null) extractPanorama(g, d);
        g.fill(0, 0, width, height, 0x40000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float delta) {
        float s = minecraft.getWindow().getGuiScale();
        Gfx g = new Gfx(gg, s);
        var mh = minecraft.mouseHandler;
        var win = minecraft.getWindow();
        long frameStart = dev.skycore.core.DevStress.ENABLED ? System.nanoTime() : 0;
        float[] devMouse = dev.skycore.core.DevStress.mouse;
        if (devMouse != null) ui.begin(g, devMouse[0], devMouse[1]); else
        ui.begin(g, (float) (mh.xpos() * win.getWidth() / Math.max(1, win.getScreenWidth()) / s),
                (float) (mh.ypos() * win.getHeight() / Math.max(1, win.getScreenHeight()) / s));
        Theme t = ui.t;
        float sw = width, sh = height;
        Rect full = new Rect(0, 0, sw, sh);

        // toolbar
        float tbw = Math.min(sw - 20, 430);
        // toolbar sits at the bottom so it never covers widgets anchored to the top of the screen
        Rect tb = new Rect((sw - tbw) / 2, sh - 32, tbw, 26);
        Progress.previewOverride = sample;
        try {
            ui.guard("hud editor canvas", () -> canvas.render(ui, "ed#canvas", full, sw, sh, false));
        } finally {
            Progress.previewOverride = false;
        }
        ui.occlude(tb);
        ui.overlay(() -> {
            g.glow(tb.x(), tb.y(), tb.w(), tb.h(), 10, Col.alpha(0xFF000000, 0.5f));
            g.roundRect(tb.x(), tb.y(), tb.w(), tb.h(), 8, Col.alpha(t.card(), 0.96f));
            g.roundOutline(tb.x(), tb.y(), tb.w(), tb.h(), 8, 1f / g.s, Col.mix(t.border(), t.primary(), 0.3f));
            g.logo(tb.x() + 6, tb.y() + 4, 18);
            g.text(g.ellipsize(HudStore.activeProfile(), 90, 1f), tb.x() + 28, tb.y() + 9, t.text(), 1f, true);
            float bx = tb.right() - 4;
            bx -= 64;
            if (ui.button("ed#done", new Rect(bx, tb.y() + 3, 60, 20), Lang.t("action.done"), Icon.CHECK, Ui.Style.PRIMARY)) onClose();
            bx -= 24;
            if (ui.iconButton("ed#reset", new Rect(bx, tb.y() + 3, 20, 20), Icon.RESET, Lang.t("hud.reset_layout"))) HudStore.resetActive();
            bx -= 22;
            if (ui.iconButton("ed#grid", new Rect(bx, tb.y() + 3, 20, 20), Icon.OVERVIEW, Lang.t("hud.grid"), canvas.grid, t.textDim())) canvas.grid = !canvas.grid;
            bx -= 22;
            if (ui.iconButton("ed#panel", new Rect(bx, tb.y() + 3, 20, 20), Icon.SETTINGS, Lang.t("hud.inspector"), panelOpen, t.textDim())) panelOpen = !panelOpen;
            bx -= 116;
            String[] modes = {Lang.t("hud.mode.live"), Lang.t("hud.mode.sample")};
            sample = ui.segmented("ed#mode", new Rect(bx, tb.y() + 3, 112, 20), modes, sample ? 1 : 0) == 1;
        });

        // hint
        if (!canvas.busy()) {
            String hint = Lang.t("hud.editor_hint", HudCanvas.modifierName());
            float hw = g.width(hint, 0.85f) + 16;
            g.roundRect((sw - hw) / 2, sh - 52, hw, 14, 7, Col.alpha(0xFF000000, 0.55f));
            g.textCentered(hint, sw / 2, sh - 48.5f, Col.alpha(0xFFFFFFFF, 0.8f), 0.85f, false);        }

        // floating inspector
        HudStore.WidgetState sel = HudStore.widget(canvas.selected);
        if (panelOpen && sel != null) {
            float pw = 224;
            if (px < 0) px = sw - pw - 8;
            px = Math.max(0, Math.min(sw - pw, px));
            Rect panel = new Rect(px, py, pw, Math.min(sh - py - 64, 420));
            ui.occlude(panel);
            final HudStore.WidgetState fsel = sel;
            ui.overlay(() -> {
                g.glow(panel.x(), panel.y(), panel.w(), panel.h(), 12, Col.alpha(0xFF000000, 0.55f));
                g.roundRect(panel.x(), panel.y(), panel.w(), panel.h(), 8, Col.alpha(t.card(), 0.97f));
                g.roundOutline(panel.x(), panel.y(), panel.w(), panel.h(), 8, 1f / g.s, Col.mix(t.border(), t.primary(), 0.3f));
                Rect head = new Rect(panel.x(), panel.y(), panel.w(), 16);
                g.icon(Icon.DRAG, panel.cx() - 5, panel.y() + 3, 10, t.textMute());
                if (ui.hover(head)) ui.setCursor(CursorTypes.RESIZE_ALL);
                if (ui.pressedOn(head)) { ui.active = "ed#panel#drag"; dragDX = ui.mx - px; dragDY = ui.my - py; }
                if ("ed#panel#drag".equals(ui.active) && ui.mouseDown()) { px = ui.mx - dragDX; py = Math.max(0, Math.min(sh - 40, ui.my - dragDY)); }
                Rect view = new Rect(panel.x() + 10, panel.y() + 18, panel.w() - 20, panel.h() - 24);
                float off = ui.scroll("ed#insp", view, ui.state("ed#insp#h", 400f));
                ui.pushClip(view);
                final float[] usedBox = {0};
                boolean inspOk = ui.guard("hud inspector", () -> usedBox[0] = inspector.render(ui, new Rect(view.x(), view.y() - off, view.w() - 6, 0), fsel, full));
                float used = inspOk ? usedBox[0] : 0;
                ui.popClip();
                ui.setState("ed#insp#h", used + 6);
            });
        }
        ui.end();
        g.end();
        if (dev.skycore.core.DevStress.ENABLED) dev.skycore.core.DevStress.frame("hudeditor", System.nanoTime() - frameStart);
    }

    /** Dev stress run only: centre of the Done button in UI units. */
    public float[] devDoneCenter() {
        float tbw = Math.min(width - 20, 430);
        float right = (width - tbw) / 2 + tbw;
        return new float[]{right - 4 - 64 + 30, height - 32 + 13};
    }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) { ui.onPress(e.button()); return true; }
    @Override public boolean mouseReleased(MouseButtonEvent e) { ui.onRelease(e.button()); return true; }
    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) { return true; }
    @Override public boolean mouseScrolled(double x, double y, double h, double v) { ui.onScroll(v); return true; }
    @Override public boolean charTyped(CharacterEvent e) { ui.onChar(e.codepointAsString()); return true; }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (ui.onKey(e)) return true;
        if (e.key() == 256) { if (!ui.modalOpen()) onClose(); return true; }
        return super.keyPressed(e);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        // called from the Done button during rendering and from Escape: replace the screen on the next tick, not mid-frame
        if (closeRequested) return;
        closeRequested = true;
        dev.skycore.core.Skycore.later(this::finish);
    }

    private boolean closeRequested;

    private void finish() {
        try { HudStore.save(); } catch (RuntimeException e) { Ui.logFault("HUD save on close", e); }
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(parent);
    }
}
