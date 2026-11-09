package dev.skycore.tracking.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based parser for Hypixel chat and action-bar text (colour codes already stripped).
 * <p>
 * Each rule is a regex + an extractor. Several patterns may map to the same event so different
 * message variants are supported; new formats are added with {@link #register}. Player-written
 * chat (rank prefixes, party/guild/DM channels) is rejected first, so nobody can fake progress by typing.
 */
public final class MessageParser {
    public enum Channel { CHAT, ACTION_BAR }

    /**
     * @param confidence how certain a match of this rule is (0..1); format-specific rules score high,
     *                   loose or unverified formats lower. The tracker can raise/lower it with context.
     * @param language   "en" for Hypixel's built-in formats; user patterns may add others (e.g. "de").
     */
    public record Rule(String id, Channel channel, Pattern pattern, Function<Matcher, ParsedEvent> extract, double confidence, String language) {}

    /** A recognised event plus which rule matched and how confident the match is. */
    public record Recognized(ParsedEvent event, String rule, double confidence) {}

    private final List<Rule> rules = new ArrayList<>();

    /** Lines that come from players rather than the server. */
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:(?:Party|Guild|Co-op|Officer) >|From |To |\\[\\d+\\] |\\[(?!NPC\\])[A-Z+]+(?:\\+\\+?)?\\] [\\w]{1,16}: |[\\w]{3,16}: )");

    private static final String NUM = "([\\d,]+(?:\\.\\d+)?)";
    private static final String NUM_SUFFIX = "([\\d,]+(?:\\.\\d+)?[kKmMbB]?)";
    private static final String ROMAN_OR_INT = "([IVXLCDM]+|\\d+)";

    public static MessageParser defaults() {
        MessageParser p = new MessageParser();
        // ---- action bar: skills (absolute and percentage display modes)
        p.register("skill_abs", Channel.ACTION_BAR, "\\+" + NUM + " (\\w+) \\(" + NUM + "/" + NUM_SUFFIX + "\\)", 0.95, "en",
                m -> new ParsedEvent.SkillProgress(m.group(2), num(m.group(1)), num(m.group(3)), num(m.group(4))));
        p.register("skill_pct", Channel.ACTION_BAR, "\\+" + NUM + " (\\w+) \\(" + NUM + "%\\)", 0.85, "en",
                m -> new ParsedEvent.SkillPercent(m.group(2), num(m.group(1)), num(m.group(3))));
        // ---- dungeons
        p.register("cata_xp", Channel.CHAT, "^\\+" + NUM + " Catacombs Experience\\b", 0.9, "en",
                m -> new ParsedEvent.CatacombsXp(num(m.group(1))));
        p.register("cata_xp_alt", Channel.CHAT, "^Catacombs Experience:? \\+" + NUM + "$", 0.75, "en",
                m -> new ParsedEvent.CatacombsXp(num(m.group(1))));
        p.register("class_xp", Channel.CHAT, "^\\+" + NUM + " (Healer|Mage|Berserk|Archer|Tank) Experience( \\(Team Bonus\\))?", 0.9, "en",
                m -> new ParsedEvent.ClassXp(m.group(2), num(m.group(1)), m.group(3) != null));
        p.register("team_score", Channel.CHAT, "^Team Score: (\\d+) \\(([SABCD]\\+?)\\)", 0.9, "en",
                m -> new ParsedEvent.TeamScore(Integer.parseInt(m.group(1)), m.group(2)));
        // ---- slayer
        p.register("slayer_done", Channel.CHAT, "^SLAYER QUEST COMPLETE!$", 0.9, "en", m -> new ParsedEvent.SlayerQuestComplete());
        p.register("slayer_lvl", Channel.CHAT, "(Zombie|Spider|Wolf|Enderman|Blaze|Vampire) Slayer LVL (\\d+) - Next LVL in " + NUM + " XP!", 0.95, "en",
                m -> new ParsedEvent.SlayerLevel(m.group(1), Integer.parseInt(m.group(2)), num(m.group(3))));
        p.register("slayer_max", Channel.CHAT, "(Zombie|Spider|Wolf|Enderman|Blaze|Vampire) Slayer LVL (\\d+) - LVL MAXED OUT!", 0.95, "en",
                m -> new ParsedEvent.SlayerLevel(m.group(1), Integer.parseInt(m.group(2)), -1));
        // ---- skills / profile
        p.register("skill_up", Channel.CHAT, "^SKILL LEVEL UP (\\w+) (?:" + ROMAN_OR_INT + " ?[➜→>]+ ?)?" + ROMAN_OR_INT + "$", 0.95, "en",
                m -> new ParsedEvent.SkillLevelUp(m.group(1), roman(m.group(3))));
        p.register("profile", Channel.CHAT, "^You are playing on profile: (\\w+)", 0.98, "en",
                m -> new ParsedEvent.ProfileSwitch(m.group(1)));
        // ---- collections / bestiary / HOTM
        p.register("collection_up", Channel.CHAT, "^COLLECTION LEVEL UP (.+?) (?:" + ROMAN_OR_INT + " ?[➜→>]+ ?)?" + ROMAN_OR_INT + "$", 0.9, "en",
                m -> new ParsedEvent.CollectionLevelUp(m.group(1).trim(), roman(m.group(3))));
        p.register("bestiary_ms", Channel.CHAT, "(?i)^BESTIARY MILESTONE " + ROMAN_OR_INT + "\\b", 0.85, "en",
                m -> new ParsedEvent.BestiaryMilestone(roman(m.group(1).toUpperCase())));
        p.register("bestiary_family", Channel.CHAT, "^BESTIARY (?!MILESTONE)(.+?) " + ROMAN_OR_INT + " ?[➜→>]+ ?" + ROMAN_OR_INT + "$", 0.8, "en",
                m -> new ParsedEvent.BestiaryFamilyTier(m.group(1).trim(), roman(m.group(3))));
        p.register("hotm_up", Channel.CHAT, "(?i)^(?:HOTM|HEART OF THE MOUNTAIN)(?: LEVEL UP| TIER UP)?.*?\\b(?:Tier |Level )?" + ROMAN_OR_INT + "$", 0.7, "en",
                m -> new ParsedEvent.HotmTier(roman(m.group(1).toUpperCase())));
        // ---- dungeon lifecycle / class / slayer quest / kuudra
        p.register("dungeon_start", Channel.CHAT, "^\\[NPC\\] Mort: Here, I found this map when I first entered the dungeon\\.", 0.9, "en", m -> new ParsedEvent.DungeonStart());
        p.register("class_selected", Channel.CHAT, "^You have selected the (Healer|Mage|Berserk|Archer|Tank) Dungeon Class!", 0.95, "en",
                m -> new ParsedEvent.ClassSelected(m.group(1)));
        p.register("run_summary", Channel.CHAT, "^> EXTRA STATS <$", 0.9, "en", m -> new ParsedEvent.RunSummary());
        p.register("slayer_started", Channel.CHAT, "^SLAYER QUEST STARTED!$", 0.9, "en", m -> new ParsedEvent.SlayerQuestStarted());
        p.register("slayer_requirement", Channel.CHAT, "^» Slay " + NUM + " Combat XP worth of (\\w+)\\.?$", 0.85, "en",
                m -> new ParsedEvent.SlayerRequirement(m.group(2), num(m.group(1))));
        p.register("slayer_failed", Channel.CHAT, "^(?:SLAYER QUEST FAILED!|Your Slayer Quest has been cancelled!)$", 0.9, "en", m -> new ParsedEvent.SlayerQuestFailed());
        p.register("dungeon_floor", Channel.CHAT, "^(Master Mode )?(?:The )?Catacombs - (?:Floor ([IVX]+)|(Entrance))$", 0.85, "en",
                m -> new ParsedEvent.DungeonFloorSummary(dev.skycore.data.Activity.floor(m.group(1) != null, m.group(2) == null ? 0 : roman(m.group(2)), m.group(3) != null)));
        p.register("kuudra_down", Channel.CHAT, "^KUUDRA DOWN!$", 0.7, "en", m -> new ParsedEvent.KuudraDown());
        return p;
    }

    public void register(String id, Channel channel, String regex, Function<Matcher, ParsedEvent> extract) {
        register(id, channel, regex, 0.9, "en", extract);
    }

    public void register(String id, Channel channel, String regex, double confidence, String language, Function<Matcher, ParsedEvent> extract) {
        rules.add(new Rule(id, channel, Pattern.compile(regex), extract, Math.max(0, Math.min(1, confidence)), language));
    }

    public List<Rule> rules() { return java.util.Collections.unmodifiableList(rules); }

    public static boolean isPlayerMessage(String line) { return PLAYER_CHAT.matcher(line).find(); }

    /**
     * Chat: the first matching rule wins (one line = one fact). Action bar: every match is returned, because
     * Hypixel can show several skill gains in one bar. Invalid extractions (e.g. level ≤ 0) are dropped.
     */
    public List<ParsedEvent> parse(String text, Channel channel) {
        List<ParsedEvent> out = new ArrayList<>();
        for (Recognized r : recognize(text, channel)) out.add(r.event());
        return out;
    }

    /** Like {@link #parse} but keeps the matching rule id and its confidence. */
    public List<Recognized> recognize(String text, Channel channel) {
        List<Recognized> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String line = channel == Channel.CHAT ? text.trim() : text;
        if (channel == Channel.CHAT && isPlayerMessage(line)) return out;
        for (Rule r : rules) {
            if (r.channel() != channel || disabled.contains(r.id())) continue;
            boolean user = r.id().startsWith("custom:");
            // user rules run against a step budget so a pathological regex can never freeze the game
            Matcher m = r.pattern().matcher(user ? new PatternSafety.Budgeted(line, PatternSafety.STEP_BUDGET) : line);
            try {
                while (m.find()) {
                    ParsedEvent e;
                    try { e = r.extract().apply(m); } catch (RuntimeException ex) { e = null; }
                    if (e != null && valid(e)) { out.add(new Recognized(e, r.id(), r.confidence())); hits.merge(r.id(), 1L, Long::sum); }
                    if (channel == Channel.CHAT) break;
                }
            } catch (PatternSafety.BudgetExceeded | StackOverflowError ex) {
                disabled.add(r.id());
                disabledReasons.put(r.id(), "disabled at runtime: too expensive to evaluate");
            }
            if (channel == Channel.CHAT && !out.isEmpty()) return out;
        }
        return out;
    }

    private final java.util.Set<String> disabled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, String> disabledReasons = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> hits = new java.util.concurrent.ConcurrentHashMap<>();

    /** Matches per rule id since the parser was built (for the pattern status panel). */
    public java.util.Map<String, Long> hits() { return java.util.Collections.unmodifiableMap(hits); }

    /** User rules switched off at runtime because they exceeded the evaluation budget. */
    public java.util.Map<String, String> disabledRules() { return java.util.Collections.unmodifiableMap(disabledReasons); }

    public long builtInCount() { return rules.stream().filter(r -> !r.id().startsWith("custom:")).count(); }

    private static boolean valid(ParsedEvent e) {
        return switch (e) {
            case ParsedEvent.SkillLevelUp s -> s.level() > 0;
            case ParsedEvent.CollectionLevelUp c -> c.level() > 0;
            case ParsedEvent.BestiaryMilestone b -> b.level() > 0;
            case ParsedEvent.BestiaryFamilyTier b -> b.tier() > 0;
            case ParsedEvent.HotmTier h -> h.tier() > 0 && h.tier() <= 10;
            case ParsedEvent.SkillProgress s -> s.need() > 0 && s.current() >= 0;
            case ParsedEvent.CatacombsXp c -> c.xp() > 0;
            case ParsedEvent.ClassXp c -> c.xp() > 0;
            default -> true;
        };
    }

    // ------------------------------------------------------------------ number helpers
    public static double num(String s) {
        s = s.replace(",", "").trim();
        if (s.isEmpty()) return 0;
        double mult = 1;
        char last = Character.toLowerCase(s.charAt(s.length() - 1));
        if (last == 'k') mult = 1e3;
        else if (last == 'm') mult = 1e6;
        else if (last == 'b') mult = 1e9;
        if (mult > 1) s = s.substring(0, s.length() - 1);
        return Double.parseDouble(s) * mult;
    }

    /**
     * Locale-tolerant number: "1,234.5" (English) and "1.234,5" (German) both → 1234.5. A separator followed by
     * groups of exactly three digits is a thousands separator ("12,500" / "12.500" → 12500).
     */
    public static double numLocalized(String v) {
        String s = v.replace(" ", "").replace(" ", "").trim();
        if (s.isEmpty()) return 0;
        char last = Character.toLowerCase(s.charAt(s.length() - 1));
        double mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : last == 'b' ? 1e9 : 1;
        if (mult > 1) s = s.substring(0, s.length() - 1);
        int dot = s.lastIndexOf('.'), comma = s.lastIndexOf(',');
        String normalized;
        if (dot >= 0 && comma >= 0) {
            normalized = comma > dot ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (comma >= 0) {
            normalized = s.matches("\\d{1,3}(,\\d{3})+") ? s.replace(",", "") : s.replace(',', '.');
        } else if (dot >= 0) {
            normalized = s.matches("\\d{1,3}(\\.\\d{3})+") ? s.replace(".", "") : s;
        } else normalized = s;
        return Double.parseDouble(normalized) * mult;
    }

    /** Roman (I…MMM) or arabic numeral → int; -1 if invalid. */
    public static int roman(String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; case 'C' -> 100; case 'D' -> 500; case 'M' -> 1000;
                default -> -1;
            };
            if (v < 0) return -1;
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }
}
