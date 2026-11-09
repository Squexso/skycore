package dev.skycore.tracking;

import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;
import dev.skycore.data.Goals;
import dev.skycore.data.History;
import dev.skycore.data.Progress;
import dev.skycore.data.SessionLog;
import dev.skycore.data.StatDef;
import dev.skycore.data.XpTables;
import dev.skycore.tracking.Health.Source;
import dev.skycore.tracking.parse.CustomPatterns;
import dev.skycore.tracking.parse.Dedupe;
import dev.skycore.tracking.parse.MenuParser;
import dev.skycore.tracking.parse.MessageParser;
import dev.skycore.tracking.parse.PanelParser;
import dev.skycore.tracking.parse.ParsedEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passive data collection. Observes only what Hypixel sends to the client – chat, action bar, sidebar,
 * tab list and the items of menus the <i>player</i> opens – and never sends input, clicks or opens anything.
 * <p>
 * Parsing lives in the pure {@code tracking.parse} package (unit-tested); this class adds context
 * (am I in a dungeon? which slayer tier? which profile?), confidence, dedupe, health reporting, the optional
 * debugger and applies the results.
 * <p>
 * Profile safety: after joining, profile-specific data is only written once the profile has been identified
 * (chat "You are playing on profile", the tab list, or the user). Chat events arriving before that are held
 * for up to two minutes and then applied to the identified profile – or dropped, never guessed.
 */
public final class HypixelTracker {
    private HypixelTracker() {}

    /** Events below this confidence are logged but not applied. */
    public static final double MIN_CONFIDENCE = 0.5;
    private static final long PENDING_MAX_AGE = 120_000, RUN_RESUME_WINDOW = 10 * 60_000L;
    private static final int PENDING_MAX = 40;

    private static volatile MessageParser parser = MessageParser.defaults();
    private static CustomPatterns.Result customResult = new CustomPatterns.Result(0, List.of());
    private static long patternsLoadedAt, patternsMtime = -1;
    private static int patternWatchTimer;
    private static final Dedupe EVENTS = new Dedupe(4000);
    private static final Map<String, String> lastActionBar = new HashMap<>();

    // panels
    private static boolean onSkyBlock;
    private static String area = "", floor, slayerTier, kuudraTier, sidebarClass;
    private static int sidebarTimer, tabTimer = 60, menuTimer, menuHash, snapshotTimer, healthTimer;

    // dungeon run state
    private static boolean runActive;
    private static long runStartedAt, summarySeenAt, lastRunCompletedAt;
    private static String runFloor, runClass, lastRank;
    private static int lastScore;
    /** A run interrupted by a disconnect: resumed if the player is back on the same floor in time. */
    private record SuspendedRun(String floor, long startedAt, String dungeonClass, long at, String profileKey) {}
    private static SuspendedRun suspended;

    // slayer
    private static final SlayerSession slayer = new SlayerSession(History::mayorAt);
    private static final java.util.ArrayDeque<dev.skycore.data.Activity.SlayerQuest> pendingQuests = new java.util.ArrayDeque<>();
    private static int nametagTimer;
    private static String slayerPhase;
    // exact floor evidence of the current run
    private static final java.util.LinkedHashSet<String> runFloorsSeen = new java.util.LinkedHashSet<>();
    private static String summaryFloor;
    private static long slayerCompletedAt;
    private static boolean slayerVisible;
    private static String lastSidebarSig = "", lastTabSig = "";
    /** The last run whose floor was simply not shown – a summary line arriving shortly after can still resolve it. */
    private static String lastUncertainRun;
    private static long lastUncertainRunAt;

    // profile gate
    private record Pending(ParsedEvent event, String source, String rule, double conf, long t) {}
    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();

    // playtime / afk
    private static double lastX, lastZ;
    private static float lastYaw, lastPitch;
    private static long lastInput = System.currentTimeMillis();
    private static int secondTicks;

    // ------------------------------------------------------------------ status (for the UI)
    public static boolean onSkyBlock() { return onSkyBlock; }
    public static String area() { return area; }
    public static boolean inDungeon() { return floor != null || area.contains("Catacombs"); }
    public static boolean runActive() { return runActive; }
    /** A slayer quest is on the sidebar or being followed right now. */
    public static boolean slayerActive() { return slayerVisible || slayer.active(); }
    public static String currentClass() { return runClass != null ? runClass : sidebarClass; }
    public static CustomPatterns.Result customPatterns() { return customResult; }
    public static MessageParser parser() { return parser; }
    public static long patternsLoadedAt() { return patternsLoadedAt; }
    public static int pendingCount() { return pending.size(); }

    public static boolean inMiningArea() {
        String a = area.toLowerCase();
        return a.contains("dwarven") || a.contains("crystal") || a.contains("mines") || a.contains("glacite") || a.contains("mineshaft")
                || a.contains("forge") || a.contains("royal") || a.contains("gunpowder") || a.contains("deep cavern");
    }

    // ------------------------------------------------------------------ patterns (validated, hot-reloaded)
    /**
     * Load user patterns (config/skycore/patterns.json) on top of the built-in rules. The new rule set is built and
     * validated completely before it replaces the active one; if the file is not valid JSON the previous user
     * rules stay active.
     */
    public static void loadPatterns() {
        Path file = Storage.dir().resolve("patterns.json");
        Path example = Storage.dir().resolve("patterns.example.json");
        try {
            if (!Files.exists(example)) Files.writeString(example, EXAMPLE_PATTERNS);
            patternsMtime = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
            MessageParser next = MessageParser.defaults();
            CustomPatterns.Result r = Files.exists(file) ? CustomPatterns.load(next, Files.readString(file)) : new CustomPatterns.Result(0, List.of());
            for (String err : r.errors()) Skycore.LOG.warn("patterns.json: {}", err);
            if (!r.jsonValid() && customResult.loaded() > 0) {
                // keep the working rules; report the problem
                customResult = new CustomPatterns.Result(customResult.loaded(), r.errors(), customResult.entries(), false);
            } else {
                parser = next;
                customResult = r;
            }
            patternsLoadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            Skycore.LOG.warn("Could not load patterns.json", e);
            customResult = new CustomPatterns.Result(customResult.loaded(), List.of("could not read patterns.json: " + e.getClass().getSimpleName()), customResult.entries(), false);
        }
    }

