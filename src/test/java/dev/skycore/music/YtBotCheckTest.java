package dev.skycore.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YtBotCheckTest {
    @Test
    void recognizesTheSignInCheck() {
        assertTrue(YtDlp.botCheck("ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies-from-browser"));
        assertFalse(YtDlp.botCheck("ERROR: Video unavailable"));
        assertFalse(YtDlp.botCheck(null));
    }
}
