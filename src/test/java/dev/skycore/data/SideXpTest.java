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
        // the user's list of messages
        String[] fromList = {"A Sea Archer appeared.", "The Monster of the Deep has surfaced!", "A Scarecrow appeared.",
                "The Catfish swam to the surface!", "The Sea Leech rose from the depths!", "A Guardian Defender appeared.",
                "Deep Sea Terror surfaced!", "The Water Hydra has awakened!", "The Emperor has appeared!", "A Nightmare surfaced!",
                "It must be a full moon, it's a Werewolf!", "A Phantom Fisher appeared.", "The Grim Reaper has appeared!",
                "Frozen Steve froze for ages, until now!", "Frosty the Snowman is throwing snowballs!",
                "The Grinch is stealing your gifts!", "A Nurse Shark appeared.", "An Ice Golem appeared!", "The Yeti has awakened!",
                "A Blue Shark appeared.", "A Tiger Shark appeared.", "Great White Shark appeared!", "Magma Slug surfaced!",
                "Moogma emerged!", "A Lava Piranha appeared.", "A Lava Flame appeared.", "A Fire Eel appeared.", "Taurus appeared!",
                "Thunder appeared!", "Lord Jawbus has awakened!", "A Water Worm surfaces!", "A Poisoned Water Worm surfaces!",
                "An Abyssal Miner appeared."};
        for (String line : fromList) assertTrue(SideXp.isCreatureLine(line), line);
        // the second, complete list
        String[] list2 = {"Pitch Darkness reveals a Night Squid.", "You stumbled upon a Sea Guardian.", "A Catfish!", "It's the Carrot King!",
                "Your Chumcap Bucket trembles, it's an Agarimoo.", "You have awoken the Deep Sea Protector, prepare for a battle!",
                "The Water Hydra has surfaced!", "The Sea Emperor arises from the depths.", "An Oasis Rabbit hops out of the water!",
                "A Lava Blaze flies out of the magma!", "The mighty Taurus charges out of the lava!",
                "The ancient terror of the Crimson Isle, Lord Jawbus, has broken free!", "You fished up... Fried Chicken?",
                "BrrRrRRr! A Yeti appeared!", "Hide your kids, hide your wife, it's a Great White Shark!",
                "The end of days is here! Ragnarok has awoken!", "The elusive Nessie surfaces to greet you!"};
        for (String line : list2) assertTrue(SideXp.isCreatureLine(line), line);
        // the third list
        String[] list3 = {"A Sea Archer stood on the shore!", "A Catfish bit the line!", "A Trash Gobbler emerged from the junk!",
                "gorF jumps out of the water!", "The Grinch stole your catch!", "A Jumpin' Jack springs up from the hotspot!",
                "The legendary Wiki Tiki has been summoned!", "A Great White Shark attacks from the deep!"};
        for (String line : list3) assertTrue(SideXp.isCreatureLine(line), line);
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