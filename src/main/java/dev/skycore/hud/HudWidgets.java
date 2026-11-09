package dev.skycore.hud;

import dev.skycore.core.Lang;
import dev.skycore.data.Estimate;
import dev.skycore.data.Goals;
import dev.skycore.data.ProfileData;
import dev.skycore.data.ProfileData.StatProgress;
import dev.skycore.data.Progress;
import dev.skycore.data.SessionLog;
import dev.skycore.data.StatDef;
import dev.skycore.ui.Rect;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Builds and draws the in-game HUD widgets. Content comes only from tracked data (or labelled samples in preview). */
public final class HudWidgets {
    private HudWidgets() {}

    public record Row(String label, String value, int color, float bar) {
        Row(String label, String value) { this(label, value, 0, -1); }
    }

    public record Content(String title, Icon icon, float bar, String barLabel, List<Row> rows) {}

    public static String nameKey(String id) { return "hud.widget." + id; }

    public static Icon icon(String id) {
        return switch (id) {
            case "catacombs" -> Icon.DUNGEONS;
            case "skill" -> Icon.SKILLS;
            case "slayer" -> Icon.SLAYER;
            case "session" -> Icon.CLOCK;
            default -> Icon.GOALS;
        };
    }

    /** Configurable info lines per widget (keys for WidgetState.lines). */
    public static String[] lineKeys(String id) {
        return switch (id) {
            case "catacombs" -> new String[]{"remaining", "runs", "last"};
            case "skill", "slayer" -> new String[]{"remaining", "rate", "eta"};
            case "session" -> new String[]{"time", "xp", "rate"};
            default -> new String[]{"bars"};
        };
    }

    public static Content build(String id) {
        ProfileData d = Progress.view();
        Theme t = Theme.get();
        List<Row> rows = new ArrayList<>();
        WidgetLines lines = new WidgetLines(HudStore.widget(id));
        switch (id) {
            case "catacombs" -> {
                StatDef def = StatDef.get("catacombs");
                StatProgress p = d.peek("catacombs");
                if (!p.known()) {
                    rows.add(new Row(Lang.t("hud.status"), Lang.t("hud.not_calibrated"), t.textDim(), -1));
                    return new Content(Lang.t(def.nameKey()), Icon.DUNGEONS, -1, null, rows);
                }
                Estimate.Range r = Estimate.forNextLevel(def, p);
                if (lines.on("remaining") && p.need > 0) rows.add(new Row(Lang.t("hud.xp_left"), Lang.compact(p.remaining())));
                if (lines.on("runs")) rows.add(new Row(Lang.t("hud.est_runs"), r == null ? "—" : r.format()));
                if (lines.on("last") && !p.gains.isEmpty()) rows.add(new Row(Lang.t("hud.last_run"), "+" + Lang.compact(p.lastGain()), t.success(), -1));
                return new Content(Lang.t(def.nameKey()) + " " + p.level, Icon.DUNGEONS, p.need > 0 ? p.frac() : 1, Lang.percent(p.frac()), rows);
            }
            case "skill", "slayer" -> {
                String prefix = id.equals("skill") ? "skill_" : "slayer_";
                StatDef best = null;
                StatProgress bp = null;
                for (StatDef def : StatDef.ALL.values()) {
                    if (!def.id().startsWith(prefix)) continue;
                    StatProgress p = d.peek(def.id());
                    if (p.updated > 0 && (bp == null || p.updated > bp.updated)) { best = def; bp = p; }
                }
                if (best == null) {
                    rows.add(new Row(Lang.t("hud.status"), Lang.t(id.equals("skill") ? "hud.no_skill" : "hud.no_slayer"), t.textDim(), -1));
                    return new Content(Lang.t(nameKey(id)), icon(id), -1, null, rows);
                }
                if (lines.on("remaining") && bp.need > 0) rows.add(new Row(Lang.t("hud.xp_left"), Lang.compact(bp.remaining())));
                if (lines.on("rate")) {
                    if (id.equals("skill")) {
                        SessionLog.Totals tt = SessionLog.totals(SessionLog.range(SessionLog.Period.TODAY));
                        double xp = tt.byStat().getOrDefault(best.id(), 0d);
                        double h = tt.playMillis() / 3600_000.0;
                        rows.add(new Row(Lang.t("hud.xp_hour"), h > 0.05 ? Lang.compact(xp / h) : "—"));
                    } else if (!bp.gains.isEmpty()) {
                        rows.add(new Row(Lang.t("hud.last_boss"), "+" + Lang.compact(bp.lastGain()), t.success(), -1));
                    }
                }
                if (lines.on("eta")) {
                    Estimate.Range r = Estimate.forNextLevel(best, bp);
                    rows.add(new Row(Lang.t(id.equals("skill") ? "hud.eta" : "hud.est_bosses"), r == null ? "—" : r.format()));
                }
                String title = Lang.t(best.nameKey()) + (bp.known() ? " " + bp.level : "");
                return new Content(title, best.icon(), bp.need > 0 || bp.percent >= 0 ? bp.frac() : -1, Lang.percent(bp.frac()), rows);
            }
            case "session" -> {
                SessionLog.Totals tt = SessionLog.totals(SessionLog.range(SessionLog.Period.TODAY));
                double h = tt.playMillis() / 3600_000.0;
                if (lines.on("time")) rows.add(new Row(Lang.t("hud.playtime"), Lang.duration(tt.playMillis())));
                if (lines.on("xp")) rows.add(new Row(Lang.t("hud.xp_today"), Lang.compact(tt.xp())));
                if (lines.on("rate")) rows.add(new Row(Lang.t("hud.xp_hour"), h > 0.05 ? Lang.compact(tt.xp() / h) : "—"));
                return new Content(Lang.t("hud.widget.session"), Icon.CLOCK, -1, null, rows);
            }
            default -> {
                int n = 0;
                for (Goals.Goal g : Goals.all()) {
                    if (g.paused || g.completed != 0 || n >= 3) continue;
                    StatDef def = StatDef.get(g.stat);
                    rows.add(new Row(Lang.t(def.nameKey()) + " " + g.target, Lang.percent(Goals.fraction(g)), 0, lines.on("bars") ? Goals.fraction(g) : -1));
                    n++;
                }
                if (rows.isEmpty()) rows.add(new Row(Lang.t("hud.no_goals"), "", t.textDim(), -1));
                return new Content(Lang.t("hud.widget.goals"), Icon.GOALS, -1, null, rows);
            }
        }
    }

