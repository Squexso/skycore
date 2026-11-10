package dev.skycore.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReputationTest {
    static final long DAY = 24 * 3600_000L;
    static final long T0 = 1_800_000_000_000L;

    @BeforeEach void clean() { Reputation.reset(); }

    private static void block(long now, int cur) {
        Reputation.onChat("                              Mage Reputation", now);
        Reputation.onChat("", now);
        Reputation.onChat("          Friendly                                         Trusted", now);
        Reputation.onChat("          [■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■]", now);
        Reputation.onChat("                            " + cur + " / 3000 (62.7%)", now);
    }

    @Test void statusBlockSetsLevel() {
        block(T0, 1880);
        Reputation.Fac f = Reputation.get("Mage");
        assertEquals(1880, f.cur);
        assertEquals(3000, f.need);
        assertEquals("Friendly", f.from);
        assertEquals("Trusted", f.to);
        assertEquals("Mage", Reputation.current());
    }

    @Test void gainRightAfterBlockIsNotAddedTwice() {
        block(T0, 1880);
        Reputation.onChat("You earned 25 Mage Reputation.", T0 + 100);
        assertEquals(1880, Reputation.get("Mage").cur);
        assertEquals(25, Reputation.today("Mage", T0));
    }

    @Test void laterGainsAddUp() {
        block(T0, 1880);
        Reputation.onChat("+50 Mage Reputation", T0 + 60_000);
        Reputation.onChat("You gained 20 Reputation for killing the miniboss!", T0 + 90_000);
        assertEquals(1950, Reputation.get("Mage").cur);
        assertEquals(70, Reputation.session("Mage"));
    }

    @Test void bestiaryRewardLinesAreNotReputation() {
        block(T0, 1880);
        Reputation.onChat("+2 Mage Outlaw  Magic Find", T0 + 60_000);
        Reputation.onChat("+2% Mage Outlaw coins", T0 + 60_000);
        assertEquals(1880, Reputation.get("Mage").cur);
    }

    @Test void levelUpNeedsNewReading() {
        block(T0, 2990);
        Reputation.onChat("+50 Mage Reputation", T0 + 60_000);
        assertFalse(Reputation.get("Mage").known());
    }

    @Test void etaFromCompletedDays() {
        block(T0 + 5 * DAY, 1000); // 2000 left
        for (int d = 0; d < 5; d++) Reputation.gain("Mage", 500, T0 + d * DAY);
        Reputation.Eta e = Reputation.eta("Mage", T0 + 5 * DAY + 1000);
        assertNotNull(e);
        assertEquals(4.0, e.mid(), 0.01);
        assertTrue(e.fast() <= e.mid() && e.mid() <= e.slow());
        assertFalse(e.preliminary());
    }

    @Test void noEtaWithoutReadingOrData() {
        assertNull(Reputation.eta("Mage", T0));
        block(T0, 1000);
        assertNull(Reputation.eta("Mage", T0));
    }
}
