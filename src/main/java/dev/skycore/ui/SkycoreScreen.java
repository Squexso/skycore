package dev.skycore.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.skycore.core.Config;
import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.data.Progress;
import dev.skycore.hud.HudRenderer;
import dev.skycore.tracking.HypixelTracker;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.pages.AppearancePage;
import dev.skycore.ui.pages.GoalsPage;
import dev.skycore.ui.pages.HudStudioPage;
import dev.skycore.ui.pages.SharePage;
import dev.skycore.ui.pages.NotificationsPage;
import dev.skycore.ui.pages.OverviewPage;
import dev.skycore.ui.pages.ProgressionPage;
import dev.skycore.ui.pages.SessionsPage;
import dev.skycore.ui.pages.SettingsPage;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The SKYCORE overlay. A real Minecraft {@link net.minecraft.client.gui.screens.Screen}: the game keeps
 * rendering behind it (optionally dimmed / blurred) and the window can be dragged, resized and rescaled.
 */
public class SkycoreScreen extends net.minecraft.client.gui.screens.Screen {
    public static final float MIN_W = 560, MIN_H = 360;
    private static final float HEADER = 46;

    private final Ui ui = new Ui();
    private final Page[] pages = {new OverviewPage(), new ProgressionPage(), new HudStudioPage(), new SessionsPage(), new GoalsPage(),
            new AppearancePage(), new NotificationsPage(), new SharePage(), new SettingsPage()};
    private static final int MAIN_PAGES = 5;
    private int page;
    private final float[] contentHeights = new float[pages.length];

    private float wx, wy, ww, wh; // window in UI units
    private float scale;
    private long openedAt;
    private long closingAt;
    private long pageSwitchedAt;
    private float dragDX, dragDY;
    private int resizeMode; // bit 1 = right, bit 2 = bottom

    public SkycoreScreen() {
        super(Component.literal("SKYCORE"));
        page = Math.max(0, Math.min(pages.length - 1, Skycore.config().lastPage));
    }

    public SkycoreScreen(int page) {
        this();
        this.page = page;
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        closingAt = 0;
        finishQueued = false;
        finished = false;
        layoutWindow();
        pages[page].onShow();
    }

    // ------------------------------------------------------------------ geometry
    public float uiScale() { return scale; }

    private float computeScale() {
        var win = Minecraft.getInstance().getWindow();
        float s = Skycore.config().uiScale > 0 ? Skycore.config().uiScale : win.getGuiScale();
        float fit = Math.min(win.getWidth() / (MIN_W + 20), win.getHeight() / (MIN_H + 20));
        // snap to half steps so the bitmap font stays as crisp as possible
        fit = Math.max(1f, (float) Math.floor(fit * 2) / 2f);
        return Math.max(1f, Math.min(s, fit));
    }

    public float screenW() { return Minecraft.getInstance().getWindow().getWidth() / scale; }

    public float screenH() { return Minecraft.getInstance().getWindow().getHeight() / scale; }

    private void layoutWindow() {
        scale = computeScale();
        Config.WindowState w = Skycore.config().window;
        float sw = screenW(), sh = screenH();
        ww = w.w > 0 ? w.w : Math.min(sw * 0.92f, 1000);
        wh = w.h > 0 ? w.h : Math.min(sh * 0.9f, 620);
        ww = Math.max(MIN_W, Math.min(sw - 8, ww));
        wh = Math.max(MIN_H, Math.min(sh - 8, wh));
        wx = w.x >= 0 ? w.x : (sw - ww) / 2;
        wy = w.y >= 0 ? w.y : (sh - wh) / 2;
        clampWindow();
    }

    private void clampWindow() {
        float sw = screenW(), sh = screenH();
        ww = Math.max(MIN_W, Math.min(sw, ww));
        wh = Math.max(MIN_H, Math.min(sh, wh));
        wx = Math.max(0, Math.min(sw - ww, wx));
        wy = Math.max(0, Math.min(sh - wh, wy));
    }