    private record WidgetLines(HudStore.WidgetState w) {
        boolean on(String k) { return w == null || w.line(k); }
    }

    // ------------------------------------------------------------------ caching & visibility
    private record Cached(Content content, int version, long time, boolean sample, String lang) {}

    private static final java.util.Map<String, Cached> CACHE = new java.util.HashMap<>();

    /**
     * Content is rebuilt only when tracked data changed (Progress version), the language/sample mode changed,
     * or once per second for time-based rows (playtime, XP/h) – not every frame.
     */
    public static Content cached(String id) {
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(id);
        boolean sample = Progress.isSample();
        String lang = Lang.code();
        if (c == null || c.version != Progress.version() || now - c.time > 1000 || c.sample != sample || !c.lang.equals(lang)) {
            c = new Cached(build(id), Progress.version(), now, sample, lang);
            CACHE.put(id, c);
        }
        return c.content;
    }

    /** A skill counts as "in use" while its XP was updated within this time. */
    private static final long SKILL_ACTIVE_MS = 120_000;

    /** True while the activity a widget belongs to is going on (dungeon run, slayer quest, recent skill XP). */
    public static boolean activityNow(String id) {
        if (Progress.isSample()) return true;
        return switch (id) {
            case "catacombs" -> dev.skycore.tracking.HypixelTracker.inDungeon() || dev.skycore.tracking.HypixelTracker.runActive();
            case "slayer" -> dev.skycore.tracking.HypixelTracker.slayerActive();
            case "skill" -> {
                ProfileData d = Progress.view();
                long now = System.currentTimeMillis();
                for (StatDef def : StatDef.ALL.values()) {
                    if (!def.id().startsWith("skill_")) continue;
                    var gains = d.peek(def.id()).gains; // only real XP gains count, not level re-reads from menus
                    if (!gains.isEmpty() && now - gains.get(gains.size() - 1).t < SKILL_ACTIVE_MS) yield true;
                }
                yield false;
            }
            default -> true;
        };
    }

    /** Widget visibility rule ("auto", "always", "dungeon", "mining"). The editor always shows every widget. */
    public static boolean visibleNow(HudStore.WidgetState w) {
        return switch (w.visibility == null ? "always" : w.visibility) {
            case "auto" -> activityNow(w.id);
            case "dungeon" -> dev.skycore.tracking.HypixelTracker.inDungeon();
            case "mining" -> dev.skycore.tracking.HypixelTracker.inMiningArea();
            default -> true;
        };
    }

    private static String title(HudStore.WidgetState st, Content c) {
        return st.title != null && !st.title.isBlank() ? st.title : c.title();
    }