    /** Every 5 s: reload patterns.json / rewards.json when they changed (file-time lookups only). */
    private static void watchPatterns() {
        if (++patternWatchTimer < 100) return;
        patternWatchTimer = 0;
        try {
            Path file = Storage.dir().resolve("patterns.json");
            long m = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
            if (m != patternsMtime) loadPatterns();
            Path rw = Storage.dir().resolve("rewards.json");
            long rm = Files.exists(rw) ? Files.getLastModifiedTime(rw).toMillis() : 0;
            if (rm != rewardsMtime) loadRewards();
        } catch (Exception ignored) {}
    }

    private static long rewardsMtime = -1;

    /** User overrides of the reward table (validated; built-in values stay untouched). */
    public static void loadRewards() {
        Path file = Storage.dir().resolve("rewards.json");
        try {
            Path example = Storage.dir().resolve("rewards.example.json");
            if (!Files.exists(example)) Files.writeString(example, EXAMPLE_REWARDS);
            rewardsMtime = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
            dev.skycore.data.Rewards.load(Files.exists(file) ? Files.readString(file) : null);
            for (String e : dev.skycore.data.Rewards.errors()) Skycore.LOG.warn("rewards.json: {}", e);
        } catch (Exception e) {
            Skycore.LOG.warn("Could not load rewards.json", e);
        }
    }

    private static final String EXAMPLE_REWARDS = """
            {
              "version": 1,
              "slayer": { "enderman": [5, 25, 100, 500] },
              "floors": { "F7": 20000 }
            }
            """;

    // ------------------------------------------------------------------ connection lifecycle
    /**
     * Disconnect / world change: drop transient state. A dungeon run in progress is suspended (it may be resumed
     * after reconnecting); the profile becomes unknown until the game confirms it again.
     */
    public static void reset() {
        handleQuest(slayer.disconnect(System.currentTimeMillis()), "disconnect");
        slayerPhase = null;
        if (runActive && System.currentTimeMillis() - lastRunCompletedAt > 60_000)
            suspended = new SuspendedRun(runFloor, runStartedAt, currentClass(), System.currentTimeMillis(), Progress.profileKey());
        runActive = false;
        onSkyBlock = false;
        floor = runFloor = runClass = sidebarClass = slayerTier = kuudraTier = lastRank = null;
        area = "";
        lastScore = 0;
        lastActionBar.clear();
        EVENTS.clear();
        menuHash = 0;
        slayerCompletedAt = 0;
        slayerVisible = false;
        pending.clear();
        Progress.markUnknown();
        for (Source s : new Source[]{Source.CHAT, Source.ACTION_BAR, Source.SIDEBAR, Source.TAB, Source.MENU, Source.PROFILE, Source.DUNGEON, Source.SLAYER})
            Health.available(s, false, "health.note.offline");
    }

    /** Game shutdown: a suspended or active run can no longer be resumed – record it as incomplete (no XP). */
    public static void shutdown() {
        SessionLog.profileLost("shutdown", Progress.real());
        reset();
        finishSuspended(true);
    }

    private static void finishSuspended(boolean force) {
        if (suspended == null) return;
        if (!force && System.currentTimeMillis() - suspended.at() < RUN_RESUME_WINDOW) return;
        if (suspended.profileKey() != null && suspended.profileKey().equals(Progress.profileKey())) {
            Progress.recordIncompleteRun(DungeonClassifier.classify(suspended.floor() == null ? List.of() : List.of(suspended.floor()), null), suspended.startedAt(), suspended.dungeonClass());
            log("run_incomplete", "catacombs", 0, "chat", "disconnect", 1, true);
        }
        suspended = null;
    }

    private static boolean enabled() { return Skycore.config().trackingEnabled; }

    private static void log(String kind, String stat, double amount, String source, String rule, double confidence, boolean applied) {
        History.event(Progress.profileName(), kind, stat, amount, source, rule, confidence, applied);
    }

    // ------------------------------------------------------------------ chat & action bar
    public static void onGameMessage(Component message, boolean overlay) {
        if (!enabled()) return;
        Source src = overlay ? Source.ACTION_BAR : Source.CHAT;
        try {
            Health.activity(src);
            String text = StringUtil.stripColor(message.getString());
            var channel = overlay ? MessageParser.Channel.ACTION_BAR : MessageParser.Channel.CHAT;
            List<MessageParser.Recognized> found = parser.recognize(text, channel);
            String source = overlay ? "action_bar" : "chat";
            DebugLog.Filter filter = overlay ? DebugLog.Filter.ACTION_BAR : DebugLog.Filter.CHAT;
            if (found.isEmpty() && DebugLog.enabled() && !overlay) {
                if (MessageParser.isPlayerMessage(text.trim())) DebugLog.playerChat();
                else if (onSkyBlock) DebugLog.unmatched(filter, text, Progress.profileName());
            }
            for (MessageParser.Recognized r : found) {
                double conf = Math.max(0, Math.min(1, r.confidence() + contextBonus(r.event())));
                if (conf < MIN_CONFIDENCE) {
                    log("ignored", null, 0, source, r.rule(), conf, false);
                    DebugLog.problem("low_confidence", r.rule());
                    debug(r.event(), r.rule(), conf, "-", false, "low_confidence", "history");
                    continue;
                }
                dispatch(r.event(), text, source, r.rule(), conf, System.currentTimeMillis());
            }
            if (!found.isEmpty()) { Health.success(src); Goals.check(); }
        } catch (Exception e) {
            Health.failure(src, e.getClass().getSimpleName());
            Skycore.LOG.warn("SKYCORE failed to handle a message", e); // never let a malformed message crash the game
        }
    }

    /** Context raises or lowers confidence: a Catacombs XP line inside a dungeon / right after the run summary is very likely genuine. */
    private static double contextBonus(ParsedEvent e) {
        long now = System.currentTimeMillis();
        boolean afterSummary = now - summarySeenAt < 30_000;
        return switch (e) {
            case ParsedEvent.CatacombsXp c -> afterSummary || inDungeon() ? 0.08 : -0.2;
            case ParsedEvent.ClassXp c -> afterSummary || inDungeon() ? 0.08 : -0.25;
            case ParsedEvent.TeamScore s -> inDungeon() ? 0.05 : -0.2;
            case ParsedEvent.KuudraDown k -> kuudraTier != null ? 0.2 : -0.1;
            case ParsedEvent.SlayerLevel l -> now - slayerCompletedAt < 15_000 ? 0.03 : 0;
            default -> 0;
        };
    }

