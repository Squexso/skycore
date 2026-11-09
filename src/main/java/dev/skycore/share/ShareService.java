package dev.skycore.share;

import com.google.gson.reflect.TypeToken;
import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;
import dev.skycore.data.DataStatus;
import dev.skycore.data.Estimate;
import dev.skycore.data.Goals;
import dev.skycore.data.ProfileData.StatProgress;
import dev.skycore.data.Progress;
import dev.skycore.data.StatDef;
import dev.skycore.notify.Notifier;
import dev.skycore.share.Template.Value;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Prepares milestone messages from SKYCORE's own verified data. Passive: it only reads what the tracker already
 * recorded and what the game printed to the player's own chat. It never sends a message, never presses keys and never
 * writes the clipboard on its own (unless the player enabled auto-copy).
 */
public final class ShareService implements Progress.Listener {
    private static ShareState state = new ShareState();
    private static Inbox inbox = new Inbox(state, s -> { });
    private static boolean dirty, initialised;
    private static final int[] LAST = {-1, -1, -1, -1, -1};
    private static final Random RND = new Random();
    private static int tickCounter;

    private ShareService() {}

    public static void init() {
        if (initialised) return;
        initialised = true;
        state = Storage.load("share.json", new TypeToken<ShareState>() {}.getType(), ShareState::new);
        state.normalize();
        inbox = new Inbox(state, s -> Minecraft.getInstance().keyboardHandler.setClipboard(s));
        Progress.addListener(new ShareService());
        Goals.addOnComplete(ShareService::onGoalComplete);
    }

    public static ShareSettings settings() { return Skycore.config().share; }

    /** Progress-update colour: a muted cyan. Themes that already have a cyan primary keep theirs. */
    public static int cyan(Theme t) { return t.restrained ? 0xFF5BB5C9 : t.primary(); }

    public static Inbox inbox() { return inbox; }

    public static ShareState state() { return state; }

    public static void markDirty() { dirty = true; }

