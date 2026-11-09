package dev.skycore.core;

import dev.skycore.hud.HudEditorScreen;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.pages.ProgressionPage;
import dev.skycore.ui.pages.SessionsPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Development aid (only active with -Dskycore.capture=true, i.e. the "runCapture" Gradle task):
 * opens every page (and several sub-views) with sample data, saves a screenshot of each and quits.
 * Never runs for players, and never writes config or data files.
 */
public final class DevCapture {
    public static final boolean ENABLED = Boolean.getBoolean("skycore.capture");
    private static final String LANG = System.getProperty("skycore.capture.lang", "en");
    private record Shot(String name, Supplier<net.minecraft.client.gui.screens.Screen> screen) {}
    private static final List<Shot> SHOTS = new ArrayList<>();
    private static int tick, step = -1;

    private DevCapture() {}

    private static void build() {
        for (int i = 0; i < 9; i++) { int p = i; SHOTS.add(new Shot(String.valueOf(i), () -> new SkycoreScreen(p))); }
        String[] tabs = {"slayer", "skills", "foraging", "bestiary", "mining", "collections"};
        for (int i = 0; i < tabs.length; i++) {
            int tab = i + 1;
            SHOTS.add(new Shot("prog_" + tabs[i], () -> { SkycoreScreen s = new SkycoreScreen(1); ((ProgressionPage) s.page(1)).setTab(tab); return s; }));
        }
        SHOTS.add(new Shot("prog_slayer_tiers", () -> { SkycoreScreen s = new SkycoreScreen(1); ((ProgressionPage) s.page(1)).setTab(1); pendingPage = 1; pendingScroll = 380; return s; }));
        SHOTS.add(new Shot("prog_floors", () -> { SkycoreScreen s = new SkycoreScreen(1); ((ProgressionPage) s.page(1)).setTab(0); pendingPage = 1; pendingScroll = 300; return s; }));
        SHOTS.add(new Shot("prog_quest_details", () -> { SkycoreScreen s = new SkycoreScreen(1); ProgressionPage p = (ProgressionPage) s.page(1); p.setTab(1); p.openFirstDetails(s.ui(), true); return s; }));
        SHOTS.add(new Shot("prog_run_details", () -> { SkycoreScreen s = new SkycoreScreen(1); ProgressionPage p = (ProgressionPage) s.page(1); p.setTab(0); p.openFirstDetails(s.ui(), false); return s; }));
        SHOTS.add(new Shot("prog_calibrate", () -> { SkycoreScreen s = new SkycoreScreen(1); ((ProgressionPage) s.page(1)).openCalibrate(s.ui(), dev.skycore.data.StatDef.get("catacombs")); return s; }));
        SHOTS.add(new Shot("prog_calibrate_missing", () -> { SkycoreScreen s = new SkycoreScreen(1); ((ProgressionPage) s.page(1)).openCalibrate(s.ui(), null); return s; }));
        SHOTS.add(new Shot("appearance_dropdown", () -> { SkycoreScreen s = new SkycoreScreen(5); s.ui().setState("ap#theme#open", true); return s; }));
        SHOTS.add(new Shot("appearance_picker", () -> { SkycoreScreen s = new SkycoreScreen(5); ((dev.skycore.ui.pages.AppearancePage) s.page(5)).openPicker(dev.skycore.ui.theme.Theme.Slot.PRIMARY); return s; }));
        SHOTS.add(new Shot("sessions_week_bars", () -> { SkycoreScreen s = new SkycoreScreen(3); ((SessionsPage) s.page(3)).setView(2, 1); return s; }));
        SHOTS.add(new Shot("settings_scrolled", () -> scrolled(7, 560)));
        SHOTS.add(new Shot("notifications_scrolled", () -> scrolled(6, 520)));
        SHOTS.add(new Shot("hud_studio_scrolled", () -> scrolled(2, 460)));
        SHOTS.add(new Shot("settings_datatrack", () -> scrolled(7, 700)));
        for (int y : new int[]{640, 1180, 1450, 1720, 1990, 2260, 2800}) SHOTS.add(new Shot("reliability_" + y, () -> scrolled(7, y)));
        // real (non-sample) views with an in-memory demo: per-profile sessions, unassigned data, profiles
        SHOTS.add(new Shot("real_sessions", () -> { realDemo(); SkycoreScreen s = new SkycoreScreen(3); ((SessionsPage) s.page(3)).setView(0, 0); pendingPage = 3; pendingScroll = 1150; return s; }));
        SHOTS.add(new Shot("real_sessions_top", () -> { realDemo(); return new SkycoreScreen(3); }));
        SHOTS.add(new Shot("real_profiles", () -> { realDemo(); return scrolled(7, 1560); }));
        SHOTS.add(new Shot("real_datatrack", () -> { realDemo(); return scrolled(7, 840); }));
        for (int y : new int[]{700, 1400, 1750, 2100, 2800}) SHOTS.add(new Shot("share_" + y, () -> scrolled(7, y)));
        SHOTS.add(new Shot("hud_editor", () -> new HudEditorScreen(null)));
        String only = System.getProperty("skycore.capture.only", "");
        if (!only.isEmpty()) {
            java.util.Set<String> keep = java.util.Set.of(only.split(","));
            SHOTS.removeIf(s -> !keep.contains(s.name()));
        }
    }

