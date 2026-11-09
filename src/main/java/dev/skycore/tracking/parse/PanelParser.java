package dev.skycore.tracking.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the passive information panels Hypixel always sends: the sidebar (scoreboard) and the tab list.
 * Pure – takes already colour-stripped lines, returns what it recognised.
 */
public final class PanelParser {
    private PanelParser() {}

    /** Sidebar state. Fields are null / -1 when not shown. */
    /** @param slayerPhase "combat" (gathering XP), "boss" (boss spawned), "slain" (boss killed) or null */
    public record Sidebar(boolean skyblock, String area, String floor, String slayerBoss, String slayerTier,
                          String dungeonClass, String kuudraTier, List<ParsedEvent> values, String slayerPhase) {}

    private static final Pattern PHASE_COMBAT = Pattern.compile("\\([\\d,.kKmM]+/[\\d,.kKmM]+\\) Combat XP");

    private static final Pattern FLOOR = Pattern.compile("The Catacombs \\((F\\d|M\\d|E)\\)");
    private static final Pattern AREA = Pattern.compile("[⏣ф]\\s*(.+)$");
    private static final Pattern SLAYER = Pattern.compile("^(Revenant Horror|Tarantula Broodfather|Sven Packmaster|Voidgloom Seraph|Inferno Demonlord|Riftstalker Bloodfiend) ([IVX]+)$");
    private static final Pattern KUUDRA = Pattern.compile("Kuudra's Hollow \\(T(\\d)\\)");
    private static final Pattern POWDER = Pattern.compile("^\\W*(Mithril|Gemstone|Glacite)(?: Powder)?: ([\\d,]+)");
    private static final Pattern MOTES = Pattern.compile("^\\W*Motes: ([\\d,]+)");
    private static final Pattern COPPER = Pattern.compile("^\\W*Copper: ([\\d,]+)");
    private static final Pattern BITS = Pattern.compile("^\\W*Bits: ([\\d,]+)");
    private static final Pattern GARDEN_LEVEL = Pattern.compile("^\\W*Garden Level: ([IVXLC]+|\\d+)");
    private static final Pattern ENIGMA = Pattern.compile("^\\W*Enigma Souls: ([\\d,]+)\\s*/\\s*([\\d,]+)");
    private static final Pattern PROFILE = Pattern.compile("^\\s*Profile: (\\w+)");
    private static final Pattern SKILL = Pattern.compile("^\\s*(Farming|Mining|Combat|Foraging|Fishing|Enchanting|Alchemy|Taming|Carpentry|Hunting|Runecrafting|Social) (\\d+): (MAX|[\\d.]+%)");
    private static final Pattern AREA_TAB = Pattern.compile("^\\s*Area: (.+)$");

    /**
     * @param title      sidebar title (e.g. "SKYBLOCK")
     * @param playerName own name – used to find "[M] Name" (own dungeon class) in the party list
     */
    public static Sidebar sidebar(String title, List<String> lines, String playerName) {
        boolean sky = title != null && title.toUpperCase().contains("SKYBLOCK");
        String area = null, floor = null, boss = null, tier = null, cls = null, kuudra = null, phase = null;
        List<ParsedEvent> values = new ArrayList<>();
        Pattern own = playerName == null ? null : Pattern.compile("\\[([MHBAT])\\] " + Pattern.quote(playerName) + "\\b");
        for (String raw : lines) {
            String line = raw.trim();
            Matcher m;
            if ((m = FLOOR.matcher(line)).find()) floor = m.group(1);
            if ((m = AREA.matcher(line)).find()) area = m.group(1).trim();
            if ((m = SLAYER.matcher(line)).find()) { boss = m.group(1); tier = m.group(2); }
            if ((m = KUUDRA.matcher(line)).find()) kuudra = "T" + m.group(1);
            if (line.equals("Slay the boss!")) phase = "boss";
            else if (line.equals("Boss slain!")) phase = "slain";
            else if (PHASE_COMBAT.matcher(line).find()) phase = "combat";
            if (own != null && (m = own.matcher(line)).find()) cls = switch (m.group(1)) {
                case "M" -> "Mage"; case "H" -> "Healer"; case "B" -> "Berserk"; case "A" -> "Archer"; default -> "Tank";
            };
            values(line, values);
        }
        return new Sidebar(sky, area, floor, boss, tier, cls, kuudra, values, boss == null ? null : phase);
    }

    /** Tab-list entries: profile name, area, skill levels/percentages, powder and other values. */
    public static List<ParsedEvent> tab(List<String> lines) {
        List<ParsedEvent> out = new ArrayList<>();
        for (String raw : lines) {
            if (raw == null) continue;
            Matcher m;
            if ((m = PROFILE.matcher(raw)).find()) { out.add(new ParsedEvent.ProfileSwitch(m.group(1))); continue; }
            if ((m = SKILL.matcher(raw)).find()) {
                String p = m.group(3);
                out.add(new ParsedEvent.SkillLevelPercent(m.group(1), Integer.parseInt(m.group(2)), p.equals("MAX") ? -1 : Double.parseDouble(p.replace("%", ""))));
                continue;
            }
            if ((m = AREA_TAB.matcher(raw)).find()) { out.add(new ParsedEvent.ValueReading("area:" + m.group(1).trim(), 0)); continue; }
            values(raw.trim(), out);
        }
        return out;
    }

    private static void values(String line, List<ParsedEvent> out) {
        Matcher m;
        if ((m = POWDER.matcher(line)).find()) out.add(new ParsedEvent.ValueReading("powder_" + m.group(1).toLowerCase(), MessageParser.num(m.group(2))));
        else if ((m = MOTES.matcher(line)).find()) out.add(new ParsedEvent.ValueReading("rift_motes", MessageParser.num(m.group(1))));
        else if ((m = COPPER.matcher(line)).find()) out.add(new ParsedEvent.ValueReading("garden_copper", MessageParser.num(m.group(1))));
        else if ((m = BITS.matcher(line)).find()) out.add(new ParsedEvent.ValueReading("bits", MessageParser.num(m.group(1))));
        else if ((m = GARDEN_LEVEL.matcher(line)).find()) { int lv = MessageParser.roman(m.group(1)); if (lv > 0) out.add(new ParsedEvent.ValueReading("garden_level", lv)); }
        else if ((m = ENIGMA.matcher(line)).find()) {
            out.add(new ParsedEvent.ValueReading("rift_enigma_souls", MessageParser.num(m.group(1))));
            out.add(new ParsedEvent.ValueReading("rift_enigma_total", MessageParser.num(m.group(2))));
        }
    }
}