    /** Events that change data of one specific profile. */
    static boolean profileSpecific(ParsedEvent e) {
        return switch (e) {
            case ParsedEvent.TeamScore t -> false;
            case ParsedEvent.RunSummary r -> false;
            case ParsedEvent.DungeonStart d -> false;
            case ParsedEvent.ClassSelected c -> false;
            case ParsedEvent.SlayerQuestStarted q -> false;
            case ParsedEvent.SlayerQuestComplete q -> false;
            case ParsedEvent.ProfileSwitch p -> false;
            case ParsedEvent.SlayerRequirement r -> false;
            case ParsedEvent.SlayerQuestFailed f -> false;
            case ParsedEvent.DungeonFloorSummary f -> false;
            case ParsedEvent.MayorInfo m -> false;
            case ParsedEvent.SlayerLevel l -> false; // the quest record waits for the profile itself; the level update is applied below
            case ParsedEvent.ValueReading v -> !v.key().startsWith("area:");
            default -> true;
        };
    }

    /** The profile gate: profile-specific events wait until the profile is known. */
    private static void dispatch(ParsedEvent ev, String raw, String source, String rule, double conf, long t) {
        if (profileSpecific(ev) && !Progress.isIdentified()) {
            boolean oneOff = source.equals("chat") || source.equals("action_bar");
            if (oneOff) {
                pending.addLast(new Pending(ev, source, rule, conf, t));
                while (pending.size() > PENDING_MAX) pending.removeFirst();
            }
            debug(ev, rule, conf, "-", false, oneOff ? "profile_unknown_held" : "profile_unknown_skipped", "-");
            return;
        }
        apply(ev, raw, source, rule, conf, t);
    }

    /** The profile was identified: apply events that arrived before (if still fresh), then read panels again. */
    private static void releasePending() {
        long now = System.currentTimeMillis();
        while (!pending.isEmpty()) {
            Pending p = pending.removeFirst();
            if (now - p.t() > PENDING_MAX_AGE) { debug(p.event(), p.rule(), p.conf(), "-", false, "profile_unknown_expired", "-"); continue; }
            apply(p.event(), "", p.source(), p.rule(), p.conf(), p.t());
        }
        while (!pendingQuests.isEmpty()) recordQuest(pendingQuests.removeFirst());
        tabTimer = 95;   // re-read the tab list and sidebar right away
        sidebarTimer = 19;
        menuHash = 0;
    }

    private static void identified(String name, String source) {
        Minecraft mc = Minecraft.getInstance();
        String owner = mc.player == null ? null : mc.player.getUUID().toString();
        boolean was = Progress.isIdentified();
        String before = Progress.profileKey();
        String key = Progress.identify(name, owner, source);
        if (key == null) return;
        Health.success(Source.PROFILE);
        if (!was || !key.equals(before)) {
            log("profile", null, 0, source, "profile", 1, true);
            // a run suspended by a disconnect belongs to the profile it was played on
            if (suspended != null && !key.equals(suspended.profileKey())) finishSuspendedFor(suspended);
            releasePending();
        }
    }

    private static void finishSuspendedFor(SuspendedRun s) {
        // the run was on another profile – it stays unrecorded rather than being written to the wrong one
        Skycore.LOG.info("Suspended run belonged to another profile – not recorded");
        suspended = null;
    }

    /** Manual identification from the profile indicator (the user confirms which profile is active). */
    public static void confirmProfile(String key) {
        if (key == null) return;
        String name = Progress.displayName(key);
        Minecraft mc = Minecraft.getInstance();
        String owner = mc.player == null ? null : mc.player.getUUID().toString();
        if (Progress.identify(name, owner, "manual") != null) { Health.success(Source.PROFILE); releasePending(); }
    }

