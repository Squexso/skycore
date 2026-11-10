package dev.skycore.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SideXpTest {
    // real fishing gains: 45-182 per catch
    private static final double[] USUAL = {182, 91, 46, 45, 182, 64, 64, 45, 182, 64, 46, 182, 46, 291 / 3.0};

    @Test
    void bigFishingGainsAreSeaCreatures() {
        SideXp.reset();
        SideXp.onGain("skill_fishing", 1341, 1_000, USUAL);
        SideXp.onGain("skill_fishing", 2457, 2_000, USUAL);
        SideXp.onGain("skill_fishing", 182, 3_000, USUAL); // a normal catch
        SideXp.onGain("skill_fishing", 91, 4_000, USUAL);
        assertEquals(2, SideXp.get("skill_fishing").count());
        assertEquals(3798, SideXp.get("skill_fishing").xp(), 0.001);
    }

    @Test
    void noVerdictWithoutEnoughHistory() {
        SideXp.reset();
        SideXp.onGain("skill_fishing", 2457, 1_000, new double[]{45, 46});
        assertEquals(0, SideXp.get("skill_fishing").count());
        assertFalse(SideXp.isBigGain(500, null));
    }

    @Test
    void previousExcludesTheNewestGain() {
        var g = new java.util.ArrayList<ProfileData.Gain>();
        for (int i = 1; i <= 3; i++) g.add(new ProfileData.Gain(i, i * 10));
        assertArrayEquals(new double[]{10, 20}, SideXp.previous(g), 0.001);
        assertEquals(0, SideXp.previous(new java.util.ArrayList<>()).length);
    }

    @Test
    void combatGainWhileMiningCountsAsAMob() {
        SideXp.reset();
        SideXp.onGain("skill_mining", 40, 1_000, USUAL);
        SideXp.onGain("skill_combat", 300, 5_000, USUAL);
        SideXp.onGain("skill_combat", 200, 30_000, USUAL);
        assertEquals(2, SideXp.get("skill_mining").count());
        assertEquals(500, SideXp.get("skill_mining").xp(), 0.001);
        assertEquals(0, SideXp.get("skill_fishing").count());
    }

    @Test
    void combatGainWithoutAHostSkillIsNotCounted() {
        SideXp.reset();
        SideXp.onGain("skill_combat", 300, 5_000, USUAL);
        SideXp.onGain("skill_mining", 40, 60_000, USUAL); // far later than the pending window
        assertEquals(0, SideXp.get("skill_mining").count());
    }

    @Test
    void combatBarThatComesFirstIsCreditedWhenTheHostGainFollows() {
        SideXp.reset();
        SideXp.onGain("skill_combat", 300, 10_000, USUAL);
        SideXp.onGain("skill_foraging", 40, 11_000, USUAL);
        assertEquals(1, SideXp.get("skill_foraging").count());
    }

    @Test
    void hostGoesStaleAfterTwoMinutes() {
        SideXp.reset();
        SideXp.onGain("skill_mining", 40, 0, USUAL);
        SideXp.onGain("skill_combat", 300, 125_000, USUAL);
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