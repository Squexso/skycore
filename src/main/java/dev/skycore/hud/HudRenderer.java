package dev.skycore.hud;

import dev.skycore.core.Config;
import dev.skycore.core.Skycore;
import dev.skycore.notify.Notifier;
import dev.skycore.tracking.HypixelTracker;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Draws enabled HUD widgets and notification toasts every frame (cheap: no allocation-heavy work). */
public final class HudRenderer {
    private HudRenderer() {}

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("skycore", "hud"), (g, delta) -> render(g));
    }

    private static void render(GuiGraphicsExtractor gg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        Config cfg = Skycore.config();
        boolean editor = mc.screen instanceof HudEditorScreen;
        Gfx g = new Gfx(gg, mc.getWindow().getGuiScale());
        try {
            float sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
            if (!editor && !(mc.screen instanceof SkycoreScreen) && cfg.hudEnabled && (!cfg.hudOnlySkyBlock || HypixelTracker.onSkyBlock() || cfg.sampleData)) {
                drawWidgets(g, sw, sh);
            }
            if (!(mc.screen instanceof SkycoreScreen)) drawToasts(g, sw, sh);
        } finally {
            g.end();
        }
    }

    public static void drawWidgets(Gfx g, float sw, float sh) {
        for (HudStore.WidgetState w : HudStore.widgets()) {
            if (!w.enabled || !HudWidgets.visibleNow(w)) continue;
            HudWidgets.Content c = HudWidgets.cached(w.id);
            Rect r = HudWidgets.measure(w, c);
            float x = Math.max(0, Math.min(sw - r.w(), w.x * sw));
            float y = Math.max(0, Math.min(sh - r.h(), w.y * sh));
            HudWidgets.draw(g, w, c, x, y);
        }
    }

    /** Toasts. Units = GUI pixels. */
    public static void drawToasts(Gfx g, float sw, float sh) {
        List<Notifier.Toast> list = Notifier.active();
        if (list.isEmpty()) return;
        Config.NotificationSettings ns = Skycore.config().notifications;
        Theme t = Theme.get();
        float scale = ns.textScale;
        float tw = 170 * scale, gap = 6;
        boolean right = ns.position.endsWith("right"), center = ns.position.endsWith("center"), bottom = ns.position.startsWith("bottom");
        float y = bottom ? sh - 8 : 8;
        long now = System.currentTimeMillis();
        for (int i = list.size() - 1; i >= 0; i--) {
            Notifier.Toast toast = list.get(i);
            float age = (now - toast.created()) / 1000f;
            float in = Math.min(1, age / 0.28f), out = Math.min(1, Math.max(0, (toast.duration() + 0.5f - age) / 0.3f));
            float e = ease(Math.min(in, out));
            List<String> body = g.wrap(toast.body(), (tw - 42 * scale) / scale, 1f);
            float th = (26 + body.size() * 10.5f) * scale;
            float x = right ? sw - tw - 8 : center ? (sw - tw) / 2 : 8;
            float ty = bottom ? y - th : y;
            float a = 1;
            g.push();
            switch (ns.animation) {
                case "fade" -> a = e;
                case "pop" -> { g.scaleAround(x + tw / 2, ty + th / 2, 0.85f + 0.15f * e); a = e; }
                default -> { float off = (1 - e) * (tw + 12); x += right ? off : center ? 0 : -off; if (center) ty += (bottom ? 1 : -1) * (1 - e) * 20; a = Math.min(1, e * 1.5f); }
            }
            float prev = g.alpha;
            g.alpha = a;
            drawToastCard(g, toast, body, x, ty, tw, th, scale, Math.max(0, 1 - age / toast.duration()));
            g.alpha = prev;
            g.pop();
            float step = (th + gap) * e;
            y += bottom ? -step : step;
        }
    }

    /** Height of a toast card for the given body width (units). */
    public static float toastHeight(Gfx g, Notifier.Toast toast, float tw, float scale) {
        return (26 + g.wrap(toast.body(), (tw - 42 * scale) / scale, 1f).size() * 10.5f) * scale;
    }

    /** One toast card (shared by the live notifications and the Notifications page preview). */
    public static void drawToastCard(Gfx g, Notifier.Toast toast, List<String> body, float x, float ty, float tw, float th, float scale, float life) {
        Theme t = Theme.get();
        Config.NotificationSettings ns = Skycore.config().notifications;
        float rad = Math.min(t.radius + 2, 10);
        g.glow(x, ty + 3, tw, th, 12, Col.alpha(0xFF000000, 0.45f));
        int bgc = ns.background == null || ns.background.isEmpty() ? Col.mix(t.card(), t.text(), 0.05f) : Theme.parse(ns.background);
        g.roundRect(x, ty, tw, th, rad, Col.alpha(bgc, ns.backgroundOpacity));
        g.roundOutline(x, ty, tw, th, rad, 1f / g.s * Math.max(1, Math.round(g.s / 2)), Col.alpha(t.border(), 0.9f));
        // a slim accent bar carries the category colour – the rest stays neutral
        g.roundRect(x, ty + rad * 0.7f, 2.5f * scale, th - rad * 1.4f, 1.25f, toast.color());
        float ic = 18 * scale;
        g.roundRect(x + 10 * scale, ty + 8 * scale, ic, ic, 5 * scale, Col.alpha(toast.color(), 0.14f));
        g.icon(toast.icon(), x + 10 * scale + ic * 0.2f, ty + 8 * scale + ic * 0.2f, ic * 0.6f, toast.color());
        float tx = x + 34 * scale;
        g.text(g.ellipsize(toast.title(), (tw - 42 * scale) / scale, 1f), tx, ty + 8 * scale, t.text(), scale, Gfx.Weight.SEMIBOLD);
        for (int l = 0; l < body.size(); l++) g.text(body.get(l), tx, ty + (19.5f + l * 10.5f) * scale, t.textDim(), scale);
        if (life >= 0) g.roundRect(x + 10 * scale, ty + th - 3.5f * scale, (tw - 20 * scale) * life, 1.5f * scale, 1, Col.alpha(toast.color(), 0.55f));
    }

    private static float ease(float x) { return 1 - (float) Math.pow(1 - x, 3); }
}
