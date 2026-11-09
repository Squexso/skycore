package dev.skycore.data;

import dev.skycore.data.ProfileData.StatProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Clearly-labelled preview data (Settings → "Show sample data"). Never mixed with real tracking:
 * the UI shows a SAMPLE DATA badge whenever this is active, and nothing here is ever saved.
 */
final class SampleData {
    private SampleData() {}

    static ProfileData profile() {
        Random r = new Random(42);
        ProfileData d = new ProfileData();
        long now = System.currentTimeMillis();
        set(d, "catacombs", 23, 98_400, r, 9_000, 3_000, 14);
        set(d, "slayer_zombie", 7, 52_000, r, 1_500, 300, 10);
        set(d, "slayer_enderman", 5, 3_100, r, 600, 150, 8);
        set(d, "slayer_wolf", 6, 12_400, r, 650, 100, 6);
        set(d, "skill_mining", 57, 2_560_000, r, 4_200, 1_500, 30);
        set(d, "skill_combat", 49, 1_900_000, r, 2_000, 800, 20);
        set(d, "skill_farming", 46, 610_000, r, 1_800, 400, 20);
        set(d, "skill_foraging", 38, 1_150_000, r, 2_500, 700, 20);
        set(d, "skill_fishing", 30, 300_000, r, 900, 300, 10);
        set(d, "skill_enchanting", 52, 2_100_000, r, 15_000, 4_000, 10);
        set(d, "skill_alchemy", 50, 0, r, 500, 100, 5);
        set(d, "skill_taming", 41, 900_000, r, 900, 200, 5);
        set(d, "hotm", 7, 120_000, r, 400, 120, 10);
        set(d, "class_mage", 26, 90_000, r, 7_000, 1_500, 14);
        set(d, "class_healer", 18, 20_000, r, 1_200, 300, 6);
        set(d, "class_berserk", 21, 30_000, r, 1_400, 300, 6);
        set(d, "class_archer", 15, 4_000, r, 1_100, 300, 6);
        set(d, "class_tank", 12, 1_000, r, 900, 300, 6);
        d.stat("catacombs").count = 214;
        d.stat("slayer_zombie").count = 61;
        d.stat("slayer_enderman").count = 18;
        d.stat("slayer_wolf").count = 33;
        for (var e : d.stats.values()) e.source = "menu";
        StatProgress b = d.stat("bestiary");
        b.level = 24;
        b.xp = 4;
        b.need = 10;
        b.count = 37;
        b.source = "menu";
        b.calibrated = true;
        for (int i = 0; i < 14; i++) {
            ProfileData.DungeonRun run = new ProfileData.DungeonRun();
            run.t = now - i * 23 * 60_000L;
            run.xp = d.stat("catacombs").gains.get(13 - i).amount;
            run.floor = i % 3 == 0 ? "M5" : "F7";
            run.score = i % 4 == 1 ? "S" : "S+";
            run.scorePoints = i % 4 == 1 ? 285 : 302;
            run.dungeonClass = "Mage";
            run.classXp = d.stat("class_mage").gains.get(13 - i).amount;
            d.runs.add(run);
        }
        d.addActivity("activity.collection", "COLLECTIONS", "Mithril", "12");
        d.addActivity("activity.level", "MINING", "stat.skill_mining", "57");
        d.addActivity("activity.run", "DUNGEONS", "F7", "10,900");
        d.addActivity("activity.run", "DUNGEONS", "F7", "10,500");
        d.addActivity("activity.level", "SLAYER", "stat.slayer_zombie", "7");
        d.collections.put("Mithril", 12);
        d.collections.put("Gemstone", 9);
        d.collections.put("Wheat", 14);
        d.collections.put("Enchanted Diamond", 7);
        cp(d, "Mithril", 12, 412_000, 1_000_000);
        cp(d, "Gemstone", 9, 48_500, 100_000);
        cp(d, "Wheat", 14, 2_100_000, 2_500_000);
        d.bestRunXp = 11_500;
        val(d, "powder_mithril", 2_345_000);
        val(d, "powder_gemstone", 812_400);
        val(d, "powder_glacite", 96_200);
        d.floorCompletions.put("F7", 142);
        d.floorCompletions.put("M5", 38);
        d.floorCompletions.put("F6", 61);
        for (ProfileData.DungeonRun run : d.runs) { run.durationMs = (6 + run.t % 4) * 60_000L; run.startedAt = run.t - run.durationMs; }
        Progress.migrateActivities(d);
        sampleQuests(d, now);
        for (var a : d.activity) a.t = now - (long) (r.nextDouble() * 6 * 3600_000L);
        d.activity.sort((x, y) -> Long.compare(y.t, x.t));
        return d;
    }

    /** Sample mayor shown only in sample mode. */
    public static Activity.MayorSnapshot mayor() {
        Activity.MayorSnapshot m = new Activity.MayorSnapshot();
        m.mayor = "Aatrox";
        m.minister = "Paul";
        m.ministerKnown = true;
        m.perks.add("Slashed Pricing");
        m.perks.add("Slayer XP Buff");
        m.slayerPerk = "Slayer XP Buff";
        m.slayerXpPercent = 25;
        m.observedAt = System.currentTimeMillis() - 2 * 3600_000L;
        m.source = "menu";
        m.termStart = Mayors.termStart(System.currentTimeMillis());
        return m;
    }

