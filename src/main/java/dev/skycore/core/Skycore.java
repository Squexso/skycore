package dev.skycore.core;

import com.mojang.blaze3d.platform.InputConstants;
import dev.skycore.data.Goals;
import dev.skycore.data.Progress;
import dev.skycore.data.SessionLog;
import dev.skycore.hud.HudEditorScreen;
import dev.skycore.hud.HudRenderer;
import dev.skycore.hud.HudStore;
import dev.skycore.notify.Notifier;
import dev.skycore.tracking.HypixelTracker;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.theme.Theme;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires everything together: persistence, input, events, HUD. */
public final class Skycore {
    public static final Logger LOG = LoggerFactory.getLogger("SKYCORE");
    private static Config config = new Config();
    private static boolean configDirty;
    private static KeyMapping openKey, hudKey, copyShareKey;
    private static Screen pendingScreen;
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> LATER = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Runs the task at the start of the next client tick, never inside a render pass. Screen transitions requested
     * from a button callback must go through here: the button is evaluated while its screen is being drawn.
     */
    public static void later(Runnable r) { LATER.add(r); }

    private static int saveTimer;
    private static boolean wasOnSkyBlock;

    private Skycore() {}

    public static Config config() { return config; }

    public static void markDirty() { configDirty = true; }

    /** Startup stage timing (log only): proves when SKYCORE runs and how long each step takes. */
    private static long stageStart, stageLast;

    private static void stage(String name) {
        long now = System.nanoTime();
        LOG.info("SKYCORE startup: {} (+{} ms, {} ms total)", name, (now - stageLast) / 1_000_000, (now - stageStart) / 1_000_000);
        stageLast = now;
    }

    /**
     * Client entrypoint only – runs after Minecraft has started (never during pre-launch, mod discovery or the
     * graphics adapter probe). SKYCORE has no preLaunch entrypoint, no mixins and no access wideners.
     */
    public static void init() {
        stageStart = stageLast = System.nanoTime();
        LOG.info("SKYCORE startup: begin (client entrypoint, thread {})", Thread.currentThread().getName());
        config = Storage.load("config.json", Config.class, Config::new);
        config.normalize();
        Lang.load(config.language);
        Theme.rebuild();
        stage("config, language, theme");
        Progress.load();
        SessionLog.load();
        Goals.load();
        HudStore.load();
        dev.skycore.data.History.load();
        stage("data files");
        // crash detection: a lock left behind means the previous session ended without a clean shutdown
        SessionGuard.io = Storage::async;
        var interrupted = SessionGuard.begin(Storage.dir(), System.currentTimeMillis());
        if (interrupted != null) {
            dev.skycore.data.History.session(interrupted);
            LOG.warn("Previous session was interrupted (last saved {}); nothing was estimated for the gap", interrupted.end);
        }
        HypixelTracker.loadPatterns();
        HypixelTracker.loadRewards();
        Notifier.init();
        dev.skycore.share.ShareService.init();
        HudRenderer.register();
        // the integrity check is not needed to start – it runs on the first client tick instead
        Backups.autoBackup(); // writes on the storage thread
        stage("session guard, patterns, notifications, HUD, backup scheduled");

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skycore", "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skycore.open", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, cat));
        hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skycore.hud_editor", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));

        copyShareKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skycore.copy_share", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));
        ClientReceiveMessageEvents.GAME.register(HypixelTracker::onGameMessage);
        ClientTickEvents.END_CLIENT_TICK.register(Skycore::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            HypixelTracker.shutdown();
            var rec = SessionGuard.end(System.currentTimeMillis(), Progress.profileName());
            if (rec != null) dev.skycore.data.History.session(rec);
            saveAll();
            Storage.flush();
            SessionGuard.release(); // only after everything is on disk
        });
        // leaving a server: drop transient tracker state and flush data immediately; the profile must be
        // identified again after reconnecting before profile data is written
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            HypixelTracker.reset();
            SessionGuard.disconnected();
            saveAll();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommands.literal("skycore")
                        .executes(c -> { pendingScreen = new SkycoreScreen(); return 1; })
                        .then(ClientCommands.literal("hud").executes(c -> { pendingScreen = new HudEditorScreen(null); return 1; }))));
        stage("keys, events, commands");
        LOG.info("SKYCORE initialised");
    }

    private static boolean firstTick = true;

    private static void tick(Minecraft mc) {
        if (firstTick) {
            firstTick = false;
            long t = System.nanoTime();
            dev.skycore.data.Integrity.checkNow();
            LOG.info("SKYCORE startup: integrity check on first tick ({} ms)", (System.nanoTime() - t) / 1_000_000);
        }
        HypixelTracker.tick(mc);
        DevCapture.tick(mc);
        DevStress.tick(mc);
        for (Runnable r; (r = LATER.poll()) != null; ) {
            try { r.run(); } catch (RuntimeException e) { dev.skycore.ui.Ui.logFault("deferred task", e); }
        }
        if (pendingScreen != null && mc.screen == null) {
            mc.setScreen(pendingScreen);
            pendingScreen = null;
        }
        while (openKey.consumeClick()) if (mc.screen == null) mc.setScreen(new SkycoreScreen());
        while (hudKey.consumeClick()) if (mc.screen == null) mc.setScreen(new HudEditorScreen(null));
        while (copyShareKey.consumeClick()) dev.skycore.share.ShareService.copyLatest(); // the player's own key press
        dev.skycore.share.ShareService.tick();
        boolean sky = HypixelTracker.onSkyBlock();
        if (sky && !wasOnSkyBlock) Notifier.inactivityReminder();
        wasOnSkyBlock = sky;
        if (++saveTimer >= 600) { // every 30 s
            saveTimer = 0;
            saveAll();
            SessionGuard.heartbeat(System.currentTimeMillis(), Progress.profileName());
        }
        Backups.periodic();
    }

    public static void saveConfig() {
        Storage.save("config.json", config);
        configDirty = false;
    }

    public static void saveAll() {
        if (DevCapture.blockSaving()) return;
        if (configDirty) saveConfig();
        Progress.saveIfDirty();
        SessionLog.saveIfDirty();
        Goals.saveIfDirty();
        dev.skycore.share.ShareService.saveIfDirty();
        HudStore.saveIfDirty();
        dev.skycore.data.History.saveIfDirty();
    }

    public static KeyMapping openKey() { return openKey; }

    public static KeyMapping hudKey() { return hudKey; }

    /** Replace all settings (import) – language, theme and HUD scale apply immediately. */
    public static void replaceConfig(Config c) {
        c.normalize();
        config = c;
        Lang.load(c.language);
        Theme.rebuild();
        markDirty();
    }

    /** Restore default settings. Tracking data, goals and HUD layouts are not touched. */
    public static void resetSettings() {
        replaceConfig(new Config());
        saveConfig();
    }

    /** Change language at runtime (every visible string is looked up per frame, so the switch is instant). */
    public static void setLanguage(String code) {
        config.language = code;
        Lang.load(code);
        markDirty();
    }
}
