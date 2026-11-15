package dev.skycore.tracking;

import dev.skycore.core.Skycore;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

/**
 * Optional: when the "Hypixel Mod API" mod is installed, the server tells SKYCORE exactly where the player is (game, mode, map)
 * without anyone having to read the sidebar. It is used through reflection only, so SKYCORE neither needs the mod nor breaks
 * when it is missing or changes; any problem just leaves the sidebar reading in charge. Nothing is sent to anyone but Hypixel's
 * own mod channel, and only the player's own location is received.
 */
public final class HypixelModBridge {
    private HypixelModBridge() {}

    private static final String API = "net.hypixel.modapi.HypixelModAPI";
    private static final String LOCATION = "net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket";
    private static final String HANDLER = "net.hypixel.modapi.handler.ClientboundPacketHandler";

    private static final Map<String, String> AREAS = Map.ofEntries(
            Map.entry("dynamic", "Private Island"), Map.entry("hub", "Hub"), Map.entry("dungeon_hub", "Dungeon Hub"),
            Map.entry("dungeon", "The Catacombs"), Map.entry("mining_3", "Dwarven Mines"), Map.entry("crystal_hollows", "Crystal Hollows"),
            Map.entry("mining_1", "Gold Mine"), Map.entry("mining_2", "Deep Caverns"), Map.entry("farming_1", "The Barn"),
            Map.entry("combat_1", "Spider's Den"), Map.entry("combat_2", "Blazing Fortress"), Map.entry("combat_3", "The End"),
            Map.entry("foraging_1", "The Park"), Map.entry("garden", "Garden"), Map.entry("rift", "The Rift"), Map.entry("kuudra", "Kuudra's Hollow"));

    private static boolean tried;
    private static volatile String serverType = "", mode = "";

    /** Once the player is in a world: connect to the Mod API if it is installed. */
    public static void tick() {
        if (tried) return;
        tried = true;
        if (!FabricLoader.getInstance().isModLoaded("hypixel-mod-api")) return;
        try {
            Class<?> api = Class.forName(API);
            Object inst = api.getMethod("getInstance").invoke(null);
            Class<?> loc = Class.forName(LOCATION);
            Class<?> handlerType = Class.forName(HANDLER);
            InvocationHandler h = (proxy, m, args) -> {
                if (m.getName().equals("handle") && args != null && args.length == 1) {
                    try { onLocation(args[0]); } catch (ReflectiveOperationException | RuntimeException e) { Skycore.LOG.debug("Mod API location unreadable: {}", e.toString()); }
                }
                return null;
            };
            Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType}, h);
            api.getMethod("registerHandler", Class.class, handlerType).invoke(inst, loc, handler);
            api.getMethod("subscribeToEventPacket", Class.class).invoke(inst, loc);
            Skycore.LOG.info("SKYCORE: Hypixel Mod API found, location updates enabled");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Skycore.LOG.warn("SKYCORE: Hypixel Mod API could not be used: {}", e.toString());
        }
    }

    private static void onLocation(Object packet) throws ReflectiveOperationException {
        Optional<?> type = (Optional<?>) packet.getClass().getMethod("getServerType").invoke(packet);
        Optional<?> m = (Optional<?>) packet.getClass().getMethod("getMode").invoke(packet);
        String t = "";
        if (type.isPresent()) { Method name = type.get().getClass().getMethod("name"); name.setAccessible(true); t = String.valueOf(name.invoke(type.get())); }
        serverType = t;
        mode = m.map(Object::toString).orElse("");
        Skycore.LOG.info("SKYCORE: Mod API location: {} / {}", serverType, mode);
    }

    /** Left the server or the world: the old location no longer holds. */
    public static void clear() {
        serverType = "";
        mode = "";
    }

    /** True when the Mod API says the player is on SkyBlock. False also means "not told". */
    public static boolean onSkyBlock() { return "SKYBLOCK".equalsIgnoreCase(serverType); }

    /** Readable area for the current mode, or null when unknown. */
    public static String area() { return areaFor(mode); }

    static String areaFor(String mode) { return mode == null ? null : AREAS.get(mode.toLowerCase()); }
}