    private static void sampleQuests(ProfileData d, long now) {
        Activity.MayorSnapshot m = mayor();
        int[][] plan = {{4, 12}, {1, 3}, {3, 2}};
        int k = 0;
        for (int[] p : plan)
            for (int i = 0; i < p[1]; i++, k++) {
                Activity.SlayerQuest q = new Activity.SlayerQuest();
                q.t = now - (k * 11 + 3) * 60_000L;
                q.type = "enderman";
                q.tier = p[0];
                q.startedAt = q.t - (3 + k % 3) * 60_000L;
                q.durationMs = q.t - q.startedAt;
                q.mayor = m;
                q.classification.key = q.key();
                q.classification.confidence = 0.97;
                q.classification.confirmed = true;
                q.classification.evidence.add("evidence.sidebar|enderman:" + p[0]);
                q.classification.evidence.add("evidence.level_message|enderman");
                long base = Rewards.slayerBase(Activity.Slayer.ENDERMAN, p[0]);
                q.calc = Rewards.slayer(Activity.Slayer.ENDERMAN, p[0], m, base * 1.25);
                q.id = "sample-" + k;
                Progress.addQuest(d, q);
            }
        Activity.SlayerQuest u = new Activity.SlayerQuest();
        u.t = now - 90 * 60_000L;
        u.type = "enderman";
        u.mayor = m;
        u.classification.evidence.add("evidence.level_message|enderman");
        u.classification.missing.add("evidence.no_tier");
        u.classification.candidates.add("enderman:4");
        u.classification.confidence = 0.45;
        u.calc = Rewards.slayer(null, -1, m, 625);
        u.calc.note = "calc.note.unclassified";
        u.id = "sample-uncertain";
        Progress.addQuest(d, u);
    }

    private static void val(ProfileData d, String key, double v) {
        ProfileData.ValueRecord r = new ProfileData.ValueRecord();
        r.value = v;
        r.source = "scoreboard";
        r.updated = System.currentTimeMillis() - 900_000L;
        d.values.put(key, r);
    }

    private static void cp(ProfileData d, String name, int level, double cur, double need) {
        ProfileData.CollectionProgress c = new ProfileData.CollectionProgress();
        c.level = level;
        c.current = cur;
        c.need = need;
        c.updated = System.currentTimeMillis() - 3600_000L;
        d.collectionProgress.put(name, c);
    }

    private static void set(ProfileData d, String id, int level, double xp, Random r, double gain, double spread, int n) {
        StatDef def = StatDef.get(id);
        StatProgress p = d.stat(id);
        p.level = level;
        p.need = XpTables.need(def.table(), level);
        p.xp = p.need > 0 ? Math.min(xp, p.need - 1) : 0;
        p.calibrated = true;
        p.updated = System.currentTimeMillis();
        long t = System.currentTimeMillis() - n * 20 * 60_000L;
        for (int i = 0; i < n; i++) p.gains.add(new ProfileData.Gain(t + i * 20 * 60_000L, Math.max(1, gain + (r.nextDouble() * 2 - 1) * spread)));
    }

    static SessionLog.Data sessions() {
        Random r = new Random(7);
        SessionLog.Data d = new SessionLog.Data();
        long now = System.currentTimeMillis() / SessionLog.BUCKET;
        String[] stats = {"catacombs", "skill_mining", "slayer_zombie", "skill_foraging", "skill_combat", "skill_farming", "hotm"};
        double[] base = {14_000, 30_000, 2_500, 9_000, 6_000, 5_000, 1_500};
        for (long b = now - 30 * 144; b <= now; b++) {
            long hourOfDay = (b * 10 / 60 + 1) % 24;
            boolean evening = hourOfDay >= 16 && hourOfDay <= 23;
            if (r.nextDouble() > (evening ? 0.75 : 0.12)) continue;
            SessionLog.Bucket k = new SessionLog.Bucket();
            k.playSeconds = 360 + r.nextInt(241);
            int focus = (int) ((b / 18) % 4);
            for (int i = 0; i < stats.length; i++) {
                boolean active = i == focus || r.nextDouble() < 0.18;
                if (i == 3 && b > now - 3 * 144) active = false; // foraging inactive recently → shows the warning state
                if (active) k.xp.put(stats[i], base[i] * (0.5 + r.nextDouble()));
            }
            d.buckets.put(b, k);
        }
        return d;
    }

    static List<Goals.Goal> goals() {
        List<Goals.Goal> out = new ArrayList<>();
        out.add(goal("catacombs", 30));
        out.add(goal("skill_mining", 60));
        out.add(goal("bestiary", 30));
        return out;
    }

    private static Goals.Goal goal(String stat, int target) {
        Goals.Goal g = new Goals.Goal();
        g.stat = stat;
        g.target = target;
        return g;
    }
}
