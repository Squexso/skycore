package dev.skycore.tracking;

import dev.skycore.data.Activity;
import dev.skycore.data.Activity.Classification;
import dev.skycore.data.Activity.MayorSnapshot;
import dev.skycore.data.Activity.Slayer;
import dev.skycore.data.Activity.SlayerQuest;
import dev.skycore.data.Rewards;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * Follows one slayer quest from start to reward and decides exactly which slayer type and tier it was.
 * Pure (no Minecraft types) and unit-tested.
 * <p>
 * Evidence, strongest first:
 * <ul>
 *   <li>sidebar quest line "Voidgloom Seraph IV" – type and tier (0.95)</li>
 *   <li>the player's own boss nametag (a "Spawned by: &lt;you&gt;" tag right next to it) – type and tier (0.90)</li>
 *   <li>"Enderman Slayer LVL …" after the kill – type only (0.90)</li>
 *   <li>"» Slay … Combat XP worth of Endermen" at quest start – type only (0.85)</li>
 * </ul>
 * The tier is <b>never</b> taken from the XP amount: if no tier evidence exists the quest is stored as uncertain, with
 * the tiers whose reward matches the observed XP listed as candidates only. Conflicting evidence (two different
 * tiers or types) also makes the quest uncertain. A boss spawn is not a completion; only "SLAYER QUEST COMPLETE!"
 * finishes a quest, and a second completion within 3 s is ignored.
 */
public final class SlayerSession {
    public static final double CONFIRM = 0.8;
    static final long XP_WAIT = 8_000, LEVEL_WINDOW = 15_000, DUPLICATE = 3_000, IGNORE_AFTER_FINISH = 5_000;

    record Ev(Slayer type, int tier, double confidence, String source) {}

    private final LongFunction<MayorSnapshot> mayorAt;
    private final List<Ev> evidence = new ArrayList<>();
    private boolean active;
    private long startedAt, completedAt, finishedAt, lastCompletion;
    private Slayer levelType;
    private double observed = -1;
    static final long LATE_WINDOW = 10_000;
    private SlayerQuest lastFinished;
    /** Late evidence for the quest that just ended, picked up by the tracker. */
    public record Late(String questId, String key, String source, boolean conflict) {}
    private Late late;

    /** Late evidence collected since the last call (null if none). */
    public Late takeLate() { Late l = late; late = null; return l; }

    private void lateEvidence(long t, Slayer s, int tier, String source) {
        if (lastFinished == null || t - finishedAt > LATE_WINDOW || s == null || tier < 1) return;
        String key = s.key(tier);
        if (lastFinished.classified()) {
            // never change a confirmed quest; a disagreement is only reported
            if (!key.equals(lastFinished.key())) late = new Late(lastFinished.id, key, source, true);
            return;
        }
        if (lastFinished.type != null && !lastFinished.type.equals(s.id)) { late = new Late(lastFinished.id, key, source, true); return; }
        late = new Late(lastFinished.id, key, source, false);
    }

    public SlayerSession(LongFunction<MayorSnapshot> mayorAt) { this.mayorAt = mayorAt; }

    public boolean active() { return active; }

    public boolean awaitingReward() { return completedAt > 0; }

    /** "SLAYER QUEST STARTED!" – a new quest; anything collected before belongs to an older quest. */
    public SlayerQuest started(long t) {
        SlayerQuest pending = completedAt > 0 ? finish(t, "complete") : null;
        begin(t);
        return pending;
    }

    private void begin(long t) {
        lastFinished = null; // late evidence never crosses into a new quest
        late = null;
        evidence.clear();
        active = true;
        startedAt = t;
        completedAt = 0;
        levelType = null;
        observed = -1;
    }

    public void requirement(long t, String mobs) {
        Slayer s = Slayer.byMobs(mobs);
        if (s == null) return;
        if (!active) begin(t);
        evidence.add(new Ev(s, -1, 0.85, "evidence.quest_line"));
    }

    /** Sidebar quest line. Starts tracking a quest that was already running when the player joined. */
    public void sidebar(long t, String boss, String tierRoman, String phase) {
        Slayer s = Slayer.byBoss(boss);
        if (s == null) return;
        int tier = romanTier(tierRoman, s);
        if (!active) lateEvidence(t, s, tier, "evidence.sidebar");
        if (!active) {
            // a finished quest's "Boss slain!" can stay visible for a moment – never start a new quest from it
            if ("slain".equals(phase) || t - finishedAt < IGNORE_AFTER_FINISH) return;
            begin(t);
            startedAt = 0; // start time unknown
        }
        addOnce(new Ev(s, tier, 0.95, "evidence.sidebar"));
    }

    /** The player's own boss nametag (ownership already checked by the caller). */
    public void ownBoss(long t, String boss, String tierRoman) {
        Slayer s = Slayer.byBoss(boss);
        if (s == null) return;
        if (!active) { lateEvidence(t, s, romanTier(tierRoman, s), "evidence.boss_nametag"); return; }
        addOnce(new Ev(s, romanTier(tierRoman, s), 0.9, "evidence.boss_nametag"));
    }

    /** "SLAYER QUEST COMPLETE!" @return true if accepted (false = duplicate or no quest) */
    public boolean completed(long t) {
        if (t - lastCompletion < DUPLICATE) return false;
        if (!active && completedAt == 0) {
            // completion without any quest context: record what little we know (uncertain)
            begin(t);
            startedAt = 0;
        }
        lastCompletion = t;
        completedAt = t;
        return true;
    }

