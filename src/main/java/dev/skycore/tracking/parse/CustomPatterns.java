package dev.skycore.tracking.parse;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-defined recognition rules (config/skycore/patterns.json). Lets new Hypixel formats – or translated
 * messages – be supported without changing code. Each pattern names an event {@code kind} and uses named
 * capture groups for the values, e.g.
 * <pre>
 * [{ "id": "de_cata", "language": "de", "channel": "chat", "kind": "catacombs_xp",
 *    "regex": "^\\+(?&lt;xp&gt;[\\d.,]+) Catacombs-Erfahrung", "confidence": 0.8 }]
 * </pre>
 * Every entry is validated before activation (JSON shape, known kind, required named groups, regex safety).
 * Invalid entries are skipped and reported, never crash the game. User rules are kept separate from the
 * built-in ones (ids are prefixed {@code custom:}).
 */
public final class CustomPatterns {
    public static class Entry {
        public String id;
        public String language = "en";
        public String channel = "chat";
        public String kind;
        public String regex;
        public double confidence = 0.75;
    }

    /** Per-entry validation outcome, shown in the pattern status panel. */
    public record EntryStatus(String id, String kind, String language, boolean active, String error) {}

    /**
     * @param jsonValid false = the file could not be read as a pattern list at all (the previous rules stay active)
     */
    public record Result(int loaded, List<String> errors, List<EntryStatus> entries, boolean jsonValid) {
        public Result(int loaded, List<String> errors) { this(loaded, errors, List.of(), true); }
    }

    public static final String[] KINDS = {"catacombs_xp", "class_xp", "skill_progress", "skill_level_up", "slayer_level", "slayer_done",
            "collection_level_up", "bestiary_milestone", "hotm_tier", "profile", "dungeon_start", "run_summary", "kuudra_down"};

    /** Named groups each kind needs to produce a usable event. */
    static final Map<String, List<String>> REQUIRED = Map.ofEntries(
            Map.entry("catacombs_xp", List.of("xp")),
            Map.entry("class_xp", List.of("class", "xp")),
            Map.entry("skill_progress", List.of("skill", "gain", "current", "need")),
            Map.entry("skill_level_up", List.of("skill", "level")),
            Map.entry("slayer_level", List.of("type", "level")),
            Map.entry("collection_level_up", List.of("name", "level")),
            Map.entry("bestiary_milestone", List.of("level")),
            Map.entry("hotm_tier", List.of("tier")),
            Map.entry("profile", List.of("name")));

    public static final int MAX_ENTRIES = 100;

    private CustomPatterns() {}

    /** Validate and register into {@code parser}. */
    public static Result load(MessageParser parser, String json) {
        List<String> errors = new ArrayList<>();
        List<EntryStatus> statuses = new ArrayList<>();
        if (json == null || json.isBlank()) return new Result(0, errors, statuses, true);
        List<Entry> entries;
        try {
            entries = new Gson().fromJson(json, new TypeToken<List<Entry>>() {}.getType());
        } catch (Exception e) {
            String m = e.getMessage() == null ? "?" : e.getMessage();
            errors.add("patterns.json is not valid JSON: " + (m.length() > 140 ? m.substring(0, 140) + "…" : m));
            return new Result(0, errors, statuses, false);
        }
        if (entries == null) return new Result(0, errors, statuses, true);
        int n = 0;
        Set<String> ids = new HashSet<>();
        for (Entry e : entries) {
            if (statuses.size() >= MAX_ENTRIES) { errors.add("more than " + MAX_ENTRIES + " patterns – the rest were skipped"); break; }
            String err = validate(e, ids);
            String id = e == null || e.id == null ? "?" : e.id;
            if (err != null) {
                errors.add(id + ": " + err);
                statuses.add(new EntryStatus(id, e == null ? null : e.kind, e == null ? null : e.language, false, err));
                continue;
            }
            MessageParser.Channel ch = "action_bar".equalsIgnoreCase(e.channel) ? MessageParser.Channel.ACTION_BAR : MessageParser.Channel.CHAT;
            String lang = e.language == null || e.language.isBlank() ? "en" : e.language.trim();
            parser.register("custom:" + e.id, ch, e.regex, e.confidence, lang, extractor(e.kind));
            ids.add(e.id);
            statuses.add(new EntryStatus(e.id, e.kind, lang, true, null));
            n++;
        }
        return new Result(n, errors, statuses, true);
    }