    private static void apply(ParsedEvent ev, String raw, String source, String rule, double conf, long now) {
        switch (ev) {
            case ParsedEvent.SkillProgress s -> {
                StatDef def = StatDef.byActionBar(s.skill());
                if (def == null) { debug(ev, rule, conf, "-", false, "unknown_skill", "-"); return; }
                String key = s.skill() + s.gain() + "|" + s.current() + "/" + s.need();
                if (key.equals(lastActionBar.put(def.id(), key))) return; // Hypixel repeats the bar every second (not logged: too frequent)
                int level = XpTables.levelFromNeed(def.table(), (long) s.need(), def.maxLevel());
                if (level < 0) { debug(ev, rule, conf, "new", false, "level_not_derivable", "-"); return; }
                double delta = Progress.observe(def, level, s.current(), s.need(), source);
                double gain = delta >= 0 && delta <= Math.max(s.gain() * 20, 1) ? delta : s.gain();
                Progress.recordGainOnly(def, gain);
                log("xp", def.id(), gain, source, rule, conf, true);
                debug(ev, rule, conf, "new", true, "verified_value", "progress");
            }
            case ParsedEvent.SkillPercent s -> {
                StatDef def = StatDef.byActionBar(s.skill());
                if (def == null) return;
                String key = s.skill() + s.gain() + "|" + s.percent();
                if (key.equals(lastActionBar.put(def.id(), key))) return;
                Progress.observePercent(def, s.percent());
                Progress.recordGainOnly(def, s.gain());
                log("xp", def.id(), s.gain(), source, rule, conf, true);
                debug(ev, rule, conf, "new", true, "percent_value", "progress");
            }
            case ParsedEvent.TeamScore t -> { lastRank = t.rank(); lastScore = t.score(); debug(ev, rule, conf, "-", true, "run_context", "-"); }
            case ParsedEvent.RunSummary r -> { summarySeenAt = now; debug(ev, rule, conf, "-", true, "run_context", "-"); }
            case ParsedEvent.DungeonStart d -> { startRun(now); Health.success(Source.DUNGEON); debug(ev, rule, conf, "-", true, "run_started", "history"); }
            case ParsedEvent.ClassSelected c -> { runClass = c.dungeonClass(); debug(ev, rule, conf, "-", true, "class_selected", "-"); }
            case ParsedEvent.CatacombsXp c -> {
                if (EVENTS.duplicate("cata:" + c.xp(), now)) { log("duplicate", "catacombs", c.xp(), source, rule, conf, false); debug(ev, rule, conf, "duplicate", false, "duplicate", "history"); return; }
                if (!Skycore.config().tracks("dungeons")) { log("xp", "catacombs", c.xp(), source, rule, conf, false); debug(ev, rule, conf, "new", false, "category_disabled", "history"); return; }
                StatDef def = StatDef.get("catacombs");
                Progress.gain(def, c.xp());
                Progress.count(def);
                var cls = classifyRun();
                var run = Progress.recordRun(c.xp(), cls, lastRank, lastScore, runActive ? runStartedAt : 0, currentClass());
                log("run", run != null && run.floor != null ? "catacombs:" + run.floor : "catacombs", c.xp(), source, rule, conf, run != null);
                debug(ev, rule, conf, run == null ? "duplicate" : "new", run != null, run == null ? "duplicate" : cls.confirmed ? "run_completed" : "run_unclassified", "progress+history");
                if (run != null && run.floor == null) {
                    DebugLog.problem("run_unclassified", String.join(",", cls.missing));
                    boolean onlyMissing = cls.candidates.isEmpty();
                    lastUncertainRun = onlyMissing ? run.id : null;
                    lastUncertainRunAt = now;
                }
                runFloorsSeen.clear();
                summaryFloor = null;
                Health.success(Source.DUNGEON);
                lastRunCompletedAt = now;
                runActive = false;
                suspended = null;
                lastRank = null;
                lastScore = 0;
            }
            case ParsedEvent.ClassXp c -> {
                if (EVENTS.duplicate("class:" + c.dungeonClass() + c.xp() + c.teamBonus(), now)) { debug(ev, rule, conf, "duplicate", false, "duplicate", "-"); return; }
                StatDef def = StatDef.byClass(c.dungeonClass());
                if (def == null) return;
                Progress.gain(def, c.xp());
                if (!c.teamBonus()) { Progress.count(def); Progress.attachClassXp(c.dungeonClass(), c.xp()); runClass = c.dungeonClass(); }
                log("xp", def.id(), c.xp(), source, rule, conf, true);
                debug(ev, rule, conf, "new", true, "class_xp", "progress+history");
            }
            case ParsedEvent.SlayerQuestStarted q -> {
                handleQuest(slayer.started(now), rule);
                debug(ev, rule, conf, "-", true, "slayer_context", "-");
            }
            case ParsedEvent.SlayerRequirement r -> {
                slayer.requirement(now, r.mobs());
                debug(ev, rule, conf, "-", true, "slayer_type_evidence", "-");
            }
            case ParsedEvent.SlayerQuestFailed f -> {
                handleQuest(slayer.failed(now), rule);
                debug(ev, rule, conf, "-", false, "quest_failed", "progress");
            }
            case ParsedEvent.SlayerQuestComplete q -> {
                boolean dup = EVENTS.duplicate("slayer_done", now) || !slayer.completed(now);
                if (!dup) { slayerCompletedAt = now; Health.success(Source.SLAYER); }
                debug(ev, rule, conf, dup ? "duplicate" : "new", !dup, dup ? "duplicate" : "slayer_context", "-");
            }
            case ParsedEvent.DungeonFloorSummary f -> {
                if (f.floor() != null) summaryFloor = f.floor();
                if (f.floor() != null && lastUncertainRun != null && now - lastUncertainRunAt <= 20_000 && Progress.isIdentified()) {
                    boolean ok = Progress.resolveRunLate(lastUncertainRun, f.floor(), "evidence.run_summary");
                    reconcileLog(DebugLog.Filter.DUNGEON, lastUncertainRun, f.floor(), ok ? "auto_resolved" : "auto_rejected", "late_evidence");
                    lastUncertainRun = null;
                }
                debug(ev, rule, conf, "-", f.floor() != null, f.floor() != null ? "floor_evidence" : "floor_unknown", "-");
            }
            case ParsedEvent.MayorInfo m -> {
                dev.skycore.data.Mayors.Observation o = new dev.skycore.data.Mayors.Observation();
                o.t = now;
                o.role = m.role();
                o.name = m.name();
                o.perks.addAll(m.perks());
                o.slayerXpPercent = m.slayerXpPercent();
                o.slayerPerk = m.slayerPerk();
                o.slayerScope.addAll(m.slayerScope());
                o.source = source;
                History.observeMayor(o);
                if (Progress.isIdentified()) Progress.attachMayor(History::mayorAt);
                debug(ev, rule, conf, "-", true, "mayor_observed", "history");
            }
            case ParsedEvent.SlayerLevel l -> {
                StatDef def = StatDef.bySlayer(l.type());
                if (def == null) return;
                if (EVENTS.duplicate("slayer:" + l.type() + l.level() + "|" + l.remaining(), now)) { debug(ev, rule, conf, "duplicate", false, "duplicate", "-"); return; }
                if (!Progress.isIdentified()) { // the quest itself is still classified; its record waits for the profile
                    handleQuest(slayer.level(now, l.type(), -1), rule);
                    debug(ev, rule, conf, "-", false, "profile_unknown_skipped", "-");
                    return;
                }
                Health.success(Source.SLAYER);
                boolean afterQuest = now - slayerCompletedAt < 15_000;
                if (afterQuest) { Progress.count(def); slayerCompletedAt = 0; }
                if (l.remaining() < 0) {
                    Progress.setLevel(def, l.level());
                    handleQuest(slayer.level(now, l.type(), -1), rule);
                    log("level", def.id(), l.level(), source, rule, conf, true);
                    debug(ev, rule, conf, "new", true, "max_level", "progress+history");
                    return;
                }
                long need = XpTables.need(def.table(), l.level());
                if (need <= 0) return;
                double delta = Progress.observe(def, l.level(), Math.max(0, need - l.remaining()), need, source);
                dev.skycore.data.Activity.SlayerQuest quest = afterQuest ? slayer.level(now, l.type(), delta) : null;
                handleQuest(quest, rule);
                if (delta > 0 && afterQuest) {
                    // gains are tagged with the confirmed tier only – never with a guess
                    String tag = quest != null && quest.classified() ? dev.skycore.data.Activity.roman(quest.tier) : null;
                    Progress.recordGainOnly(def, delta, tag);
                    log("boss", def.id(), delta, source, rule, conf, true);
                    debug(ev, rule, conf, "new", true, "boss_xp", "progress+history");
                } else {
                    log("verify", def.id(), 0, source, rule, conf, true);
                    debug(ev, rule, conf, "new", true, "verified_value", "progress");
                }
            }
            case ParsedEvent.SkillLevelUp s -> {
                StatDef def = StatDef.byActionBar(s.skill());
                if (def != null && !EVENTS.duplicate("skillup:" + s.skill() + s.level(), now)) {
                    Progress.setLevel(def, s.level());
                    log("level", def.id(), s.level(), source, rule, conf, true);
                    debug(ev, rule, conf, "new", true, "level_up", "progress+history");
                }
            }
            case ParsedEvent.ProfileSwitch p -> {
                identified(p.name(), source);
                debug(ev, rule, conf, "-", true, "profile_identified", "progress");
            }
            case ParsedEvent.CollectionLevelUp c -> {
                if (Skycore.config().tracks("collections") && !EVENTS.duplicate("coll:" + c.name() + c.level(), now)) {
                    Progress.collectionLevel(c.name(), c.level());
                    log("milestone", "collection:" + c.name(), c.level(), source, rule, conf, true);
                    debug(ev, rule, conf, "new", true, "collection_level", "progress+history");
                }
            }
            case ParsedEvent.BestiaryMilestone b -> {
                if (Skycore.config().tracks("bestiary") && !EVENTS.duplicate("bms:" + b.level(), now)) {
                    Progress.milestone(StatDef.get("bestiary"), b.level());
                    log("milestone", "bestiary", b.level(), source, rule, conf, true);
                    debug(ev, rule, conf, "new", true, "milestone", "progress+history");
                }
            }
            case ParsedEvent.BestiaryFamilyTier b -> {
                if (!EVENTS.duplicate("bfam:" + b.family() + b.tier(), now)) {
                    Progress.bestiaryFamilyTier(b.family(), b.tier());
                    log("milestone", "bestiary_family", b.tier(), source, rule, conf, true);
                    debug(ev, rule, conf, "new", true, "family_tier", "progress+history");
                }
            }
            case ParsedEvent.HotmTier h -> {
                if (Skycore.config().tracks("mining") && !EVENTS.duplicate("hotm:" + h.tier(), now)) {
                    Progress.milestone(StatDef.get("hotm"), h.tier());
                    log("milestone", "hotm", h.tier(), source, rule, conf, true);
                    debug(ev, rule, conf, "new", true, "milestone", "progress+history");
                }
            }
            case ParsedEvent.KuudraDown k -> {
                if (EVENTS.duplicate("kuudra", now)) { debug(ev, rule, conf, "duplicate", false, "duplicate", "-"); return; }
                String key = "kuudra_" + (kuudraTier == null ? "unknown" : kuudraTier.toLowerCase());
                Progress.increment(key, source);
                log("run", key, 1, source, rule, conf, true);
                debug(ev, rule, conf, "new", true, "kuudra_completed", "progress+history");
            }
            case ParsedEvent.SkillLevelPercent s -> {
                StatDef def = StatDef.byActionBar(s.skill());
                if (def == null) return;
                if (s.percent() < 0) Progress.observeMax(def, s.level(), source);
                else Progress.observeApprox(def, s.level(), s.percent(), source);
            }
            case ParsedEvent.ValueReading v -> {
                if (v.key().startsWith("area:")) { area = v.key().substring(5); return; }
                Progress.value(v.key(), v.value(), source);
            }
            case ParsedEvent.MenuProgress m -> applyMenu(m);
            case ParsedEvent.SlayerKillCounts k -> {
                var o = Progress.observeSlayerCounts(k.slayerId(), k.counts(), now);
                reconcileOutcome(DebugLog.Filter.SLAYER, "slayer:" + k.slayerId(), o);
            }
            case ParsedEvent.FloorCompletionCounts f -> {
                var o = Progress.observeFloorCounts(f.counts(), now);
                reconcileOutcome(DebugLog.Filter.DUNGEON, "dungeon", o);
            }
        }
    }

