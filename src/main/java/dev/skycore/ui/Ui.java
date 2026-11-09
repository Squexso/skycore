package dev.skycore.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.skycore.core.Skycore;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Immediate-mode UI toolkit shared by every SKYCORE page – the single source of truth for how controls look,
 * move and respond. Pages describe their interface every frame ("if (ui.button(...)) doThing()"); the toolkit
 * owns interaction state (hover, drag, focus, animation). Popups/modals render in later layers and block input
 * for everything beneath them.
 * <p>
 * Design rules encoded here:
 * <ul>
 *   <li>Type: Regular for descriptions, Medium for labels/controls, SemiBold for headings, Bold for key numbers.</li>
 *   <li>Colour: neutral by default; the primary accent marks interaction/selection only.</li>
 *   <li>Depth: soft shadows for floating layers; glow is reserved for the window frame.</li>
 *   <li>Motion: hover ≈150 ms, popups ≈180 ms, modals ≈200 ms; all scaled by the user's motion setting.</li>
 * </ul>
 */
public final class Ui {
    public enum Style { PRIMARY, SECONDARY, GHOST, DANGER }

    /** Custom rendering for dropdown options (theme previews etc.). */
    public interface OptionRenderer { void draw(Rect r, int index, boolean selected, boolean hovered); }

    // animation speeds (exponential approach; ~time to settle)
    public static final float HOVER = 16f, POPUP = 14f, PAGE = 12f;

    public Gfx g;
    public Theme t;
    public float mx = -1, my = -1;
    public float dt;
    private long lastFrame;

    // input (queued between frames)
    private boolean pressed, released, down, rightPressed;
    private float scrollAcc;
    private final StringBuilder typed = new StringBuilder();
    private final List<Integer> focusKeys = new ArrayList<>();
    private final List<Integer> frameKeys = new ArrayList<>();
    private boolean ctrlDown, shiftDown, altDown;
    /** When true, the next key press is captured (key-binding editor). */
    public boolean capturingKey;
    private KeyEvent capturedKey;

    // interaction state
    public String active;      // widget currently held by the mouse
    public String focus;       // focused text field
    private final Map<String, Float> anim = new HashMap<>();
    private final Map<String, Object> state = new HashMap<>();
    private final Deque<Rect> clip = new ArrayDeque<>();
    private record Occluder(Rect rect, int layer) {}
    private List<Occluder> occluders = new ArrayList<>(), occludersNext = new ArrayList<>();
    private int layer;
    private final List<Runnable> overlays = new ArrayList<>();
    private CursorType cursor = CursorTypes.ARROW;
    private boolean clickConsumed, scrollConsumed;

    // tooltip (delayed + faded)
    private String tooltip, shownTip;
    private float tipAge;
    private float tipX, tipY;

    // modal fade-out
    private boolean modalThisFrame, modalLastFrame;
    private Rect modalArea;
    private float modalFade;

    // ------------------------------------------------------------------ input from Screen
    public void onPress(int button) {
        if (button == 0) { pressed = true; down = true; }
        if (button == 1) rightPressed = true;
    }

    public void onRelease(int button) {
        if (button == 0) { released = true; down = false; }
    }

    public void onScroll(double amount) { scrollAcc += (float) amount; }

    public void onChar(String s) { if (focus != null) typed.append(s); }

    /** @return true if the key was consumed (focused text field or key capture). */
    public boolean onKey(KeyEvent e) {
        if (capturingKey) { capturedKey = e; capturingKey = false; return true; }
        frameKeys.add(e.key());
        if (focus == null) return false;
        focusKeys.add(e.key());
        return true;
    }

    /** The key captured since {@link #capturingKey} was set (consumed once). */
    public KeyEvent takeCapturedKey() {
        KeyEvent k = capturedKey;
        capturedKey = null;
        return k;
    }

    public boolean keyThisFrame(int key) { return frameKeys.contains(key); }

    /** A modal was shown last frame (Esc should close it rather than the screen). */
    public boolean modalOpen() { return modalLastFrame; }