    private static int pendingPage = -1;
    private static float pendingScroll;

    /** The scroll target is applied once the page has measured its height (a few ticks after opening). */
    private static SkycoreScreen scrolled(int page, float y) {
        pendingPage = page;
        pendingScroll = y;
        return new SkycoreScreen(page);
    }

    public static void tick(Minecraft mc) {
        if (!ENABLED || DevStress.ENABLED) return;
        if (step == -1) {
            if (mc.screen == null || mc.getOverlay() != null) return;
            if (++tick < 80) return;
            Skycore.LOG.info("capture: starting over {}", mc.screen.getClass().getSimpleName());
            Skycore.config().sampleData = true;
            // QA: open every collapsible section so its content is captured
            if (Boolean.getBoolean("skycore.capture.expand"))
                for (String id : new String[]{"ap.theme", "ap.colors", "ap.type", "ap.effects", "nt.general", "nt.alerts", "nt.look", "nt.sound", "nt.text",
                        "set.general", "set.interface", "set.keys", "set.tracking", "set.hud", "set.data", "set.datatrack", "hud.general", "hud.look", "hud.pos",
                        "set.profiles", "set.backup", "set.integrity", "set.patterns", "set.debug",
                        "sh.routing", "sh.milestones", "sh.checkpoints", "sh.templates", "sh.signature", "sh.clipboard", "sh.community"})
                    Skycore.config().expanded.put(id, true);
            if (Boolean.getBoolean("skycore.capture.expand")) { demoDebugger(); demoShare(); }
            Skycore.setLanguage(LANG);
            build();
            step = 0;
            tick = 0;
            mc.setScreen(SHOTS.get(0).screen().get());
            return;
        }
        tick++;
        if (tick == 10 && pendingPage >= 0 && mc.screen instanceof SkycoreScreen sc) {
            sc.ui().setState("page" + pendingPage + "#target", pendingScroll);
            pendingPage = -1;
        }
        if (tick == 45) {
            String name = "skycore_" + LANG + "_" + System.getProperty("skycore.capture.tag", "") + SHOTS.get(step).name() + ".png";
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1, c -> Skycore.LOG.info("capture: {}", c.getString()));
        }
        if (tick >= 55) {
            tick = 0;
            step++;
            if (step < SHOTS.size()) mc.setScreen(SHOTS.get(step).screen().get());
            else mc.stop();
        }
    }

    private static boolean seeded;

    /**
     * Screenshot run only (nothing is saved): a confirmed profile "Apple" with two sessions, a switch to "Banana",
     * and playtime recorded while no profile was confirmed (→ unassigned), all through the normal APIs.
     */
    private static void realDemo() {
        Skycore.config().sampleData = false;
        if (seeded) return;
        seeded = true;
        dev.skycore.data.StatDef mining = dev.skycore.data.StatDef.get("skill_mining");
        dev.skycore.data.SessionLog.addPlaySeconds(420);                   // profile unknown → held
        dev.skycore.data.SessionLog.profileLost("profile_unknown", null);  // never confirmed → unassigned
        dev.skycore.data.Progress.identify("Apple", "00000000-demo-0000-0000-000000000000", "chat");
        for (int i = 0; i < 1500; i++) dev.skycore.data.SessionLog.addPlaySeconds(1);
        dev.skycore.data.SessionLog.record(mining, 18_400);
        dev.skycore.data.Progress.identify("Banana", "00000000-demo-0000-0000-000000000000", "tab");
        for (int i = 0; i < 300; i++) dev.skycore.data.SessionLog.addPlaySeconds(1);
        dev.skycore.data.Progress.identify("Apple", "00000000-demo-0000-0000-000000000000", "chat");
        for (int i = 0; i < 900; i++) dev.skycore.data.SessionLog.addPlaySeconds(1);
        dev.skycore.data.SessionLog.record(mining, 6_100);
        dev.skycore.tracking.DebugLog.problem("quest_unclassified", "evidence.no_tier");
        dev.skycore.tracking.DebugLog.problem("reconcile_ambiguous", "slayer:enderman");
    }

    /** Screenshot run only: synthetic prepared messages so the Share page layout can be reviewed. */
    private static void demoShare() {
        var st = dev.skycore.share.ShareService.state();
        if (!st.inbox.isEmpty()) return;
        String[][] rows = {{"slayer_major", "major", "Voidgloom Seraph 7", "Major achievement unlocked: Voidgloom Seraph 7. That grind was worth it.", "all", "0"},
                {"goal_checkpoint", "checkpoint", "Reach Catacombs 40", "Progress update on Reach Catacombs 40: level 30, ~10 levels to go. Keep going and show a longer sentence here.", "party", "1"},
                {"runs", "completed", "500 dungeon runs", "Another milestone down! 500 dungeon runs secured. The grind continues.", "manual", "1"}};
        for (String[] r : rows) {
            var m = new dev.skycore.share.Prepared();
            m.category = r[0]; m.kind = r[1]; m.title = r[2]; m.text = r[3]; m.destination = r[4]; m.estimated = r[5].equals("1");
            st.inbox.add(m);
        }
    }

    /** Screenshot run only: a few clearly synthetic debugger entries so the panel layout can be reviewed. */
    private static void demoDebugger() {
        dev.skycore.tracking.DebugLog.setEnabled(true);
        long now = System.currentTimeMillis();
        dev.skycore.tracking.DebugLog.add(new dev.skycore.tracking.DebugLog.Entry(now - 9000, dev.skycore.tracking.DebugLog.Filter.DUNGEON, "CatacombsXp", "cata_xp", 0.98,
                java.util.Map.of("xp", "45000"), "Sample", "new", true, "run_completed", "progress+history"));
        dev.skycore.tracking.DebugLog.add(new dev.skycore.tracking.DebugLog.Entry(now - 7000, dev.skycore.tracking.DebugLog.Filter.DUNGEON, "CatacombsXp", "cata_xp", 0.98,
                java.util.Map.of("xp", "45000"), "Sample", "duplicate", false, "duplicate", "history"));
        dev.skycore.tracking.DebugLog.add(new dev.skycore.tracking.DebugLog.Entry(now - 5000, dev.skycore.tracking.DebugLog.Filter.ACTION_BAR, "SkillProgress", "skill_abs", 0.95,
                java.util.Map.of("skill", "Mining", "gain", "120", "current", "41200", "need", "1000000"), "Sample", "new", true, "verified_value", "progress"));
        dev.skycore.tracking.DebugLog.add(new dev.skycore.tracking.DebugLog.Entry(now - 3000, dev.skycore.tracking.DebugLog.Filter.CHAT, "KuudraDown", "kuudra_down", 0.45,
                java.util.Map.of(), "Sample", "-", false, "low_confidence", "history"));
        dev.skycore.tracking.DebugLog.unmatched(dev.skycore.tracking.DebugLog.Filter.CHAT, "synthetic line", "Sample");
    }

    /** Capture runs must never overwrite the developer's real config with sample-data settings. */
    public static boolean blockSaving() { return ENABLED; }
}