    /** "… Slayer LVL n - Next LVL in …" with the XP delta computed from the stored level (-1 if unknown). */
    public SlayerQuest level(long t, String levelName, double xpDelta) {
        if (completedAt == 0 || t - completedAt > LEVEL_WINDOW) return null;
        levelType = Slayer.byLevelName(levelName);
        observed = xpDelta > 0 ? xpDelta : -1;
        return finish(t, "complete");
    }

    public SlayerQuest failed(long t) {
        if (!active) return null;
        return finish(t, "failed");
    }

    /** Called every tick: a completion without a level message is finished without observed XP. */
    public SlayerQuest tick(long t) {
        if (completedAt > 0 && t - completedAt > XP_WAIT) return finish(t, "complete");
        return null;
    }

    /** Disconnect: a completed quest is finished without XP, an open quest is recorded as interrupted (not counted). */
    public SlayerQuest disconnect(long t) {
        if (completedAt > 0) return finish(t, "complete");
        if (active) return finish(t, "interrupted");
        return null;
    }

    private void addOnce(Ev e) {
        for (Ev x : evidence) if (x.source().equals(e.source()) && x.type() == e.type() && x.tier() == e.tier()) return;
        evidence.add(e);
    }

    private static int romanTier(String r, Slayer s) {
        if (r == null || s == null) return -1;
        int n = dev.skycore.tracking.parse.MessageParser.roman(r.trim());
        return n >= 1 && n <= s.maxTier ? n : -1;
    }

    private SlayerQuest finish(long t, String status) {
        SlayerQuest q = new SlayerQuest();
        long end = completedAt > 0 ? completedAt : t;
        q.t = end;
        q.status = status;
        q.startedAt = startedAt;
        q.durationMs = startedAt > 0 ? end - startedAt : 0;
        List<Ev> ev = new ArrayList<>(evidence);
        if (levelType != null) ev.add(new Ev(levelType, -1, 0.9, "evidence.level_message"));
        q.mayor = mayorAt == null ? null : mayorAt.apply(end);
        q.classification = classify(ev, observed, q.mayor);
        String key = q.classification.key;
        if (key != null) {
            int i = key.indexOf(':');
            q.type = key.substring(0, i);
            q.tier = Integer.parseInt(key.substring(i + 1));
        } else {
            Slayer only = singleType(ev);
            q.type = only == null ? null : only.id;
        }
        Slayer s = q.type == null ? null : Slayer.byId(q.type);
        q.calc = q.classification.confirmed ? Rewards.slayer(s, q.tier, q.mayor, observed) : Rewards.slayer(null, -1, q.mayor, observed);
        if (!q.classification.confirmed) q.calc.note = "calc.note.unclassified";
        q.id = "slayer-" + end + "-" + (q.type == null ? "unknown" : q.type) + "-" + q.tier;
        active = false;
        completedAt = 0;
        finishedAt = t;
        lastFinished = "complete".equals(status) ? q : null;
        evidence.clear();
        levelType = null;
        observed = -1;
        return q;
    }

    private static Slayer singleType(List<Ev> ev) {
        Set<Slayer> types = new LinkedHashSet<>();
        for (Ev e : ev) types.add(e.type());
        return types.size() == 1 ? types.iterator().next() : null;
    }

    /** Pure classification from evidence (unit-tested). */
    static Classification classify(List<Ev> ev, double observed, MayorSnapshot mayor) {
        Classification c = new Classification();
        Set<Slayer> types = new LinkedHashSet<>();
        Set<Integer> tiers = new LinkedHashSet<>();
        double typeConf = 0, tierConf = 0;
        Set<String> sources = new LinkedHashSet<>();
        for (Ev e : ev) {
            types.add(e.type());
            typeConf = Math.max(typeConf, e.confidence());
            if (e.tier() > 0) { tiers.add(e.tier()); tierConf = Math.max(tierConf, e.confidence()); }
            sources.add(e.source());
            c.evidence.add(e.source() + "|" + e.type().id + (e.tier() > 0 ? ":" + e.tier() : ""));
        }
        if (types.isEmpty()) c.missing.add("evidence.no_type");
        if (types.size() > 1) c.missing.add("evidence.conflict_type");
        if (tiers.isEmpty()) c.missing.add("evidence.no_tier");
        if (tiers.size() > 1) c.missing.add("evidence.conflict_tier");
        Slayer type = types.size() == 1 ? types.iterator().next() : null;
        if (type != null && tiers.size() == 1) {
            int tier = tiers.iterator().next();
            c.key = type.key(tier);
            double agree = Math.max(0, sources.size() - 1) * 0.02;
            c.confidence = Math.min(0.99, Math.min(typeConf, tierConf) + agree);
            c.confirmed = c.confidence >= CONFIRM;
        } else {
            c.confidence = type != null ? Math.min(0.5, typeConf * 0.5) : 0;
            if (type != null) for (int tier : Rewards.tiersMatching(type, observed, mayor)) c.candidates.add(type.key(tier));
            if (type != null && tiers.size() > 1) for (int tier : tiers) if (!c.candidates.contains(type.key(tier))) c.candidates.add(type.key(tier));
        }
        return c;
    }
}
