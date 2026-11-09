package dev.skycore.notify;

import dev.skycore.core.Config;
import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.data.Estimate;
import dev.skycore.data.Goals;
import dev.skycore.data.ProfileData.StatProgress;
import dev.skycore.data.Progress;
import dev.skycore.data.SessionLog;
import dev.skycore.data.StatDef;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides when to notify (thresholds, level-ups, milestones, goals, personal bests) and owns the toast queue.
 * One-off alerts are keyed and remembered per profile, so the same milestone never fires twice – not even after
 * a restart.
 */
public final class Notifier implements Progress.Listener {
    public record Toast(String title, String body, Icon icon, int color, long created, float duration) {}

    public static final String[] CATEGORIES = {"level_up", "runs_away", "goals", "milestone", "best_run", "inactive"};

    private static final List<Toast> toasts = new ArrayList<>();
    private static boolean remindedThisSession, initialised;

    public static void init() {
        if (initialised) return; // never register listeners twice
        initialised = true;
        Progress.addListener(new Notifier());
        Goals.setOnComplete(g -> {
            StatDef d = StatDef.get(g.stat);
            String name = Goals.displayName(g);
            push("goals", Lang.t("notify.goal.title"), text("goals", Lang.t("notify.goal.body", name),
                    Map.of("goal", name, "stat", Lang.t(d.nameKey()), "level", String.valueOf(g.target))), Icon.TROPHY, Theme.get().success());
        });
    }

