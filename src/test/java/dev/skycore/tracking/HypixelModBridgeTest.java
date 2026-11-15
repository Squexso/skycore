package dev.skycore.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HypixelModBridgeTest {
    @Test
    void modesBecomeReadableAreas() {
        assertEquals("The Catacombs", HypixelModBridge.areaFor("dungeon"));
        assertEquals("Dwarven Mines", HypixelModBridge.areaFor("MINING_3"));
        assertEquals("Hub", HypixelModBridge.areaFor("hub"));
    }

    @Test
    void unknownOrMissingModeGivesNull() {
        assertNull(HypixelModBridge.areaFor("something_new"));
        assertNull(HypixelModBridge.areaFor(null));
        assertFalse(HypixelModBridge.onSkyBlock());
    }
}
