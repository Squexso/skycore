package dev.skycore.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetersTest {
    static final long S = 1_000_000_000L;

    @Test void unknownBeforeFirstWorld() {
        assertEquals(-1, new Meters.TpsEstimator().value(0));
    }

    @Test void fullSpeedWithoutUpdatesIsTwenty() {
        Meters.TpsEstimator e = new Meters.TpsEstimator();
        long gt = 100;
        for (int i = 0; i < 100; i++) e.observe(gt++, i * 50_000_000L);
        assertEquals(20.0, e.value(100 * 50_000_000L), 1e-9);
    }

    @Test void slowServerShowsLowerTps() {
        Meters.TpsEstimator e = new Meters.TpsEstimator();
        // client counts 20 ticks per second, the server only 15: every ~second the server time is behind and gets re-set
        long ns = 0, server = 1000;
        e.observe(server, ns);
        long client = server;
        for (int sec = 1; sec <= 10; sec++) {
            for (int i = 0; i < 19; i++) { ns += 50_000_000L; client++; e.observe(client, ns); }
            ns += 50_000_000L;
            server += 15;
            client = server; // update from the server
            e.observe(client, ns);
        }
        double v = e.value(ns);
        assertTrue(v > 14 && v < 16, "tps " + v);
    }

    @Test void staleReadingFallsBackToTwenty() {
        Meters.TpsEstimator e = new Meters.TpsEstimator();
        e.observe(1000, 0);
        e.observe(1010, S);        // update 1
        e.observe(1025, 2 * S);    // update 2: 15 ticks in 1 s
        assertTrue(e.value(2 * S) < 20);
        assertEquals(20.0, e.value(30 * S), 1e-9);
    }

    @Test void colourClasses() {
        assertEquals(0, Meters.fpsClass(120));
        assertEquals(2, Meters.fpsClass(20));
        assertEquals(1, Meters.tpsClass(17));
        assertEquals(2, Meters.pingClass(350));
        assertEquals("20", Meters.tpsText(20));
        assertEquals("17.5", Meters.tpsText(17.5));
    }
}
