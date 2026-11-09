package dev.skycore.data;

import dev.skycore.data.Activity.MayorSnapshot;
import dev.skycore.data.Activity.Slayer;
import dev.skycore.data.Activity.XpCalc;
import dev.skycore.tracking.parse.MenuParser;
import dev.skycore.tracking.parse.ParsedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MayorBonusTest {
    private static final long T = Mayors.termStart(1_790_000_000_000L) + 10 * Mayors.DAY; // inside one term

    @BeforeEach
    void setUp() { Rewards.load(null); }

    private static Mayors.Observation obs(long t, String role, String name, double pct, String perk, String... perks) {
        Mayors.Observation o = new Mayors.Observation();
        o.t = t;
        o.role = role;
        o.name = name;
        o.slayerXpPercent = pct;
        o.slayerPerk = perk;
        o.perks.addAll(List.of(perks));
        o.source = "menu";
        return o;
    }

    private static MayorSnapshot aatroxWithBuff() {
        List<Mayors.Observation> l = new ArrayList<>();
        Mayors.observe(l, obs(T, "mayor", "Aatrox", 25, "Slayer XP Buff", "Slashed Pricing", "Slayer XP Buff"));
        return Mayors.at(l, T + 3600_000L);
    }

    @Test
    void aatroxWithTheSlayerPerk() {
        MayorSnapshot m = aatroxWithBuff();
        XpCalc t1 = Rewards.slayer(Slayer.ENDERMAN, 1, m, -1);
        XpCalc t4 = Rewards.slayer(Slayer.ENDERMAN, 4, m, 625);
        assertEquals(5, t1.base, 1e-9);
        assertEquals(6.25, t1.expected, 1e-9, "T1 uses the T1 base reward");
        assertEquals(625, t4.expected, 1e-9);
        assertTrue(t4.complete);
        assertTrue(t4.bonuses.get(0).applied);
        assertEquals("Slayer XP Buff", t4.bonuses.get(0).perk);
        assertEquals("observed", t4.kind);
        assertNull(t4.note, "observed equals expected – no discrepancy");
    }

    @Test
    void aatroxWithoutTheSlayerPerk() {
        List<Mayors.Observation> l = new ArrayList<>();
        Mayors.observe(l, obs(T, "mayor", "Aatrox", -1, null, "Slashed Pricing", "Pathfinder"));
        MayorSnapshot onlyMayor = Mayors.at(l, T);
        XpCalc c = Rewards.slayer(Slayer.ENDERMAN, 4, onlyMayor, -1);
        assertEquals(500, c.expected, 1e-9, "no bonus without the perk");
        assertFalse(c.complete, "but the minister could still hold a Slayer XP perk");
        assertEquals("calc.bonus.minister_unknown", c.bonuses.get(0).reason);
        Mayors.observe(l, obs(T + 1000, "minister", "Paul", -1, null, "EZPZ"));
        XpCalc known = Rewards.slayer(Slayer.ENDERMAN, 4, Mayors.at(l, T), -1);
        assertEquals(500, known.expected, 1e-9);
        assertTrue(known.complete);
        assertFalse(known.bonuses.get(0).applied);
        assertEquals("calculated", known.kind);
    }

    @Test
    void mayorUnknownOrEffectUnknownNeverInventsABonus() {
        XpCalc none = Rewards.slayer(Slayer.ENDERMAN, 4, null, -1);
        assertEquals(500, none.expected, 1e-9);
        assertFalse(none.complete);
        assertEquals("estimated", none.kind);
        List<Mayors.Observation> l = new ArrayList<>();
        Mayors.observe(l, obs(T, "mayor", "Aatrox", -1, "Slayer XP Buff", "Slayer XP Buff")); // perk seen, size not shown
        XpCalc unknownSize = Rewards.slayer(Slayer.ENDERMAN, 4, Mayors.at(l, T), -1);
        assertEquals(500, unknownSize.expected, 1e-9, "no multiplier is invented");
        assertFalse(unknownSize.complete);
        assertEquals("calc.bonus.effect_unknown", unknownSize.bonuses.get(0).reason);
    }

    @Test
    void bonusOnlyForTheSlayerItAppliesTo() {
        List<Mayors.Observation> l = new ArrayList<>();
        Mayors.Observation o = obs(T, "mayor", "Aatrox", 25, "Revenant Bonus", "Revenant Bonus");
        o.slayerScope.add("zombie");
        Mayors.observe(l, o);
        MayorSnapshot m = Mayors.at(l, T);
        assertEquals(625, Rewards.slayer(Slayer.ZOMBIE, 4, m, -1).expected, 1e-9);
        XpCalc e = Rewards.slayer(Slayer.ENDERMAN, 4, m, -1);
        assertEquals(500, e.expected, 1e-9);
        assertEquals("calc.bonus.not_applicable", e.bonuses.get(0).reason);
    }

    @Test
    void mayorChangesBetweenTermsAndHistoryKeepsItsConditions() {
        List<Mayors.Observation> l = new ArrayList<>();
        Mayors.observe(l, obs(T, "mayor", "Aatrox", 25, "Slayer XP Buff", "Slayer XP Buff"));
        long next = T + Mayors.YEAR;
        assertNotEquals(Mayors.termStart(T), Mayors.termStart(next));
        assertNull(Mayors.at(l, next), "an old observation is never used for the next term");
        Mayors.observe(l, obs(next, "mayor", "Paul", -1, null, "EZPZ"));
        assertEquals("Aatrox", Mayors.at(l, T + 1000).mayor);
        assertEquals("Paul", Mayors.at(l, next + 1000).mayor);

        // a quest recorded under Aatrox keeps Aatrox after the mayor changed
        Activity.SlayerQuest q = new Activity.SlayerQuest();
        q.mayor = Mayors.at(l, T + 1000);
        q.calc = Rewards.slayer(Slayer.ENDERMAN, 4, q.mayor, 625);
        Mayors.observe(l, obs(next + 5000, "mayor", "Paul", -1, null, "EZPZ"));
        assertEquals("Aatrox", q.mayor.mayor);
        assertEquals(625, q.calc.expected, 1e-9);
        // term math
        assertTrue(Mayors.termStart(T) <= T && T < Mayors.termEnd(T));
        assertEquals(Mayors.YEAR, Mayors.termEnd(T) - Mayors.termStart(T));
    }

    @Test
    void jerryPerksAreOnlyTrustedBriefly() {
        List<Mayors.Observation> l = new ArrayList<>();
        Mayors.observe(l, obs(T, "mayor", "Jerry", 25, "Slayer XP Buff", "Slayer XP Buff"));
        assertNotNull(Mayors.at(l, T + 10 * 60_000L));
        assertNull(Mayors.at(l, T + 2 * 3600_000L), "Jerry's perks rotate – not assumed hours later");
    }

    @Test
    void discrepancyIsReportedNotOverwritten() {
        XpCalc c = Rewards.slayer(Slayer.ENDERMAN, 4, aatroxWithBuff(), 700);
        assertEquals(700, c.observed, 1e-9, "observed value preserved");
        assertEquals(625, c.expected, 1e-9);
        assertEquals("calc.note.discrepancy", c.note);
        ActivityStats st = new ActivityStats();
        st.tableCheck(700, 625);
        st.tableCheck(625, 625);
        assertEquals(1, st.tableMatches);
        assertEquals(1, st.tableMismatches);
    }

    @Test
    void mayorMenuParsing() {
        var aatrox = new MenuParser.Item("Mayor Aatrox", List.of("Perks", "Slashed Pricing", "Starting slayer quests is half price.", "",
                "Slayer XP Buff", "Earn 25% more Slayer XP.", "", "Pathfinder", "Gain rare drops more often."));
        var events = MenuParser.parse("Calendar and Events", List.of(aatrox));
        ParsedEvent.MayorInfo mi = (ParsedEvent.MayorInfo) events.stream().filter(e -> e instanceof ParsedEvent.MayorInfo).findFirst().orElseThrow();
        assertEquals("mayor", mi.role());
        assertEquals("Aatrox", mi.name());
        assertEquals(25, mi.slayerXpPercent(), 1e-9);
        assertEquals("Slayer XP Buff", mi.slayerPerk());
        assertTrue(mi.perks().containsAll(List.of("Slashed Pricing", "Slayer XP Buff", "Pathfinder")), mi.perks().toString());
        assertTrue(mi.slayerScope().isEmpty());
        // an election candidate (with votes) is not the mayor in office
        var candidate = new MenuParser.Item("Mayor Aatrox", List.of("Slayer XP Buff", "Earn 25% more Slayer XP.", "Votes: 12,345"));
        assertTrue(MenuParser.parse("Election, Year 400", List.of(candidate)).stream().noneMatch(e -> e instanceof ParsedEvent.MayorInfo));
        // same item in an unrelated menu is ignored
        assertTrue(MenuParser.parse("Auction House", List.of(aatrox)).stream().noneMatch(e -> e instanceof ParsedEvent.MayorInfo));
        var minister = new MenuParser.Item("Minister Paul", List.of("EZPZ", "Gain 10 bonus score on dungeon runs."));
        ParsedEvent.MayorInfo m2 = (ParsedEvent.MayorInfo) MenuParser.parse("Calendar and Events", List.of(minister)).get(0);
        assertEquals("minister", m2.role());
        assertEquals(-1, m2.slayerXpPercent(), 1e-9);
    }

    @Test
    void rewardOverridesAreValidated() {
        Rewards.load("""
                { "version": 3, "slayer": { "enderman": [6, null, 110, 520, 999], "dragon": [1] , "zombie": [-5] }, "floors": { "F7": 25000, "F9": 1 } }""");
        assertEquals(6, Rewards.slayerBase(Slayer.ENDERMAN, 1));
        assertEquals(25, Rewards.slayerBase(Slayer.ENDERMAN, 2), "null keeps the built-in value");
        assertEquals(520, Rewards.slayerBase(Slayer.ENDERMAN, 4));
        assertEquals(-1, Rewards.slayerBase(Slayer.ENDERMAN, 5), "Voidgloom has no T5");
        assertEquals(5, Rewards.slayerBase(Slayer.ZOMBIE, 1), "invalid override ignored");
        assertEquals(25000, Rewards.floorFallback("F7"));
        assertEquals(4, Rewards.errors().size(), Rewards.errors().toString()); // extra tier, unknown type, negative, unknown floor
        assertTrue(Rewards.source(true).contains("v3"));
        Rewards.load("{ broken");
        assertEquals(500, Rewards.slayerBase(Slayer.ENDERMAN, 4), "invalid file → built-in values");
        assertEquals(1, Rewards.errors().size());
        Rewards.load(null);
    }
}
