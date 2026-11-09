package dev.skycore.tracking.parse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PatternSafetyTest {
    @Test
    void acceptsNormalPatterns() {
        assertNull(PatternSafety.check("^\\+(?<xp>[\\d.,]+) Catacombs-Erfahrung"));
        assertNull(PatternSafety.check("^FERTIGKEIT AUFGESTIEGEN (?<skill>\\w+) (?<level>[IVXLC\\d]+)$"));
        assertNull(PatternSafety.check("(?:a|b)?c+d{2,5}"));
        assertNull(PatternSafety.check("(?>a+)+b"), "atomic groups cannot backtrack");
    }

    @Test
    void rejectsDangerousPatterns() {
        assertNotNull(PatternSafety.check("(a+)+$"), "classic nested quantifier");
        assertNotNull(PatternSafety.check("(\\d*)*x"));
        assertNotNull(PatternSafety.check("(?:x|y+)*z"));
        assertNotNull(PatternSafety.check("^(.*a){20}$"), "counted repetition over .* explodes even on Java 21 – caught by the timed trial");
        assertNotNull(PatternSafety.check("(\\w)\\1"), "back-references");
        assertNotNull(PatternSafety.check("(unclosed"));
        assertNotNull(PatternSafety.check(""));
        assertNotNull(PatternSafety.check("a".repeat(PatternSafety.MAX_LENGTH + 1)));
    }

    @Test
    void runtimeBudgetDisablesAnExpensiveUserRule() {
        MessageParser parser = MessageParser.defaults();
        // bypasses validation on purpose: the runtime guard must still protect the game
        parser.register("custom:evil", MessageParser.Channel.CHAT, "^(.*a){20}$", 0.9, "en", m -> new ParsedEvent.RunSummary());
        long t0 = System.nanoTime();
        var r = parser.recognize("a".repeat(40) + "b", MessageParser.Channel.CHAT);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.isEmpty());
        assertTrue(ms < 2000, "aborted quickly (" + ms + " ms)");
        assertTrue(parser.disabledRules().containsKey("custom:evil"), "rule is switched off after exceeding the budget");
        // built-in rules keep working
        assertFalse(parser.recognize("+250 Catacombs Experience", MessageParser.Channel.CHAT).isEmpty());
    }

    @Test
    void validationBeforeActivation() {
        MessageParser parser = MessageParser.defaults();
        String json = """
                [
                  {"id":"ok","kind":"catacombs_xp","regex":"^XP (?<xp>\\\\d+)$","language":"de","confidence":0.8},
                  {"id":"nogroup","kind":"catacombs_xp","regex":"^XP (\\\\d+)$"},
                  {"id":"ok","kind":"run_summary","regex":"^dup$"},
                  {"id":"conf","kind":"run_summary","regex":"^x$","confidence":1.5},
                  {"id":"chan","kind":"run_summary","regex":"^x$","channel":"sidebar"},
                  {"id":"evil","kind":"run_summary","regex":"(a+)+$"},
                  {"id":"bad id!","kind":"run_summary","regex":"^x$"}
                ]""";
        CustomPatterns.Result r = CustomPatterns.load(parser, json);
        assertTrue(r.jsonValid());
        assertEquals(1, r.loaded(), r.errors().toString());
        assertEquals(6, r.errors().size(), r.errors().toString());
        assertEquals(7, r.entries().size());
        assertTrue(r.entries().get(0).active());
        assertTrue(r.entries().get(1).error().contains("(?<xp>"), r.entries().get(1).error());
        assertTrue(r.entries().get(2).error().contains("duplicate"));
        // user rules are separate from built-ins
        assertEquals(1, parser.rules().stream().filter(x -> x.id().startsWith("custom:")).count());
        assertTrue(parser.builtInCount() > 15);
        assertEquals(42, ((ParsedEvent.CatacombsXp) parser.parse("XP 42", MessageParser.Channel.CHAT).get(0)).xp(), 1e-9);
    }

    @Test
    void invalidJsonIsReportedNotApplied() {
        CustomPatterns.Result r = CustomPatterns.load(MessageParser.defaults(), "[{ broken");
        assertFalse(r.jsonValid());
        assertEquals(0, r.loaded());
        assertEquals(1, r.errors().size());
    }

    @Test
    void hitsAreCountedPerRule() {
        MessageParser p = MessageParser.defaults();
        p.recognize("+250 Catacombs Experience", MessageParser.Channel.CHAT);
        p.recognize("+300 Catacombs Experience", MessageParser.Channel.CHAT);
        assertEquals(2L, p.hits().get("cata_xp"));
    }
}
