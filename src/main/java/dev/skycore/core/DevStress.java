package dev.skycore.core;

import dev.skycore.hud.HudEditorScreen;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.pages.ProgressionPage;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development aid (only with -Dskycore.stress=true together with the capture run, never for players):
 * opens and closes SKYCORE windows and dialogs over and over through the same input paths a player uses
 * (X button click, Escape, modal X), checks that the game returns to the normal screen every time,
 * and records frame times and UI faults. Sample data only; saving is blocked by the capture mode.
 */
public final class DevStress {
    public static final boolean ENABLED = Boolean.getBoolean("skycore.stress");
    private static final int ROUNDS = Integer.getInteger("skycore.stress.rounds", 3);

    /** When set, screens use this pointer position (UI units) instead of the real mouse. */
    public static volatile float[] mouse;

    private record Step(int ticks, Runnable run) {}

    private static final Deque<Step> queue = new ArrayDeque<>();
    private static int wait, boot, failures, checks, scenarios;
    private static boolean started, finished;
    private static final Map<String, List<Long>> FRAMES = new LinkedHashMap<>();
    private static final List<String> LOG = new ArrayList<>();

    private DevStress() {}

    public static void frame(String key, long nanos) {
        synchronized (FRAMES) { FRAMES.computeIfAbsent(key, k -> new ArrayList<>()).add(nanos); }
    }

