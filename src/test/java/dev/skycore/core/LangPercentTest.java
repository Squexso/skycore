package dev.skycore.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangPercentTest {
    @Test
    void neverShowsOneHundredPercentBeforeTheGoalIsReached() {
        assertEquals("99%", Lang.percent(199_906.0 / 200_000.0)); // 99.95%
        assertEquals("99%", Lang.percent(0.996));
        assertEquals("100%", Lang.percent(1.0));
        assertEquals("87%", Lang.percent(0.87));
        assertEquals("0%", Lang.percent(0));
    }
}