    // ------------------------------------------------------------------ frame
    public void begin(Gfx g, float mouseX, float mouseY) {
        this.g = g;
        this.t = Theme.get();
        this.mx = mouseX;
        this.my = mouseY;
        long now = System.nanoTime();
        dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        var win = Minecraft.getInstance().getWindow();
        ctrlDown = InputConstants.isKeyDown(win, InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(win, 345);
        shiftDown = InputConstants.isKeyDown(win, InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(win, InputConstants.KEY_RSHIFT);
        altDown = InputConstants.isKeyDown(win, 342) || InputConstants.isKeyDown(win, 346);
        layer = 0;
        clip.clear();
        faultsThisFrame = 0;
        tooltip = null;
        cursor = CursorTypes.ARROW;
        clickConsumed = false;
        scrollConsumed = false;
        modalThisFrame = false;
        if (!down && active != null && !released) active = null;
    }

    public void end() {
        // overlays may schedule further overlays (dropdown inside a modal) → process in layers
        int depth = 0;
        while (!overlays.isEmpty() && depth < 6) {
            g.layer();
            layer = ++depth;
            List<Runnable> run = new ArrayList<>(overlays);
            overlays.clear();
            for (Runnable r : run) guard("overlay", r);
        }
        overlays.clear();
        layer = 0;
        // modal disappearance: fade the backdrop out after the dialog closed
        if (modalLastFrame && !modalThisFrame) modalFade = 1;
        modalLastFrame = modalThisFrame;
        if (modalFade > 0 && modalArea != null) {
            modalFade = t.animIntensity < 0.05f ? 0 : Math.max(0, modalFade - dt / (0.16f * t.animIntensity));
            g.layer();
            g.rect(modalArea.x(), modalArea.y(), modalArea.w(), modalArea.h(), Col.alpha(0xFF05060C, 0.5f * modalFade * modalFade));
        }
        drawTooltip();
        if (pressed && focus != null && !clickConsumed) focus = null;
        occluders = occludersNext;
        occludersNext = new ArrayList<>();
        pressed = released = rightPressed = false;
        scrollAcc = 0;
        typed.setLength(0);
        focusKeys.clear();
        frameKeys.clear();
        if (!down) active = null;
        g.g.requestCursor(cursor);
    }

    public boolean ctrl() { return ctrlDown; }
    public boolean shift() { return shiftDown; }
    public boolean alt() { return altDown; }
    public boolean mouseDown() { return down; }
    public boolean pressedThisFrame() { return pressed; }
    public boolean releasedThisFrame() { return released; }
    public boolean rightPressedThisFrame() { return rightPressed; }
    public void consumeClick() { clickConsumed = true; }
    public boolean clickConsumed() { return clickConsumed; }
    public void setCursor(CursorType c) { cursor = c; }

    /** Scroll delta for a hovered region (consumed once). */
    public float takeScroll(Rect r) {
        if (scrollConsumed || scrollAcc == 0 || !hover(r)) return 0;
        scrollConsumed = true;
        return scrollAcc;
    }

    // ------------------------------------------------------------------ hit testing & state
    public boolean hover(Rect r) {
        if (!r.contains(mx, my)) return false;
        for (Rect c : clip) if (!c.contains(mx, my)) return false;
        for (Occluder o : occluders) if (o.layer > layer && o.rect.contains(mx, my)) return false;
        return true;
    }

    /** Mouse pressed on r this frame and not yet consumed. */
    public boolean pressedOn(Rect r) {
        if (pressed && !clickConsumed && hover(r)) { clickConsumed = true; return true; }
        return false;
    }

    /** Register a popup area: it blocks input for everything drawn in lower layers (from the next frame on). */
    public void occlude(Rect r) { occludersNext.add(new Occluder(r, layer + 1)); }

    public boolean overHigherLayer() {
        for (Occluder o : occluders) if (o.layer > layer && o.rect.contains(mx, my)) return true;
        return false;
    }

    public void overlay(Runnable r) { overlays.add(r); }

    // ------------------------------------------------------------------ fault containment
    private static final java.util.Set<String> LOGGED_FAULTS = new java.util.HashSet<>();
    private int faultsThisFrame;

    /**
     * Runs one piece of UI code. If it throws, the graphics state it left open (matrices, scissors, alpha, layer) is
     * restored and the failure is logged once per distinct cause, so one broken widget cannot take the whole game down.
     * Returns false when the code failed.
     */
    public boolean guard(String what, Runnable r) {
        int pose = g.depth(), scis = g.scissors(), clips = clip.size(), lay = layer;
        float a = g.alpha;
        try {
            r.run();
            return true;
        } catch (RuntimeException e) {
            g.unwind(pose, scis);
            while (clip.size() > clips) clip.pop();
            g.alpha = a;
            layer = lay;
            faultsThisFrame++;
            logFault(what, e);
            return false;
        }
    }

    public int faultsThisFrame() { return faultsThisFrame; }

    /** Number of contained UI faults (also read by the dev stress run). */
    public static final java.util.concurrent.atomic.AtomicInteger FAULTS = new java.util.concurrent.atomic.AtomicInteger();

    public static void logFault(String what, Throwable e) {
        FAULTS.incrementAndGet();
        StackTraceElement[] st = e.getStackTrace();
        String key = what + "|" + e.getClass().getName() + "|" + (st.length > 0 ? st[0].toString() : "");
        synchronized (LOGGED_FAULTS) {
            if (LOGGED_FAULTS.size() > 32 || !LOGGED_FAULTS.add(key)) return;
        }
        dev.skycore.core.Skycore.LOG.error("SKYCORE UI fault in {} (further identical faults are not logged)", what, e);
    }

    /** Drops queued overlays and pointer state after a failed frame. */
    public void recover() {
        overlays.clear();
        clip.clear();
        layer = 0;
        active = null;
        tooltip = null;
    }

    public boolean inOverlay() { return layer > 0; }

    public void pushClip(Rect r) {
        clip.push(r);
        g.scissor(r.x(), r.y(), r.w(), r.h());
    }

    public void popClip() {
        clip.pop();
        g.endScissor();
    }

    public float anim(String id, float target, float speed) {
        float v = anim.getOrDefault(id, target);
        if (t.animIntensity < 0.05f) v = target;
        else v += (target - v) * (1 - (float) Math.exp(-dt * speed / t.animIntensity));
        if (Math.abs(v - target) < 0.002f) v = target;
        anim.put(id, v);
        return v;
    }

    public void setAnim(String id, float v) { anim.put(id, v); }

    public float hoverAnim(String id, boolean on) { return anim(id + "#h", on ? 1 : 0, HOVER); }

    @SuppressWarnings("unchecked")
    public <T> T state(String id, T def) {
        Object o = state.get(id);
        if (o == null) { state.put(id, def); return def; }
        return (T) o;
    }

    public void setState(String id, Object v) { state.put(id, v); }

    /** Contextual help, shown after a short delay near the cursor. */
    public void tooltip(String text) { if (text != null && !text.isEmpty()) { tooltip = text; tipNow = false; } }

    /** Tooltip that follows the cursor without delay (chart data points). */
    public void tooltipNow(String text) { if (text != null && !text.isEmpty()) { tooltip = text; tipNow = true; } }

    private boolean tipNow;

    public static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.6f));
    }

    private float hairline() { return 1f / g.s * Math.max(1, Math.round(g.s / 2)); }

    // ------------------------------------------------------------------ surfaces
    public float radius() { return t.radius; }

    public void card(Rect r) { card(r, false, null); }

    /**
     * Standard card. Hover only lightens the surface and border a little (no glow); "highlighted" marks a
     * selected/drop-target card with an accent border.
     */
    public void card(Rect r, boolean highlighted, String hoverId) {
        // Minecraft's GUI render state compares every new element with the elements already in the current layer, which
        // is quadratic. A card draws hundreds of small fills (rounded corners), so each card starts its own layer. Layers
        // are drawn in creation order, so the visual stacking is unchanged.
        g.layer();
        float h = 0;
        if (hoverId != null && t.hover) h = hoverAnim(hoverId, hover(r));
        float rad = radius() + 2;
        int base = Col.alpha(t.card(), t.opacity * 0.5f + 0.5f);
        int top = Col.mix(base, t.surfaceHover(), h * 0.35f);
        int bottom = t.gradients ? Col.mix(top, t.bg(), 0.18f) : top;
        g.roundRectV(r.x(), r.y(), r.w(), r.h(), rad, top, bottom);
        if (t.borders || highlighted) {
            int bc = highlighted ? Col.alpha(t.primary(), 0.7f) : Col.mix(Col.alpha(t.border(), 0.75f), Col.alpha(t.border(), 1f), h);
            g.roundOutline(r.x(), r.y(), r.w(), r.h(), rad, hairline(), bc);
        }
    }

    /** Inner tile (stat boxes, list rows). */
    public void tile(Rect r) {
        g.roundRect(r.x(), r.y(), r.w(), r.h(), radius(), Col.alpha(t.surface(), Math.max(0.7f, t.opacity)));
    }

    public void separator(float x, float y, float w) { g.rect(x, y, w, hairline(), t.separator()); }

    public void vseparator(float x, float y, float h) { g.rect(x, y, hairline(), h, Col.alpha(t.border(), 0.6f)); }

    // ------------------------------------------------------------------ text helpers
    public void label(String s, float x, float y) { g.text(s, x, y, t.text(), 1f, Weight.MEDIUM); }
    public void dim(String s, float x, float y) { g.text(s, x, y, t.textDim()); }
    public void heading(String s, float x, float y) { g.text(s, x, y, t.text(), 1.5f, Weight.SEMIBOLD); }

    /** Small secondary label above a value (sentence case, not shouty uppercase). */
    public void caption(String s, float x, float y) { g.text(s, x, y, t.textDim(), 0.9f, Weight.MEDIUM); }

    // ------------------------------------------------------------------ buttons
    public boolean button(String id, Rect r, String label, Icon icon, Style style) { return button(id, r, label, icon, style, true); }

    public boolean button(String id, Rect r, String label, Icon icon, Style style, boolean enabled) {
        boolean hov = enabled && hover(r);
        float h = hoverAnim(id, hov && (active == null || id.equals(active)));
        if (hov) setCursor(CursorTypes.POINTING_HAND);
        if (!enabled && hover(r)) setCursor(CursorTypes.NOT_ALLOWED);
        if (enabled && pressedOn(r)) active = id;
        boolean held = id.equals(active) && down;
        float press = anim(id + "#press", held ? 1 : 0, 24f);
        float rad = Math.min(radius(), r.h() / 2);
        float prev = g.alpha;
        if (!enabled) g.alpha *= 0.45f;
        int fg;
        switch (style) {
            case PRIMARY -> {
                int c = Col.mix(t.primary(), 0xFFFFFFFF, h * 0.10f);
                c = Col.mix(c, 0xFF000000, press * 0.14f);
                g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, c);
                fg = Col.onColor(c);
            }
            case DANGER -> {
                g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, Col.alpha(t.danger(), 0.12f + 0.12f * h + 0.08f * press));
                fg = Col.mix(t.danger(), 0xFFFFFFFF, 0.3f);
            }
            case GHOST -> {
                if (h > 0 || press > 0) g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, Col.alpha(t.surfaceHover(), Math.max(h, press)));
                fg = Col.mix(t.textDim(), t.text(), h);
            }
            default -> {
                g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, Col.mix(Col.mix(t.surface(), t.surfaceHover(), h), t.track(), press));
                fg = t.text();
            }
        }
        float iw = icon != null ? 10 : 0;
        float lw = label == null || label.isEmpty() ? 0 : g.width(label, 1f, Weight.MEDIUM);
        float gap = iw > 0 && lw > 0 ? 5 : 0;
        float cx = r.cx() - (iw + gap + lw) / 2f;
        if (icon != null) g.icon(icon, cx, r.cy() - 5, 10, fg);
        if (lw > 0) g.text(label, cx + iw + gap, r.cy() - 4.5f, fg, 1f, Weight.MEDIUM);
        g.alpha = prev;
        return enabled && clicked(id, r);
    }

    private boolean clicked(String id, Rect r) {
        if (released && id.equals(active) && hover(r)) {
            active = null;
            clickSound();
            return true;
        }
        return false;
    }

    public boolean iconButton(String id, Rect r, Icon icon, String tip) { return iconButton(id, r, icon, tip, false, t.textDim()); }

    public boolean iconButton(String id, Rect r, Icon icon, String tip, boolean on, int color) {
        boolean hov = hover(r);
        float h = hoverAnim(id, hov);
        if (hov) { setCursor(CursorTypes.POINTING_HAND); tooltip(tip); }
        if (pressedOn(r)) active = id;
        float rad = Math.min(radius(), r.h() / 2);
        float onA = anim(id + "#on", on ? 1 : 0, HOVER);
        if (onA > 0) g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, Col.alpha(t.primary(), 0.16f * onA));
        if (h > 0) g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, Col.alpha(t.surfaceHover(), h * 0.9f));
        float is = Math.min(r.w(), r.h()) * 0.58f;
        int c = Col.mix(Col.mix(color, t.text(), h), t.primary(), onA);
        g.icon(icon, r.cx() - is / 2, r.cy() - is / 2, is, c);
        return clicked(id, r);
    }

    /** Text-like link button (e.g. "Calibrate"). */
    public boolean link(String id, float x, float y, String label, Icon icon) {
        float w = g.width(label, 1f, Weight.MEDIUM) + (icon != null ? 12 : 0);
        Rect r = new Rect(x - 2, y - 2, w + 4, 13);
        boolean hov = hover(r);
        float h = hoverAnim(id, hov);
        if (hov) setCursor(CursorTypes.POINTING_HAND);
        if (pressedOn(r)) active = id;
        int c = Col.mix(t.primary(), Col.mix(t.primary(), 0xFFFFFFFF, 0.3f), h);
        if (icon != null) g.icon(icon, x, y - 0.5f, 9, c);
        g.text(label, x + (icon != null ? 12 : 0), y, c, 1f, Weight.MEDIUM);
        return clicked(id, r);
    }

    // ------------------------------------------------------------------ toggle
    public boolean toggle(String id, Rect r, boolean value) {
        boolean hov = hover(r);
        if (hov) setCursor(CursorTypes.POINTING_HAND);
        if (pressedOn(r)) active = id;
        boolean v = value;
        if (clicked(id, r)) v = !v;
        float k = anim(id + "#k", v ? 1 : 0, POPUP);
        float h = hoverAnim(id, hov);
        Rect track = new Rect(r.right() - 26, r.cy() - 7, 26, 14);
        int off = Col.mix(t.track(), t.surfaceHover(), h);
        g.roundRect(track.x(), track.y(), track.w(), track.h(), 7, Col.mix(off, t.primary(), k));
        float kx = track.x() + 2 + k * 12;
        // thumb stays readable when the accent is a very light colour (the default design uses an off-white)
        int onThumb = Col.luminance(t.primary()) > 0.7f ? 0xFF14151A : 0xFFFFFFFF;
        g.circle(kx + 5, track.cy(), 5, Col.mix(0xFFC9CEDB, onThumb, k));
        return v;
    }

    /** Row with label (+ optional description) on the left and a toggle on the right. */
    public boolean toggleRow(String id, Rect r, String label, String desc, boolean value) { return toggleRow(id, r, label, desc, value, null); }

    public boolean toggleRow(String id, Rect r, String label, String desc, boolean value, String tip) {
        boolean hov = hover(r);
        float h = hoverAnim(id + "#row", hov);
        if (h > 0) g.roundRect(r.x(), r.y(), r.w(), r.h(), radius(), Col.alpha(t.surface(), h * 0.55f));
        if (hov) tooltip(tip);
        float ty = desc == null ? r.cy() - 4.5f : r.cy() - 9;
        g.text(label, r.x() + 8, ty, t.text(), 1f, Weight.MEDIUM);
        if (desc != null) g.text(g.ellipsize(desc, (r.w() - 60) / 0.85f, 1f), r.x() + 8, ty + 11, t.textDim(), 0.85f);
        return toggle(id, new Rect(r.x(), r.y(), r.w() - 8, r.h()), value);
    }

    // ------------------------------------------------------------------ slider
    /** Arrow keys adjust a hovered slider by one step (Shift = ×5). */
    public float slider(String id, Rect r, float value, float min, float max, float step) {
        Rect hit = r.expand(3);
        boolean hov = hover(hit);
        if (hov) setCursor(CursorTypes.POINTING_HAND);
        if (pressedOn(hit)) active = id;
        float v = value;
        if (id.equals(active) && down) {
            float f = Math.max(0, Math.min(1, (mx - r.x()) / r.w()));
            v = min + f * (max - min);
            if (step > 0) v = Math.round(v / step) * step;
        }
        if (hov && step > 0) {
            float k = (shiftDown ? 5 : 1) * step;
            if (keyThisFrame(262)) v += k;
            if (keyThisFrame(263)) v -= k;
        }
        v = Math.max(min, Math.min(max, v));
        float f = (v - min) / (max - min);
        float shown = anim(id + "#v", f, 22f);
        float h = hoverAnim(id, hov || id.equals(active));
        float ty = r.cy() - 2;
        g.roundRect(r.x(), ty, r.w(), 4, 2, t.track());
        g.roundRect(r.x(), ty, Math.max(4, r.w() * shown), 4, 2, t.primary());
        float kx = r.x() + r.w() * shown;
        g.circle(kx, r.cy(), 6.5f + h, Col.alpha(t.primary(), 0.18f * h));
        g.circle(kx, r.cy(), 5, 0xFFFFFFFF);
        return v;
    }

    /** Label + value on top, slider below. */
    public float sliderRow(String id, Rect r, String label, float value, float min, float max, float step, String valueText) {
        return sliderRow(id, r, label, value, min, max, step, valueText, null);
    }

    public float sliderRow(String id, Rect r, String label, float value, float min, float max, float step, String valueText, String tip) {
        if (hover(r)) tooltip(tip);
        g.text(label, r.x(), r.y() + 2, t.text(), 1f, Weight.MEDIUM);
        g.textRight(valueText, r.right(), r.y() + 2, t.textDim(), 1f, Weight.REGULAR);
        return slider(id, new Rect(r.x() + 2, r.y() + 15, r.w() - 4, 12), value, min, max, step);
    }

    // ------------------------------------------------------------------ pills / segmented
    /**
     * Underlined tabs: quiet text on a hairline baseline, the selected tab in full text colour with a 2 px indicator
     * that slides to it. (The name is kept from the old pill tabs so call sites do not change.)
     */
    public int pills(String id, float x, float y, String[] labels, int selected, float maxW) {
        float gapX = 20, rowH = 24;
        float cx = x, cy = y;
        int sel = selected;
        Rect[] rects = new Rect[labels.length];
        for (int i = 0; i < labels.length; i++) {
            float w = g.width(labels[i], 1f, Weight.MEDIUM);
            if (cx + w > x + maxW && cx > x) { cx = x; cy += rowH + 6; }
            rects[i] = new Rect(cx, cy, w, rowH - 2);
            cx += w + gapX;
        }
        float base = cy + rowH - 1;
        separator(x, base, maxW);
        Rect target = rects[Math.max(0, Math.min(rects.length - 1, selected))];
        float ix = anim(id + "#ix", target.x(), PAGE), iy = anim(id + "#iy", target.y(), PAGE), iw = anim(id + "#iw", target.w(), PAGE);
        g.rect(ix, iy + rowH - 2, iw, 2, t.primary());
        for (int i = 0; i < labels.length; i++) {
            Rect r = rects[i];
            Rect hit = new Rect(r.x() - gapX / 2, r.y(), r.w() + gapX, r.h());
            boolean hov = hover(hit);
            float h = hoverAnim(id + i, hov);
            if (hov) setCursor(CursorTypes.POINTING_HAND);
            int c = i == selected ? t.text() : Col.mix(t.textDim(), t.text(), h);
            g.text(labels[i], r.x(), r.y() + 5, c, 1f, Weight.MEDIUM);
            if (pressedOn(hit)) active = id + i;
            if (clicked(id + i, hit)) sel = i;
        }
        lastPillsBottom = cy + rowH;
        return sel;
    }

    public float lastPillsBottom;

    /** Equal-width segmented control for single choices. */
    public int segmented(String id, Rect r, String[] labels, int selected) {
        float rad = Math.min(radius(), r.h() / 2);
        g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, t.surface());
        Rect[] cols = r.inset(2).columns(labels.length, 2);
        float pos = anim(id + "#pos", selected, PAGE);
        float cw = cols[0].w();
        float px = r.x() + 2 + pos * (cw + 2);
        float irad = Math.min(radius(), (r.h() - 4) / 2);
        g.roundRect(px, r.y() + 2, cw, r.h() - 4, irad, t.surfaceHover());
        int sel = selected;
        for (int i = 0; i < labels.length; i++) {
            Rect c = cols[i];
            boolean hov = hover(c);
            if (hov) setCursor(CursorTypes.POINTING_HAND);
            String l = g.ellipsize(labels[i], c.w() - 6, 1f);
            int col = i == selected ? t.text() : hov ? Col.mix(t.textDim(), t.text(), 0.6f) : t.textDim();
            g.textCentered(l, c.cx(), c.cy() - 4.5f, col, 1f, i == selected ? Weight.SEMIBOLD : Weight.MEDIUM);
            if (pressedOn(c)) active = id + i;
            if (clicked(id + i, c)) sel = i;
        }
        return sel;
    }

    // ------------------------------------------------------------------ stepper
    public int stepper(String id, Rect r, int value, int min, int max) {
        g.roundRect(r.x(), r.y(), r.w(), r.h(), Math.min(radius(), r.h() / 2), t.surface());
        int v = value;
        float b = r.h();
        if (iconButton(id + "-", new Rect(r.x(), r.y(), b, b), Icon.MINUS, null) && v > min) v--;
        if (iconButton(id + "+", new Rect(r.right() - b, r.y(), b, b), Icon.PLUS, null) && v < max) v++;
        Rect mid = new Rect(r.x() + b, r.y(), r.w() - 2 * b, r.h());
        float sc = takeScroll(mid);
        if (sc != 0) v = Math.max(min, Math.min(max, v + (sc > 0 ? 1 : -1) * (shift() ? 5 : 1)));
        if (hover(mid)) {
            if (keyThisFrame(265) || keyThisFrame(262)) v = Math.min(max, v + 1);
            if (keyThisFrame(264) || keyThisFrame(263)) v = Math.max(min, v - 1);
        }
        g.textCentered(String.valueOf(v), mid.cx(), mid.cy() - 4.5f, t.text(), 1f, Weight.SEMIBOLD);
        return v;
    }

    // ------------------------------------------------------------------ text field
    public String textField(String id, Rect r, String value, String placeholder, int maxLen, IntPredicate allowed) {
        return textField(id, r, value, placeholder, maxLen, allowed, true);
    }

    /** @param valid false shows the error state (red outline). */
    public String textField(String id, Rect r, String value, String placeholder, int maxLen, IntPredicate allowed, boolean valid) {
        boolean hov = hover(r);
        if (hov) setCursor(CursorTypes.IBEAM);
        if (pressedOn(r)) focus = id;
        boolean f = id.equals(focus);
        float fa = anim(id + "#f", f ? 1 : 0, HOVER);
        String v = value == null ? "" : value;
        if (f) {
            for (int i = 0; i < typed.length(); i++) {
                char ch = typed.charAt(i);
                if (v.length() < maxLen && (allowed == null || allowed.test(ch))) v += ch;
            }
            for (int k : focusKeys) {
                if (k == InputConstants.KEY_BACKSPACE && !v.isEmpty()) v = ctrlDown ? "" : v.substring(0, v.length() - 1);
                else if (k == 257 || k == 335 || k == InputConstants.KEY_ESCAPE) focus = null; // enter / escape
                else if (k == 86 && ctrlDown) { // ctrl+v
                    String clipText = Minecraft.getInstance().keyboardHandler.getClipboard();
                    for (char ch : clipText.toCharArray()) if (v.length() < maxLen && (allowed == null || allowed.test(ch))) v += ch;
                }
            }
        }
        float rad = Math.min(radius(), r.h() / 2);
        g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, Col.mix(t.surface(), t.surfaceHover(), hov ? 0.4f : 0));
        int ring = !valid ? t.danger() : Col.mix(Col.alpha(t.border(), 0.8f), t.primary(), fa);
        g.roundOutline(r.x(), r.y(), r.w(), r.h(), rad, (1 + fa * 0.5f) / g.s * Math.max(1, Math.round(g.s / 2)), ring);
        float tx = r.x() + 7, ty = r.cy() - 4.5f;
        pushClip(r.inset(3, 0));
        if (v.isEmpty() && !f) g.text(placeholder, tx, ty, t.textMute());
        else {
            float w = g.width(v);
            float shift = Math.max(0, w - (r.w() - 18));
            g.text(v, tx - shift, ty, t.text());
            if (f && (System.currentTimeMillis() / 530) % 2 == 0) g.rect(tx - shift + w + 1, ty - 1, 1, 11, t.primary());
        }
        popClip();
        return v;
    }

    // ------------------------------------------------------------------ dropdown
    public int dropdown(String id, Rect r, String[] options, int selected) { return dropdown(id, r, options, selected, null, 18); }

    /**
     * Single-choice dropdown. Shows only the current choice; the list opens (animated) below – or above when there
     * is no room – stays inside the screen, highlights the selection and closes on choice or outside click.
     */
    public int dropdown(String id, Rect r, String[] options, int selected, OptionRenderer renderer, float itemH) {
        boolean open = state(id + "#open", Boolean.FALSE);
        boolean hov = hover(r);
        float h = hoverAnim(id, hov || open);
        if (hov) setCursor(CursorTypes.POINTING_HAND);
        float rad = Math.min(radius(), r.h() / 2);
        g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, Col.mix(t.surface(), t.surfaceHover(), h));
        if (open) g.roundOutline(r.x(), r.y(), r.w(), r.h(), rad, hairline(), Col.alpha(t.primary(), 0.6f));
        String cur = selected >= 0 && selected < options.length ? options[selected] : "";
        if (renderer != null && selected >= 0) renderer.draw(new Rect(r.x() + 2, r.y(), r.w() - 22, r.h()), selected, false, false);
        else g.text(g.ellipsize(cur, r.w() - 28, 1f), r.x() + 8, r.cy() - 4.5f, t.text(), 1f, Weight.MEDIUM);
        float rot = anim(id + "#rot", open ? 1 : 0, POPUP);
        g.icon(rot > 0.5f ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, r.right() - 17, r.cy() - 5, 10, Col.mix(t.textDim(), t.text(), h));
        if (pressedOn(r)) active = id;
        if (clicked(id, r)) {
            open = !open;
            setState(id + "#open", open);
            if (open) setAnim(id + "#pop", 0f);
        }
        final int[] result = {selected};
        if (open) {
            float listH = Math.min(options.length * itemH + 8, 220);
            float sh = Minecraft.getInstance().getWindow().getHeight() / g.s, sw = Minecraft.getInstance().getWindow().getWidth() / g.s;
            boolean up = r.bottom() + 4 + listH > sh - 4 && r.y() - 4 - listH > 4;
            float lx = Math.max(4, Math.min(sw - r.w() - 4, r.x()));
            Rect list = new Rect(lx, up ? r.y() - 4 - listH : r.bottom() + 4, Math.max(r.w(), 120), listH);
            occlude(list);
            overlay(() -> {
                float a = anim(id + "#pop", 1, POPUP);
                float vis = 0.35f + 0.65f * a;
                Rect shown = up ? new Rect(list.x(), list.bottom() - list.h() * vis, list.w(), list.h() * vis) : list.withH(list.h() * vis);
                float prev = g.alpha;
                g.alpha *= a;
                g.glow(shown.x(), shown.y(), shown.w(), shown.h(), 12, Col.alpha(0xFF000000, 0.45f));
                g.roundRect(shown.x(), shown.y(), shown.w(), shown.h(), radius(), Col.mix(t.card(), t.text(), 0.05f));
                g.roundOutline(shown.x(), shown.y(), shown.w(), shown.h(), radius(), hairline(), Col.alpha(t.border(), 0.9f));
                Rect view = list.inset(4);
                float off = scroll(id + "#scroll", view, options.length * itemH);
                pushClip(view.intersect(shown.inset(1)));
                for (int i = 0; i < options.length; i++) {
                    Rect it = new Rect(view.x(), view.y() + i * itemH - off, view.w(), itemH);
                    boolean ih = hover(it);
                    float hh = hoverAnim(id + "#o" + i, ih);
                    boolean sel = i == selected;
                    if (sel) g.roundRect(it.x(), it.y(), it.w(), it.h(), Math.min(radius(), 6), Col.alpha(t.primary(), 0.14f));
                    else if (hh > 0) g.roundRect(it.x(), it.y(), it.w(), it.h(), Math.min(radius(), 6), Col.alpha(t.surfaceHover(), hh));
                    if (ih) setCursor(CursorTypes.POINTING_HAND);
                    if (renderer != null) renderer.draw(it, i, sel, ih);
                    else g.text(g.ellipsize(options[i], it.w() - 26, 1f), it.x() + 6, it.cy() - 4.5f, sel ? t.text() : Col.mix(t.textDim(), t.text(), hh), 1f, sel ? Weight.SEMIBOLD : Weight.MEDIUM);
                    if (sel) g.icon(Icon.CHECK, it.right() - 15, it.cy() - 4.5f, 9, t.primary());
                    if (pressedOn(it)) active = id + "#o" + i;
                    if (clicked(id + "#o" + i, it)) { result[0] = i; setState(id + "#open", false); setState(id + "#chosen", i); }
                }
                popClip();
                g.alpha = prev;
                if (pressed && !clickConsumed && !list.contains(mx, my) && !r.contains(mx, my)) setState(id + "#open", false);
            });
        }
        Integer chosen = (Integer) state.remove(id + "#chosen");
        return chosen != null ? chosen : result[0];
    }

    // ------------------------------------------------------------------ collapsible sections
    /** Handle returned by {@link #beginSection}. Draw content only when {@code visible}. */
    public record Section(String id, boolean visible, float contentY, float x, float w, boolean clipped) {}

    private static final float SECTION_HEAD = 44;

    /**
     * Expandable settings group. Only the header is shown until the user opens it; the open state is remembered
     * across sessions. Content height is measured each frame and animated.
     */
    public Section beginSection(String id, float x, float y, float w, String title, String desc, Icon icon, boolean defaultOpen) {
        var map = Skycore.config().expanded;
        boolean open = map.getOrDefault(id, defaultOpen);
        float a = anim("sec#" + id, open ? 1 : 0, PAGE);
        float h = state("sec#h" + id, 0f);
        float total = SECTION_HEAD + (h + 12) * a;
        Rect card = new Rect(x, y, w, total);
        card(card);
        Rect head = new Rect(x, y, w, SECTION_HEAD);
        boolean hov = hover(head);
        float hh = hoverAnim("sec#" + id, hov);
        if (hov) setCursor(CursorTypes.POINTING_HAND);
        if (hh > 0) g.roundRect(head.x() + 1, head.y() + 1, head.w() - 2, head.h() - 2, radius() + 1, Col.alpha(t.surface(), hh * 0.5f));
        float tx = x + 16;
        if (icon != null) { g.icon(icon, tx, y + 16, 12, open ? t.primary() : t.textDim()); tx += 22; }
        g.text(title, tx, y + (desc == null ? 17 : 11), t.text(), 1.05f, Weight.SEMIBOLD);
        if (desc != null) g.text(g.ellipsize(desc, (w - (tx - x) - 50) / 0.85f, 1f), tx, y + 25, t.textDim(), 0.85f);
        g.icon(a > 0.5f ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, x + w - 26, y + 17, 10, Col.mix(t.textDim(), t.text(), hh));
        if (pressedOn(head)) active = "sec#" + id;
        if (clicked("sec#" + id, head)) {
            map.put(id, !open);
            Skycore.markDirty();
        }
        boolean visible = open || a > 0.001f;
        boolean clipped = false;
        if (visible) {
            if (a < 0.999f) { pushClip(new Rect(x, y + SECTION_HEAD, w, (h + 12) * a)); clipped = true; }
            if (y + SECTION_HEAD > 0 && hh > 0.01f) separator(x + 16, y + SECTION_HEAD - 1, w - 32);
        }
        return new Section(id, visible, y + SECTION_HEAD + 4, x + 16, w - 32, clipped);
    }

    /** @return y just below the section (including a standard 10 px gap). */
    public float endSection(Section s, float contentBottom) {
        if (s.visible()) setState("sec#h" + s.id(), Math.max(0, contentBottom - s.contentY()));
        if (s.clipped()) popClip();
        float a = anim.getOrDefault("sec#" + s.id(), 0f);
        float h = state("sec#h" + s.id(), 0f);
        return s.contentY() - 4 + (h + 12) * a + 10;
    }

    // ------------------------------------------------------------------ progress
    public void progress(String id, Rect r, float frac, int c1, int c2) {
        float f = id == null ? frac : anim(id + "#p", Math.max(0, Math.min(1, frac)), 6f);
        float rad = r.h() / 2;
        g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, t.track());
        if (f <= 0.001f) return;
        float fw = Math.max(r.h(), r.w() * f);
        Rect fill = new Rect(r.x(), r.y(), fw, r.h());
        if (t.gradients && c1 != c2) roundGradientH(fill, rad, c1, Col.mix(c1, c2, f));
        else g.roundRect(fill.x(), fill.y(), fill.w(), fill.h(), rad, c1);
    }

    private void roundGradientH(Rect r, float rad, int a, int b) {
        if (a == b) { g.roundRect(r.x(), r.y(), r.w(), r.h(), rad, a); return; }
        float cap = Math.max(rad, 0.01f);
        g.roundRect(r.x(), r.y(), cap * 2, r.h(), rad, a);
        g.roundRect(r.right() - cap * 2, r.y(), cap * 2, r.h(), rad, b);
        g.gradientH(r.x() + cap, r.y(), r.w() - cap * 2, r.h(), Col.mix(a, b, cap / r.w()), Col.mix(a, b, 1 - cap / r.w()));
    }

    // ------------------------------------------------------------------ scrolling
    /** Smooth scroll region; returns the current offset. Also draws the scrollbar. */
    public float scroll(String id, Rect view, float contentH) {
        float max = Math.max(0, contentH - view.h());
        float target = state(id + "#target", 0f);
        float delta = takeScroll(view);
        if (delta != 0) target -= delta * 30;
        Rect bar = new Rect(view.right() - 4, view.y(), 4, view.h());
        if (max > 0) {
            float th = Math.max(20, view.h() * view.h() / contentH);
            float cur = anim(id + "#off", Math.max(0, Math.min(max, target)), 18f);
            float ty = view.y() + (view.h() - th) * (cur / max);
            Rect thumb = new Rect(bar.x(), ty, 4, th);
            boolean hov = hover(bar.expand(3));
            if (pressedOn(thumb.expand(3))) { active = id + "#bar"; setState(id + "#grab", my - ty); }
            if ((id + "#bar").equals(active) && down) {
                float grab = state(id + "#grab", 0f);
                target = (my - grab - view.y()) / Math.max(1, view.h() - th) * max;
            }
            float ha = hoverAnim(id + "#bar", hov || (id + "#bar").equals(active));
            g.roundRect(thumb.x(), thumb.y(), thumb.w(), thumb.h(), 2, Col.alpha(Col.mix(t.textMute(), t.textDim(), ha), 0.45f + 0.35f * ha));
        }
        target = Math.max(0, Math.min(max, target));
        setState(id + "#target", target);
        return anim(id + "#off", target, 18f);
    }

    public void resetScroll(String id) {
        setState(id + "#target", 0f);
        anim.put(id + "#off", 0f);
    }

    // ------------------------------------------------------------------ modal
    /** Draw a modal dialog (call every frame while open). */
    public void modal(Rect screen, float w, float h, String title, java.util.function.Consumer<Rect> body, Runnable onClose) {
        occlude(screen);
        modalThisFrame = true;
        modalArea = screen;
        overlay(() -> {
            float a = anim("modal#a", 1, POPUP);
            g.rect(screen.x(), screen.y(), screen.w(), screen.h(), Col.alpha(0xFF05060C, 0.5f * a));
            float sw = Math.min(w, screen.w() - 16), shh = Math.min(h, screen.h() - 16);
            Rect d = new Rect(screen.cx() - sw / 2, screen.cy() - shh / 2 + (1 - a) * 10, sw, shh);
            float prev = g.alpha;
            g.alpha *= a;
            g.glow(d.x(), d.y(), d.w(), d.h(), 22, Col.alpha(0xFF000000, 0.55f));
            g.roundRect(d.x(), d.y(), d.w(), d.h(), radius() + 4, Col.mix(t.card(), t.text(), 0.03f));
            g.roundOutline(d.x(), d.y(), d.w(), d.h(), radius() + 4, hairline(), Col.alpha(t.border(), 0.9f));
            g.text(title, d.x() + 18, d.y() + 16, t.text(), 1.2f, Weight.SEMIBOLD);
            // Closing clears the caller's dialog state (typically a field the body reads). Once closed this frame the body
            // must not run, otherwise it dereferences the state the close callback just reset.
            boolean[] closed = {false};
            Runnable close = () -> { if (!closed[0]) { closed[0] = true; onClose.run(); } };
            if (iconButton("modal#close", new Rect(d.right() - 32, d.y() + 10, 22, 22), Icon.CLOSE, null)) close.run();
            // a failing dialog body closes the dialog instead of crashing the game
            if (!closed[0] && !guard("modal " + title, () -> body.accept(new Rect(d.x() + 18, d.y() + 40, d.w() - 36, d.h() - 56)))) close.run();
            g.alpha = prev;
            if (pressed && !clickConsumed && !d.contains(mx, my) && !overHigherLayer()) { clickConsumed = true; close.run(); }
            if (keyThisFrame(InputConstants.KEY_ESCAPE) && focus == null) close.run();
        });
    }

    public void resetModalAnim() { anim.put("modal#a", 0f); }

    // ------------------------------------------------------------------ tooltip
    private void drawTooltip() {
        if (tooltip == null) { shownTip = null; tipAge = 0; return; }
        if (!tooltip.equals(shownTip)) { shownTip = tooltip; if (!tipNow) tipAge = 0; tipX = mx; tipY = my; }
        if (tipNow) { tipX = mx; tipY = my; }
        tipAge += dt;
        float delay = tipNow ? 0 : 0.35f;
        if (tipAge < delay) return;
        float a = t.animIntensity < 0.05f ? 1 : Math.min(1, (tipAge - delay) / 0.15f);
        g.layer();
        String[] lines = tooltip.split("\n");
        float w = 0;
        for (int i = 0; i < lines.length; i++) w = Math.max(w, g.width(lines[i], 0.92f, i == 0 ? Weight.MEDIUM : Weight.REGULAR));
        w = Math.min(w, 260);
        float lh = 10.5f;
        float h = lines.length * lh + 10;
        float sw = Minecraft.getInstance().getWindow().getWidth() / g.s, sh = Minecraft.getInstance().getWindow().getHeight() / g.s;
        float x = tipX + 12, y = tipY + 16;
        if (x + w + 14 > sw - 4) x = tipX - w - 18;
        if (y + h > sh - 4) y = tipY - h - 8;
        x = Math.max(4, x);
        y = Math.max(4, y) + (1 - a) * 3;
        float prev = g.alpha;
        g.alpha = a;
        g.glow(x, y, w + 14, h, 8, 0x55000000);
        g.roundRect(x, y, w + 14, h, Math.min(radius(), 6), Col.mix(t.card(), t.text(), 0.08f));
        g.roundOutline(x, y, w + 14, h, Math.min(radius(), 6), hairline(), Col.alpha(t.border(), 0.9f));
        for (int i = 0; i < lines.length; i++)
            g.text(g.ellipsize(lines[i], w / 0.92f, 1f), x + 7, y + 6 + i * lh, i == 0 ? t.text() : t.textDim(), 0.92f, i == 0 ? Weight.MEDIUM : Weight.REGULAR);
        g.alpha = prev;
    }
}
