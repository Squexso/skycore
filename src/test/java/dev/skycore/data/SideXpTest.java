package dev.skycore.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SideXpTest {
    @Test
    void recognisesTheRealCreatureLines() {
        assertTrue(SideXp.isCreatureLine("You caught a Sea Walker."));
        assertTrue(SideXp.isCreatureLine("You reeled in a Sea Archer."));
        assertTrue(SideXp.isCreatureLine("You have angered a legendary creature... Lord Jawbus has arrived."));
        assertTrue(SideXp.isCreatureLine("DOUBLE HOOK! You have angered a legendary creature... Lord Jawbus has arrived."));
        assertTrue(SideXp.isCreatureLine("  You caught an Oasis Rabbit.  "));
        // the other forms from real logs
        assertTrue(SideXp.isCreatureLine("A Squid appeared."));
        assertTrue(SideXp.isCreatureLine("Huh? A Catfish!"));
        assertTrue(SideXp.isCreatureLine("You've discovered a Guardian Defender of the sea."));
        assertTrue(SideXp.isCreatureLine("It looks like you've disrupted the Sea Witch's brewing session. Watch out, she's furious!"));
        assertTrue(SideXp.isCreatureLine("The Rider of the Deep has emerged."));
    }

    @Test
    void itemCatchesAndPlayerChatAreNotCreatures() {
        assertFalse(SideXp.isCreatureLine("GOOD CATCH! You caught a Sponge x16!"));
        assertFalse(SideXp.isCreatureLine("TROPHY FISH! You caught Blobfish BRONZE!"));
        assertFalse(SideXp.isCreatureLine("GREAT CATCH! You caught an Enchanted Raw Salmon x4!"));
        assertFalse(SideXp.isCreatureLine("[MVP+] Rod_S: DOUBLE HOOK! You have angered a legendary creature... Lord Jawbus has arrived."));
        assertFalse(SideXp.isCreatureLine("[VIP] Steve: You caught a Sea Walker."));
        assertFalse(SideXp.isCreatureLine(null));
        // lines from real logs that look similar but are not sea creatures
        assertFalse(SideXp.isCreatureLine("A Spirit Bear has appeared!"));
        assertFalse(SideXp.isCreatureLine("HOPPITY'S HUNT A Chocolate Lunch Egg has appeared!"));
        assertFalse(SideXp.isCreatureLine("SLAYER MINIBOSS! The Sven Alpha spawned!"));
        assertFalse(SideXp.isCreatureLine("You can't use Worm Baits outside of Crystal Hollows."));
        assertFalse(SideXp.isCreatureLine("Your Plasmaflux Power Orb despawned."));
    }

    @Test
    void everyCreatureLineCountsOneAndDoubleHookCountsTwo() {
        SideXp.reset();
        SideXp.onChat("You caught a Sea Walker.", 1_000);
        SideXp.onChat("You reeled in a Sea Archer.", 20_000);
        SideXp.onChat("You caught a Sea Walker.", 40_000);
        SideXp.onChat("You caught a Sea Walker.", 40_010); // double hook: two lines at once
        assertEquals(4, SideXp.get("skill_fishing").count());
    }

    @Test
    void theFishingXpAfterTheLineBelongsToThatCreature() {
        SideXp.reset();
        SideXp.onChat("You caught a Sea Walker.", 1_000);
        SideXp.onGain("skill_fishing", 655, 2_000);
        SideXp.onGain("skill_fishing", 91, 15_000); // a normal catch later
        assertEquals(1, SideXp.get("skill_fishing").count());
        assertEquals(655, SideXp.get("skill_fishing").xp(), 0.001);
    }

    @Test
    void theFishingXpCanAlsoComeBeforeTheLine() {
        SideXp.reset();
        SideXp.onGain("skill_fishing", 2457, 1_000);
        SideXp.onChat("You reeled in a Sea Archer.", 1_500);
        assertEquals(1, SideXp.get("skill_fishing").count());
        assertEquals(2457, SideXp.get("skill_fishing").xp(), 0.001);
    }

    @Test
    void normalCatchesDoNotCount() {
        SideXp.reset();
        SideXp.onGain("skill_fishing", 182, 1_000);
        SideXp.onGain("skill_fishing", 91, 9_000);
        SideXp.onChat("GOOD CATCH! You caught a Sponge x16!", 9_500);
        assertEquals(0, SideXp.get("skill_fishing").count());
        assertEquals(0, SideXp.get("skill_fishing").xp(), 0.001);
    }

    @Test
    void combatGainWhileMiningCountsAsAMob() {
        SideXp.reset();
        SideXp.onGain("skill_mining", 40, 1_000);
        SideXp.onGain("skill_combat", 300, 5_000);
        SideXp.onGain("skill_combat", 200, 30_000);
        assertEquals(2, SideXp.get("skill_mining").count());
        assertEquals(500, SideXp.get("skill_mining").xp(), 0.001);
        assertEquals(0, SideXp.get("skill_fishing").count());
    }

    @Test
    void combatGainWithoutAHostSkillIsNotCounted() {
        SideXp.reset();
        SideXp.onGain("skill_combat", 300, 5_000);
        SideXp.onGain("skill_mining", 40, 60_000);
        assertEquals(0, SideXp.get("skill_mining").count());
    }

    @Test
    void combatBarThatComesFirstIsCreditedWhenTheHostGainFollows() {
        SideXp.reset();
        SideXp.onGain("skill_combat", 300, 10_000);
        SideXp.onGain("skill_foraging", 40, 11_000);
        assertEquals(1, SideXp.get("skill_foraging").count());
    }

    @Test
    void hostGoesStaleAfterTwoMinutes() {
        SideXp.reset();
        SideXp.onGain("skill_mining", 40, 0);
        SideXp.onGain("skill_combat", 300, 125_000);
        assertEquals(0, SideXp.get("skill_mining").count());
    }

    @Test
    void hiddenXpBetweenReadingsIsRecovered() {
        assertEquals(900, SideXp.gainFromDelta(900, 40, 200_000, 60_000), 0.001);
        assertEquals(40, SideXp.gainFromDelta(900, 40, 200_000, 600_000), 0.001);
        assertEquals(40, SideXp.gainFromDelta(150_000, 40, 200_000, 30_000), 0.001);
        assertEquals(40, SideXp.gainFromDelta(-1, 40, 200_000, 30_000), 0.001);
        assertEquals(35, SideXp.gainFromDelta(35, 40, 200_000, 30_000), 0.001);
    }
}