    /** Debugger entry: event type, rule, confidence and the extracted values only. */
    private static void debug(ParsedEvent ev, String rule, double conf, String dedupe, boolean accepted, String reason, String persisted) {
        if (!DebugLog.enabled()) return;
        DebugLog.Filter f = switch (ev) {
            case ParsedEvent.SkillProgress s -> DebugLog.Filter.ACTION_BAR;
            case ParsedEvent.SkillPercent s -> DebugLog.Filter.ACTION_BAR;
            case ParsedEvent.CatacombsXp c -> DebugLog.Filter.DUNGEON;
            case ParsedEvent.ClassXp c -> DebugLog.Filter.DUNGEON;
            case ParsedEvent.TeamScore t -> DebugLog.Filter.DUNGEON;
            case ParsedEvent.RunSummary r -> DebugLog.Filter.DUNGEON;
            case ParsedEvent.DungeonStart d -> DebugLog.Filter.DUNGEON;
            case ParsedEvent.ClassSelected c -> DebugLog.Filter.DUNGEON;
            case ParsedEvent.SlayerLevel l -> DebugLog.Filter.SLAYER;
            case ParsedEvent.SlayerQuestComplete q -> DebugLog.Filter.SLAYER;
            case ParsedEvent.SlayerQuestStarted q -> DebugLog.Filter.SLAYER;
            case ParsedEvent.MenuProgress m -> DebugLog.Filter.MENU;
            default -> DebugLog.Filter.CHAT;
        };
        DebugLog.add(new DebugLog.Entry(System.currentTimeMillis(), f, ev.getClass().getSimpleName(), rule, conf, values(ev),
                Progress.isIdentified() ? Progress.profileName() : null, dedupe, accepted, reason, persisted));
    }

