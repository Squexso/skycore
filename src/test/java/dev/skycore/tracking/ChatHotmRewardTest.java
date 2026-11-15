package dev.skycore.tracking;

import dev.skycore.tracking.parse.MenuParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatHotmRewardTest {
    @Test
    void readsTheRewardLine() {
        assertEquals(800, MenuParser.chatHotmReward("    +800 HOTM Exp"), 0.001);
        assertEquals(1430, MenuParser.chatHotmReward("+1,430 HOTM Exp"), 0.001);
    }

    @Test
    void ignoresOtherLines() {
        assertEquals(0, MenuParser.chatHotmReward("Steve: +800 HOTM Exp lol"), 0.001);
        assertEquals(0, MenuParser.chatHotmReward("Gemstone Powder x82"), 0.001);
        assertEquals(0, MenuParser.chatHotmReward(null), 0.001);
    }
}