    /** @return null if the entry can be activated, otherwise why not. */
    static String validate(Entry e, Set<String> takenIds) {
        if (e == null || e.id == null || e.id.isBlank()) return "entry without id";
        if (!e.id.matches("[A-Za-z0-9_.-]{1,40}")) return "id may only use letters, digits, '_', '-', '.' (max 40)";
        if (takenIds.contains(e.id)) return "duplicate id";
        if (e.kind == null || extractor(e.kind) == null) return "unknown kind '" + e.kind + "'";
        if (e.regex == null) return "missing regex";
        if (e.channel != null && !e.channel.equalsIgnoreCase("chat") && !e.channel.equalsIgnoreCase("action_bar")) return "channel must be 'chat' or 'action_bar'";
        if (!(e.confidence >= 0 && e.confidence <= 1)) return "confidence must be between 0 and 1";
        if (e.language != null && e.language.length() > 8) return "language code too long";
        String unsafe = PatternSafety.check(e.regex);
        if (unsafe != null) return unsafe;
        Set<String> groups = Pattern.compile(e.regex).namedGroups().keySet();
        for (String g : REQUIRED.getOrDefault(e.kind, List.of()))
            if (!groups.contains(g)) return "kind '" + e.kind + "' needs a named group (?<" + g + ">…)";
        return null;
    }

    private static String g(Matcher m, String name) {
        try { return m.group(name); } catch (IllegalArgumentException e) { return null; }
    }

    private static double num(Matcher m, String name) {
        String v = g(m, name);
        return v == null ? -1 : MessageParser.numLocalized(v);
    }

    private static int lvl(Matcher m, String name) {
        String v = g(m, name);
        return v == null ? -1 : MessageParser.roman(v.trim().toUpperCase());
    }

    static Function<Matcher, ParsedEvent> extractor(String kind) {
        return switch (kind) {
            case "catacombs_xp" -> m -> new ParsedEvent.CatacombsXp(num(m, "xp"));
            case "class_xp" -> m -> new ParsedEvent.ClassXp(g(m, "class"), num(m, "xp"), g(m, "team") != null);
            case "skill_progress" -> m -> new ParsedEvent.SkillProgress(g(m, "skill"), num(m, "gain"), num(m, "current"), num(m, "need"));
            case "skill_level_up" -> m -> new ParsedEvent.SkillLevelUp(g(m, "skill"), lvl(m, "level"));
            case "slayer_level" -> m -> new ParsedEvent.SlayerLevel(g(m, "type"), lvl(m, "level"), g(m, "remaining") == null ? -1 : num(m, "remaining"));
            case "slayer_done" -> m -> new ParsedEvent.SlayerQuestComplete();
            case "collection_level_up" -> m -> new ParsedEvent.CollectionLevelUp(g(m, "name"), lvl(m, "level"));
            case "bestiary_milestone" -> m -> new ParsedEvent.BestiaryMilestone(lvl(m, "level"));
            case "hotm_tier" -> m -> new ParsedEvent.HotmTier(lvl(m, "tier"));
            case "profile" -> m -> new ParsedEvent.ProfileSwitch(g(m, "name"));
            case "dungeon_start" -> m -> new ParsedEvent.DungeonStart();
            case "run_summary" -> m -> new ParsedEvent.RunSummary();
            case "kuudra_down" -> m -> new ParsedEvent.KuudraDown();
            default -> null;
        };
    }
}
