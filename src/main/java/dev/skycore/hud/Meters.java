package dev.skycore.hud;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;

/**
 * Live readouts for the meters HUD widget: FPS, server TPS, ping, memory and position.
 * <p>
 * Hypixel does not report its TPS, so it is estimated from the world time: the server sends the game time about once per
 * second, and the client otherwise counts it up by itself, so a value that is not "last + 1" marks such an update. The
 * ticks between two updates divided by the real seconds is the server tick rate. Without any updates the client and the
 * server agree, which counts as a full 20.
 */
public final class Meters {
    private Meters() {}

    /** Server TPS from observed game-time updates. Pure: the caller supplies the clock. */
    public static final class TpsEstimator {
        static final int WINDOW = 8;
        static final long MIN_INTERVAL_NS = 500_000_000L, MAX_INTERVAL_NS = 12_000_000_000L, STALE_NS = 8_000_000_000L;
        private long lastGt = -1, packetGt = -1, packetNs, lastSampleNs;
        private final ArrayDeque<Double> samples = new ArrayDeque<>();

        public void reset() { lastGt = packetGt = -1; samples.clear(); lastSampleNs = 0; }

        /** Call once per client tick with the current game time. */
        public void observe(long gt, long ns) {
            if (lastGt < 0) { lastGt = gt; return; }
            if (gt == lastGt) return; // time is frozen, nothing learned
            boolean update = gt != lastGt + 1;
            lastGt = gt;
            if (!update) return;
            if (packetGt >= 0 && gt > packetGt) {
                long dt = ns - packetNs;
                if (dt >= MIN_INTERVAL_NS && dt <= MAX_INTERVAL_NS) {
                    double tps = Math.min(20.0, Math.max(0.0, (gt - packetGt) * 1e9 / dt));
                    samples.addLast(tps);
                    while (samples.size() > WINDOW) samples.pollFirst();
                    lastSampleNs = ns;
                }
            }
            packetGt = gt;
            packetNs = ns;
        }

        /** Smoothed TPS (at most 20), or -1 before the first world is seen. */
        public double value(long ns) {
            if (lastGt < 0) return -1;
            if (samples.isEmpty() || ns - lastSampleNs > STALE_NS) return 20.0;
            double sum = 0;
            for (double s : samples) sum += s;
            double avg = sum / samples.size();
            return avg >= 19.5 ? 20.0 : avg; // tick-granularity jitter must not show as 19.3
        }
    }

    private static final TpsEstimator TPS = new TpsEstimator();
    private static Object lastLevel;

    /** Client tick: feeds the TPS estimate. */
    public static void tick(Minecraft mc) {
        if (mc.level == null) { lastLevel = null; TPS.reset(); lastPing = -1; return; }
        if (mc.level != lastLevel) { lastLevel = mc.level; TPS.reset(); }
        TPS.observe(mc.level.getGameTime(), System.nanoTime());
        pingTick(mc);
    }

    public static double tps() { return TPS.value(System.nanoTime()); }

    public static int fps(Minecraft mc) { return mc.getFps(); }

    // ---- ping: Hypixel fills the tab-list latency with a fixed dummy value, so the real round trip is measured instead.
    // This is the one thing SKYCORE sends: the same tiny vanilla ping request the F3+3 network chart uses, every 5 seconds,
    // only while the ping readout is switched on. The game files the answer in the debug overlay's ping logger.
    static final long PING_EVERY_MS = 5000, PING_FRESH_MS = 20_000;
    private static long nextPingAt, lastPingAt;
    private static int lastPing = -1;

    private static void pingTick(Minecraft mc) {
        long now = System.currentTimeMillis();
        HudStore.WidgetState w = HudStore.widget("m_ping");
        var conn = mc.getConnection();
        if (w == null || !w.enabled || !w.line("realping") || conn == null || mc.level == null) { lastPing = -1; return; }
        var log = mc.getDebugOverlay().getPingLogger();
        if (log.size() > 0) {
            long v = log.get(log.size() - 1);
            if (v > 0 && v < 30_000) { lastPing = (int) v; lastPingAt = now; }
            log.reset();
        }
        if (now >= nextPingAt) {
            nextPingAt = now + PING_EVERY_MS;
            conn.send(new net.minecraft.network.protocol.ping.ServerboundPingRequestPacket(net.minecraft.util.Util.getMillis()));
        }
    }

    /** Measured round trip in ms, or -1 when there is no fresh measurement (switched off, not answered, not in a world). */
    public static int ping(Minecraft mc) {
        return System.currentTimeMillis() - lastPingAt > PING_FRESH_MS ? -1 : lastPing;
    }

    /** Used heap in percent of the maximum. */
    public static int memoryPercent() {
        Runtime r = Runtime.getRuntime();
        return (int) Math.round((r.totalMemory() - r.freeMemory()) * 100.0 / r.maxMemory());
    }

    /** Colour class of a reading: 0 good, 1 medium, 2 bad. */
    public static int fpsClass(int fps) { return fps >= 60 ? 0 : fps >= 30 ? 1 : 2; }
    public static int tpsClass(double tps) { return tps >= 19 ? 0 : tps >= 15 ? 1 : 2; }
    public static int pingClass(int ms) { return ms < 100 ? 0 : ms < 200 ? 1 : 2; }
    public static int memoryClass(int pct) { return pct < 70 ? 0 : pct < 90 ? 1 : 2; }

    public static String tpsText(double tps) { return tps >= 20 ? "20" : String.format(java.util.Locale.ROOT, "%.1f", tps); }
}