    private static void say(String s) { LOG.add(s); Skycore.LOG.info("stress: {}", s); }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; say("FAIL " + what); }
    }

    private static void add(int wait, Runnable r) { queue.add(new Step(wait, r)); }

    private static Minecraft mc() { return Minecraft.getInstance(); }

    // ------------------------------------------------------------------ building blocks
    private static void open(Screen s) { add(8, () -> mc().setScreen(s)); }

    private static void clickAt(SkycoreScreen s, java.util.function.Supplier<float[]> pos) {
        add(3, () -> mouse = pos.get());
        add(3, () -> s.ui().onPress(0));
        add(3, () -> s.ui().onRelease(0));
        add(3, () -> mouse = null);
    }

    private static float[] modalX(SkycoreScreen s, float h) {
        var r = s.screenRect();
        float sw = Math.min(320, r.w() - 16), shh = Math.min(h, r.h() - 16);
        float dx = r.cx() - sw / 2, dy = r.cy() - shh / 2;
        return new float[]{dx + sw - 21, dy + 21};
    }

    private static void expectNoScreen(String what) { add(12, () -> { }); add(1, () -> check(!(mc().screen instanceof SkycoreScreen) && !(mc().screen instanceof HudEditorScreen), what + ": back to the normal screen (now " + (mc().screen == null ? "none" : mc().screen.getClass().getSimpleName()) + ")")); }

    private static void escape(Screen s) { add(3, () -> s.keyPressed(new KeyEvent(256, 0, 0))); }

    private static void setAnimations(boolean on) {
        add(1, () -> { Skycore.config().theme.animations = on; Theme.rebuild(); });
    }

    // ------------------------------------------------------------------ scenarios
    private static void build() {
        for (int round = 0; round < ROUNDS; round++) {
            for (int page = 0; page < 9; page++) {
                final int p = page;
                // 1. main window X (animations on and off)
                for (boolean anim : new boolean[]{true, false}) {
                    setAnimations(anim);
                    SkycoreScreen s = new SkycoreScreen(p);
                    open(s);
                    clickAt(s, () -> new float[]{s.devCloseCenter()[0], s.devCloseCenter()[1]});
                    expectNoScreen("page " + p + " X anim=" + anim);
                    add(1, () -> scenarios++);
                }
                setAnimations(true);
                // 2. Escape
                SkycoreScreen e = new SkycoreScreen(p);
                open(e);
                escape(e);
                expectNoScreen("page " + p + " Escape");
                add(1, () -> scenarios++);
                // 3. open, switch pages quickly, close immediately
                SkycoreScreen q = new SkycoreScreen(p);
                open(q);
                add(1, () -> { q.openPage((p + 1) % 9); q.openPage((p + 3) % 9); q.openPage(p); });
                clickAt(q, () -> q.devCloseCenter());
                expectNoScreen("page " + p + " switch then X");
                add(1, () -> scenarios++);
            }
            // 4. settings changed, then X
            SkycoreScreen st = new SkycoreScreen(8);
            open(st);
            add(1, () -> { Skycore.config().theme.glow = 0.2f; Skycore.config().window.blur = !Skycore.config().window.blur; Skycore.markDirty(); });
            add(6, () -> { });
            clickAt(st, () -> st.devCloseCenter());
            expectNoScreen("settings changed then X");
            add(1, () -> scenarios++);
            // 5. calibrate dialog: modal X, then main X
            SkycoreScreen cd = new SkycoreScreen(1);
            open(cd);
            add(8, () -> ((ProgressionPage) cd.page(1)).openCalibrate(cd.ui(), dev.skycore.data.StatDef.get("catacombs")));
            clickAt(cd, () -> modalX(cd, 210));
            add(10, () -> check(mc().screen == cd, "calibrate modal X leaves the window open"));
            clickAt(cd, () -> cd.devCloseCenter());
            expectNoScreen("calibrate modal X then main X");
            add(1, () -> scenarios++);
            // 6. calibrate dialog: Escape, then Escape
            SkycoreScreen ce = new SkycoreScreen(1);
            open(ce);
            add(8, () -> ((ProgressionPage) ce.page(1)).openCalibrate(ce.ui(), dev.skycore.data.StatDef.get("catacombs")));
            escape(ce);
            add(10, () -> check(mc().screen == ce, "calibrate Escape leaves the window open"));
            escape(ce);
            expectNoScreen("calibrate Escape then Escape");
            add(1, () -> scenarios++);
            // 7. calibrate with a missing definition
            SkycoreScreen cm = new SkycoreScreen(1);
            open(cm);
            add(8, () -> ((ProgressionPage) cm.page(1)).openCalibrate(cm.ui(), null));
            clickAt(cm, () -> modalX(cm, 130));
            add(10, () -> check(mc().screen == cm, "fallback modal X leaves the window open"));
            clickAt(cm, () -> cm.devCloseCenter());
            expectNoScreen("fallback dialog then main X");
            add(1, () -> scenarios++);
            // 8. HUD editor: Done button, Escape, and the parent restored
            HudEditorScreen he = new HudEditorScreen(null);
            open(he);
            add(3, () -> mouse = he.devDoneCenter());
            add(3, () -> he.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(0, 0, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false));
            add(3, () -> he.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(0, 0, new net.minecraft.client.input.MouseButtonInfo(0, 0))));
            add(3, () -> mouse = null);
            expectNoScreen("HUD editor Done");
            HudEditorScreen he2 = new HudEditorScreen(null);
            open(he2);
            escape(he2);
            expectNoScreen("HUD editor Escape");
            add(1, () -> scenarios += 2);
            // 9. editor opened from the SKYCORE window returns to it
            SkycoreScreen host = new SkycoreScreen(2);
            open(host);
            add(8, () -> mc().setScreen(new HudEditorScreen(host)));
            escape2();
            add(12, () -> check(mc().screen == host, "HUD editor Escape returns to the parent window"));
            clickAt(host, () -> host.devCloseCenter());
            expectNoScreen("parent window X after the editor");
            add(1, () -> scenarios++);
        }
    }

    private static void escape2() { add(3, () -> { if (mc().screen != null) mc().screen.keyPressed(new KeyEvent(256, 0, 0)); }); }

    // ------------------------------------------------------------------ engine
    public static void tick(Minecraft mc) {
        if (!ENABLED || finished) return;
        if (!started) {
            if (mc.screen == null || mc.getOverlay() != null) return;
            if (++boot < 100) return;
            started = true;
            Skycore.config().sampleData = true;
            Skycore.LOG.info("stress: starting; Sodium loaded = {}", net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium"));
            build();
            return;
        }
        if (wait > 0) { wait--; return; }
        Step s = queue.poll();
        if (s == null) { finish(mc); return; }
        try { s.run().run(); } catch (RuntimeException ex) { failures++; say("FAIL step threw " + ex); Skycore.LOG.error("stress step", ex); }
        wait = s.ticks();
    }

    private static void finish(Minecraft mc) {
        finished = true;
        StringBuilder sb = new StringBuilder();
        sb.append("scenarios=").append(scenarios).append(" checks=").append(checks).append(" failures=").append(failures)
                .append(" uiFaults=").append(Ui.FAULTS.get()).append(" sodium=")
                .append(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")).append('\n');
        synchronized (FRAMES) {
            for (var e : FRAMES.entrySet()) {
                List<Long> l = new ArrayList<>(e.getValue());
                if (l.size() > 20) l = l.subList(20, l.size()); // drop JIT warm-up
                java.util.Collections.sort(l);
                if (l.isEmpty()) continue;
                sb.append(String.format("frame %-10s n=%d p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms%n", e.getKey(), l.size(),
                        l.get(l.size() / 2) / 1e6, l.get((int) (l.size() * 0.95)) / 1e6, l.get(Math.min(l.size() - 1, (int) (l.size() * 0.99))) / 1e6, l.get(l.size() - 1) / 1e6));
            }
        }
        for (String s : LOG) sb.append(s).append('\n');
        Skycore.LOG.info("stress RESULT\n{}", sb);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(mc.gameDirectory.getPath(), "stress_result.txt"), sb.toString()); } catch (Exception ignored) {}
        mc.stop();
    }
}