    // ------------------------------------------------------------------ layout & drawing (units = GUI pixels)
    public static Rect measure(HudStore.WidgetState st, Content c) {
        float pad = st.padding, ts = st.textScale <= 0 ? 1 : st.textScale;
        float w = 0;
        if (st.showTitle) w = Gfx.measure(title(st, c), true) * ts + (st.showIcon ? 13 * ts : 0) + (c.barLabel() != null ? Gfx.measure(c.barLabel(), false) * 0.8f * ts + 10 : 0);
        for (Row r : c.rows()) w = Math.max(w, (Gfx.measure(r.label(), false) + Gfx.measure(r.value(), false)) * ts + 14);
        w = Math.max(96, w) + pad * 2;
        float h = pad * 2;
        if (st.showTitle) h += 10 * ts + st.spacing;
        if (st.showBar && c.bar() >= 0) h += 5 + st.spacing + 1;
        for (Row r : c.rows()) h += 9 * ts + st.spacing + (r.bar() >= 0 ? 5 : 0);
        h -= st.spacing;
        return new Rect(0, 0, w * st.scale, h * st.scale);
    }

    public static void draw(Gfx g, HudStore.WidgetState st, Content c, float x, float y) {
        Theme t = Theme.get();
        Rect size = measure(st, c);
        float ts = st.textScale <= 0 ? 1 : st.textScale;
        g.push();
        g.translate(x, y);
        g.g.pose().scale(st.scale, st.scale);
        float w = size.w() / st.scale, h = size.h() / st.scale;
        int accent = st.accent == null || st.accent.isEmpty() ? t.primary() : Theme.parse(st.accent);
        int bg = Col.alpha(Theme.parse(st.background), st.opacity);
        int text = Theme.parse(st.text);
        int dim = Col.mix(text, bg, 0.4f);
        float rad = Math.min(t.radius, 8);
        if (t.glow > 0) g.glow(0, 0, w, h, 6, Col.alpha(accent, 0.18f * t.glow * st.opacity));
        g.roundRect(0, 0, w, h, rad, bg);
        if (t.borders) g.roundOutline(0, 0, w, h, rad, 1f / g.s, Col.alpha(accent, 0.35f * st.opacity + 0.1f));
        g.roundRect(0, rad * 0.8f, 2, h - rad * 1.6f, 1, accent);
        float pad = st.padding, cy = pad;
        if (st.showTitle) {
            float tx = pad;
            if (st.showIcon) { g.icon(c.icon(), pad, cy - 0.5f * ts, 9 * ts, accent); tx += 13 * ts; }
            float labelW = c.barLabel() != null ? g.width(c.barLabel(), 0.8f * ts) + 8 : 0;
            g.text(g.ellipsize(title(st, c), (w - tx - pad - labelW) / ts, 1f), tx, cy, text, ts, true);
            if (c.barLabel() != null) g.textRight(c.barLabel(), w - pad, cy + ts, accent, 0.8f * ts, false);
            cy += 10 * ts + st.spacing;
        }
        if (st.showBar && c.bar() >= 0) {
            bar(g, new Rect(pad, cy, w - pad * 2, 4), c.bar(), accent, t);
            cy += 5 + st.spacing + 1;
        }
        for (Row r : c.rows()) {
            g.text(r.label(), pad, cy, dim, ts);
            g.textRight(r.value(), w - pad, cy, r.color() != 0 ? r.color() : text, ts, false);
            cy += 9 * ts;
            if (r.bar() >= 0) { bar(g, new Rect(pad, cy, w - pad * 2, 3), r.bar(), accent, t); cy += 5; }
            cy += st.spacing;
        }
        g.pop();
    }

    private static void bar(Gfx g, Rect r, float f, int accent, Theme t) {
        g.roundRect(r.x(), r.y(), r.w(), r.h(), r.h() / 2, Col.alpha(0xFFFFFFFF, 0.08f));
        float fw = Math.max(r.h(), r.w() * Math.max(0, Math.min(1, f)));
        if (t.gradients) {
            g.roundRect(r.x(), r.y(), r.h(), r.h(), r.h() / 2, accent);
            int end = Col.mix(accent, t.progressEnd(), f);
            g.gradientH(r.x() + r.h() / 2, r.y(), fw - r.h(), r.h(), accent, end);
            g.roundRect(r.x() + fw - r.h(), r.y(), r.h(), r.h(), r.h() / 2, end);
        } else g.roundRect(r.x(), r.y(), fw, r.h(), r.h() / 2, accent);
    }
}
