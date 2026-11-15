package dev.skycore.tracking;

import dev.skycore.tracking.parse.MenuParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShardBoostTest {
    private static MenuParser.Item item(String name, String... lore) { return new MenuParser.Item(name, List.of(lore)); }

    @Test
    void readsBothKindsOfBonus() {
        var troglobyte = item("Troglobyte", "Mountain Climber X (Mining)", "Commissions grant +10% more HOTM", "Exp.", "Owned: 64 Shards", "Attribute Maxed!");
        var hoarder = item("Dwarven Training X", "Mining", "", "Gain +10% more Heart of the", "Mountain Exp.", "", "Enabled: Yes", "Attribute Level: 10 (MAX!)");
        var hydra = item("Water Hydra", "Fishing Speed X (Fishing)", "Grants +30  Fishing Speed.");
        double[] b = MenuParser.shardHotmBonus(List.of(hydra, troglobyte, hoarder));
        assertEquals(10, b[0], 0.0001);
        assertEquals(10, b[1], 0.0001);
    }

    @Test
    void aSwitchedOffAttributeCountsAsZero() {
        var off = item("Dwarven Training X", "Gain +10% more Heart of the", "Mountain Exp.", "Enabled: No");
        assertEquals(0, MenuParser.shardHotmBonus(List.of(off))[1], 0.0001);
    }

    @Test
    void nothingShownMeansUnchanged() {
        double[] none = MenuParser.shardHotmBonus(List.of(item("Water Hydra", "Grants +30  Fishing Speed.")));
        assertEquals(-1, none[0], 0.0001);
        assertEquals(-1, none[1], 0.0001);
    }
}
