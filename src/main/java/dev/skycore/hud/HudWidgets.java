package dev.skycore.hud;

import dev.skycore.core.Lang;
import dev.skycore.data.Estimate;
import dev.skycore.data.Goals;
import dev.skycore.data.Pace;
import dev.skycore.data.ProfileData;
import dev.skycore.data.ProfileData.StatProgress;
import dev.skycore.data.Progress;
import dev.skycore.data.SessionLog;
import dev.skycore.data.SideXp;
import dev.skycore.data.StatDef;
import dev.skycore.data.XpTables;
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

    /** tail = optional small coloured text (e.g. a trend) drawn right of the value; empty/null = none. */
    public record Row(String label, String value, int color, float bar, String tail, int tailColor) {
        Row(String label, String value) { this(label, value, 0, -1, null, 0); }
        Row(String label, String value, int color, float bar) { this(label, value, color, bar, null, 0); }
        boolean hasTail() { return tail != null && !tail.isEmpty(); }
    }

    public record Content(String title, Icon icon, float bar, String barLabel, List<Row> rows) {}

    public static String nameKey(String id) { return "hud.widget." + id; }

    public static Icon icon(String id) {
        return switch (id) {
            case "catacombs" -> Icon.DUNGEONS;
            case "skill" -> Icon.SKILLS;
            case "slayer" -> Icon.SLAYER;
            case "session" -> Icon.CLOCK;
            case "music" -> Icon.SOUND;
            case "reputation", "hotm", "m_fps", "m_tps", "m_ping", "m_ram", "m_xyz" -> Icon.SPARK;
            default -> Icon.GOALS;
        };
    }

    /** Configurable info lines per widget (keys for WidgetState.lines). */
    public static String[] lineKeys(String id) {
        return switch (id) {
            case "catacombs" -> new String[]{"remaining", "runs", "last"};
            case "skill" -> new String[]{"remaining", "rate", "trend", "eta", "creatures"};
            case "slayer" -> new String[]{"remaining", "rate", "eta"};
            case "session" -> new String[]{"time", "xp", "rate", "pace"};
            case "reputation" -> new String[]{"rep_left", "rep_today", "rep_eta"};
            case "music" -> new String[]{"music_artist", "music_status"};
            case "hotm" -> new String[]{"hotm_xp", "hotm_left", "hotm_target"};
            case "m_ping" -> new String[]{"labels", "realping"};
            case "m_fps", "m_tps", "m_ram", "m_xyz" -> new String[]{"labels"};
            default -> new String[]{"bars", "eta"};
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
                // The shown stat is the one with the newest real XP gain. "updated" is also touched by tab-list and menu
                // reads of every skill, so using it made the box jump between skills every few seconds.
                StatDef best = null;
                StatProgress bp = null;
                long bestT = 0;
                // Combat XP from a kill while gathering must not steal the box: while another skill gained XP in the last
                // two minutes, Combat only wins if nothing else is active.
                long nowMs = System.currentTimeMillis();
                boolean otherActive = false;
                for (StatDef def : StatDef.ALL.values()) {
                    if (!def.id().startsWith(prefix) || def.id().equals("skill_combat")) continue;
                    StatProgress p = d.peek(def.id());
                    if (!p.gains.isEmpty() && nowMs - p.gains.get(p.gains.size() - 1).t <= 120_000L) otherActive = true;
                }
                for (StatDef def : StatDef.ALL.values()) {
                    if (!def.id().startsWith(prefix)) continue;
                    if (otherActive && id.equals("skill") && def.id().equals("skill_combat")) continue;
                    StatProgress p = d.peek(def.id());
                    long gainT = p.gains.isEmpty() ? 0 : p.gains.get(p.gains.size() - 1).t;
                    if (gainT > bestT) { best = def; bp = p; bestT = gainT; }
                }
                if (best == null) { // nothing gained yet: fall back to the newest reading
                    for (StatDef def : StatDef.ALL.values()) {
                        if (!def.id().startsWith(prefix)) continue;
                        StatProgress p = d.peek(def.id());
                        if (p.updated > 0 && (bp == null || p.updated > bp.updated)) { best = def; bp = p; }
                    }
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
                        // trend vs. the last hour: plain signed percent (the UI font has no arrow glyphs), only when significant
                        Double tr = lines.on("trend") && !Progress.isSample() ? Pace.trendOf(best.id()) : null;
                        rows.add(new Row(Lang.t("hud.xp_hour"), h > 0.05 ? Lang.compact(xp / h) : "—", 0, -1,
                                tr == null || h <= 0.05 ? null : Pace.signedPercent(tr), tr == null ? 0 : tr > 0 ? Col.mix(t.success(), t.textDim(), 0.25f) : Col.mix(t.danger(), t.textDim(), 0.35f)));
                    } else if (!bp.gains.isEmpty()) {
                        rows.add(new Row(Lang.t("hud.last_boss"), "+" + Lang.compact(bp.lastGain()), t.success(), -1));
                    }
                }
                if (lines.on("eta")) {
                    Estimate.Range r = Estimate.forNextLevel(best, bp);
                    rows.add(new Row(Lang.t(id.equals("skill") ? "hud.eta" : "hud.est_bosses"), r == null ? "—" : r.format()));
                }
                if (id.equals("skill") && lines.on("creatures") && SideXp.isHost(best.id())) {
                    SideXp.Counter c = Progress.isSample() ? new SideXp.Counter(14, 5200) : SideXp.get(best.id());
                    // Fishing: the creature lines and the XP lines are not reliably paired in time, so only the count is shown there.
                    String v = c.count() == 0 ? "0" : best.id().equals("skill_fishing") ? String.valueOf(c.count()) : c.count() + " (+" + Lang.compact(c.xp()) + ")";
                    rows.add(new Row(Lang.t("hud.creatures." + best.id()), v, c.count() > 0 ? t.success() : 0, -1));
                }
                String title = Lang.t(best.nameKey()) + (bp.known() ? " " + bp.level : "");
                return new Content(title, best.icon(), bp.need > 0 || bp.percent >= 0 ? bp.frac() : -1, Lang.percent(bp.frac()), rows);
            }
            case "reputation" -> {
                return reputation(lines, t);
            }
            case "music" -> {
                return music(lines, t);
            }
            case "hotm" -> {
                return hotm(lines, t);
            }
            case "m_fps", "m_tps", "m_ping", "m_ram", "m_xyz" -> {
                return meter(id, lines, t);
            }
            case "session" -> {
                SessionLog.Totals tt = SessionLog.totals(SessionLog.range(SessionLog.Period.TODAY));
                double h = tt.playMillis() / 3600_000.0;
                if (lines.on("time")) rows.add(new Row(Lang.t("hud.playtime"), Lang.duration(tt.playMillis())));
                if (lines.on("xp")) rows.add(new Row(Lang.t("hud.xp_today"), Lang.compact(tt.xp())));
                if (lines.on("rate")) rows.add(new Row(Lang.t("hud.xp_hour"), h > 0.05 ? Lang.compact(tt.xp() / h) : "—"));
                if (lines.on("pace") && !Progress.isSample()) {
                    if (dev.skycore.tracking.HypixelTracker.afk()) {
                        rows.add(new Row(Lang.t("hud.status"), Lang.t("hud.paused_afk"), t.textDim(), -1));
                    } else {
                        Pace.Result pr = Pace.current();
                        if (pr != null && pr.significant()) {
                            int c = pr.deviation() > 0 ? Col.mix(t.success(), t.textDim(), 0.35f) : Col.mix(t.danger(), t.textDim(), 0.45f);
                            rows.add(new Row(Lang.t("hud.pace"), Lang.t("hud.pace.value", Pace.signedPercent(pr.deviation())), c, -1));
                        }
                    }
                }
                return new Content(Lang.t("hud.widget.session"), Icon.CLOCK, -1, null, rows);
            }
            default -> {
                int n = 0;
                for (Goals.Goal g : Goals.all()) {
                    if (g.paused || g.completed != 0 || n >= 3) continue;
                    StatDef def = StatDef.get(g.stat);
                    rows.add(new Row(Lang.t(def.nameKey()) + " " + g.target, Lang.percent(Goals.fraction(g)), 0, lines.on("bars") ? Goals.fraction(g) : -1));
                    n++;
                    // honest from-to estimate; no row when no estimate is possible
                    if (lines.on("eta")) {
                        double rem = Goals.xpRemaining(g);
                        if (rem > 0 && Goals.current(g) >= 0) {
                            Estimate.Range e = Estimate.forXp(def, d.peek(g.stat), rem);
                            if (e != null) {
                                Estimate.Range tm = Estimate.withTime(def, d.peek(g.stat), e);
                                rows.add(new Row(Lang.t("hud.eta"), (tm != null ? tm : e).span(), t.textDim(), -1));
                            }
                        }
                    }
                }
                if (rows.isEmpty()) rows.add(new Row(Lang.t("hud.no_goals"), "", t.textDim(), -1));
                return new Content(Lang.t("hud.widget.goals"), Icon.GOALS, -1, null, rows);
            }
        }
    }

    /** Heart of the Mountain as plain numbers: XP now / needed, XP to the next tier and XP to a chosen target tier. */
    private static Content hotm(WidgetLines lines, Theme t) {
        StatDef def = StatDef.get("hotm");
        StatProgress p = Progress.view().peek("hotm");
        List<Row> rows = new ArrayList<>();
        String name = Lang.t(def.nameKey());
        if (!p.known()) {
            rows.add(new Row(Lang.t("hud.status"), Lang.t("hud.no_hotm"), t.textDim(), -1));
            return new Content(name, Icon.MINING, -1, null, rows);
        }
        boolean maxed = p.level >= def.maxLevel() || (p.need <= 0 && p.percent < 0);
        HudStore.WidgetState w = lines.w();
        int target = Math.max(2, Math.min(def.maxLevel(), w == null ? def.maxLevel() : w.targetTier));
        if (lines.on("hotm_xp")) {
            String v = maxed ? Lang.t("progress.maxed") : p.need > 0 ? Lang.num(p.xp) + " / " + Lang.num(p.need) : Lang.percent(p.frac());
            rows.add(new Row(Lang.t("hud.hotm.xp"), v));
        }
        if (lines.on("hotm_left") && !maxed && p.need > 0)
            rows.add(new Row(Lang.t("hud.hotm.left", p.level + 1), Lang.num(p.remaining())));
        if (lines.on("hotm_target")) {
            String label = Lang.t("hud.hotm.left", target);
            if (p.level >= target) rows.add(new Row(label, Lang.t("hud.hotm.reached"), t.success(), -1));
            else if (p.need > 0) rows.add(new Row(label, Lang.num(XpTables.xpTo(def.table(), p.level, p.xp, target))));
        }
        return new Content(name + " " + p.level, Icon.MINING, maxed ? 1 : p.need > 0 || p.percent >= 0 ? p.frac() : -1, null, rows);
    }

    /** The single-value readouts (FPS, TPS, ping, memory, position): each is its own widget without a box. */
    public static boolean isMeter(String id) { return id != null && id.startsWith("m_"); }

    private static int meterColor(Theme t, HudStore.WidgetState w, int cls) {
        String hex = w == null ? "" : cls == 0 ? w.colorGood : cls == 1 ? w.colorMid : w.colorBad;
        if (hex != null && !hex.isEmpty()) return Theme.parse(hex);
        return cls == 0 ? t.success() : cls == 1 ? t.warning() : t.danger();
    }

    /** Colour of a reading: by value (good / medium / bad) or the plain text colour when "colour by value" is off. */
    private static int meterPaint(Theme t, HudStore.WidgetState w, int cls) {
        if (w == null) return cls < 0 ? t.text() : meterColor(t, null, cls);
        return cls < 0 || !w.autoColor ? Theme.parse(w.text) : meterColor(t, w, cls);
    }

    private static Content meter(String id, WidgetLines lines, Theme t) {
        HudStore.WidgetState w = lines.w();
        boolean lb = w == null || w.line("labels");
        Minecraft mc = Minecraft.getInstance();
        String label, value = "—";
        int cls = -1;
        switch (id) {
            case "m_fps" -> { label = "FPS"; int v = Meters.fps(mc); value = String.valueOf(v); cls = Meters.fpsClass(v); }
            case "m_tps" -> { label = "TPS"; double v = Meters.tps(); if (v >= 0) { value = Meters.tpsText(v); cls = Meters.tpsClass(v); } }
            case "m_ping" -> { label = "Ping"; int v = Meters.ping(mc); if (v >= 0) { value = String.valueOf(v); cls = Meters.pingClass(v); } }
            case "m_ram" -> { label = "RAM"; int v = Meters.memoryPercent(); value = v + "%"; cls = Meters.memoryClass(v); }
            default -> { label = "XYZ"; if (mc.player != null) value = mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ(); }
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(lb ? label : "", value, meterPaint(t, w, cls), -1));
        return new Content("", Icon.SPARK, -1, null, rows);
    }
    /** Now playing: song, artist, progress and the time; the keybinds (unbound by default) pause and skip. */
    private static Content music(WidgetLines lines, Theme t) {
        List<Row> rows = new ArrayList<>();
        String title, artist; long pos, dur; dev.skycore.music.Player.Status st;
        if (Progress.isSample()) { title = "Funk You"; artist = "Westside Zay"; pos = 83_000; dur = 268_000; st = dev.skycore.music.Player.Status.PLAYING; }
        else {
            dev.skycore.music.Player pl = dev.skycore.music.Music.player();
            dev.skycore.music.MusicTrack cur = pl.current();
            if (cur == null) { rows.add(new Row(Lang.t("hud.status"), Lang.t("music.nothing_playing"), t.textDim(), -1)); return new Content(Lang.t("hud.widget.music"), Icon.SOUND, -1, null, rows); }
            title = cur.title; artist = cur.artist; pos = pl.positionMs(); dur = pl.durationMs(); st = pl.status();
        }
        if (title.length() > 30) title = title.substring(0, 29) + "…";
        if (lines.on("music_artist") && !artist.isEmpty()) rows.add(new Row("", artist.length() > 30 ? artist.substring(0, 29) + "…" : artist, t.textDim(), -1));
        if (lines.on("music_status") && st != dev.skycore.music.Player.Status.PLAYING)
            rows.add(new Row("", Lang.t(st == dev.skycore.music.Player.Status.PAUSED ? "music.paused" : "music.loading"), t.textDim(), -1));
        float frac = dur > 0 ? Math.max(0, Math.min(1, pos / (float) dur)) : -1;
        String label = dur > 0 ? mmss(pos) + " / " + mmss(dur) : mmss(pos);
        return new Content(title, Icon.SOUND, frac, label, rows);
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Crimson Isle reputation box: level bar, reputation left, gained today and the estimate in play days. */
    private static Content reputation(WidgetLines lines, Theme t) {
        List<Row> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        String fac = Progress.isSample() ? "Mage" : dev.skycore.data.Reputation.current();
        if (fac == null) {
            rows.add(new Row(Lang.t("hud.status"), Lang.t("hud.rep.none"), t.textDim(), -1));
            return new Content(Lang.t("hud.widget.reputation"), Icon.SPARK, -1, null, rows);
        }
        dev.skycore.data.Reputation.Fac f = Progress.isSample() ? sampleRep() : dev.skycore.data.Reputation.get(fac);
        String title = Lang.t("hud.rep.title." + fac.toLowerCase());
        if (f == null) return new Content(title, Icon.SPARK, -1, null, rows);
        long today = Progress.isSample() ? 175 : dev.skycore.data.Reputation.today(fac, now);
        if (f.known()) {
            if (lines.on("rep_left")) rows.add(new Row(Lang.t("hud.rep.left"), Lang.num(f.remaining())));
        } else {
            rows.add(new Row(Lang.t("hud.status"), Lang.t("hud.rep.unread"), t.textDim(), -1));
        }
        if (lines.on("rep_today")) rows.add(new Row(Lang.t("hud.rep.today"), "+" + Lang.num(today), today > 0 ? t.success() : 0, -1));
        if (lines.on("rep_eta") && f.known()) {
            dev.skycore.data.Reputation.Eta e = Progress.isSample() ? new dev.skycore.data.Reputation.Eta(2, 2.6, 3.4, 5, 480) : dev.skycore.data.Reputation.eta(fac, now);
            rows.add(new Row(Lang.t("hud.rep.eta"), e == null ? "—" : dev.skycore.data.Reputation.formatDays(e)));
        }
        String label = f.known() ? Lang.num(f.cur) + " / " + Lang.num(f.need) : null;
        return new Content(title, Icon.SPARK, f.known() ? f.frac() : -1, label, rows);
    }

    private static dev.skycore.data.Reputation.Fac sampleRep() {
        dev.skycore.data.Reputation.Fac f = new dev.skycore.data.Reputation.Fac();
        f.cur = 1880; f.need = 3000;
        return f;
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

    private static long now0() { return System.currentTimeMillis(); }

    /** True while the activity a widget belongs to is going on (dungeon run, slayer quest, recent skill XP). */
    public static boolean activityNow(String id) {
        if (Progress.isSample()) return true;
        return switch (id) {
            case "catacombs" -> dev.skycore.tracking.HypixelTracker.inDungeon() || dev.skycore.tracking.HypixelTracker.runActive();
            case "slayer" -> dev.skycore.tracking.HypixelTracker.slayerActive();
            case "music" -> dev.skycore.music.Music.started() && dev.skycore.music.Music.player().status() != dev.skycore.music.Player.Status.IDLE;
            case "reputation" -> now0() - dev.skycore.data.Reputation.lastGainAt() < 10 * 60_000L;
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
    private static String meterText(Row r) { return r.label().isEmpty() ? r.value() : r.label() + " " + r.value(); }

    public static Rect measure(HudStore.WidgetState st, Content c) {
        if (isMeter(st.id)) {
            float ts0 = st.textScale <= 0 ? 1 : st.textScale, w0 = 8, h0 = 0;
            for (Row r : c.rows()) { w0 = Math.max(w0, Gfx.measure(meterText(r), false) * ts0); h0 += 9 * ts0 + st.spacing; }
            return new Rect(0, 0, (w0 + 2) * st.scale, Math.max(9 * ts0, h0 - st.spacing + 1) * st.scale);
        }
        float pad = st.padding, ts = st.textScale <= 0 ? 1 : st.textScale;
        float w = 0;
        if (st.showTitle) w = Gfx.measure(title(st, c), true) * ts + (st.showIcon ? 13 * ts : 0) + (c.barLabel() != null ? Gfx.measure(c.barLabel(), false) * 0.8f * ts + 10 : 0);
        for (Row r : c.rows()) w = Math.max(w, (Gfx.measure(r.label(), false) + Gfx.measure(r.value(), false) + (r.hasTail() ? Gfx.measure(r.tail(), false) * 0.85f + 4 : 0)) * ts + 14);
        w = Math.max(96, w) + pad * 2;
        float h = pad * 2;
        if (st.showTitle) h += 10 * ts + st.spacing;
        if (st.showBar && c.bar() >= 0) h += 5 + st.spacing + 1;
        for (Row r : c.rows()) h += 9 * ts + st.spacing + (r.bar() >= 0 ? 5 : 0);
        h -= st.spacing;
        return new Rect(0, 0, w * st.scale, h * st.scale);
    }

    public static void draw(Gfx g, HudStore.WidgetState st, Content c, float x, float y) {
        if (isMeter(st.id)) { drawMeters(g, st, c, x, y); return; }
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
            float vr = w - pad;
            if (r.hasTail()) { // small trend text at the right edge, value shifted left of it
                g.textRight(r.tail(), vr, cy + 0.5f * ts, r.tailColor(), 0.85f * ts, false);
                vr -= g.width(r.tail(), 0.85f * ts) + 4;
            }
            g.textRight(r.value(), vr, cy, r.color() != 0 ? r.color() : text, ts, false);
            cy += 9 * ts;
            if (r.bar() >= 0) { bar(g, new Rect(pad, cy, w - pad * 2, 3), r.bar(), accent, t); cy += 5; }
            cy += st.spacing;
        }
        g.pop();
    }

    /** Plain coloured numbers with a soft shadow, no background, border or title. */
    private static void drawMeters(Gfx g, HudStore.WidgetState st, Content c, float x, float y) {
        Theme t = Theme.get();
        float ts = st.textScale <= 0 ? 1 : st.textScale;
        g.push();
        g.translate(x, y);
        g.g.pose().scale(st.scale, st.scale);
        float cy = 0;
        for (Row r : c.rows()) {
            String s = meterText(r);
            int col = Col.alpha(r.color() != 0 ? r.color() : Theme.parse(st.text), st.opacity);
            g.text(s, 0.6f, cy + 0.6f, Col.alpha(0xFF000000, 0.75f * st.opacity), ts);
            g.text(s, 0, cy, col, ts);
            cy += 9 * ts + st.spacing;
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
