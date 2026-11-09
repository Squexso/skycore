package dev.skycore.tracking.parse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Museum, Community Center, Kuudra, Garden and Rift values – read only from menus the player opens and from panels. */
class CoverageParserTest {
    private static Map<String, Double> values(List<ParsedEvent> events) {
        return events.stream().filter(e -> e instanceof ParsedEvent.ValueReading).map(e -> (ParsedEvent.ValueReading) e)
                .collect(Collectors.toMap(ParsedEvent.ValueReading::key, ParsedEvent.ValueReading::value, (a, b) -> b));
    }

    @Test
    void menuValues() {
        var museum = MenuParser.parse("Your Museum", List.of(new MenuParser.Item("Museum Rewards", List.of("Items Donated: 120/350", "Click to view!"))));
        assertEquals(120, values(museum).get("museum_donated"));
        assertEquals(350, values(museum).get("museum_total"));

        var fame = MenuParser.parse("Community Shop", List.of(new MenuParser.Item("Fame Rank", List.of("Fame: 12,345"))));
        assertEquals(12_345, values(fame).get("community_fame"));

        var kuudra = MenuParser.parse("Kuudra Completions", List.of(
                new MenuParser.Item("Infernal", List.of("Completions: 42")),
                new MenuParser.Item("Stats", List.of("Burning Tier Completions: 7"))));
        assertEquals(42, values(kuudra).get("kuudra_completions_infernal"));
        assertEquals(7, values(kuudra).get("kuudra_completions_burning"));

        // the same lore in an unrelated menu is ignored (no guessing)
        assertTrue(values(MenuParser.parse("Auction House", List.of(new MenuParser.Item("Infernal", List.of("Completions: 42", "Fame: 5"))))).isEmpty());
    }

    @Test
    void menuKindIsKnownForBestiaryAndGarden() {
        var e = MenuParser.parse("Bestiary ➜ Hub", List.of(new MenuParser.Item("Zombie VII", List.of("Progress to Tier VIII: 40%", "40/100"))));
        assertEquals("bestiary", ((ParsedEvent.MenuProgress) e.get(0)).menu());
        var g = MenuParser.parse("Desk", List.of(new MenuParser.Item("Garden Level XV", List.of("Progress to Level XVI: 10%"))));
        assertEquals("garden", ((ParsedEvent.MenuProgress) g.get(0)).menu());
        assertEquals("Garden", ((ParsedEvent.MenuProgress) g.get(0)).name());
    }

    @Test
    void panelValues() {
        var v = values(PanelParser.tab(List.of(" Garden Level: XII", " Enigma Souls: 30/62", " Copper: 1,200")));
        assertEquals(12, v.get("garden_level"));
        assertEquals(30, v.get("rift_enigma_souls"));
        assertEquals(62, v.get("rift_enigma_total"));
        assertEquals(1200, v.get("garden_copper"));
    }
}