    public void resetWindow() {
        Config.WindowState w = Skycore.config().window;
        w.x = w.y = -1;
        w.w = w.h = 0;
        Skycore.markDirty();
        layoutWindow();
    }

    /** Rect covering the whole screen in UI units (for modals). */
    /** Dev stress run only: centre of the window's X button in UI units. */
    public float[] devCloseCenter() { return new float[]{wx + ww - 24, wy + 22}; }

    /** Rect covering the whole screen in UI units (for modals). */
    public Rect screenRect() { return new Rect(0, 0, screenW(), screenH()); }

    public void openPage(int i) {
        if (i == page) return;
        page = i;
        pageSwitchedAt = System.currentTimeMillis();
        Skycore.config().lastPage = i;
        Skycore.markDirty();
        pages[i].onShow();
    }

    public int pageIndex(Class<? extends Page> type) {
        for (int i = 0; i < pages.length; i++) if (type.isInstance(pages[i])) return i;
        return 0;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        layoutWindow();
    }

    // ------------------------------------------------------------------ rendering
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Config.WindowState w = Skycore.config().window;
        float a = openProgress();
        if (minecraft.level == null) extractPanorama(g, delta); // opened from the main menu
        else if (w.blur && a > 0.5f) extractBlurredBackground(g);
        if (w.backgroundDim > 0) g.fill(0, 0, width, height, Col.alpha(0xFF04050B, w.backgroundDim * a));
    }

    private float openProgress() {
        float k = Theme.get().animIntensity;
        if (k < 0.05f) return closingAt > 0 ? 0 : 1;
        long now = System.currentTimeMillis();
        if (closingAt > 0) return 1 - ease(Math.min(1, (now - closingAt) / (140f * k)));
        return ease(Math.min(1, (now - openedAt) / (200f * k)));
    }

    private static float ease(float x) { return 1 - (float) Math.pow(1 - x, 3); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float delta) {
        float newScale = computeScale();
        if (Math.abs(newScale - scale) > 0.001f) layoutWindow();
        if (closingAt > 0 && openProgress() <= 0.01f) { queueFinish(); return; } // draw nothing; the screen is replaced next tick
        Gfx g = new Gfx(gg, scale);
        long frameStart = dev.skycore.core.DevStress.ENABLED ? System.nanoTime() : 0;
        try {
            renderFrame(g);
            frameFaults = ui.faultsThisFrame() > 0 ? frameFaults + 1 : 0;
        } catch (RuntimeException e) {
            // never let a UI bug become a game crash: log it, restore the graphics state and keep the game running
            Ui.logFault("SkycoreScreen", e);
            g.unwind(1, 0);
            ui.recover();
            frameFaults++;
        }
        g.end();
        if (dev.skycore.core.DevStress.ENABLED) dev.skycore.core.DevStress.frame("page" + page, System.nanoTime() - frameStart);
        if (frameFaults >= 5) { // the screen keeps failing: leave it rather than loop
            frameFaults = 0;
            Skycore.LOG.error("SKYCORE: closing the window after repeated rendering faults");
            queueFinish();
        }
    }

    private int frameFaults;

    private void renderFrame(Gfx g) {
        var mh = minecraft.mouseHandler;
        float[] devMouse = dev.skycore.core.DevStress.mouse;
        if (devMouse != null) ui.begin(g, devMouse[0], devMouse[1]); else
        ui.begin(g, (float) (mh.xpos() * minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getScreenWidth()) / scale),
                (float) (mh.ypos() * minecraft.getWindow().getHeight() / Math.max(1, minecraft.getWindow().getScreenHeight()) / scale));
        Theme t = ui.t;
        float a = openProgress();
        g.alpha = a;
        g.push();
        g.scaleAround(wx + ww / 2, wy + wh / 2, 0.965f + 0.035f * a);
        handleWindowInteraction();
        Rect win = new Rect(wx, wy, ww, wh);
        drawFrame(win, t);
        float sideW = ww < 640 ? 56 : 164;
        drawSidebar(new Rect(wx, wy, sideW, wh), sideW < 100);
        Rect content = new Rect(wx + sideW, wy, ww - sideW, wh);
        drawHeader(content.top(HEADER));
        Rect view = new Rect(content.x() + 1, content.y() + HEADER, content.w() - 2, content.h() - HEADER - 1);
        float off = ui.scroll("page" + page, view.inset(0, 0), contentHeights[page] + 16);
        ui.pushClip(view);
        float ak = Theme.get().animIntensity;
        float pa = ak >= 0.05f ? ease(Math.min(1, (System.currentTimeMillis() - pageSwitchedAt) / (220f * ak))) : 1;
        float prevAlpha = g.alpha;
        g.alpha = prevAlpha * pa;
        g.push();
        g.translate(0, (1 - pa) * 8);
        final int pg = page;
        final Rect pageRect = new Rect(view.x() + 18, view.y() + 4 - off, view.w() - 36, view.h());
        if (!ui.guard("page " + pg, () -> contentHeights[pg] = pages[pg].render(ui, pageRect, this))) {
            g.text(Lang.t("error.page_failed"), pageRect.x(), pageRect.y() + 8, t.textDim(), 1f);
        }
        g.pop();
        g.alpha = prevAlpha;
        ui.popClip();
        drawResizeGrip(win);
        ui.end();
        g.pop();
        // toasts above the window
        g.alpha = 1;
        float gs = minecraft.getWindow().getGuiScale();
        g.layer();
        g.push();
        g.g.pose().scale(gs / scale, gs / scale);
        HudRenderer.drawToasts(g, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
        g.pop();
    }

    private void handleWindowInteraction() {
        Rect header = new Rect(wx, wy, ww, HEADER);
        Rect grip = new Rect(wx + ww - 14, wy + wh - 14, 14, 14);
        Rect rightEdge = new Rect(wx + ww - 4, wy + HEADER, 7, wh - HEADER - 14);
        Rect bottomEdge = new Rect(wx + 14, wy + wh - 4, ww - 28, 7);
        if (ui.hover(grip)) ui.setCursor(CursorTypes.RESIZE_ALL);
        else if (ui.hover(rightEdge)) ui.setCursor(CursorTypes.RESIZE_EW);
        else if (ui.hover(bottomEdge)) ui.setCursor(CursorTypes.RESIZE_NS);
        if (ui.pressedOn(grip)) { ui.active = "win#resize"; resizeMode = 3; }
        else if (ui.pressedOn(rightEdge)) { ui.active = "win#resize"; resizeMode = 1; }
        else if (ui.pressedOn(bottomEdge)) { ui.active = "win#resize"; resizeMode = 2; }
        // header drag (buttons in the header consume their own clicks first because they are drawn later; we only start drag on empty header space)
        if ("win#resize".equals(ui.active) && ui.mouseDown()) {
            if ((resizeMode & 1) != 0) ww = ui.mx - wx + 4;
            if ((resizeMode & 2) != 0) wh = ui.my - wy + 4;
            clampWindow();
            saveWindow();
        }
        if ("win#drag".equals(ui.active) && ui.mouseDown()) {
            ui.setCursor(CursorTypes.RESIZE_ALL);
            wx = ui.mx - dragDX;
            wy = ui.my - dragDY;
            clampWindow();
            saveWindow();
        }
        if (ui.hover(header) && ui.pressedThisFrame() && !ui.clickConsumed() && ui.active == null && !overHeaderButton()) {
            ui.consumeClick();
            ui.active = "win#drag";
            dragDX = ui.mx - wx;
            dragDY = ui.my - wy;
        }
    }

    /** The right part of the header holds badges and the close button; dragging starts only elsewhere. */
    private boolean overHeaderButton() { return ui.mx > wx + ww - 220 && ui.my < wy + 36; }

    private void saveWindow() {
        Config.WindowState w = Skycore.config().window;
        w.x = wx;
        w.y = wy;
        w.w = ww;
        w.h = wh;
        Skycore.markDirty();
    }

    private void drawFrame(Rect r, Theme t) {
        Gfx g = ui.g;
        float rad = t.radius + 6;
        // depth: a soft dark shadow, plus a faint accent halo only when glow is enabled
        // The shadow is clipped by the screen edge and shows through a see-through window as a dark band, so it only
        // gets as large as the free space around the window and fades out as the window becomes more transparent.
        float room = Math.min(Math.min(r.x(), r.y()), Math.min(screenW() - r.right(), screenH() - r.bottom()));
        float solid = Math.max(0f, Math.min(1f, (t.opacity - 0.6f) / 0.35f));
        float shadow = Math.min(28f, room) * solid;
        if (shadow >= 3f) g.glow(r.x(), r.y() + Math.min(6f, shadow / 4), r.w(), r.h(), shadow, Col.alpha(0xFF000000, 0.45f));
        if (t.glow > 0 && room >= 3f && solid > 0f) g.glow(r.x(), r.y(), r.w(), r.h(), Math.min(16f, room), Col.alpha(t.primary(), 0.12f * t.glow * solid));
        int top = Col.alpha(t.bg(), t.opacity), bottom = Col.alpha(t.gradients ? t.bgAlt() : t.bg(), t.opacity);
        g.roundRectV(r.x(), r.y(), r.w(), r.h(), rad, top, bottom);
        if (t.borders) g.roundOutline(r.x(), r.y(), r.w(), r.h(), rad, 1f / g.s * Math.max(1, Math.round(g.s / 2)), Col.alpha(t.border(), 0.9f));
    }

    private void drawSidebar(Rect r, boolean compact) {
        Gfx g = ui.g;
        Theme t = ui.t;
        g.rect(r.right() - 1f / g.s, r.y() + 14, 1f / g.s * Math.max(1, Math.round(g.s / 2)), r.h() - 28, Col.alpha(t.border(), 0.8f));
        // logo
        // flat mark: a ring around a solid core, one colour. The wordmark is the product name in the interface typeface.
        float lx = r.x() + (compact ? (r.w() - 20) / 2 : 18);
        g.ringOutline(lx + 10, r.y() + 26, 9, 1.6f, t.text());
        g.circle(lx + 10, r.y() + 26, 3.2f, t.text());
        if (!compact) g.text("SKYCORE", lx + 28, r.y() + 21, t.text(), 1.15f, dev.skycore.ui.gfx.Gfx.Weight.SEMIBOLD);
        float y = r.y() + 58;
        String[] keys = {"nav.overview", "nav.progression", "nav.hud", "nav.sessions", "nav.goals", "nav.appearance", "nav.notifications", "nav.share", "nav.settings"};
        for (int i = 0; i < pages.length; i++) {
            if (i == 0 && !compact) { ui.caption(Lang.t("nav.section.main"), r.x() + 18, y); y += 13; }
            if (i == MAIN_PAGES) {
                y += 6;
                ui.separator(r.x() + 14, y, r.w() - 28);
                y += 10;
                if (!compact) { ui.caption(Lang.t("nav.section.custom"), r.x() + 18, y); y += 13; }
            }
            Rect item = new Rect(r.x() + 8, y, r.w() - 16, 24);
            boolean sel = i == page;
            boolean hov = ui.hover(item);
            if (hov) ui.setCursor(CursorTypes.POINTING_HAND);
            float h = ui.hoverAnim("nav" + i, hov);
            float on = ui.anim("nav" + i + "#on", sel ? 1 : 0, 14f);
            if (on > 0 || h > 0) {
                // active = one surface step plus a hairline; hover = a lighter step. No accent bar, no tint.
                int bg = Col.mix(Col.alpha(t.surfaceHover(), h * 0.8f), t.surfaceHover(), on);
                g.roundRect(item.x(), item.y(), item.w(), item.h(), Math.min(t.radius, 9), bg);
                if (on > 0) g.roundOutline(item.x(), item.y(), item.w(), item.h(), Math.min(t.radius, 9), 1f / g.s * Math.max(1, Math.round(g.s / 2)), Col.alpha(t.border(), 0.9f * on));
            }
            int ic = Col.mix(Col.mix(t.textDim(), t.text(), h), t.text(), on);
            float ix = compact ? item.cx() - 6 : item.x() + 12;
            g.icon(pages[i].icon(), ix, item.cy() - 6, 12, ic);
            if (!compact) g.text(Lang.t(keys[i]), item.x() + 32, item.cy() - 4.5f, Col.mix(Col.mix(t.textDim(), t.text(), h), t.text(), on), 1f,
                    sel ? dev.skycore.ui.gfx.Gfx.Weight.SEMIBOLD : dev.skycore.ui.gfx.Gfx.Weight.MEDIUM);
            else if (hov) ui.tooltip(Lang.t(keys[i]));
            if (ui.pressedOn(item)) ui.active = "nav" + i;
            if (ui.releasedThisFrame() && ("nav" + i).equals(ui.active) && hov) { ui.active = null; Ui.clickSound(); openPage(i); }
            y += 27;
        }
        // profile chip at the bottom
        if (!compact && r.h() > 400) {
            Rect chip = new Rect(r.x() + 10, r.bottom() - 46, r.w() - 20, 36);
            ui.separator(r.x() + 14, chip.y() - 8, r.w() - 28);
            if (ui.hover(chip)) g.roundRect(chip.x(), chip.y(), chip.w(), chip.h(), Math.min(t.radius, 9), Col.alpha(t.surfaceHover(), 0.6f));
            g.text(g.ellipsize(Progress.playerName(), chip.w() - 24, 1f), chip.x() + 8, chip.y() + 7, t.text(), 1f, dev.skycore.ui.gfx.Gfx.Weight.SEMIBOLD);
            // identity indicator: confirmed by the game / waiting for confirmation (updates suspended) / offline
            boolean on = HypixelTracker.onSkyBlock(), sample = Progress.isSample();
            boolean confirmed = Progress.isIdentified();
            String state = sample ? "profile.state.sample" : !on ? "status.offline" : confirmed ? "profile.state.confirmed" : "profile.state.unknown";
            int dot = sample ? t.textDim() : !on ? t.textMute() : confirmed ? t.success() : t.warning();
            g.circle(chip.x() + 11, chip.y() + 24, 2.2f, dot);
            g.text(g.ellipsize(Lang.t(state), chip.w() - 28, 0.85f), chip.x() + 18, chip.y() + 20.5f, on && !confirmed && !sample ? t.warning() : t.textDim(), 0.85f);
            if (ui.hover(chip)) {
                ui.tooltip(Lang.t(state + ".tip"));
                ui.setCursor(CursorTypes.POINTING_HAND);
            }
            if (ui.pressedOn(chip)) {
                Ui.clickSound();
                dev.skycore.core.Skycore.config().expanded.put("set.profiles", true);
                openPage(pageIndex(SettingsPage.class));
            }
        }
    }

    private static final String[][] TITLES = {{"nav.overview", "page.overview.sub"}, {"nav.progression", "page.progression.sub"},
            {"nav.hud", "page.hud.sub"}, {"nav.sessions", "page.sessions.sub"}, {"nav.goals", "page.goals.sub"},
            {"nav.appearance", "page.appearance.sub"}, {"nav.notifications", "page.notifications.sub"}, {"nav.share", "page.share.sub"}, {"nav.settings", "page.settings.sub"}};

    private void drawHeader(Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        g.text(Lang.t(TITLES[page][0]), r.x() + 18, r.y() + 11, t.text(), 1.55f, dev.skycore.ui.gfx.Gfx.Weight.SEMIBOLD);
        g.text(Lang.t(TITLES[page][1]), r.x() + 18, r.y() + 29, t.textDim(), 0.9f);
        float bx = r.right() - 36;
        if (ui.iconButton("win#close", new Rect(bx, r.y() + 10, 24, 24), Icon.CLOSE, Lang.t("action.close"))) onClose();
        bx -= 6;
        if (Progress.isSample()) {
            // neutral chip: it informs, it is not a warning
            String s = Lang.t("badge.sample");
            float w = g.width(s, 0.85f, dev.skycore.ui.gfx.Gfx.Weight.MEDIUM) + 26;
            bx -= w;
            Rect chip = new Rect(bx, r.y() + 14, w, 16);
            boolean hov = ui.hover(chip);
            if (hov) g.roundRect(chip.x(), chip.y(), chip.w(), chip.h(), 8, t.surfaceHover());
            g.icon(Icon.INFO, chip.x() + 6, chip.y() + 3.5f, 9, t.textDim());
            g.text(s, chip.x() + 19, chip.y() + 4, t.textDim(), 0.85f, dev.skycore.ui.gfx.Gfx.Weight.MEDIUM);
            if (hov) { ui.tooltip(Lang.t("badge.sample.tip")); ui.setCursor(CursorTypes.POINTING_HAND); }
            if (ui.pressedOn(chip)) { Ui.clickSound(); openPage(pageIndex(SettingsPage.class)); }
            bx -= 8;
        }
        if (!Skycore.openKey().isUnbound()) {
            String key = Skycore.openKey().getTranslatedKeyMessage().getString();
            float kw = g.width(key, 0.8f, dev.skycore.ui.gfx.Gfx.Weight.MEDIUM) + 12;
            bx -= kw;
            Rect kr = new Rect(bx, r.y() + 15, kw, 14);
            // keycap: a small raised surface with a hairline
            g.roundRect(kr.x(), kr.y(), kr.w(), kr.h(), 4, t.surface());
            g.roundOutline(kr.x(), kr.y(), kr.w(), kr.h(), 4, 1f / g.s * Math.max(1, Math.round(g.s / 2)), Col.alpha(t.border(), 0.9f));
            g.text(key, kr.x() + 6, kr.y() + 3.6f, t.textDim(), 0.8f, dev.skycore.ui.gfx.Gfx.Weight.MEDIUM);
            if (ui.hover(kr)) ui.tooltip(Lang.t("header.key_tip"));
        }
        ui.separator(r.x() + 18, r.bottom() - 1, r.w() - 36);
    }

    private void drawResizeGrip(Rect win) {
        Rect grip = new Rect(win.right() - 15, win.bottom() - 15, 12, 12);
        boolean on = ui.hover(grip) || "win#resize".equals(ui.active);
        ui.g.icon(Icon.GRIP, grip.x(), grip.y(), 12, on ? ui.t.primary() : Col.alpha(ui.t.textMute(), 0.8f));
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        ui.onPress(e.button());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        ui.onRelease(e.button());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) { return true; }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        ui.onScroll(v);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (ui.onKey(e)) return true;
        if (e.key() == 256) { if (!ui.modalOpen()) onClose(); return true; } // escape closes a dialog first
        if (Skycore.openKey().matches(e) && closingAt == 0 && ui.focus == null) { onClose(); return true; }
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        ui.onChar(e.codepointAsString());
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        // Only marks the screen as closing. The X button calls this from inside the render pass, so the actual screen
        // replacement happens later (queueFinish -> next tick), never while this screen is still being drawn.
        if (closingAt > 0) return;
        closingAt = System.currentTimeMillis();
    }

    private boolean finishQueued;
    private boolean finished;

    private void queueFinish() {
        if (finishQueued) return;
        finishQueued = true;
        Skycore.later(this::finishClose);
    }

    private void finishClose() {
        if (finished) return;
        finished = true;
        try { Skycore.saveAll(); } catch (RuntimeException e) { Ui.logFault("save on close", e); } // a failed save must never keep the window open
        if (minecraft != null && minecraft.gui.screen() == this) minecraft.gui.setScreen(null);
    }

    public Ui ui() { return ui; }

    public Page page(int i) { return pages[i]; }
}
