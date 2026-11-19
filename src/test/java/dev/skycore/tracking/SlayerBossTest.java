package dev.skycore.tracking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlayerBossTest {
    @Test
    void healthIsShownShort() {
        assertEquals("7.6M", SlayerBoss.hpText(7_600_000));
        assertEquals("12M", SlayerBoss.hpText(12_000_000));
        assertEquals("450K", SlayerBoss.hpText(450_000));
        assertEquals("1.5K", SlayerBoss.hpText(1_500));
        assertEquals("800", SlayerBoss.hpText(800));
        assertEquals("1M", SlayerBoss.hpText(999_990));
        assertEquals("0", SlayerBoss.hpText(0));
    }

    @Test
    void mobsLeftFromTheLastKills() {
        assertEquals(-1, SlayerBoss.left(100, 1000, List.of()));
        assertEquals(3, SlayerBoss.left(940, 1000, List.of(20.0, 20.0, 20.0)));
        assertEquals(1, SlayerBoss.left(990, 1000, List.of(20.0)));
        assertEquals(0, SlayerBoss.left(1000, 1000, List.of(20.0)));
    }

    @Test
    void theCountAppearsOnlyForTheLastThreeMobs() {
        SlayerBoss.reset();
        long now = 1000;
        SlayerBoss.sidebar(List.of("Slayer Quest", "Revenant Horror V", "(400/1,000) Combat XP"), "combat", now);
        SlayerBoss.sidebar(List.of("Slayer Quest", "Revenant Horror V", "(420/1,000) Combat XP"), "combat", now);
        assertEquals(SlayerBoss.Mode.NONE, SlayerBoss.view(now).mode()); // 29 mobs left
        SlayerBoss.sidebar(List.of("Revenant Horror V", "(960/1,000) Combat XP"), "combat", now);
        SlayerBoss.sidebar(List.of("Revenant Horror V", "(980/1,000) Combat XP"), "combat", now);
        SlayerBoss.View v = SlayerBoss.view(now);
        assertEquals(SlayerBoss.Mode.MOBS, v.mode());
        assertEquals(1, v.mobs());
    }

    @Test
    void bossHealthComesFromTheNameTagAndGoesStale() {
        SlayerBoss.reset();
        SlayerBoss.sidebar(List.of("Slay the boss!"), "boss", 0);
        SlayerBoss.tag("☠ Voidgloom Seraph IV 7.6M❤", 1000);
        SlayerBoss.View v = SlayerBoss.view(1500);
        assertEquals(SlayerBoss.Mode.HP, v.mode());
        assertEquals("7.6M", v.hp());
        assertEquals(SlayerBoss.Mode.NONE, SlayerBoss.view(9000).mode());
        SlayerBoss.sidebar(List.of("Boss slain!"), "slain", 9000);
        SlayerBoss.tag("Voidgloom Seraph IV 100K❤", 9000);
        assertEquals(SlayerBoss.Mode.NONE, SlayerBoss.view(9000).mode());
    }
}
