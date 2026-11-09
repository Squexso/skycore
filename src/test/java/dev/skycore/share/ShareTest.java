package dev.skycore.share;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skycore.core.Config;
import dev.skycore.core.Storage;
import dev.skycore.data.DataStatus;
import dev.skycore.share.Template.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ShareTest {
    private static ShareSettings settings() {
        ShareSettings s = new ShareSettings();
        s.normalize();
        return s;
    }

    private static Map<String, Value> vars(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Value) kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ milestone detection
    @Test
    void milestonesAreDetectedAndSmallOnesAreNotInTheDefaults() {
        ShareSettings s = settings();
        assertEquals(List.of(20, 30, 40, 50), s.catacombsLevels);
        assertFalse(s.catacombsLevels.contains(5), "small levels are not announced by default");
        assertEquals(40, Milestones.crossed(s.catacombsLevels, 39, 40));
        assertNull(Milestones.crossed(s.catacombsLevels, 40, 41));
        assertEquals(50, Milestones.crossed(s.catacombsLevels, 38, 50), "a jump over several only announces the highest");
        assertEquals(30, Milestones.next(s.catacombsLevels, 25));
        assertNull(Milestones.next(s.catacombsLevels, 50));
        assertEquals(List.of(500, 750, 1000, 1500, 2000), s.runMilestones);
        assertEquals(750, Milestones.crossed(s.runMilestones, 700, 760));
        assertEquals(List.of(7, 10), s.hotmTiers);
        assertTrue(s.skillMax);
    }

    @Test
    void slayerMajorFromTheConfiguredLevelOrTheMaximum() {
        ShareSettings s = settings();
        assertFalse(Milestones.isMajorSlayer(s, 5, 9));
        assertTrue(Milestones.isMajorSlayer(s, 7, 9));
        assertTrue(Milestones.isMajorSlayer(s, 5, 5), "the maximum of a slayer is always major (vampire has 5)");
    }

    // ------------------------------------------------------------------ checkpoints and duplicates
    @Test
    void checkpointsFireOnceAndOnlyTheMostAdvanced() {
        ShareSettings s = settings();
        ShareState st = new ShareState();
        String key = Milestones.goalKey("p", "g1", 1L);
        assertTrue(Milestones.checkpoints(s, st, key, 40, 10, 0.25).isEmpty(), "first sight never announces");
        var hits = Milestones.checkpoints(s, st, key, 40, 20, 0.52);
        assertEquals(1, hits.size());
        assertEquals("percent", hits.get(0).kind());
        assertEquals(50, hits.get(0).value());
        assertTrue(Milestones.checkpoints(s, st, key, 40, 20, 0.52).isEmpty(), "duplicate prevention");
        // 10 left, then a jump straight to 4 left: only "5 left" is announced, "10 left" is not repeated
        var ten = Milestones.checkpoints(s, st, key, 40, 30, 0.75);
        assertEquals(10, ten.get(0).value());
        var jump = Milestones.checkpoints(s, st, key, 40, 36, 0.9);
        assertEquals(1, jump.size());
        assertEquals(5, jump.get(0).value());
        assertEquals(2, Milestones.checkpoints(s, st, key, 40, 38, 0.95).get(0).value());
        assertTrue(Milestones.checkpoints(s, st, key, 40, 38, 0.95).isEmpty());
    }

    @Test
    void aGoalThatAlreadyPassedCheckpointsStaysSilent() {
        ShareSettings s = settings();
        ShareState st = new ShareState();
        String key = Milestones.goalKey("p", "g2", 1L);
        assertTrue(Milestones.checkpoints(s, st, key, 40, 36, 0.9).isEmpty(), "no retroactive messages for 50 % / 10 left / 5 left");
        assertTrue(Milestones.checkpoints(s, st, key, 40, 36, 0.9).isEmpty());
        assertEquals(2, Milestones.checkpoints(s, st, key, 40, 38, 0.95).get(0).value());
    }

    @Test
    void completionIsAnnouncedOncePerGoalCycle() {
        ShareState st = new ShareState();
        String a = Milestones.goalKey("p", "g", 100L), b = Milestones.goalKey("p", "g", 200L);
        assertTrue(Milestones.completion(st, a));
        assertFalse(Milestones.completion(st, a));
        assertTrue(Milestones.completion(st, b), "a goal created again is a new cycle");
    }

    @Test
    void checkpointsUseTheConfiguredValues() {
        ShareSettings s = settings();
        s.checkpointPercents = new ArrayList<>(List.of(25));
        s.checkpointRemaining = new ArrayList<>(List.of(3));
        ShareState st = new ShareState();
        String key = Milestones.goalKey("p", "g3", 1L);
        Milestones.checkpoints(s, st, key, 40, 1, 0.02);
        var hits = Milestones.checkpoints(s, st, key, 40, 12, 0.3);
        assertEquals(25, hits.get(0).value());
        assertTrue(Milestones.checkpoints(s, st, key, 40, 20, 0.5).isEmpty(), "50 % is not configured, so it does not fire");
        assertEquals(3, Milestones.checkpoints(s, st, key, 40, 37, 0.93).get(0).value());
    }

    // ------------------------------------------------------------------ restart persistence
    @Test
    void announcedKeysSurviveARestart(@TempDir Path dir) {
        Storage.useDir(dir);
        ShareState st = new ShareState();
        String key = Milestones.goalKey("p", "g4", 1L);
        Milestones.checkpoints(settings(), st, key, 40, 10, 0.25);
        assertEquals(1, Milestones.checkpoints(settings(), st, key, 40, 20, 0.5).size());
        assertTrue(st.fire("lvl|p|catacombs|40"));
        Prepared p = new Prepared();
        p.text = "hello";
        st.add(p);
        Storage.save("share.json", st);
        Storage.flush();
        ShareState loaded = Storage.load("share.json", new com.google.gson.reflect.TypeToken<ShareState>() {}.getType(), ShareState::new);
        loaded.normalize();
        assertTrue(Milestones.checkpoints(settings(), loaded, key, 40, 20, 0.5).isEmpty(), "the 50 % checkpoint is not repeated after a restart");
        assertFalse(loaded.fire("lvl|p|catacombs|40"), "the level milestone is not repeated after a restart");
        assertEquals(1, loaded.inbox.size());
        assertEquals("hello", loaded.inbox.get(0).text);
    }

    @Test
    void stateStaysBounded() {
        ShareState st = new ShareState();
        for (int i = 0; i < ShareState.MAX_KEYS + 50; i++) st.fire("k" + i);
        assertEquals(ShareState.MAX_KEYS, st.fired.size());
        for (int i = 0; i < ShareState.MAX_INBOX + 5; i++) { Prepared p = new Prepared(); p.text = "m" + i; st.add(p); }
        assertEquals(ShareState.MAX_INBOX, st.inbox.size());
        assertEquals("m" + (ShareState.MAX_INBOX + 4), st.inbox.get(0).text, "newest first");
    }

    // ------------------------------------------------------------------ templates
    @Test
    void placeholdersAreReplaced() {
        String out = Template.render("{milestone} done by {player}. Next: {next_goal}.",
                vars("milestone", Value.of("Catacombs 40"), "player", Value.of("Steve"), "next_goal", Value.of("Catacombs 50")));
        assertEquals("Catacombs 40 done by Steve. Next: Catacombs 50.", out);
    }

    @Test
    void missingValuesDropTheSentenceInsteadOfLeavingAGap() {
        Map<String, Value> v = vars("milestone", Value.of("Catacombs 40"));
        assertEquals("Catacombs 40 secured.", Template.render("{milestone} secured. After {runs_since} runs.", v));
        assertEquals("Catacombs 40 secured.", Template.render("{milestone} secured. Total {runs_total} runs!", v));
        v.put("runs_total", new Value("12", DataStatus.UNAVAILABLE));
        assertEquals("Catacombs 40 secured.", Template.render("{milestone} secured. Total {runs_total} runs!", v), "unavailable is treated as missing");
        assertNull(Template.render("{runs_total} runs, {runs_since} since", v), "nothing usable left");
        assertNull(Template.render("", v));
        assertNull(Template.render(null, v));
    }

    @Test
    void estimatesAreMarkedAndObservedValuesAreNot() {
        Map<String, Value> v = vars("remaining", Value.estimated("2 runs"), "milestone", Value.of("Catacombs 40"),
                "runs_total", Value.tracked("1,250"), "current_level", new Value("40", DataStatus.MANUAL));
        assertEquals("~2 runs left for Catacombs 40.", Template.render("{remaining} left for {milestone}.", v));
        assertEquals("1,250 runs.", Template.render("{runs_total} runs.", v));
        assertEquals("Level ~40.", Template.render("Level {current_level}.", v), "hand-entered values are marked too");
    }

    @Test
    void unknownPlaceholdersAreReportedAndNeverShownRaw() {
        assertEquals(List.of("nope"), Template.unknown("Hi {nope} {player}"));
        assertEquals("Hello.", Template.render("Hello. {nope} is here.", vars()));
        for (String p : Template.PLACEHOLDERS) assertTrue(Template.unknown("{" + p + "}").isEmpty(), p);
        assertEquals(9, Template.PLACEHOLDERS.size());
    }

    @Test
    void variantsDoNotRepeatTheSameWording() {
        Random rnd = new Random(1);
        int last = -1;
        for (int i = 0; i < 200; i++) {
            int n = Template.pick(3, last, rnd);
            assertTrue(n >= 0 && n < 3);
            assertNotEquals(last, n);
            last = n;
        }
        assertEquals(0, Template.pick(1, 0, rnd));
    }

    @Test
    void messageBuilderFallsBackToAnotherVariantAndReturnsNullWithoutData() {
        ShareSettings s = settings();
        Map<String, Value> v = vars("milestone", Value.of("Catacombs 40"));
        var b = MessageBuilder.build(s, "catacombs", Destination.PARTY, List.of("{runs_total} runs total", "{milestone} secured"), -1, v, new Random(3));
        assertEquals("Catacombs 40 secured", b.text());
        assertFalse(b.estimated());
        assertNull(MessageBuilder.build(s, "catacombs", Destination.PARTY, List.of("{runs_total} runs total"), -1, v, new Random(3)), "never invents a missing value");
        assertNull(MessageBuilder.build(s, "catacombs", Destination.PARTY, List.of(), -1, v, new Random(3)));
        var est = MessageBuilder.build(s, "catacombs", Destination.PARTY, List.of("About {remaining} left"), -1, vars("remaining", Value.estimated("3 runs")), new Random(3));
        assertEquals("About ~3 runs left", est.text());
        assertTrue(est.estimated());
    }

    @Test
    void signatureIsOptionalAndPerCategory() {
        ShareSettings s = settings();
        Map<String, Value> v = vars("milestone", Value.of("Catacombs 40"));
        assertEquals("Catacombs 40", MessageBuilder.build(s, "catacombs", Destination.PARTY, List.of("{milestone}"), -1, v, new Random()).text(), "off by default");
        s.category("catacombs").watermark = true;
        assertEquals("Catacombs 40 | SKYCORE", MessageBuilder.build(s, "catacombs", Destination.PARTY, List.of("{milestone}"), -1, v, new Random()).text());
        assertEquals("Catacombs 40", MessageBuilder.build(s, "slayer", Destination.PARTY, List.of("{milestone}"), -1, v, new Random()).text(), "other categories are independent");
        s.signature = "— SKYCORE Private";
        assertTrue(MessageBuilder.build(s, "catacombs", Destination.ALL, List.of("{milestone}"), -1, v, new Random()).text().endsWith("— SKYCORE Private"));
        s.signature = "  ";
        assertEquals("Catacombs 40", MessageBuilder.build(s, "catacombs", Destination.PARTY, List.of("{milestone}"), -1, v, new Random()).text(), "an empty signature adds nothing");
    }

    @Test
    void longMessagesFitTheChatLimitIncludingThePrefix() {
        ShareSettings s = settings();
        s.category("catacombs").watermark = true;
        String longText = "word ".repeat(80).trim();
        var b = MessageBuilder.build(s, "catacombs", Destination.PARTY, List.of(longText), -1, vars(), new Random());
        assertTrue(b.text().length() + Destination.PARTY.prefix.length() <= MessageBuilder.CHAT_LIMIT);
        assertFalse(b.text().contains("SKYCORE"), "the signature is dropped before the message is shortened");
        assertTrue(b.text().endsWith("…"));
    }

    // ------------------------------------------------------------------ routing
    @Test
    void defaultRoutingFollowsTheSpecification() {
        ShareSettings s = settings();
        assertEquals(Destination.ALL, Routing.resolve(s, "hotm_major", null));
        assertEquals(Destination.PARTY, Routing.resolve(s, "catacombs", "catacombs"));
        assertEquals(Destination.PARTY, Routing.resolve(s, "slayer", "slayer_zombie"));
        assertEquals(Destination.ALL, Routing.resolve(s, "slayer_major", "slayer_enderman"));
        assertEquals(Destination.MANUAL, Routing.resolve(s, "runs", "catacombs"), "large run milestones are configurable, manual by default");
        assertEquals(Destination.PARTY, Routing.resolve(s, "goal_checkpoint", "catacombs"));
        assertEquals(Destination.CLIPBOARD, Routing.resolve(s, "collection", null));
    }

    @Test
    void customRulesOverrideDefaultsAndTheFirstMatchWins() {
        ShareSettings s = settings();
        s.rules.add(new ShareSettings.RouteRule("slayer_major", "slayer_enderman", "party"));
        s.rules.add(new ShareSettings.RouteRule("slayer_major", "", "clipboard"));
        s.rules.add(new ShareSettings.RouteRule("slayer_major", "", "all"));
        assertEquals(Destination.PARTY, Routing.resolve(s, "slayer_major", "slayer_enderman"));
        assertEquals(Destination.CLIPBOARD, Routing.resolve(s, "slayer_major", "slayer_zombie"), "an any-stat rule applies to the rest");
        assertEquals(Destination.PARTY, Routing.resolve(s, "catacombs", "catacombs"), "other categories keep their default");
        s.category("catacombs").destination = "all";
        assertEquals(Destination.ALL, Routing.resolve(s, "catacombs", "catacombs"), "the category's own setting is used without a rule");
    }

    @Test
    void disabledCategoriesAndTheMasterSwitchStopPreparing() {
        ShareSettings s = settings();
        assertTrue(Routing.enabled(s, "catacombs"));
        s.category("catacombs").enabled = false;
        assertFalse(Routing.enabled(s, "catacombs"));
        assertTrue(Routing.enabled(s, "slayer"));
        s.enabled = false;
        assertFalse(Routing.enabled(s, "slayer"));
    }

    // ------------------------------------------------------------------ clipboard
    private static Prepared msg(String text, Destination d) {
        Prepared p = new Prepared();
        p.text = text;
        p.destination = d.id();
        return p;
    }

    @Test
    void theClipboardIsNeverWrittenWithoutACopyAction() {
        ShareSettings s = settings();
        List<String> clip = new ArrayList<>();
        Inbox box = new Inbox(new ShareState(), clip::add);
        assertFalse(s.autoCopy, "auto-copy is off by default");
        box.add(s, msg("Another one!", Destination.PARTY));
        box.add(s, msg("Second", Destination.ALL));
        assertTrue(clip.isEmpty(), "preparing a message must not touch the clipboard");
        Prepared first = box.latest();
        box.copy(first, Destination.ALL);
        assertEquals(List.of("/ac Second"), clip, "copy writes exactly once, with the prefix the player confirms");
        assertTrue(box.justCopied(first), "visual confirmation");
        box.copy(first, Destination.CLIPBOARD);
        assertEquals("Second", clip.get(1), "plain copy has no command prefix");
    }

    @Test
    void autoCopyOnlyWhenTheUserEnabledIt() {
        ShareSettings s = settings();
        s.autoCopy = true;
        List<String> clip = new ArrayList<>();
        Inbox box = new Inbox(new ShareState(), clip::add);
        box.add(s, msg("Hello", Destination.PARTY));
        assertEquals(List.of("/pc Hello"), clip);
        box.add(s, msg("Manual one", Destination.MANUAL));
        assertEquals("Manual one", clip.get(1), "a manual destination is copied as plain text");
    }

    @Test
    void destinationsMapToChatPrefixesOnly() {
        assertEquals("/pc ", Destination.PARTY.prefix);
        assertEquals("/ac ", Destination.ALL.prefix);
        assertEquals("", Destination.CLIPBOARD.prefix);
        assertEquals("", Destination.MANUAL.prefix);
        assertEquals(Destination.PARTY, Destination.parse("PARTY", null));
        assertNull(Destination.parse("pm", null));
    }

    // ------------------------------------------------------------------ community
    private static ShareSettings community() {
        ShareSettings s = settings();
        s.community.enabled = true;
        s.community.installUrl = "https://example.org/skycore";
        return s;
    }

    private static final String DEF = "Interested in SKYCORE? Here's the installation guide: {install_url}";

    @Test
    void onlyTheExactTriggerInPartyChatIsRecognized() {
        assertEquals("Alex", Community.requester("Party > Alex: !skycore", "Me"));
        assertEquals("Alex", Community.requester("Party > [MVP+] Alex: !skycore", "Me"));
        assertEquals("Alex", Community.requester("Party > [MVP++] [GOD] Alex: !skycore", "Me"));
        assertNull(Community.requester("[MVP+] Alex: !skycore", "Me"), "All Chat");
        assertNull(Community.requester("Guild > Alex: !skycore", "Me"));
        assertNull(Community.requester("From [MVP+] Alex: !skycore", "Me"), "private message");
        assertNull(Community.requester("To Alex: !skycore", "Me"));
        assertNull(Community.requester("Party > Alex: !SKYCORE", "Me"), "exact match");
        assertNull(Community.requester("Party > Alex: !skycore please", "Me"));
        assertNull(Community.requester("Party > Alex:  !skycore", "Me"));
        assertNull(Community.requester("Party > Alex: hello !skycore", "Me"));
        assertNull(Community.requester("[MVP+] A: Party > Alex: !skycore", "Me"), "a spoof typed into another channel does not count");
        assertNull(Community.requester(null, "Me"));
    }

    @Test
    void ownMessagesAreIgnored() {
        assertNull(Community.requester("Party > Me: !skycore", "Me"));
        assertNull(Community.requester("Party > [VIP] me: !skycore", "Me"), "case-insensitive name");
    }

    @Test
    void aValidRequestPreparesAResponseWithTheConfiguredLinkOnly() {
        ShareSettings s = community();
        ShareState st = new ShareState();
        Prepared p = Community.handle(s, st, "Party > Alex: !skycore", "Me", 1_000_000L, DEF);
        assertNotNull(p);
        assertEquals("Interested in SKYCORE? Here's the installation guide: https://example.org/skycore", p.text);
        assertEquals(Destination.PARTY, p.destination());
        assertEquals("/pc " + p.text, p.clipboardText(Destination.PARTY));
        s.community.response = "Get it here: {install_url}";
        assertEquals("Get it here: https://example.org/skycore", Community.response(s, DEF));
    }

    @Test
    void cooldownPreventsSpam() {
        ShareSettings s = community();
        s.community.cooldownSeconds = 60;
        ShareState st = new ShareState();
        long t = 10_000_000L;
        assertNotNull(Community.handle(s, st, "Party > A: !skycore", "Me", t, DEF));
        assertNull(Community.handle(s, st, "Party > B: !skycore", "Me", t + 59_000, DEF), "inside the cooldown");
        assertNotNull(Community.handle(s, st, "Party > B: !skycore", "Me", t + 60_000, DEF), "after the cooldown");
        assertNull(Community.handle(s, st, "Party > Me: !skycore", "Me", t + 500_000, DEF));
        assertEquals(t + 60_000, st.lastCommunity, "ignored messages do not start a cooldown");
    }

    @Test
    void theFeatureStaysInactiveWithoutEnableOrUrl() {
        ShareState st = new ShareState();
        ShareSettings off = community();
        off.community.enabled = false;
        assertNull(Community.handle(off, st, "Party > A: !skycore", "Me", 1L, DEF), "disabled");
        ShareSettings noUrl = community();
        noUrl.community.installUrl = "";
        assertFalse(Community.active(noUrl));
        assertNull(Community.handle(noUrl, st, "Party > A: !skycore", "Me", 1L, DEF), "empty URL");
        noUrl.community.installUrl = "   ";
        assertNull(Community.handle(noUrl, st, "Party > A: !skycore", "Me", 1L, DEF));
        assertEquals(0, st.lastCommunity);
    }

    @Test
    void theInstallUrlIsEmptyByDefaultAndValidated() {
        ShareSettings fresh = new ShareSettings();
        assertEquals("", fresh.community.installUrl, "never hardcoded");
        assertFalse(fresh.community.enabled);
        assertTrue(Community.validUrl("https://example.org/a/b?x=1"));
        assertTrue(Community.validUrl("http://example.org"));
        assertFalse(Community.validUrl(""));
        assertFalse(Community.validUrl("example.org"));
        assertFalse(Community.validUrl("javascript:alert(1)"));
        assertFalse(Community.validUrl("https://exa mple.org"));
        assertFalse(Community.validUrl("https://localhost"), "needs a real host name");
        assertFalse(Community.validUrl("https://example.org/" + "a".repeat(200)));
        assertFalse(Community.active(fresh));
    }

    // ------------------------------------------------------------------ settings and shipped texts
    @Test
    void existingConfigsWithoutShareSettingsKeepWorking() {
        Config c = Storage.GSON.fromJson("{\"language\":\"de\",\"hudEnabled\":false}", Config.class);
        assertNotNull(c.share, "an old config.json has no share block: defaults apply");
        c.normalize();
        assertEquals("de", c.language, "existing settings are untouched");
        assertFalse(c.hudEnabled);
        assertFalse(c.share.autoCopy);
        assertEquals(10, c.share.categories.size());
    }

    @Test
    void normalizeKeepsWhatTheUserConfigured() {
        ShareSettings s = settings();
        s.catacombsLevels = new ArrayList<>(List.of(35, 35, 99, 12));
        s.category("hotm").destination = "bogus";
        s.slayerLevels = new ArrayList<>();
        s.normalize();
        assertEquals(List.of(12, 35), s.catacombsLevels, "sorted, distinct, in range");
        assertEquals("party", s.category("hotm").destination, "an invalid destination falls back to the default");
        assertTrue(s.slayerLevels.isEmpty(), "a list the user emptied stays empty");
    }

    private static JsonObject lang(String code) throws Exception {
        try (InputStream in = ShareTest.class.getResourceAsStream("/assets/skycore/i18n/" + code + ".json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void englishAndGermanHaveTheSameShareKeysAndValidDefaultTemplates() throws Exception {
        JsonObject en = lang("en"), de = lang("de");
        for (String k : en.keySet()) if (k.startsWith("share.") || k.equals("nav.share") || k.equals("page.share.sub"))
            assertTrue(de.has(k), "missing German text: " + k);
        for (String k : de.keySet()) if (k.startsWith("share.")) assertTrue(en.has(k), "missing English text: " + k);
        for (Kind kind : Kind.values()) {
            for (JsonObject lang : new JsonObject[]{en, de}) {
                int n = 0;
                while (lang.has("share.tpl." + kind.id() + "." + (n + 1))) {
                    String tpl = lang.get("share.tpl." + kind.id() + "." + (n + 1)).getAsString();
                    assertTrue(Template.unknown(tpl).isEmpty(), "unknown placeholder in " + tpl);
                    assertFalse(Template.used(tpl).isEmpty(), "a default text without any value: " + tpl);
                    n++;
                }
                assertTrue(n >= 3, kind + " needs at least 3 default variations");
            }
        }
        for (String k : new String[]{"share.dest.party", "share.dest.all", "share.dest.clipboard", "share.dest.manual", "share.community.default"})
            assertTrue(en.has(k) && de.has(k));
        assertTrue(en.get("share.community.default").getAsString().contains("{install_url}"));
        assertFalse(en.get("share.community.default").getAsString().contains("http"), "no URL is hardcoded");
        assertFalse(de.get("share.community.default").getAsString().contains("http"));
        for (String k : en.keySet()) if (k.startsWith("share.")) assertFalse(en.get(k).getAsString().toLowerCase().contains("github"), k);
        for (int cat = 0; cat < ShareSettings.CATEGORIES.length; cat++) assertTrue(en.has("share.cat." + ShareSettings.CATEGORIES[cat]));
    }
}