    /** Record components → strings (numbers, levels, names of skills/classes/collections). */
    private static Map<String, String> values(ParsedEvent ev) {
        Map<String, String> m = new LinkedHashMap<>();
        if (ev instanceof Record r) {
            for (var c : r.getClass().getRecordComponents()) {
                try {
                    Object v = c.getAccessor().invoke(r);
                    if (v != null) m.put(c.getName(), v instanceof Double d ? (d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d)) : String.valueOf(v));
                } catch (Exception ignored) {}
            }
        }
        return m;
    }

    private static void reconcileOutcome(DebugLog.Filter f, String family, dev.skycore.data.Reconciler.Outcome o) {
        for (var e : o.resolved().entrySet()) reconcileLog(f, e.getKey(), e.getValue(), "auto_resolved", "menu_counts");
        if (o.reason().equals("conflict") || o.reason().equals("ambiguous")) {
            DebugLog.problem("reconcile_" + o.reason(), family);
            if (DebugLog.enabled()) {
                Map<String, String> v = new LinkedHashMap<>();
                v.put("family", family);
                o.residual().forEach((k, x) -> v.put(k, String.valueOf(x)));
                DebugLog.add(new DebugLog.Entry(System.currentTimeMillis(), f, "Reconcile", "menu_counts", 0, v, Progress.profileName(), "-", false, "reconcile_" + o.reason(), "-"));
            }
        }
    }

    private static void reconcileLog(DebugLog.Filter f, String id, String key, String reason, String why) {
        if (!reason.equals("auto_resolved")) DebugLog.problem(reason, key);
        if (!DebugLog.enabled()) return;
        Map<String, String> v = new LinkedHashMap<>();
        v.put("event", id);
        v.put("resolved_to", key);
        v.put("via", why);
        DebugLog.add(new DebugLog.Entry(System.currentTimeMillis(), f, "Reconcile", why, 0.9, v, Progress.profileName(), "-",
                reason.equals("auto_resolved"), reason, reason.equals("auto_resolved") ? "progress+history" : "-"));
    }

    /** Late sidebar / nametag evidence for the slayer quest that just ended. */
    private static void handleLate(SlayerSession.Late l) {
        if (l == null) return;
        if (l.conflict()) { reconcileLog(DebugLog.Filter.SLAYER, l.questId(), l.key(), "late_conflict", "late_evidence"); return; }
        if (!Progress.isIdentified()) return;
        boolean ok = Progress.resolveQuestLate(l.questId(), l.key(), l.source());
        if (ok) reconcileLog(DebugLog.Filter.SLAYER, l.questId(), l.key(), "auto_resolved", "late_evidence");
    }

    /** Floor of the current run from all evidence collected (sidebar floors seen, end-of-run summary). */
    private static dev.skycore.data.Activity.Classification classifyRun() {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(runFloorsSeen);
        if (floor != null) seen.add(floor);
        return DungeonClassifier.classify(seen, summaryFloor);
    }

    /** Store a finished slayer quest (or hold it until the profile is confirmed). */
    private static void handleQuest(dev.skycore.data.Activity.SlayerQuest q, String rule) {
        if (q == null) return;
        if (!Progress.isIdentified()) {
            pendingQuests.addLast(q);
            while (pendingQuests.size() > 5) pendingQuests.removeFirst();
            return;
        }
        recordQuest(q);
    }

    private static void recordQuest(dev.skycore.data.Activity.SlayerQuest q) {
        boolean stored = Progress.recordSlayerQuest(q);
        if (DebugLog.enabled()) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("type", String.valueOf(q.type));
            v.put("tier", q.tier > 0 ? "T" + q.tier : "?");
            v.put("status", q.status);
            if (q.calc.observed >= 0) v.put("observed", String.valueOf(Math.round(q.calc.observed)));
            if (q.calc.expected >= 0) v.put("expected", String.valueOf(q.calc.expected));
            if (!q.classification.missing.isEmpty()) v.put("missing", String.join(",", q.classification.missing));
            DebugLog.add(new DebugLog.Entry(q.t, DebugLog.Filter.SLAYER, "SlayerQuest", "slayer_session", q.classification.confidence, v,
                    Progress.profileName(), stored ? "new" : "duplicate", stored && q.classified() && "complete".equals(q.status),
                    !stored ? "duplicate" : !"complete".equals(q.status) ? "quest_" + q.status : q.classified() ? "quest_classified" : "quest_unclassified", "progress"));
        }
        if (stored && "complete".equals(q.status) && !q.classified()) DebugLog.problem("quest_unclassified", String.join(",", q.classification.missing));
        if (stored && "complete".equals(q.status)) log("boss", q.key() == null ? "slayer" : "slayer:" + q.key(), Math.max(0, q.calc.observed), "chat", "slayer_session", q.classification.confidence, q.classified());
    }

    /**
     * The player's own boss nametag: a boss name ("Voidgloom Seraph IV") counts only if a "Spawned by: &lt;you&gt;" tag
     * floats right next to it – other players' bosses nearby are ignored. Reads names the client already renders.
     */
    private static void scanOwnBoss(Minecraft mc) {
        String me = mc.player.getGameProfile().name();
        List<double[]> mine = new ArrayList<>();
        List<Object[]> bosses = new ArrayList<>();
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.decoration.ArmorStand) || !e.hasCustomName() || e.distanceToSqr(mc.player) > 64 * 64) continue;
            String n = StringUtil.stripColor(e.getCustomName().getString());
            if (n.startsWith("Spawned by: ")) { if (n.substring(12).trim().equals(me)) mine.add(new double[]{e.getX(), e.getY(), e.getZ()}); continue; }
            java.util.regex.Matcher m = BOSS_TAG.matcher(n);
            if (m.find()) bosses.add(new Object[]{m.group(1), m.group(2), e.getX(), e.getY(), e.getZ()});
        }
        for (Object[] b : bosses)
            for (double[] p : mine) {
                double dx = (double) b[2] - p[0], dy = (double) b[3] - p[1], dz = (double) b[4] - p[2];
                if (dx * dx + dz * dz <= 2.5 * 2.5 && Math.abs(dy) <= 3) { slayer.ownBoss(System.currentTimeMillis(), (String) b[0], (String) b[1]); return; }
            }
    }

    private static final java.util.regex.Pattern BOSS_TAG = java.util.regex.Pattern.compile(
            "(Revenant Horror|Tarantula Broodfather|Sven Packmaster|Voidgloom Seraph|Inferno Demonlord|Riftstalker Bloodfiend) (I|II|III|IV|V)\\b");

    private static void startRun(long now) {
        if (runActive && now - runStartedAt < 30_000) return; // Mort's line right after entering
        runActive = true;
        runStartedAt = now;
        runFloor = floor;
        runFloorsSeen.clear();
        summaryFloor = null;
        if (floor != null) runFloorsSeen.add(floor);
        log("run_start", "catacombs", 0, "chat", "dungeon_start", 0.9, true);
    }

    private static void applyMenu(ParsedEvent.MenuProgress m) {
        int level = m.nextLevel() - 1;
        if (m.collectionMenu()) {
            if (Skycore.config().tracks("collections") && StatDef.byMenuName(m.name()) == null)
                Progress.collectionProgress(m.name(), level, m.current(), m.need(), m.percent());
            return;
        }
        StatDef def = StatDef.byMenuName(m.name());
        if (def == null) {
            // non-stat progress shown in menus the player opened
            if ("garden".equals(m.menu()) && m.name().equalsIgnoreCase("Garden") && level > 0) Progress.value("garden_level", level, "menu");
            else if ("bestiary".equals(m.menu()) && level >= 0 && Skycore.config().tracks("bestiary")) Progress.value("bestiary_family:" + m.name(), level, "menu");
            return;
        }
        if (def.id().equals("bestiary")) {
            Progress.observeMenu(def, level, m.current() >= 0 ? m.current() : 0, m.need());
            return;
        }
        double need = m.need() > 0 ? m.need() : XpTables.need(def.table(), level);
        if (m.current() >= 0) Progress.observeMenu(def, level, m.current(), need);
        else if (m.percent() >= 0) Progress.observeApprox(def, level, m.percent(), "menu");
    }

    // ------------------------------------------------------------------ periodic panels
    /** Called every client tick. Each source has its own cadence; nothing heavy runs every tick. */
    public static void tick(Minecraft mc) {
        watchPatterns();
        if (mc.player == null || mc.level == null) { if (onSkyBlock || runActive) reset(); return; }
        if (++healthTimer >= 20) { healthTimer = 0; updateAvailability(mc); }
        if (!enabled()) return;
        if (++sidebarTimer >= 20) { sidebarTimer = 0; guarded(Source.SIDEBAR, () -> readSidebar(mc)); }
        if (onSkyBlock && ++tabTimer >= 100) { tabTimer = 0; guarded(Source.TAB, () -> readTab(mc)); }
        if (onSkyBlock && mc.screen instanceof AbstractContainerScreen<?> cs && ++menuTimer >= 10) { menuTimer = 0; guarded(Source.MENU, () -> scanMenu(cs)); }
        if (++snapshotTimer >= 12_000) { snapshotTimer = 0; if (onSkyBlock && Progress.isIdentified()) History.snapshot(Progress.profileName(), Progress.real()); }
        finishSuspended(false);
        handleQuest(slayer.tick(System.currentTimeMillis()), "timeout");
        if (onSkyBlock && "boss".equals(slayerPhase) && slayer.active() && ++nametagTimer >= 20) { nametagTimer = 0; guarded(Source.SLAYER, () -> scanOwnBoss(mc)); }
        // honest playtime: AFK time (no movement for 3 min) is not counted
        double x = mc.player.getX(), z = mc.player.getZ();
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
        if (Math.abs(x - lastX) > 0.01 || Math.abs(z - lastZ) > 0.01 || yaw != lastYaw || pitch != lastPitch) lastInput = System.currentTimeMillis();
        lastX = x; lastZ = z; lastYaw = yaw; lastPitch = pitch;
        if (++secondTicks >= 20) {
            secondTicks = 0;
            if (onSkyBlock && System.currentTimeMillis() - lastInput < 180_000) SessionLog.addPlaySeconds(1);
        }
    }

    private static void guarded(Source s, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            Health.failure(s, e.getClass().getSimpleName());
            Skycore.LOG.warn("SKYCORE {} scan failed", s, e);
        }
    }

    /** Once per second: what can each source deliver right now? (inactivity is not an error) */
    private static void updateAvailability(Minecraft mc) {
        boolean on = enabled();
        for (Source s : Source.values()) if (s != Source.PERSISTENCE) Health.disabled(s, !on);
        Health.disabled(Source.DUNGEON, !on || !Skycore.config().tracks("dungeons"));
        Health.disabled(Source.SLAYER, !on || !Skycore.config().tracks("slayer"));
        Health.available(Source.CHAT, onSkyBlock, "health.note.not_skyblock");
        Health.available(Source.ACTION_BAR, onSkyBlock, "health.note.not_skyblock");
        Health.available(Source.TAB, onSkyBlock, "health.note.not_skyblock");
        Health.available(Source.MENU, onSkyBlock && mc.screen instanceof AbstractContainerScreen<?>, onSkyBlock ? "health.note.no_menu" : "health.note.not_skyblock");
        Health.available(Source.PROFILE, Progress.isIdentified(), onSkyBlock ? "health.note.profile_unknown" : "health.note.not_skyblock");
        Health.available(Source.DUNGEON, inDungeon(), "health.note.not_in_dungeon");
        Health.available(Source.SLAYER, slayerVisible, "health.note.no_slayer_quest");
        Health.available(Source.PERSISTENCE, true, null);
    }

    /** "Rescan now": read every currently visible source immediately. Opens nothing, clicks nothing. */
    public static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        guarded(Source.SIDEBAR, () -> readSidebar(mc));
        if (onSkyBlock) guarded(Source.TAB, () -> readTab(mc));
        menuHash = 0;
    }

    private static void readSidebar(Minecraft mc) {
        Scoreboard sb = mc.level.getScoreboard();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) { onSkyBlock = false; Health.available(Source.SIDEBAR, false, "health.note.no_sidebar"); return; }
        List<String> lines = new ArrayList<>();
        for (PlayerTeam team : sb.getPlayerTeams()) lines.add(StringUtil.stripColor(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString()));
        PanelParser.Sidebar s = PanelParser.sidebar(StringUtil.stripColor(obj.getDisplayName().getString()), lines, mc.player.getGameProfile().name());
        onSkyBlock = s.skyblock();
        if (!onSkyBlock) { floor = null; area = ""; Health.available(Source.SIDEBAR, false, "health.note.not_skyblock"); return; }
        Health.success(Source.SIDEBAR);
        String prevFloor = floor;
        floor = s.floor();
        if (s.area() != null) area = s.area();
        // the tier stays known after the boss dies (the quest line disappears before the level message arrives)
        slayerVisible = s.slayerTier() != null;
        if (s.slayerTier() != null) slayerTier = s.slayerTier();
        if (s.kuudraTier() != null) kuudraTier = s.kuudraTier();
        if (s.dungeonClass() != null) sidebarClass = s.dungeonClass();
        long now = System.currentTimeMillis();
        // reconnected into the same dungeon: resume the suspended run instead of starting a second one
        if (floor != null && prevFloor == null && suspended != null && floor.equals(suspended.floor()) && now - suspended.at() < RUN_RESUME_WINDOW
                && suspended.profileKey() != null && suspended.profileKey().equals(Progress.profileKey())) {
            runActive = true;
            runStartedAt = suspended.startedAt();
            runFloor = suspended.floor();
            runFloorsSeen.clear();
            runFloorsSeen.add(suspended.floor());
            runClass = suspended.dungeonClass();
            suspended = null;
            log("run_resumed", "catacombs", 0, "scoreboard", "reconnect", 0.9, true);
        } else if (floor != null && prevFloor == null && !runActive) {
            finishSuspended(true);
            startRun(now);
        }
        if (floor != null && runActive) { runFloor = floor; runFloorsSeen.add(floor); }
        slayerPhase = s.slayerPhase();
        if (s.slayerBoss() != null) slayer.sidebar(now, s.slayerBoss(), s.slayerTier(), s.slayerPhase());
        handleLate(slayer.takeLate());
        // dungeon lifecycle: leaving without a summary ends the run incomplete
        if (floor == null && prevFloor != null && runActive) {
            if (now - lastRunCompletedAt > 60_000 && Progress.isIdentified()) {
                Progress.recordIncompleteRun(classifyRun(), runStartedAt, currentClass());
                log("run_incomplete", "catacombs", 0, "scoreboard", "left_dungeon", 0.9, true);
            }
            runActive = false;
            runClass = null;
        }
        if (Progress.isIdentified()) for (ParsedEvent v : s.values()) apply(v, "", "scoreboard", "sidebar", 0.95, now);
        History.scanned("scoreboard");
        if (DebugLog.enabled()) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("area", area);
            if (floor != null) v.put("floor", floor);
            if (sidebarClass != null) v.put("class", sidebarClass);
            if (slayerTier != null) v.put("slayer", slayerTier + (slayerVisible ? "" : " (ended)"));
            if (kuudraTier != null) v.put("kuudra", kuudraTier);
            for (ParsedEvent e : s.values()) if (e instanceof ParsedEvent.ValueReading r) v.put(r.key(), String.valueOf((long) r.value()));
            String sig = v.toString();
            if (!sig.equals(lastSidebarSig)) {
                lastSidebarSig = sig;
                DebugLog.add(new DebugLog.Entry(now, DebugLog.Filter.SIDEBAR, "sidebar", "sidebar", 0.95, v, Progress.isIdentified() ? Progress.profileName() : null,
                        "-", Progress.isIdentified() || s.values().isEmpty(), Progress.isIdentified() || s.values().isEmpty() ? "panel_read" : "profile_unknown_skipped", "progress"));
            }
        }
    }

    private static void readTab(Minecraft mc) {
        var conn = mc.getConnection();
        if (conn == null) return;
        List<String> lines = new ArrayList<>();
        for (PlayerInfo info : conn.getListedOnlinePlayers()) {
            Component name = info.getTabListDisplayName();
            if (name != null) lines.add(StringUtil.stripColor(name.getString()));
        }
        List<ParsedEvent> events = PanelParser.tab(lines);
        if (events.isEmpty()) { Health.activity(Source.TAB); return; }
        long now = System.currentTimeMillis();
        // the profile line first, so everything else lands on the right profile
        for (ParsedEvent e : events) if (e instanceof ParsedEvent.ProfileSwitch p) identified(p.name(), "tab");
        for (ParsedEvent e : events) if (!(e instanceof ParsedEvent.ProfileSwitch)) dispatch(e, "", "tab", "tab_list", 0.9, now);
        Health.success(Source.TAB);
        History.scanned("tab");
        if (DebugLog.enabled()) {
            Map<String, String> v = new LinkedHashMap<>();
            int skills = 0, values = 0;
            boolean profile = false;
            for (ParsedEvent e : events) {
                if (e instanceof ParsedEvent.SkillLevelPercent) skills++;
                else if (e instanceof ParsedEvent.ProfileSwitch) profile = true;
                else values++;
            }
            v.put("skills", String.valueOf(skills));
            v.put("values", String.valueOf(values));
            v.put("profile_line", String.valueOf(profile));
            String sig = v.toString() + events.hashCode();
            if (!sig.equals(lastTabSig)) {
                lastTabSig = sig;
                DebugLog.add(new DebugLog.Entry(now, DebugLog.Filter.TAB, "tab_list", "tab_list", 0.9, v, Progress.isIdentified() ? Progress.profileName() : null,
                        "-", Progress.isIdentified(), Progress.isIdentified() ? "panel_read" : "profile_unknown_skipped", "progress"));
            }
        }
        if (Progress.isIdentified()) Goals.check();
    }

    private static void scanMenu(AbstractContainerScreen<?> screen) {
        List<MenuParser.Item> items = new ArrayList<>();
        int hash = screen.getTitle().getString().hashCode();
        for (Slot slot : screen.getMenu().slots) {
            ItemStack st = slot.getItem();
            if (st.isEmpty()) continue;
            ItemLore lore = st.get(DataComponents.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;
            List<String> lines = new ArrayList<>(lore.lines().size());
            for (Component c : lore.lines()) lines.add(StringUtil.stripColor(c.getString()));
            String name = StringUtil.stripColor(st.getHoverName().getString());
            hash = 31 * hash + name.hashCode() * 17 + lines.hashCode();
            items.add(new MenuParser.Item(name, lines));
        }
        if (hash == menuHash || items.isEmpty()) return; // unchanged since last scan
        menuHash = hash;
        Health.activity(Source.MENU);
        List<ParsedEvent> events = MenuParser.parse(StringUtil.stripColor(screen.getTitle().getString()), items);
        if (events.isEmpty()) return;
        if (!Progress.isIdentified()) {
            if (DebugLog.enabled()) DebugLog.add(new DebugLog.Entry(System.currentTimeMillis(), DebugLog.Filter.MENU, "menu", "menu_lore", 0.95,
                    Map.of("items", String.valueOf(events.size())), null, "-", false, "profile_unknown_skipped", "-"));
            menuHash = 0; // read again once the profile is known
            return;
        }
        long now = System.currentTimeMillis();
        for (ParsedEvent e : events) {
            apply(e, "", "menu", "menu_lore", 0.95, now);
            if (e instanceof ParsedEvent.MenuProgress m) {
                log("verify", m.name(), m.nextLevel() - 1, "menu", "menu_lore", 0.95, true);
                debug(e, "menu_lore", 0.95, "-", true, "verified_value", "progress");
            }
        }
        Health.success(Source.MENU);
        History.scanned("menu");
        Goals.check();
    }

    private static final String EXAMPLE_PATTERNS = """
            [
              {
                "id": "example_catacombs",
                "language": "en",
                "channel": "chat",
                "kind": "catacombs_xp",
                "regex": "^\\\\+(?<xp>[\\\\d.,]+) Catacombs XP$",
                "confidence": 0.7
              }
            ]
            """;
}
