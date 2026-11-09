package dev.skycore.tracking.parse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {
    private final MessageParser p = MessageParser.defaults();

    private ParsedEvent one(String text, MessageParser.Channel ch) {
        List<ParsedEvent> l = p.parse(text, ch);
        assertEquals(1, l.size(), "expected exactly one event for: " + text + " got " + l);
        return l.get(0);
    }

    @Test
    void skillActionBarAbsolute() {
        var e = (ParsedEvent.SkillProgress) one("100/100❤   +12.5 Mining (1,234.5/3,100,000)   50/50✎ Mana", MessageParser.Channel.ACTION_BAR);
        assertEquals("Mining", e.skill());
        assertEquals(12.5, e.gain(), 1e-9);
        assertEquals(1234.5, e.current(), 1e-9);
        assertEquals(3_100_000, e.need(), 1e-9);
    }

    @Test
    void skillActionBarSuffixAndPercent() {
        var a = (ParsedEvent.SkillProgress) one("+3 Farming (450/3.1M)", MessageParser.Channel.ACTION_BAR);
        assertEquals(3_100_000, a.need(), 1e-6);
        var b = (ParsedEvent.SkillPercent) one("+8.2 Combat (45.3%)", MessageParser.Channel.ACTION_BAR);
        assertEquals(45.3, b.percent(), 1e-9);
    }

    @Test
    void dungeonEndOfRun() {
        var c = (ParsedEvent.CatacombsXp) one("     +4,213.6 Catacombs Experience", MessageParser.Channel.CHAT);
        assertEquals(4213.6, c.xp(), 1e-9);
        var k = (ParsedEvent.ClassXp) one("  +3,100 Mage Experience (Team Bonus)", MessageParser.Channel.CHAT);
        assertEquals("Mage", k.dungeonClass());
        assertTrue(k.teamBonus());
        var s = (ParsedEvent.TeamScore) one("Team Score: 302 (S+)", MessageParser.Channel.CHAT);
        assertEquals("S+", s.rank());
        assertEquals(302, s.score());
    }

    @Test
    void slayer() {
        assertInstanceOf(ParsedEvent.SlayerQuestComplete.class, one("  SLAYER QUEST COMPLETE!", MessageParser.Channel.CHAT));
        var l = (ParsedEvent.SlayerLevel) one("   Zombie Slayer LVL 7 - Next LVL in 28,750 XP!", MessageParser.Channel.CHAT);
        assertEquals(7, l.level());
        assertEquals(28_750, l.remaining(), 1e-9);
        var m = (ParsedEvent.SlayerLevel) one("Wolf Slayer LVL 9 - LVL MAXED OUT!", MessageParser.Channel.CHAT);
        assertEquals(-1, m.remaining(), 1e-9);
    }

    @Test
    void levelUpsRomanAndArabic() {
        assertEquals(58, ((ParsedEvent.SkillLevelUp) one("SKILL LEVEL UP Mining LVII ➜ LVIII", MessageParser.Channel.CHAT)).level());
        assertEquals(12, ((ParsedEvent.SkillLevelUp) one("SKILL LEVEL UP Farming 12", MessageParser.Channel.CHAT)).level());
        var c = (ParsedEvent.CollectionLevelUp) one("COLLECTION LEVEL UP Enchanted Wheat X ➜ XI", MessageParser.Channel.CHAT);
        assertEquals("Enchanted Wheat", c.name());
        assertEquals(11, c.level());
        assertEquals(30, ((ParsedEvent.BestiaryMilestone) one("BESTIARY MILESTONE 30", MessageParser.Channel.CHAT)).level());
        var f = (ParsedEvent.BestiaryFamilyTier) one("BESTIARY Zombie III ➜ IV", MessageParser.Channel.CHAT);
        assertEquals(4, f.tier());
        assertEquals(7, ((ParsedEvent.HotmTier) one("HOTM LEVEL UP Heart of the Mountain VII", MessageParser.Channel.CHAT)).tier());
        assertEquals("Apple", ((ParsedEvent.ProfileSwitch) one("You are playing on profile: Apple", MessageParser.Channel.CHAT)).name());
    }

    @Test
    void playerChatCannotFakeProgress() {
        assertTrue(p.parse("[MVP+] Bob: +500,000 Catacombs Experience", MessageParser.Channel.CHAT).isEmpty());
        assertTrue(p.parse("[312] [VIP] Steve: SLAYER QUEST COMPLETE!", MessageParser.Channel.CHAT).isEmpty());
        assertTrue(p.parse("Party > [MVP++] Alice: Team Score: 300 (S+)", MessageParser.Channel.CHAT).isEmpty());
        assertTrue(p.parse("Guild > Bob: COLLECTION LEVEL UP Wheat XX", MessageParser.Channel.CHAT).isEmpty());
        assertTrue(p.parse("From [MVP+] Bob: BESTIARY MILESTONE 99", MessageParser.Channel.CHAT).isEmpty());
    }

    @Test
    void unrelatedLinesIgnored() {
        assertTrue(p.parse("Welcome to Hypixel SkyBlock!", MessageParser.Channel.CHAT).isEmpty());
        assertTrue(p.parse("You earned 5 Catacombs tokens", MessageParser.Channel.CHAT).isEmpty());
        assertTrue(p.parse("100/100❤   50/50✎ Mana", MessageParser.Channel.ACTION_BAR).isEmpty());
        assertTrue(p.parse("SKILL LEVEL UP Mining 0", MessageParser.Channel.CHAT).isEmpty());
    }

    @Test
    void menuLore() {
        var items = List.of(
                new MenuParser.Item("Mining XXV", List.of("Increase your mining skill", "", "Progress to Level XXVI: 45.3%", "-------------- 1,234,567/3,100,000")),
                new MenuParser.Item("Wheat XI", List.of("Progress to Wheat XII: 12.5%", "12,500/100k")),
                new MenuParser.Item("Bestiary Milestone 24", List.of("Progress to Milestone 25: 40%", "4/10")),
                new MenuParser.Item("Close", List.of()),
                new MenuParser.Item("Combat L", List.of("MAX LEVEL")));
        List<ParsedEvent> ev = MenuParser.parse("Your Skills", items);
        assertEquals(3, ev.size());
        var m = (ParsedEvent.MenuProgress) ev.get(0);
        assertEquals("Mining", m.name());
        assertEquals(26, m.nextLevel());
        assertEquals(1_234_567, m.current(), 1e-9);
        assertEquals(3_100_000, m.need(), 1e-9);
        var w = (ParsedEvent.MenuProgress) ev.get(1);
        assertEquals("Wheat", w.name());
        assertEquals(100_000, w.need(), 1e-9);
        var b = (ParsedEvent.MenuProgress) ev.get(2);
        assertEquals("Bestiary Milestone", b.name());
        assertEquals(25, b.nextLevel());
    }

    @Test
    void dedupeWindow() {
        Dedupe d = new Dedupe(3000);
        assertFalse(d.duplicate("cata:4213", 1000));
        assertTrue(d.duplicate("cata:4213", 2500));
        assertFalse(d.duplicate("cata:4213", 9000));
    }
}
