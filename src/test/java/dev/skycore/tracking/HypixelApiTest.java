package dev.skycore.tracking;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HypixelApiTest {
    private static final UUID ME = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String ANSWER = "{\"success\":true,\"profiles\":["
            + "{\"cute_name\":\"Apple\",\"selected\":false,\"members\":{\"11111111222233334444555555555555\":{\"mining_core\":{\"experience\":1000.5}}}},"
            + "{\"cute_name\":\"Tomato\",\"selected\":true,\"members\":{\"11111111222233334444555555555555\":{\"mining_core\":{\"experience\":152640.0}}}}]}";

    @Test
    void theNamedProfileWinsOtherwiseTheSelectedOne() {
        assertEquals(1000.5, HypixelApi.hotmXp(ANSWER, ME, "apple"), 0.001);
        assertEquals(152640.0, HypixelApi.hotmXp(ANSWER, ME, "Tomato"), 0.001);
        assertEquals(152640.0, HypixelApi.hotmXp(ANSWER, ME, null), 0.001);
    }

    @Test
    void missingDataGivesNullInsteadOfAnError() {
        assertNull(HypixelApi.hotmXp("{\"success\":true,\"profiles\":null}", ME, "x"));
        assertNull(HypixelApi.hotmXp("{\"success\":true,\"profiles\":[]}", ME, "x"));
        assertNull(HypixelApi.hotmXp(ANSWER, UUID.randomUUID(), "Tomato"));
        assertNull(HypixelApi.hotmXp("{\"profiles\":[{\"cute_name\":\"A\",\"selected\":true,\"members\":{\"11111111222233334444555555555555\":{}}}]}", ME, "A"));
    }
}
