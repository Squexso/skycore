package dev.skycore.data;

import dev.skycore.ui.gfx.Icon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static definition of a trackable statistic.
 *
 * @param category  progression tab: dungeons, slayer, skills, bestiary, mining, collections
 * @param group     session breakdown group: dungeons, slayer, skills, mining, foraging, other
 * @param unit      estimate unit: RUNS (per completed run), BOSSES (per slayer quest), TIME (XP rate), NONE
 */
public record StatDef(String id, String category, String group, Icon icon, long[] table, int maxLevel, Unit unit, String actionBarName) {
    public enum Unit { RUNS, BOSSES, TIME, NONE }

    public String nameKey() { return "stat." + id; }

    public static final Map<String, StatDef> ALL = new LinkedHashMap<>();
    public static final String[] CATEGORIES = {"dungeons", "slayer", "skills", "foraging", "bestiary", "mining", "collections"};
    public static final String[] CLASSES = {"Healer", "Mage", "Berserk", "Archer", "Tank"};
    public static final String[] GROUPS = {"dungeons", "slayer", "skills", "mining", "foraging", "other"};

    private static void add(StatDef d) { ALL.put(d.id, d); }

    static {
        add(new StatDef("catacombs", "dungeons", "dungeons", Icon.DUNGEONS, XpTables.CATACOMBS, 50, Unit.RUNS, null));
        for (String c : CLASSES)
            add(new StatDef("class_" + c.toLowerCase(), "dungeons", "dungeons", Icon.DUNGEONS, XpTables.CATACOMBS, 50, Unit.RUNS, c));

        add(new StatDef("slayer_zombie", "slayer", "slayer", Icon.SLAYER, XpTables.SLAYER_ZOMBIE, 9, Unit.BOSSES, "Zombie"));
        add(new StatDef("slayer_spider", "slayer", "slayer", Icon.SLAYER, XpTables.SLAYER_SPIDER, 9, Unit.BOSSES, "Spider"));
        add(new StatDef("slayer_wolf", "slayer", "slayer", Icon.SLAYER, XpTables.SLAYER_STANDARD, 9, Unit.BOSSES, "Wolf"));
        add(new StatDef("slayer_enderman", "slayer", "slayer", Icon.SLAYER, XpTables.SLAYER_STANDARD, 9, Unit.BOSSES, "Enderman"));
        add(new StatDef("slayer_blaze", "slayer", "slayer", Icon.SLAYER, XpTables.SLAYER_STANDARD, 9, Unit.BOSSES, "Blaze"));
        add(new StatDef("slayer_vampire", "slayer", "slayer", Icon.SLAYER, XpTables.SLAYER_VAMPIRE, 5, Unit.BOSSES, "Vampire"));

        add(new StatDef("skill_farming", "skills", "skills", Icon.SKILLS, XpTables.SKILL, 60, Unit.TIME, "Farming"));
        add(new StatDef("skill_mining", "skills", "mining", Icon.MINING, XpTables.SKILL, 60, Unit.TIME, "Mining"));
        add(new StatDef("skill_combat", "skills", "skills", Icon.SKILLS, XpTables.SKILL, 60, Unit.TIME, "Combat"));
        add(new StatDef("skill_foraging", "foraging", "foraging", Icon.FORAGING, XpTables.SKILL, 54, Unit.TIME, "Foraging"));
        add(new StatDef("skill_fishing", "skills", "skills", Icon.SKILLS, XpTables.SKILL, 50, Unit.TIME, "Fishing"));
        add(new StatDef("skill_enchanting", "skills", "skills", Icon.SKILLS, XpTables.SKILL, 60, Unit.TIME, "Enchanting"));
        add(new StatDef("skill_alchemy", "skills", "skills", Icon.SKILLS, XpTables.SKILL, 50, Unit.TIME, "Alchemy"));
        add(new StatDef("skill_taming", "skills", "skills", Icon.SKILLS, XpTables.SKILL, 60, Unit.TIME, "Taming"));
        add(new StatDef("skill_carpentry", "skills", "skills", Icon.SKILLS, XpTables.SKILL, 50, Unit.TIME, "Carpentry"));
        add(new StatDef("skill_hunting", "skills", "skills", Icon.SKILLS, XpTables.SKILL, 25, Unit.TIME, "Hunting"));
        add(new StatDef("skill_runecrafting", "skills", "skills", Icon.SKILLS, XpTables.RUNECRAFTING, 25, Unit.TIME, "Runecrafting"));
        add(new StatDef("skill_social", "skills", "skills", Icon.SKILLS, XpTables.SOCIAL, 25, Unit.TIME, "Social"));

        add(new StatDef("hotm", "mining", "mining", Icon.MINING, XpTables.HOTM, 10, Unit.TIME, null));
        add(new StatDef("bestiary", "bestiary", "other", Icon.BESTIARY, null, 400, Unit.NONE, null));
    }

    public static StatDef get(String id) { return ALL.get(id); }

    public static List<StatDef> inCategory(String category) {
        List<StatDef> out = new ArrayList<>();
        for (StatDef d : ALL.values()) if (d.category.equals(category)) out.add(d);
        if (category.equals("mining")) out.add(0, ALL.get("skill_mining"));
        return out;
    }

    public static StatDef byActionBar(String name) {
        for (StatDef d : ALL.values()) if (d.id.startsWith("skill_") && name.equalsIgnoreCase(d.actionBarName)) return d;
        return null;
    }

    public static StatDef byClass(String name) {
        for (StatDef d : ALL.values()) if (d.id.startsWith("class_") && name.equalsIgnoreCase(d.actionBarName)) return d;
        return null;
    }

    /** Match a SkyBlock menu item name ("Mining", "Catacombs", "Heart of the Mountain", "Bestiary Milestone", "Healer"). */
    public static StatDef byMenuName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.equalsIgnoreCase("Catacombs") || n.equalsIgnoreCase("The Catacombs")) return get("catacombs");
        if (n.equalsIgnoreCase("Heart of the Mountain") || n.equalsIgnoreCase("HOTM")) return get("hotm");
        if (n.toLowerCase().startsWith("bestiary")) return get("bestiary");
        StatDef d = byActionBar(n);
        if (d == null) d = byClass(n);
        return d;
    }

    public boolean isClass() { return id.startsWith("class_"); }

    public static StatDef bySlayer(String name) {
        for (StatDef d : ALL.values()) if (d.id.startsWith("slayer_") && name.equalsIgnoreCase(d.actionBarName)) return d;
        return null;
    }

    public static Icon categoryIcon(String c) {
        return switch (c) {
            case "dungeons" -> Icon.DUNGEONS;
            case "slayer" -> Icon.SLAYER;
            case "skills" -> Icon.SKILLS;
            case "bestiary" -> Icon.BESTIARY;
            case "mining" -> Icon.MINING;
            case "collections" -> Icon.COLLECTIONS;
            case "foraging" -> Icon.FORAGING;
            default -> Icon.SPARK;
        };
    }
}
