package dev.skycore.tracking;

import dev.skycore.tracking.parse.MenuParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShardBoostTest {
    @Test
    void readsTheCommissionBonusFromAHuntingBoxShard() {
        var troglobyte = new MenuParser.Item("Troglobyte", List.of("Cave Dweller Family", "", "Mountain Climber X (Mining)",
                "Commissions grant +10% more HOTM", "Exp.", "", "Owned: 64 Shards", "Attribute Maxed!"));
        var hydra = new MenuParser.Item("Water Hydra", List.of("Fishing Speed X (Fishing)", "Grants +30  Fishing Speed."));
        assertEquals(1.10, MenuParser.shardHotmBoost(List.of(hydra, troglobyte)), 0.0001);
    }

    @Test
    void noShardLineMeansNoBoost() {
        var hydra = new MenuParser.Item("Water Hydra", List.of("Grants +30  Fishing Speed."));
        assertEquals(1.0, MenuParser.shardHotmBoost(List.of(hydra)), 0.0001);
        assertEquals(1.0, MenuParser.shardHotmBoost(List.of()), 0.0001);
    }
}
