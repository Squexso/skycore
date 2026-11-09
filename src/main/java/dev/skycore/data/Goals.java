package dev.skycore.data;

import com.google.gson.reflect.TypeToken;
import dev.skycore.core.Storage;
import dev.skycore.data.ProfileData.StatProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** User-defined progression goals. Progress is derived live from {@link Progress}, never stored. */
public final class Goals {
    public static class Goal {
        public String id = UUID.randomUUID().toString();
        /** Optional custom name; empty = "Reach <stat> <target>". */
        public String name = "";
        public String stat;
        public int target;
        public boolean paused;
        public long created = System.currentTimeMillis();
        public long completed;
    }

    private static List<Goal> goals = new ArrayList<>();
    private static boolean dirty;
    private static Consumer<Goal> onComplete = g -> {};
    private static final List<Consumer<Goal>> extraListeners = new ArrayList<>();

    private Goals() {}

    public static void load() {
        goals = Storage.load("goals.json", new TypeToken<List<Goal>>() {}.getType(), ArrayList::new);
        goals.removeIf(g -> g.stat == null || StatDef.get(g.stat) == null);
    }

    public static void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        Storage.save("goals.json", goals);
    }

    /** Real goals (never sample data) – for merges. */
    public static List<Goal> raw() { return goals; }

    public static void markDirty() { dirty = true; }

    public static void setOnComplete(Consumer<Goal> c) { onComplete = c; }

    /** Additional completion listener (does not replace the notification one). */
    public static void addOnComplete(Consumer<Goal> c) { if (!extraListeners.contains(c)) extraListeners.add(c); }

    public static List<Goal> all() {
        if (Progress.isSample()) return SampleData.goals();
        return goals;
    }

    public static long activeCount() { return all().stream().filter(g -> !g.paused && g.completed == 0).count(); }

    public static String displayName(Goal g) {
        if (g.name != null && !g.name.isBlank()) return g.name;
        StatDef d = StatDef.get(g.stat);
        return dev.skycore.core.Lang.t("goals.reach", d == null ? g.stat : dev.skycore.core.Lang.t(d.nameKey()), g.target);
    }

    public static StatDef def(Goal g) { return StatDef.get(g.stat); }

    public static Goal create(String stat, int target, String name) {
        Goal g = new Goal();
        g.stat = stat;
        g.target = target;
        g.name = name == null ? "" : name.trim();
        goals.add(g);
        dirty = true;
        return g;
    }

    public static void delete(Goal g) { goals.remove(g); dirty = true; }

    public static void changed() { dirty = true; }

    public static Object snapshot() { return goals; }

    public static void restore(com.google.gson.JsonElement json) {
        List<Goal> l = Storage.GSON.fromJson(json, new TypeToken<List<Goal>>() {}.getType());
        if (l == null) return;
        l.removeIf(g -> g == null || g.stat == null || StatDef.get(g.stat) == null);
        for (Goal g : l) if (g.name == null) g.name = "";
        goals = l;
        dirty = true;
    }

    public static void clear() { goals = new ArrayList<>(); dirty = true; }

    /** Current fractional level shown as "57.4 / 60". */
    public static double current(Goal g) {
        StatProgress p = Progress.view().peek(g.stat);
        return p.fractionalLevel();
    }

    public static float fraction(Goal g) {
        double c = current(g);
        if (c < 0 || g.target <= 0) return 0;
        return (float) Math.max(0, Math.min(1, c / g.target));
    }

    /** XP still needed to reach the target (or -1 if unknown). */
    public static double xpRemaining(Goal g) {
        StatDef def = StatDef.get(g.stat);
        StatProgress p = Progress.view().peek(g.stat);
        if (def == null || def.table() == null || !p.known()) return -1;
        return XpTables.xpTo(def.table(), p.level, p.xp, g.target);
    }

    /** Called after progression changes; marks goals reached. */
    public static void check() {
        for (Goal g : goals) {
            if (g.completed != 0 || g.paused) continue;
            StatProgress p = Progress.real().peek(g.stat);
            if (p.known() && p.level >= g.target) {
                g.completed = System.currentTimeMillis();
                dirty = true;
                onComplete.accept(g);
                for (Consumer<Goal> l : extraListeners) {
                    try { l.accept(g); } catch (RuntimeException e) { dev.skycore.core.Skycore.LOG.warn("Goal listener failed", e); }
                }
            }
        }
    }
}
