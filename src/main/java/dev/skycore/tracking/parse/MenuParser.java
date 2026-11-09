package dev.skycore.tracking.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads exact progress from SkyBlock menu items (colour codes already stripped). Typical lore:
 * <pre>
 *   Mining XXV
 *   Progress to Level XXVI: 45.3%
 *   ------------------ 1,234,567/3,100,000
 * </pre>
 * Works for skills, Catacombs/classes, collections ("Progress to Wheat XI: 12%"), bestiary milestones and HOTM
 * tiers, as long as the "Progress to …" line is present. Items without it are ignored.
 */
public final class MenuParser {
    private static final Pattern PROGRESS = Pattern.compile("Progress to (?:Level |Tier |Milestone )?(.*?)\\s*([IVXLCDM]+|\\d+): ([\\d.]+)%");
    private static final Pattern FRACTION = Pattern.compile("([\\d,]+(?:\\.\\d+)?[kKmMbB]?)\\s*/\\s*([\\d,]+(?:\\.\\d+)?[kKmMbB]?)");
    private static final Pattern TRAILING_LEVEL = Pattern.compile("^(.*?)\\s+([IVXLCDM]+|\\d+)$");
    // values shown in specific menus the player opens (unverified formats: matched strictly, otherwise ignored)
    private static final Pattern MUSEUM_DONATED = Pattern.compile("^(?:Items )?Donated: ([\\d,]+)\\s*/\\s*([\\d,]+)");
    private static final Pattern FAME = Pattern.compile("^(?:Your )?Fame: ([\\d,]+)");
    private static final Pattern KUUDRA_TIER_COMPLETIONS = Pattern.compile("^(Basic|Hot|Burning|Fiery|Infernal)(?: Tier)? Completions: ([\\d,]+)");
    private static final Pattern COMPLETIONS = Pattern.compile("^(?:Total )?Completions: ([\\d,]+)");
    private static final Pattern MAYOR_ITEM = Pattern.compile("^(Mayor|Minister) ([A-Z][\\w ]{1,20})$");
    private static final Pattern SLAYER_BONUS = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)% (?:more|extra|bonus) (?:(Revenant|Tarantula|Sven|Voidgloom|Inferno|Riftstalker|Zombie|Spider|Wolf|Enderman|Blaze|Vampire) )?Slayer (?:XP|Experience)");
    private static final Pattern PERK_NAME = Pattern.compile("^[A-Z][A-Za-z'!&\\- ]{2,30}$");
    private static final Pattern TIER_KILLS = Pattern.compile("(?i)^(?:Tier\\s+)?([IVX]+|\\d)\\s*(?:Boss\\s+)?Kills?:\\s*([\\d,]+)$");
    private static final Pattern FLOOR_ITEM = Pattern.compile("^(Master Mode )?(?:The Catacombs - |Catacombs - )?(?:Floor ([IVX]+)|(Entrance))$");
    private static final Pattern COMPLETED = Pattern.compile("(?i)^(?:Times )?Complet(?:ed|ions):?\\s*([\\d,]+)$");
    private static final Pattern KUUDRA_TIER_NAME = Pattern.compile("^(?:Kuudra )?(Basic|Hot|Burning|Fiery|Infernal)(?: Tier)?$");

    private MenuParser() {}

    public record Item(String name, List<String> lore) {}

    public static List<ParsedEvent> parse(String containerTitle, List<Item> items) {
        List<ParsedEvent> out = new ArrayList<>();
        String title = containerTitle == null ? "" : containerTitle.toLowerCase();
        boolean collectionMenu = title.contains("collection");
        String menu = collectionMenu ? "collection" : title.contains("bestiary") ? "bestiary" : title.contains("garden") || title.contains("desk") ? "garden" : "";
        counters(title, items, out);
        for (Item it : items) {
            if (it.name() == null || it.lore() == null || it.lore().isEmpty()) continue;
            ParsedEvent e = parseItem(it, collectionMenu);
            if (e instanceof ParsedEvent.MenuProgress m && !menu.equals(m.menu()))
                e = new ParsedEvent.MenuProgress(m.name(), m.nextLevel(), m.current(), m.need(), m.percent(), m.collectionMenu(), menu);
            if (e != null) out.add(e);
            values(title, it, out);
            ParsedEvent.MayorInfo mi = mayor(title, it);
            if (mi != null) out.add(mi);
        }
        return out;
    }

    /**
     * "Mayor Aatrox" / "Minister Foxy" items (Calendar, mayor and election menus). Election <i>candidates</i> show
     * vote counts and are ignored – only the mayor/minister in office is recorded. Perk names are lines followed by a
     * description; a Slayer XP bonus is only taken from a description that states its size ("25% more Slayer XP").
     */
    static ParsedEvent.MayorInfo mayor(String title, Item it) {
        if (!(title.contains("calendar") || title.contains("mayor") || title.contains("election") || title.contains("minister"))) return null;
        Matcher m = MAYOR_ITEM.matcher(it.name().trim());
        if (!m.find()) return null;
        for (String l : it.lore()) if (l.toLowerCase().contains("vote")) return null;
        List<String> perks = new ArrayList<>();
        double pct = -1;
        String slayerPerk = null;
        List<String> scope = new ArrayList<>();
        List<String> lines = it.lore().stream().map(String::trim).toList();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            boolean nextIsDescription = i + 1 < lines.size() && !lines.get(i + 1).isEmpty() && !PERK_NAME.matcher(lines.get(i + 1)).matches();
            if (PERK_NAME.matcher(l).matches() && nextIsDescription && !l.equalsIgnoreCase("Perks") && !l.startsWith("Mayor") && !l.startsWith("Minister"))
                perks.add(l);
            Matcher b = SLAYER_BONUS.matcher(l);
            if (b.find()) {
                pct = Double.parseDouble(b.group(1));
                slayerPerk = perks.isEmpty() ? "?" : perks.get(perks.size() - 1);
                if (b.group(2) != null) scope.add(slayerId(b.group(2)));
            }
        }
        return new ParsedEvent.MayorInfo(m.group(1).toLowerCase(), m.group(2).trim(), perks, pct, slayerPerk, scope);
    }

    /**
     * Counters used for later reconciliation: boss kills per tier in slayer menus (item named after the boss,
     * lore "Tier IV Kills: 123") and completions per floor in dungeon menus (item "Floor VI" / "Master Mode Floor III",
     * lore "Completions: 42"). Unverified formats – matched strictly, otherwise nothing is produced.
     */
    static void counters(String title, List<Item> items, List<ParsedEvent> out) {
        if (title.contains("slayer")) {
            for (Item it : items) {
                if (it.name() == null || it.lore() == null) continue;
                dev.skycore.data.Activity.Slayer s = null;
                for (dev.skycore.data.Activity.Slayer x : dev.skycore.data.Activity.Slayer.values()) if (it.name().contains(x.boss)) s = x;
                if (s == null) continue;
                java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
                for (String raw : it.lore()) {
                    Matcher m = TIER_KILLS.matcher(raw.trim());
                    if (!m.find()) continue;
                    int tier = MessageParser.roman(m.group(1).toUpperCase());
                    if (tier >= 1 && tier <= s.maxTier) counts.put(s.key(tier), (long) MessageParser.num(m.group(2)));
                }
                if (!counts.isEmpty()) out.add(new ParsedEvent.SlayerKillCounts(s.id, counts));
            }
        }
        if (title.contains("catacombs") || title.contains("dungeon") || title.contains("master mode")) {
            java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
            for (Item it : items) {
                if (it.name() == null || it.lore() == null) continue;
                Matcher f = FLOOR_ITEM.matcher(it.name().trim());
                if (!f.find()) continue;
                String floor = dev.skycore.data.Activity.floor(f.group(1) != null, f.group(2) == null ? 0 : MessageParser.roman(f.group(2)), f.group(3) != null);
                if (floor == null) continue;
                for (String raw : it.lore()) {
                    Matcher m = COMPLETED.matcher(raw.trim());
                    if (m.find()) { counts.put(floor, (long) MessageParser.num(m.group(1))); break; }
                }
            }
            if (!counts.isEmpty()) out.add(new ParsedEvent.FloorCompletionCounts(counts));
        }
    }

    private static String slayerId(String word) {
        return switch (word.toLowerCase()) {
            case "revenant", "zombie" -> "zombie";
            case "tarantula", "spider" -> "spider";
            case "sven", "wolf" -> "wolf";
            case "voidgloom", "enderman" -> "enderman";
            case "inferno", "blaze" -> "blaze";
            default -> "vampire";
        };
    }

    /** Level-less values from menus: Museum donations, Community Center fame, Kuudra completions per tier. */
    static void values(String title, Item it, List<ParsedEvent> out) {
        boolean museum = title.contains("museum"), community = title.contains("community") || title.contains("fame"), kuudra = title.contains("kuudra");
        if (!museum && !community && !kuudra) return;
        Matcher tierName = KUUDRA_TIER_NAME.matcher(it.name().trim());
        String itemTier = kuudra && tierName.find() ? tierName.group(1).toLowerCase() : null;
        for (String raw : it.lore()) {
            String line = raw.trim();
            Matcher m;
            if (museum && (m = MUSEUM_DONATED.matcher(line)).find()) {
                out.add(new ParsedEvent.ValueReading("museum_donated", MessageParser.num(m.group(1))));
                out.add(new ParsedEvent.ValueReading("museum_total", MessageParser.num(m.group(2))));
            } else if (community && (m = FAME.matcher(line)).find()) {
                out.add(new ParsedEvent.ValueReading("community_fame", MessageParser.num(m.group(1))));
            } else if (kuudra && (m = KUUDRA_TIER_COMPLETIONS.matcher(line)).find()) {
                out.add(new ParsedEvent.ValueReading("kuudra_completions_" + m.group(1).toLowerCase(), MessageParser.num(m.group(2))));
            } else if (itemTier != null && (m = COMPLETIONS.matcher(line)).find()) {
                out.add(new ParsedEvent.ValueReading("kuudra_completions_" + itemTier, MessageParser.num(m.group(1))));
            }
        }
    }

    static ParsedEvent parseItem(Item it, boolean collectionMenu) {
        for (int i = 0; i < it.lore().size(); i++) {
            Matcher m = PROGRESS.matcher(it.lore().get(i));
            if (!m.find()) continue;
            int next = MessageParser.roman(m.group(2));
            if (next <= 0) return null;
            double pct = Double.parseDouble(m.group(3));
            double cur = -1, need = -1;
            // the fraction is on the same line or within the next two lines
            for (int j = i; j < Math.min(it.lore().size(), i + 3); j++) {
                Matcher f = FRACTION.matcher(it.lore().get(j).replace(" ", ""));
                if (f.find()) {
                    cur = MessageParser.num(f.group(1));
                    need = MessageParser.num(f.group(2));
                    break;
                }
            }
            return new ParsedEvent.MenuProgress(baseName(it.name()), next, cur, need, pct, collectionMenu);
        }
        return null;
    }

    /** "Mining XXV" → "Mining", "Catacombs Level 23" → "Catacombs". */
    static String baseName(String name) {
        String n = name.trim().replaceAll("\\s+Level$", "");
        Matcher m = TRAILING_LEVEL.matcher(n);
        if (m.find()) n = m.group(1).replaceAll("\\s+Level$", "").trim();
        return n;
    }
}