    public static void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        Storage.save("share.json", state);
    }

    // ------------------------------------------------------------------ templates
    /** The templates of a kind: the player's own variants, otherwise the defaults of the current language. */
    public static List<String> variants(Kind kind) {
        List<String> own = settings().templates.get(kind.id());
        if (own != null) {
            List<String> out = new ArrayList<>();
            for (String s : own) if (s != null && !s.isBlank()) out.add(s);
            if (!out.isEmpty()) return out;
        }
        return defaults(kind);
    }

    public static List<String> defaults(Kind kind) {
        List<String> out = new ArrayList<>();
        for (int n = 1; Lang.exists("share.tpl." + kind.id() + "." + n); n++) out.add(Lang.t("share.tpl." + kind.id() + "." + n));
        return out;
    }

    // ------------------------------------------------------------------ helpers
    private static boolean ready() {
        return initialised && settings().enabled && Skycore.config().trackingEnabled && Progress.isIdentified();
    }

    private static String profile() { return Progress.profileKey(); }

    /** The level of a stat is exact when the game printed it; a derived or hand-entered level is only tracked or estimated. */
    static DataStatus levelStatus(StatProgress p) {
        if (p.level < 0) return DataStatus.UNAVAILABLE;
        if (p.xpUnknown) return "manual".equals(p.source) || p.source == null ? DataStatus.MANUAL : DataStatus.VERIFIED;
        return p.status();
    }

    private static int totalRuns() {
        int sum = 0;
        for (int v : Progress.real().floorCompletions.values()) sum += v;
        return sum;
    }

    /** Runs counted by SKYCORE only cover the time it was running, so they are an estimate unless a dungeon menu was read. */
    private static DataStatus runsStatus() {
        return Progress.real().menuCounts.containsKey("dungeon") ? DataStatus.EVENT_TRACKED : DataStatus.ESTIMATED;
    }

    private static String player() {
        try {
            var u = Minecraft.getInstance().getUser();
            return u == null ? null : u.getName();
        } catch (RuntimeException e) { return null; }
    }

    private static String catName(String cat) {
        String base = switch (cat) {
            case "slayer_major" -> "slayer";
            case "hotm_major" -> "hotm";
            case "goal_checkpoint", "goal_completed" -> "goal";
            default -> cat;
        };
        return Lang.t("share.catname." + base);
    }

    private static String levels(int n) { return Lang.num(n) + " " + Lang.t(n == 1 ? "share.unit.level" : "share.unit.levels"); }

    private static String runs(int n) { return Lang.num(n) + " " + Lang.t(n == 1 ? "unit.run" : "unit.runs"); }

    private static Map<String, Value> baseVars(String cat) {
        Map<String, Value> v = new LinkedHashMap<>();
        String p = player();
        if (p != null && !p.isBlank()) v.put("player", Value.of(p));
        v.put("category", Value.of(catName(cat)));
        return v;
    }

    private static void put(Map<String, Value> v, String key, String text, DataStatus st) {
        if (text != null) v.put(key, new Value(text, st));
    }

    /** {runs_total} and {runs_since}; since = runs recorded after the previous milestone of this category. */
    private static void runVars(Map<String, Value> v, String cat) {
        int total = totalRuns();
        if (total <= 0) return;
        DataStatus st = runsStatus();
        put(v, "runs_total", Lang.num(total), st);
        Integer at = state.runsAtMilestone.get(profile() + "|" + cat);
        if (at != null && total >= at) put(v, "runs_since", Lang.num(total - at), st);
    }

    private static void rememberRuns(String cat) {
        int total = totalRuns();
        if (total > 0) state.runsAtMilestone.put(profile() + "|" + cat, total);
    }

    // ------------------------------------------------------------------ preparing a message
    /** Build and store a message; shows a small toast. Returns null if the category is off or no text could be made. */
    static Prepared emit(String cat, Kind kind, String stat, String title, Map<String, Value> vars, Icon icon, int color) {
        ShareSettings s = settings();
        if (!Routing.enabled(s, cat)) return null;
        Destination dest = Routing.resolve(s, cat, stat);
        MessageBuilder.Built b = MessageBuilder.build(s, cat, dest, variants(kind), LAST[kind.ordinal()], vars, RND);
        if (b == null) return null;
        LAST[kind.ordinal()] = b.variant();
        Prepared p = new Prepared();
        p.category = cat;
        p.kind = kind.id();
        p.stat = stat == null ? "" : stat;
        p.title = title;
        p.text = b.text();
        p.destination = dest.id();
        p.estimated = b.estimated();
        inbox.add(s, p);
        dirty = true;
        toast(p, icon, color);
        return p;
    }

    private static void toast(Prepared p, Icon icon, int color) {
        if (!settings().showToast) return;
        Notifier.push(null, Lang.t("share.toast.title", p.title), Lang.t(settings().autoCopy ? "share.toast.copied" : "share.toast.hint"), icon, color);
    }

    // ------------------------------------------------------------------ events from the tracker
    @Override
    public void onGain(StatDef def, StatProgress p, double amount) {
        if (!ready() || !settings().nearlyReached) return;
        String id = def.id();
        boolean cata = id.equals("catacombs");
        if (!cata && !id.startsWith("slayer_")) return;
        if (!p.known() || p.need <= 0) return;
        ShareSettings s = settings();
        int next = p.level + 1;
        if (!(cata ? s.catacombsLevels : s.slayerLevels).contains(next)) return;
        Estimate.Range r = Estimate.forNextLevel(def, p);
        if (r == null) return;
        List<Integer> th = new ArrayList<>(Skycore.config().notifications.runThresholds);
        th.sort((a, b) -> b - a);
        Integer hit = null;
        for (int t : th) if (r.mid() <= t) hit = t;
        if (hit == null || !state.fire("near|" + profile() + "|" + id + "@" + next + "#" + hit)) return;
        boolean major = !cata && Milestones.isMajorSlayer(s, next, def.maxLevel());
        String cat = cata ? "catacombs" : major ? "slayer_major" : "slayer";
        String name = Lang.t(def.nameKey()) + " " + next;
        Map<String, Value> v = baseVars(cat);
        put(v, "milestone", name, DataStatus.VERIFIED);
        put(v, "current_level", String.valueOf(p.level), levelStatus(p));
        put(v, "remaining", runsOrBosses(def, (int) r.mid()), DataStatus.ESTIMATED); // a range of past gains is always an estimate
        put(v, "next_goal", name, DataStatus.VERIFIED);
        if (cata) runVars(v, cat);
        emit(cat, Kind.NEARLY, id, name, v, def.icon(), cyan(Theme.get()));
    }

    private static String runsOrBosses(StatDef def, int n) {
        return Lang.num(n) + " " + Lang.t(def.unit() == StatDef.Unit.RUNS ? (n == 1 ? "unit.run" : "unit.runs") : (n == 1 ? "unit.boss" : "unit.bosses"));
    }

    @Override
    public void onLevelUp(StatDef def, StatProgress p, int level) {
        if (!ready()) return;
        ShareSettings s = settings();
        String id = def.id(), cat;
        boolean major;
        List<Integer> list = null;
        if (id.equals("catacombs")) {
            if (!s.catacombsLevels.contains(level)) return;
            cat = "catacombs"; major = level == def.maxLevel(); list = s.catacombsLevels;
        } else if (id.startsWith("slayer_")) {
            if (!s.slayerLevels.contains(level)) return;
            major = Milestones.isMajorSlayer(s, level, def.maxLevel());
            cat = major ? "slayer_major" : "slayer"; list = s.slayerLevels;
        } else if (id.equals("hotm")) {
            if (!s.hotmTiers.contains(level)) return;
            major = level == def.maxLevel();
            cat = major ? "hotm_major" : "hotm"; list = s.hotmTiers;
        } else if (id.startsWith("skill_")) {
            if (!s.skillMax || level != def.maxLevel()) return; // only the real maximum of a skill
            cat = "skill_max"; major = true;
        } else return;
        if (!state.fire("lvl|" + profile() + "|" + id + "|" + level)) return;
        String name = Lang.t(def.nameKey()) + " " + level;
        DataStatus st = levelStatus(p);
        Map<String, Value> v = baseVars(cat);
        put(v, "milestone", name, st);
        put(v, "previous_level", String.valueOf(level - 1), st);
        put(v, "current_level", String.valueOf(level), st);
        if (id.equals("catacombs")) runVars(v, cat);
        Integer nxt = list == null ? null : Milestones.next(list, level);
        if (nxt != null) {
            put(v, "next_goal", Lang.t(def.nameKey()) + " " + nxt, DataStatus.VERIFIED);
            put(v, "remaining", levels(nxt - level), st);
        }
        emit(cat, major ? Kind.MAJOR : Kind.COMPLETED, id, name, v, def.icon(), Theme.get().progress());
        if (id.equals("catacombs")) rememberRuns(cat);
    }

    @Override
    public void onMilestone(String what, int level) {
        if (!ready() || !what.startsWith("collection:")) return;
        ShareSettings s = settings();
        if (level < s.collectionMinLevel) return;
        String name = what.substring(11);
        if (!state.fire("col|" + profile() + "|" + name + "|" + level)) return;
        Map<String, Value> v = baseVars("collection");
        put(v, "milestone", name + " " + level, DataStatus.VERIFIED);
        put(v, "previous_level", String.valueOf(level - 1), DataStatus.VERIFIED);
        put(v, "current_level", String.valueOf(level), DataStatus.VERIFIED);
        emit("collection", Kind.COMPLETED, "", name + " " + level, v, Icon.COLLECTIONS, Theme.get().progress());
    }

    private static void onGoalComplete(Goals.Goal g) {
        if (!ready() || !Milestones.completion(state, Milestones.goalKey(profile(), g.id, g.created))) return;
        StatDef def = StatDef.get(g.stat);
        String name = Goals.displayName(g);
        Map<String, Value> v = baseVars("goal_completed");
        put(v, "milestone", name, DataStatus.VERIFIED);
        put(v, "current_level", String.valueOf(g.target), DataStatus.VERIFIED);
        if (def != null && def.id().equals("catacombs")) runVars(v, "goal_completed");
        emit("goal_completed", Kind.COMPLETED, g.stat, name, v, Icon.TROPHY, Theme.get().success());
        dirty = true;
    }

    // ------------------------------------------------------------------ periodic checks (goal checkpoints, run milestones)
    public static void tick() {
        if (++tickCounter < 20 || !ready()) return;
        tickCounter = 0;
        int before = state.fired.size();
        try {
            runMilestones();
            goalCheckpoints();
        } catch (RuntimeException e) {
            dev.skycore.ui.Ui.logFault("share tick", e);
        }
        if (state.fired.size() != before) dirty = true;
    }

    private static void runMilestones() {
        ShareSettings s = settings();
        int total = totalRuns();
        if (total <= 0) return;
        String prof = profile();
        Integer prev = state.runsTotal.get(prof);
        if (prev != null && prev == total) return;
        state.runsTotal.put(prof, total);
        dirty = true;
        if (prev == null || total < prev) return; // first sight, or a correction downwards: nothing to announce
        Integer m = Milestones.crossed(s.runMilestones, prev, total);
        if (m == null || !state.fire("runs|" + prof + "|" + m)) return;
        boolean major = m >= s.majorRunMilestone;
        String name = Lang.t("share.runs_milestone", Lang.num(m));
        Map<String, Value> v = baseVars("runs");
        DataStatus st = runsStatus();
        put(v, "milestone", name, st);
        put(v, "runs_total", Lang.num(total), st);
        Integer at = state.runsAtMilestone.get(prof + "|runs");
        if (at != null && total >= at) put(v, "runs_since", Lang.num(total - at), st);
        Integer nxt = Milestones.next(s.runMilestones, total);
        if (nxt != null) {
            put(v, "next_goal", Lang.t("share.runs_milestone", Lang.num(nxt)), DataStatus.VERIFIED);
            put(v, "remaining", runs(nxt - total), st);
        }
        emit("runs", major ? Kind.MAJOR : Kind.COMPLETED, "catacombs", name, v, Icon.DUNGEONS, Theme.get().progress());
        rememberRuns("runs");
    }

    private static void goalCheckpoints() {
        ShareSettings s = settings();
        String prof = profile();
        for (Goals.Goal g : new ArrayList<>(Goals.raw())) {
            if (g.paused || g.completed != 0) continue;
            StatProgress p = Progress.real().peek(g.stat);
            if (!p.known() || g.target <= 0) continue;
            double frac = Math.max(0, Math.min(1, p.fractionalLevel() / g.target));
            for (Milestones.Checkpoint c : Milestones.checkpoints(s, state, Milestones.goalKey(prof, g.id, g.created), g.target, p.level, frac)) {
                String name = Goals.displayName(g);
                DataStatus st = levelStatus(p);
                Map<String, Value> v = baseVars("goal_checkpoint");
                put(v, "milestone", name, DataStatus.VERIFIED);
                put(v, "current_level", String.valueOf(p.level), st);
                put(v, "remaining", levels(g.target - p.level), st);
                put(v, "next_goal", name, DataStatus.VERIFIED);
                emit("goal_checkpoint", Kind.CHECKPOINT, g.stat, name, v, Icon.GOALS, cyan(Theme.get()));
            }
        }
    }

    // ------------------------------------------------------------------ manual progress report (button)
    /** A report of one stat from verified data. Null if the level is unknown (nothing is invented). */
    public static Prepared report(String statId) {
        StatDef def = StatDef.get(statId);
        if (def == null || !initialised) return null;
        StatProgress p = Progress.real().peek(statId);
        if (!p.known()) return null;
        String cat = statId.equals("catacombs") ? "catacombs" : statId.startsWith("slayer_") ? "slayer" : statId.equals("hotm") ? "hotm" : "skill_max";
        DataStatus st = levelStatus(p);
        String name = Lang.t(def.nameKey()) + " " + p.level;
        Map<String, Value> v = baseVars(cat);
        put(v, "milestone", name, st);
        put(v, "current_level", String.valueOf(p.level), st);
        if (p.need > 0 && p.status() != DataStatus.UNAVAILABLE) {
            DataStatus xs = p.xpUnknown ? DataStatus.ESTIMATED : p.status();
            put(v, "remaining", Lang.compact(p.remaining()) + " XP", xs);
        }
        if (statId.equals("catacombs")) runVars(v, cat);
        Prepared out = emitAlways(cat, Kind.REPORT, statId, name, v, def.icon());
        return out;
    }

    /** Like {@link #emit} but not blocked by the category switch: the player asked for this message explicitly. */
    private static Prepared emitAlways(String cat, Kind kind, String stat, String title, Map<String, Value> vars, Icon icon) {
        ShareSettings s = settings();
        Destination dest = Routing.resolve(s, cat, stat);
        MessageBuilder.Built b = MessageBuilder.build(s, cat, dest, variants(kind), LAST[kind.ordinal()], vars, RND);
        if (b == null) return null;
        LAST[kind.ordinal()] = b.variant();
        Prepared p = new Prepared();
        p.category = cat;
        p.kind = kind.id();
        p.stat = stat;
        p.title = title;
        p.text = b.text();
        p.destination = dest.id();
        p.estimated = b.estimated();
        state.add(p);
        dirty = true;
        return p;
    }

    // ------------------------------------------------------------------ community request ("!skycore" in party chat)
    public static void onGameMessage(Component message, boolean overlay) {
        if (overlay || !initialised) return;
        ShareSettings s = settings();
        if (!s.community.enabled) return; // cheap exit for everyone who does not use the feature
        String plain = StringUtil.stripColor(message.getString());
        Prepared p = Community.handle(s, state, plain, player(), System.currentTimeMillis(), Lang.t("share.community.default"));
        if (p == null) return;
        state.add(p);
        dirty = true;
        if (s.autoCopy) inbox.copy(p, Destination.PARTY);
        if (s.community.autoSend && !p.text.isBlank() && !p.text.startsWith("/") && !p.text.equalsIgnoreCase(Community.TRIGGER)) {
            var mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.connection != null) {
                String text = p.text.length() > 256 ? p.text.substring(0, 256) : p.text;
                mc.player.connection.sendCommand("pc " + text);
                if (s.showToast) Notifier.push(null, Lang.t("share.community.sent", p.title), text, Icon.CHECK, cyan(Theme.get()));
                return;
            }
        }
        if (s.showToast) Notifier.push(null, Lang.t("share.community.toast", p.title), Lang.t(s.autoCopy ? "share.toast.copied" : "share.toast.hint"), Icon.GLOBE, cyan(Theme.get()));
    }

    // ------------------------------------------------------------------ copy action (button or key)
    /** Copy the newest prepared message for its destination. A deliberate action of the player. */
    public static boolean copyLatest() {
        Prepared p = inbox.latest();
        if (p == null) {
            Notifier.push(null, Lang.t("share.copy.none"), Lang.t("share.copy.none_body"), Icon.INFO, Theme.get().textDim());
            return false;
        }
        Destination d = p.destination() == Destination.MANUAL ? Destination.CLIPBOARD : p.destination();
        inbox.copy(p, d);
        Notifier.push(null, Lang.t("share.copy.done"), p.clipboardText(d), Icon.CHECK, Theme.get().success());
        return true;
    }
}
