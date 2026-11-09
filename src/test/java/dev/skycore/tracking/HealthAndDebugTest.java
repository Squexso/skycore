package dev.skycore.tracking;

import dev.skycore.tracking.Health.Source;
import dev.skycore.tracking.Health.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HealthAndDebugTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    void setUp() {
        Health.reset();
        Health.setClock(now::get);
        DebugLog.setEnabled(false);
    }

    @AfterEach
    void tearDown() {
        Health.setClock(null);
        DebugLog.setEnabled(false);
    }

    @Test
    void inactivityIsNeverAnError() {
        assertEquals(Status.WAITING, Health.status(Source.CHAT), "nothing seen yet = waiting");
        Health.available(Source.MENU, false, "health.note.no_menu");
        assertEquals(Status.UNAVAILABLE, Health.status(Source.MENU), "no menu open = unavailable, not error");
        Health.activity(Source.TAB);
        assertEquals(Status.WAITING, Health.status(Source.TAB), "activity without data is not an error either");
        Health.available(Source.CHAT, true, null);
        Health.success(Source.CHAT);
        assertEquals(Status.OPERATIONAL, Health.status(Source.CHAT));
        Health.available(Source.CHAT, false, "health.note.offline");
        assertEquals(Status.UNAVAILABLE, Health.status(Source.CHAT), "worked before, now offline = unavailable");
    }

    @Test
    void errorsAreReportedAndClearedBySuccessOrTime() {
        Health.success(Source.SIDEBAR);
        now.addAndGet(1000);
        Health.failure(Source.SIDEBAR, "NullPointerException");
        assertEquals(Status.ERROR, Health.status(Source.SIDEBAR));
        assertEquals(1, Health.errorCount());
        assertEquals(Status.ERROR, Health.overall());
        Health.Stats st = Health.stats(Source.SIDEBAR);
        assertEquals(1, st.successes);
        assertEquals(1, st.failures);
        assertEquals("NullPointerException", st.lastErrorText);
        // a later success clears the error state (the error stays counted)
        now.addAndGet(1000);
        Health.success(Source.SIDEBAR);
        assertEquals(Status.OPERATIONAL, Health.status(Source.SIDEBAR));
        // an old error expires
        Health.failure(Source.TAB, "boom");
        now.addAndGet(Health.ERROR_WINDOW + 1);
        assertNotEquals(Status.ERROR, Health.status(Source.TAB));
    }

    @Test
    void disabledWinsOverEverything() {
        Health.failure(Source.SLAYER, "x");
        Health.disabled(Source.SLAYER, true);
        assertEquals(Status.DISABLED, Health.status(Source.SLAYER));
    }

    @Test
    void errorTextIsBounded() {
        Health.failure(Source.PERSISTENCE, "x".repeat(500));
        assertTrue(Health.stats(Source.PERSISTENCE).lastErrorText.length() <= 120);
    }

    // ------------------------------------------------------------------ debugger
    private static DebugLog.Entry entry(DebugLog.Filter f, String profile) {
        return new DebugLog.Entry(System.currentTimeMillis(), f, "CatacombsXp", "cata_xp", 0.98, Map.of("xp", "300"), profile, "new", true, "run_completed", "progress");
    }

    @Test
    void debuggerIsOffByDefaultAndBounded() {
        DebugLog.add(entry(DebugLog.Filter.CHAT, "Apple"));
        assertEquals(0, DebugLog.size(), "off by default – nothing kept");
        DebugLog.setEnabled(true);
        for (int i = 0; i < DebugLog.CAPACITY + 50; i++) DebugLog.add(entry(i % 2 == 0 ? DebugLog.Filter.CHAT : DebugLog.Filter.DUNGEON, "Apple"));
        assertEquals(DebugLog.CAPACITY, DebugLog.size(), "ring buffer is bounded");
        assertEquals(5, DebugLog.recent(EnumSet.of(DebugLog.Filter.DUNGEON), 5).size());
        assertTrue(DebugLog.recent(EnumSet.of(DebugLog.Filter.DUNGEON), 50).stream().allMatch(e -> e.filter == DebugLog.Filter.DUNGEON));
        DebugLog.setEnabled(false);
        assertEquals(0, DebugLog.size(), "switching off discards everything");
    }

    @Test
    void debuggerNeverKeepsChatText() {
        DebugLog.setEnabled(true);
        String secret = "Guild > Bob: my secret message 12345";
        DebugLog.unmatched(DebugLog.Filter.CHAT, secret, "Apple");
        DebugLog.playerChat();
        DebugLog.add(entry(DebugLog.Filter.DUNGEON, "Apple"));
        DebugLog.add(entry(DebugLog.Filter.DUNGEON, "Banana"));
        String exported = DebugLog.export().toString();
        assertFalse(exported.contains("secret"), exported);
        assertFalse(exported.contains("Bob"), exported);
        assertFalse(exported.contains("Apple"), "profile names are aliased in exports");
        assertTrue(exported.contains("profile-1") && exported.contains("profile-2"));
        assertEquals(1, DebugLog.unmatchedCount());
        assertEquals(1, DebugLog.playerChatCount());
        assertEquals(DebugLog.anonymousHash(secret), DebugLog.anonymousHash(secret), "stable hash – repeats are recognisable");
        assertEquals(8, DebugLog.anonymousHash(secret).length());
    }
}
