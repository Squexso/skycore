package dev.skycore.share;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Milestone sharing preferences (part of config.json). Everything here only changes what SKYCORE <i>prepares</i>;
 * nothing is ever sent to the game's chat by the mod.
 */
public class ShareSettings {
    /** Share categories (routing keys). */
    public static final String[] CATEGORIES = {"catacombs", "slayer", "slayer_major", "runs", "skill_max", "hotm", "hotm_major",
            "collection", "goal_checkpoint", "goal_completed"};

    public static final String[] SIGNATURE_PRESETS = {"| SKYCORE", "— SKYCORE Private", "Tracked with SKYCORE"};
    public static final int MAX_LIST = 12;

    public boolean enabled = true;
    /** Off by default: copying always needs a deliberate click or key press. */
    public boolean autoCopy = false;
    public boolean showToast = true;

    public Map<String, Category> categories = new LinkedHashMap<>();
    /** Custom routing rules; the first matching rule wins over the category default. */
    public List<RouteRule> rules = new ArrayList<>();

    // milestones (a level that is not in a list is simply not announced)
    public List<Integer> catacombsLevels = new ArrayList<>(List.of(20, 30, 40, 50));
    public List<Integer> slayerLevels = new ArrayList<>(List.of(5, 7, 9));
    /** From this slayer level on (or at the slayer's maximum) a slayer milestone counts as major. */
    public int majorSlayerLevel = 7;
    public List<Integer> runMilestones = new ArrayList<>(List.of(500, 750, 1000, 1500, 2000));
    /** From this many runs on a run milestone counts as a major achievement. */
    public int majorRunMilestone = 1000;
    public List<Integer> hotmTiers = new ArrayList<>(List.of(7, 10));
    /** Collection levels below this are never announced. */
    public int collectionMinLevel = 10;
    /** Only the actual maximum level of a skill is announced. */
    public boolean skillMax = true;

    // goal checkpoints (the goal's own target decides what "50 %" and "N left" mean)
    public List<Integer> checkpointPercents = new ArrayList<>(List.of(50));
    /** Levels left until the goal target. */
    public List<Integer> checkpointRemaining = new ArrayList<>(List.of(10, 5, 2));
    /** "Almost there" messages before a milestone level; uses the alert thresholds of the Notifications page. */
    public boolean nearlyReached = true;

    /** Template variants per kind ("completed" …); an empty list = the built-in defaults of the current language. */
    public Map<String, List<String>> templates = new LinkedHashMap<>();

    /** Signature appended to messages of categories that enabled it. */
    public String signature = SIGNATURE_PRESETS[0];

    public CommunitySettings community = new CommunitySettings();

    public static class Category {
        public boolean enabled = true;
        /** party, all, clipboard or manual. */
        public String destination = "party";
        public boolean watermark = false;

        public Category() {}

        public Category(String destination) { this.destination = destination; }
    }

    public static class RouteRule {
        public String category = "catacombs";
        /** Optional stat id ("slayer_enderman"); empty = any. */
        public String stat = "";
        public String destination = "party";

        public RouteRule() {}

        public RouteRule(String category, String stat, String destination) {
            this.category = category;
            this.stat = stat == null ? "" : stat;
            this.destination = destination;
        }
    }

    public static class CommunitySettings {
        /** Inactive until the user enables it and supplies a valid link. */
        public boolean enabled = false;
        /** Never filled in by the mod. */
        public String installUrl = "";
        public int cooldownSeconds = 60;
        /** "" = default text. Placeholder: {install_url}. */
        public String response = "";
    }

    public static Destination defaultDestination(String category) {
        return switch (category) {
            case "hotm_major", "slayer_major" -> Destination.ALL;
            case "runs" -> Destination.MANUAL;
            case "collection" -> Destination.CLIPBOARD;
            default -> Destination.PARTY;
        };
    }

    public Category category(String id) {
        return categories.computeIfAbsent(id, k -> new Category(defaultDestination(k).id()));
    }

    /** Fill in anything missing and clamp values. Never removes what the user configured. */
    public void normalize() {
        if (categories == null) categories = new LinkedHashMap<>();
        for (String c : CATEGORIES) {
            Category cat = categories.get(c);
            if (cat == null) categories.put(c, new Category(defaultDestination(c).id()));
            else if (Destination.parse(cat.destination, null) == null) cat.destination = defaultDestination(c).id();
        }
        if (rules == null) rules = new ArrayList<>();
        rules.removeIf(r -> r == null || r.category == null);
        for (RouteRule r : rules) {
            if (r.stat == null) r.stat = "";
            if (Destination.parse(r.destination, null) == null) r.destination = "party";
        }
        catacombsLevels = clean(catacombsLevels, 1, 50, List.of(20, 30, 40, 50));
        slayerLevels = clean(slayerLevels, 1, 9, List.of(5, 7, 9));
        runMilestones = clean(runMilestones, 1, 100_000, List.of(500, 750, 1000, 1500, 2000));
        hotmTiers = clean(hotmTiers, 1, 10, List.of(7, 10));
        checkpointPercents = clean(checkpointPercents, 1, 99, List.of(50));
        checkpointRemaining = clean(checkpointRemaining, 1, 100, List.of(10, 5, 2));
        majorSlayerLevel = Math.max(1, Math.min(9, majorSlayerLevel));
        majorRunMilestone = Math.max(1, majorRunMilestone);
        collectionMinLevel = Math.max(1, collectionMinLevel);
        if (templates == null) templates = new LinkedHashMap<>();
        if (signature == null) signature = SIGNATURE_PRESETS[0];
        if (community == null) community = new CommunitySettings();
        if (community.installUrl == null) community.installUrl = "";
        if (community.response == null) community.response = "";
        community.cooldownSeconds = Math.max(5, Math.min(3600, community.cooldownSeconds));
    }

    /** Sorted, distinct, in range. A list the user emptied on purpose stays empty; only a null list gets the defaults. */
    private static List<Integer> clean(List<Integer> in, int min, int max, List<Integer> def) {
        if (in == null) return new ArrayList<>(def);
        List<Integer> out = new ArrayList<>();
        for (Integer v : in) if (v != null && v >= min && v <= max && !out.contains(v)) out.add(v);
        out.sort(Integer::compare);
        return out;
    }
}
