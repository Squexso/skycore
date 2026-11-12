package dev.skycore.hud;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MeterWidgetsTest {
    @Test void everyReadoutIsItsOwnWidget() {
        List<HudStore.WidgetState> d = HudStore.defaults();
        for (String id : new String[]{"m_fps", "m_tps", "m_ping", "m_ram", "m_xyz"}) {
            HudStore.WidgetState w = d.stream().filter(x -> id.equals(x.id)).findFirst().orElseThrow();
            assertTrue(HudWidgets.isMeter(id));
            assertTrue(w.enabled);
            assertTrue(w.autoColor);
            assertArrayEquals(new String[]{"labels"}, HudWidgets.lineKeys(id));
        }
        assertTrue(d.stream().noneMatch(x -> "meters".equals(x.id)));
        assertFalse(HudWidgets.isMeter("skill"));
    }

    @Test void readoutsDoNotStartOnTopOfEachOther() {
        List<HudStore.WidgetState> d = HudStore.defaults();
        float prev = -1;
        for (HudStore.WidgetState w : d) if (HudWidgets.isMeter(w.id)) { assertTrue(w.y > prev); prev = w.y; }
    }
}
