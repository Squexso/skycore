package dev.skycore.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageDumpTest {
    @Test
    void keepsRewardLines() {
        assertTrue(MessageDump.wanted("+440 HOTM Exp"));
        assertTrue(MessageDump.wanted("You earned 50 Mithril Faction Reputation"));
        assertTrue(MessageDump.wanted("GOBLIN SLAYER Commission Complete!"));
    }

    @Test
    void skipsPrivateAndUnrelatedChat() {
        assertFalse(MessageDump.wanted("From Steve: my hotm is 7"));
        assertFalse(MessageDump.wanted("Party > Steve: commission done"));
        assertFalse(MessageDump.wanted("Steve: hello"));
        assertFalse(MessageDump.wanted(""));
    }
}
