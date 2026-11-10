package dev.skycore.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SideXpTest {
    @Test
    void combatGainWhileFishingCountsAsACreature() {
        SideXp.reset();
        SideXp.onGain("skill_fishing", 40, 1_000);
        SideXp.onGain("skill_combat", 300, 5_000);
        SideXp.onGain("skill_combat", 200, 30_000);
        assertEquals(2, SideXp.get("skill_fishing").count());
        assertEquals(500, SideXp.get("skill_fishing").xp(), 0.001);
        assertEquals(0, SideXp.get("skill_mining").count());
    }

    @Test
    void combatGainWithoutAHostSkillIsNotCounted() {
        SideXp.reset();
        SideXp.onGain("skill_combat", 300, 5_000);
        SideXp.onGain("skill_fishing", 40, 60_000); // far later than the pending window
        assertEquals(0, SideXp.get("skill_fishing").count());
    }

    @Test
    void combatBarThatComesFirstIsCreditedWhenTheHostGainFollows() {
        SideXp.reset();
        SideXp.onGain("skill_combat", 300, 10_000);
        SideXp.onGain("skill_fishing", 40, 11_000);
        assertEquals(1, SideXp.get("skill_fishing").count());
    }

    @Test
    void hostGoesStaleAfterTwoMinutes() {
        SideXp.reset();
        SideXp.onGain("skill_fishing", 40, 0);
        SideXp.onGain("skill_combat", 300, 125_000);
        assertEquals(0, SideXp.get("skill_fishing").count());
    }

    @Test
    void hiddenXpBetweenReadingsIsRecovered() {
        // the shown gain is 40 but the total moved by 900 within a minute: a hidden creature gain
        assertEquals(900, SideXp.gainFromDelta(900, 40, 200_000, 60_000), 0.001);
        // a big jump long after the last reading (another device, a menu) is not trusted
        assertEquals(40, SideXp.gainFromDelta(900, 40, 200_000, 600_000), 0.001);
        // an absurd jump is never trusted
        assertEquals(40, SideXp.gainFromDelta(150_000, 40, 200_000, 30_000), 0.001);
        assertEquals(40, SideXp.gainFromDelta(-1, 40, 200_000, 30_000), 0.001);
        assertEquals(35, SideXp.gainFromDelta(35, 40, 200_000, 30_000), 0.001);
    }
}
