package dev.skycore.hud;

import dev.skycore.data.XpTables;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HotmWidgetTest {
    @Test void tableIsIndexedByTier() {
        assertEquals(3000, XpTables.need(XpTables.HOTM, 1));
        assertEquals(290000, XpTables.need(XpTables.HOTM, 8)); // matches the real menu: tier 8 -> 9 needs 290,000
        assertEquals(400000, XpTables.need(XpTables.HOTM, 9));
        assertEquals(-1, XpTables.need(XpTables.HOTM, 10));    // tier 10 is the maximum
    }

    @Test void xpToATargetTier() {
        assertEquals(290000 + 400000 - 148100, XpTables.xpTo(XpTables.HOTM, 8, 148100, 10), 0.001);
        assertEquals(290000 - 148100, XpTables.xpTo(XpTables.HOTM, 8, 148100, 9), 0.001);
        assertEquals(0, XpTables.xpTo(XpTables.HOTM, 10, 0, 10), 0.001);
    }

    @Test void boxIsADefaultWidgetShownWhileMining() {
        HudStore.WidgetState w = HudStore.defaults().stream().filter(x -> "hotm".equals(x.id)).findFirst().orElseThrow();
        assertEquals("mining", w.visibility);
        assertEquals(10, w.targetTier);
        assertArrayEquals(new String[]{"hotm_xp", "hotm_left", "hotm_target"}, HudWidgets.lineKeys("hotm"));
    }
}