    public static List<Toast> active() {
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> now - t.created > (long) (t.duration * 1000) + 600);
        return toasts;
    }

    /** Body text: the user's template for the category if set, otherwise the default. */
    static String text(String category, String def, Map<String, String> vars) {
        String tpl = Skycore.config().notifications.templates.getOrDefault(category, "");
        if (tpl == null || tpl.isBlank()) return def;
        String out = tpl;
        for (var e : vars.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue());
        return out;
    }

    public static void push(String category, String title, String body, Icon icon, int color) {
        Config.NotificationSettings n = Skycore.config().notifications;
        if (!n.enabled || (category != null && !n.categories.getOrDefault(category, true))) return;
        int c = n.accent == null || n.accent.isEmpty() ? color : Theme.parse(n.accent);
        toasts.add(new Toast(title, body, icon, c, System.currentTimeMillis(), n.duration));
        while (toasts.size() > 4) toasts.remove(0);
        if (n.sound) playSound(n.soundType);
    }

    public static void playSound(String type) {
        var sm = Minecraft.getInstance().getSoundManager();
        switch (type) {
            case "toast" -> sm.play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1f, 1f));
            case "orb" -> sm.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f, 0.8f));
            case "none" -> {}
            default -> sm.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.5f));
        }
    }

    public static void test() {
        push(null, Lang.t("notify.test.title"), Lang.t("notify.test.body"), Icon.BELL, Theme.get().primary());
    }

    /** Preview of one category's look & text (Notifications page). */
    public static void preview(String category) {
        Map<String, String> v = Map.of("stat", Lang.t("stat.catacombs"), "level", "24", "n", "5", "unit", Lang.t("unit.runs"), "goal", Lang.t("goals.reach", Lang.t("stat.catacombs"), 30));
        switch (category) {
            case "level_up" -> push(null, Lang.t("notify.level.title"), text(category, Lang.t("notify.level.body", v.get("stat"), 24), v), Icon.DUNGEONS, Theme.get().secondary());
            case "runs_away" -> push(null, Lang.t("notify.runs.title", v.get("stat"), 24), text(category, Lang.t("notify.runs.body", "5", v.get("unit")), v), Icon.DUNGEONS, Theme.get().primary());
            case "goals" -> push(null, Lang.t("notify.goal.title"), text(category, Lang.t("notify.goal.body", v.get("goal")), v), Icon.TROPHY, Theme.get().success());
            case "milestone" -> push(null, Lang.t("notify.milestone.title"), text(category, Lang.t("notify.milestone.body", Lang.t("stat.bestiary"), 25), v), Icon.BESTIARY, Theme.get().warning());
            case "best_run" -> push(null, Lang.t("notify.best.title"), text(category, Lang.t("notify.best.body", "12,400"), v), Icon.FLAME, Theme.get().warning());
            default -> push(null, Lang.t("notify.inactive.title", Lang.t("group.mining")), text(category, Lang.t("notify.inactive.body", Lang.t("group.mining"), "8d"), v), Icon.WARNING, Theme.get().warning());
        }
    }

    @Override
    public void onGain(StatDef def, StatProgress p, double amount) {
        if (def.unit() != StatDef.Unit.RUNS && def.unit() != StatDef.Unit.BOSSES) return;
        if (def.isClass()) return; // class XP shares the run; only the main Catacombs estimate alerts
        Estimate.Range r = Estimate.forNextLevel(def, p);
        if (r == null || !p.known()) return;
        List<Integer> th = new ArrayList<>(Skycore.config().notifications.runThresholds);
        th.sort((a, b) -> b - a);
        // the smallest threshold reached (e.g. 4 runs left with thresholds 10/5/1 → "5")
        Integer hit = null;
        for (int t : th) if (r.mid() <= t) hit = t;
        if (hit == null) return;
        if (!Progress.markNotified(def.id() + "@" + p.level + "#" + hit)) return;
        String unit = Lang.t(def.unit() == StatDef.Unit.RUNS ? (r.mid() == 1 ? "unit.run" : "unit.runs") : (r.mid() == 1 ? "unit.boss" : "unit.bosses"));
        String stat = Lang.t(def.nameKey());
        push("runs_away", Lang.t("notify.runs.title", stat, p.level + 1),
                text("runs_away", Lang.t("notify.runs.body", r.format(), unit),
                        Map.of("stat", stat, "level", String.valueOf(p.level + 1), "n", r.format(), "unit", unit)), def.icon(), Theme.get().primary());
    }

    @Override
    public void onLevelUp(StatDef def, StatProgress p, int newLevel) {
        if (!Progress.markNotified("lvl:" + def.id() + "@" + newLevel)) return;
        String stat = Lang.t(def.nameKey());
        push("level_up", Lang.t("notify.level.title"), text("level_up", Lang.t("notify.level.body", stat, newLevel),
                Map.of("stat", stat, "level", String.valueOf(newLevel))), def.icon(), Theme.get().secondary());
    }

    @Override
    public void onMilestone(String what, int level) {
        if (!Progress.markNotified("ms:" + what + "@" + level)) return;
        String name = what.startsWith("collection:") ? what.substring(11) : Lang.t("stat." + what);
        push("milestone", Lang.t("notify.milestone.title"), text("milestone", Lang.t("notify.milestone.body", name, level),
                Map.of("stat", name, "level", String.valueOf(level))), Icon.TROPHY, Theme.get().warning());
    }

    @Override
    public void onBestRun(double xp) {
        push("best_run", Lang.t("notify.best.title"), text("best_run", Lang.t("notify.best.body", Lang.num(xp)),
                Map.of("n", Lang.num(xp), "stat", Lang.t("stat.catacombs"))), Icon.FLAME, Theme.get().warning());
    }

    /** Once per game session: remind about tracked categories that have been idle for a week. */
    public static void inactivityReminder() {
        if (remindedThisSession || Progress.isSample()) return;
        remindedThisSession = true;
        long week = 7L * 24 * 3600_000L;
        for (String g : StatDef.GROUPS) {
            long last = SessionLog.lastActivity(g);
            if (last > 0 && System.currentTimeMillis() - last > week) {
                String grp = Lang.t("group." + g), ago = Lang.duration(System.currentTimeMillis() - last);
                push("inactive", Lang.t("notify.inactive.title", grp), text("inactive", Lang.t("notify.inactive.body", grp, ago),
                        Map.of("stat", grp, "n", ago)), Icon.WARNING, Theme.get().warning());
                return;
            }
        }
    }
}
