package dev.skycore.data;

import dev.skycore.core.Config;
import dev.skycore.ui.theme.Theme;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceAndRatesTest {
    @Test
    void onlyGameConfirmedValuesAreObserved() {
        assertEquals(Evidence.OBSERVED, Evidence.of(DataStatus.VERIFIED));
        // everything derived, event-based, manual or partial must never look verified
        assertEquals(Evidence.ESTIMATED, Evidence.of(DataStatus.EVENT_TRACKED));
        assertEquals(Evidence.ESTIMATED, Evidence.of(DataStatus.MANUAL));
        assertEquals(Evidence.ESTIMATED, Evidence.of(DataStatus.ESTIMATED));
        assertEquals(Evidence.UNAVAILABLE, Evidence.of(DataStatus.UNAVAILABLE));
        assertEquals(Evidence.UNAVAILABLE, Evidence.of(null));
        for (DataStatus s : DataStatus.values())
            if (s != DataStatus.VERIFIED) assertNotEquals(Evidence.OBSERVED, Evidence.of(s), s + " must not be observed");
    }

    @Test
    void rateIsUnknownWithoutEnoughPlaytime() {
        assertTrue(Double.isNaN(Rates.perHour(50_000, 0)));
        assertTrue(Double.isNaN(Rates.perHour(50_000, 299)));
        assertTrue(Double.isNaN(Rates.perHour(-1, 3600)));
        assertTrue(Double.isNaN(Rates.perHour(Double.NaN, 3600)));
    }

    @Test
    void rateIsXpOverHours() {
        assertEquals(100_000, Rates.perHour(100_000, 3600), 1e-6);
        assertEquals(60_000, Rates.perHour(10_000, 600), 1e-6);
        assertEquals(0, Rates.perHour(0, 1800), 1e-9); // played, gained nothing: a real zero, not unknown
    }

    @Test
    void newDefaultThemeIsRestrainedAndOtherPresetsAreUnchanged() {
        Config.ThemeSettings ts = new Config.ThemeSettings();
        assertEquals("skycore", ts.preset);
        assertFalse(ts.gradients, "gradients are off by default");
        assertEquals(0f, ts.glow);
        assertArrayEquals(new String[]{"skycore", "dark", "midnight", "purple_neon", "cyan_neon", "blue", "minimal_black", "custom"}, Theme.PRESETS);
        // the existing presets keep their original colours
        var mid = Theme.presetColors("midnight");
        assertEquals(0xFF3BC9F5, mid.get(Theme.Slot.PRIMARY));
        assertEquals(0xFF9B6BFF, mid.get(Theme.Slot.SECONDARY));
        assertEquals(0xFF0A0D1C, mid.get(Theme.Slot.BACKGROUND));
        var neon = Theme.presetColors("purple_neon");
        assertEquals(0xFFC86BFF, neon.get(Theme.Slot.PRIMARY));
        // gold is the XP colour; interaction colour is not gold
        var sky = Theme.presetColors("skycore");
        assertEquals(0xFFD9A63F, sky.get(Theme.Slot.PROGRESS));
        assertEquals(0xFFD9A63F, sky.get(Theme.Slot.CHART_1));
        assertNotEquals(sky.get(Theme.Slot.PRIMARY), sky.get(Theme.Slot.PROGRESS));
    }

    @Test
    void existingConfigsKeepTheirSavedThemeLayoutAndSettings() {
        // a config saved before the redesign: old default preset, gradients/glow on, custom card order
        String legacy = "{\"theme\":{\"preset\":\"midnight\",\"basePreset\":\"midnight\",\"glow\":0.35,\"gradients\":true,\"radius\":8.0},"
                + "\"overview\":[{\"id\":\"profile\",\"visible\":true,\"span\":1},{\"id\":\"catacombs\",\"visible\":false,\"span\":1}],"
                + "\"lastPage\":3}";
        Config c = new com.google.gson.Gson().fromJson(legacy, Config.class);
        c.normalize();
        assertEquals("midnight", c.theme.preset, "saved preset must not be replaced by the new default");
        assertTrue(c.theme.gradients);
        assertEquals(0.35f, c.theme.glow);
        assertEquals("profile", c.overview.get(0).id, "saved card order is kept");
        assertEquals("catacombs", c.overview.get(1).id);
        assertFalse(c.overview.get(1).visible, "hidden cards stay hidden");
        assertEquals(1, c.overview.get(1).span, "saved sizes stay");
        assertEquals(3, c.lastPage);
        // a missing card is appended with the new default size, nothing else is reordered
        assertTrue(c.overview.stream().anyMatch(x -> x.id.equals("xp_chart") && x.span == 2));
        assertFalse(Theme.presetColors(c.theme.preset).isEmpty());
        for (String p : Theme.PRESETS) if (!p.equals("custom")) assertFalse(Theme.presetColors(p).isEmpty(), p);
    }

    @Test
    void englishAndGermanStringsStayInParity() throws Exception {
        Map<?, ?> en = load("en"), de = load("de");
        for (Object k : en.keySet()) assertTrue(de.containsKey(k), "missing in de: " + k);
        for (Object k : de.keySet()) assertTrue(en.containsKey(k), "missing in en: " + k);
        for (String k : new String[]{"evidence.observed", "evidence.estimated", "evidence.unavailable", "theme.skycore", "overview.vs_yesterday"})
            assertTrue(en.containsKey(k) && de.containsKey(k), k);
        assertFalse(en.containsKey("overview.greeting"));
        for (String p : Theme.PRESETS) {
            assertTrue(en.containsKey("theme.desc." + p) && de.containsKey("theme.desc." + p), "theme description for " + p);
            assertTrue(en.containsKey("theme." + p) && de.containsKey("theme." + p), "theme name for " + p);
        }
        for (String k : new String[]{"overview.xp_today_tip", "overview.playtime_tip"})
            assertTrue(en.containsKey(k) && de.containsKey(k), k);
    }

    private static Map<?, ?> load(String lang) throws Exception {
        try (InputStream in = EvidenceAndRatesTest.class.getResourceAsStream("/assets/skycore/i18n/" + lang + ".json")) {
            assertNotNull(in);
            return new com.google.gson.Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
    }
}
