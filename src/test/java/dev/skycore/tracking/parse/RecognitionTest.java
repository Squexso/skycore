package dev.skycore.tracking.parse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecognitionTest {
    private final MessageParser p = MessageParser.defaults();

    @Test
    void everyBuiltInRuleHasAConfidenceAndLanguage() {
        for (MessageParser.Rule r : p.rules()) {
            assertTrue(r.confidence() > 0 && r.confidence() <= 1, r.id());
            assertNotNull(r.language(), r.id());
        }
    }

    @Test
    void recognizeReportsRuleAndConfidence() {
        var r = p.recognize("+10,000 Catacombs Experience", MessageParser.Channel.CHAT);
        assertEquals(1, r.size());
        assertEquals("cata_xp", r.get(0).rule());
        assertEquals(10_000, ((ParsedEvent.CatacombsXp) r.get(0).event()).xp(), 1e-9, "exactly the XP shown, nothing estimated");
        assertTrue(r.get(0).confidence() >= 0.85);
    }

    @Test
    void dungeonLifecycleAndClassAndKuudra() {
        assertInstanceOf(ParsedEvent.DungeonStart.class, p.parse("[NPC] Mort: Here, I found this map when I first entered the dungeon.", MessageParser.Channel.CHAT).get(0));
        assertEquals("Mage", ((ParsedEvent.ClassSelected) p.parse("You have selected the Mage Dungeon Class!", MessageParser.Channel.CHAT).get(0)).dungeonClass());
        assertInstanceOf(ParsedEvent.RunSummary.class, p.parse("              > EXTRA STATS <", MessageParser.Channel.CHAT).get(0));
        assertInstanceOf(ParsedEvent.SlayerQuestStarted.class, p.parse("  SLAYER QUEST STARTED!", MessageParser.Channel.CHAT).get(0));
        assertInstanceOf(ParsedEvent.KuudraDown.class, p.parse("KUUDRA DOWN!", MessageParser.Channel.CHAT).get(0));
    }

    @Test
    void colourCodesAreStrippedBeforeParsing() {
        // the tracker strips §-codes with Minecraft's StringUtil; the same text without codes must match
        String stripped = "§a+§b4,213 §3Catacombs Experience".replaceAll("§.", "");
        assertEquals(4213, ((ParsedEvent.CatacombsXp) p.parse(stripped, MessageParser.Channel.CHAT).get(0)).xp(), 1e-9);
    }

    @Test
    void localizedNumbers() {
        assertEquals(1234.5, MessageParser.numLocalized("1,234.5"), 1e-9);
        assertEquals(1234.5, MessageParser.numLocalized("1.234,5"), 1e-9);
        assertEquals(12500, MessageParser.numLocalized("12.500"), 1e-9);
        assertEquals(12500, MessageParser.numLocalized("12,500"), 1e-9);
        assertEquals(2.5, MessageParser.numLocalized("2,5"), 1e-9);
        assertEquals(1_500_000, MessageParser.numLocalized("1,5M"), 1e-6);
    }

    @Test
    void customPatternsIncludingGerman() {
        MessageParser parser = MessageParser.defaults();
        String json = """
                [
                  {"id":"de_cata","language":"de","channel":"chat","kind":"catacombs_xp",
                   "regex":"^\\\\+(?<xp>[\\\\d.,]+) Catacombs-Erfahrung","confidence":0.8},
                  {"id":"de_skill","language":"de","channel":"chat","kind":"skill_level_up",
                   "regex":"^FERTIGKEIT AUFGESTIEGEN (?<skill>\\\\w+) (?<level>[IVXLC\\\\d]+)$"},
                  {"id":"broken","kind":"catacombs_xp","regex":"(unclosed"},
                  {"id":"nokind","kind":"does_not_exist","regex":"x"}
                ]""";
        CustomPatterns.Result res = CustomPatterns.load(parser, json);
        assertEquals(2, res.loaded());
        assertEquals(2, res.errors().size(), res.errors().toString());
        var r = parser.recognize("+10.000 Catacombs-Erfahrung", MessageParser.Channel.CHAT);
        assertEquals("custom:de_cata", r.get(0).rule());
        assertEquals(10_000, ((ParsedEvent.CatacombsXp) r.get(0).event()).xp(), 1e-9);
        assertEquals(0.8, r.get(0).confidence(), 1e-9);
        assertEquals(12, ((ParsedEvent.SkillLevelUp) parser.parse("FERTIGKEIT AUFGESTIEGEN Mining XII", MessageParser.Channel.CHAT).get(0)).level());
        // player chat is still rejected for custom rules
        assertTrue(parser.parse("[MVP+] Bob: +10.000 Catacombs-Erfahrung", MessageParser.Channel.CHAT).isEmpty());
        assertEquals(0, CustomPatterns.load(MessageParser.defaults(), "not json").loaded());
    }

    @Test
    void sidebarAndTabList() {
        var sb = PanelParser.sidebar("SKYBLOCK", List.of(
                " ⏣ The Catacombs (F7)", "Time Elapsed: 04m 12s", "[M] Steve 1,234❤", "[H] Alex 900❤",
                "Slayer Quest", "Revenant Horror IV", "᠅ Mithril: 123,456", "Motes: 2,500"), "Steve");
        assertTrue(sb.skyblock());
        assertEquals("F7", sb.floor());
        assertEquals("Mage", sb.dungeonClass(), "own class from [M] Steve, not the teammate");
        assertEquals("Revenant Horror", sb.slayerBoss());
        assertEquals("IV", sb.slayerTier());
        assertEquals(2, sb.values().size());
        assertFalse(PanelParser.sidebar("HYPIXEL", List.of(), "Steve").skyblock());

        var tab = PanelParser.tab(List.of("Profile: Banana", " Mining 57: 45.3%", " Farming 60: MAX", " Gemstone: 9,876", "Area: Dwarven Mines", "random line"));
        assertEquals("Banana", ((ParsedEvent.ProfileSwitch) tab.get(0)).name());
        var mining = (ParsedEvent.SkillLevelPercent) tab.get(1);
        assertEquals(57, mining.level());
        assertEquals(45.3, mining.percent(), 1e-9);
        assertEquals(-1, ((ParsedEvent.SkillLevelPercent) tab.get(2)).percent(), 1e-9);
        assertEquals("powder_gemstone", ((ParsedEvent.ValueReading) tab.get(3)).key());
        assertEquals(5, tab.size());
    }
}
