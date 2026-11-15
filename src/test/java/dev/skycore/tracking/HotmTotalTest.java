package dev.skycore.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HotmTotalTest {
    @Test
    void totalXpAddsTheFinishedTiers() {
        assertEquals(500, HypixelTracker.hotmTotal(1, 500), 0.001);
        assertEquals(3000 + 9000 + 25000 + 60000 + 100000 + 150000 + 210000 + 150_960, HypixelTracker.hotmTotal(8, 150_960), 0.001);
    }

    @Test
    void aBoostShowsUpAsTheDifferenceOfTwoExactReads() {
        // promised 440, the menu then shows 484 more XP: ratio 1.1
        double before = HypixelTracker.hotmTotal(8, 150_960);
        double after = HypixelTracker.hotmTotal(8, 150_960 + 484);
        assertEquals(1.1, (after - before) / 440, 0.0001);
        // a claim that carries over into the next tier gives the same total difference
        assertEquals(484, HypixelTracker.hotmTotal(9, 100) - HypixelTracker.hotmTotal(8, 289_616), 0.001);
    }
}
