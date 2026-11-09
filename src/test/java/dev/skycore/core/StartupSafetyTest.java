package dev.skycore.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SKYCORE must never run during Fabric's pre-launch phase (mod discovery, mixin setup, Sodium's graphics adapter
 * probe). It may only start through the client entrypoint, after Minecraft is running – so it can never take part in
 * class-loading or mixin deadlocks of other mods at "Searching for graphics cards...".
 */
class StartupSafetyTest {
    private static JsonObject modJson() throws Exception {
        var in = StartupSafetyTest.class.getClassLoader().getResourceAsStream("fabric.mod.json");
        assertNotNull(in, "fabric.mod.json on the classpath");
        try (var r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    @Test
    void onlyTheClientEntrypointNoEarlyHooks() throws Exception {
        JsonObject m = modJson();
        JsonObject entry = m.getAsJsonObject("entrypoints");
        assertEquals(Set.of("client"), entry.keySet(), "no preLaunch / main / other early entrypoints");
        assertFalse(m.has("mixins"), "no mixins – SKYCORE never transforms classes during startup");
        assertFalse(m.has("accessWidener"), "no access wideners");
        assertFalse(m.has("jars"), "no nested jars");
        assertEquals("client", m.get("environment").getAsString());
    }

    @Test
    void loaderRequirementAcceptsTheInstalledLoader() throws Exception {
        String req = modJson().getAsJsonObject("depends").get("fabricloader").getAsString();
        assertEquals(">=0.19.0", req, "must accept Fabric Loader 0.19.5 (the user's instance)");
    }

    @Test
    void entrypointClassDoesNotInitialiseAnythingWhenLoaded() throws Exception {
        // loading (not initialising) the entrypoint class must not run SKYCORE code – Fabric may inspect it early
        Class<?> c = Class.forName("dev.skycore.SkycoreClient", false, getClass().getClassLoader());
        assertEquals(1, c.getDeclaredMethods().length, "only onInitializeClient()");
        assertEquals("onInitializeClient", c.getDeclaredMethods()[0].getName());
    }
}
