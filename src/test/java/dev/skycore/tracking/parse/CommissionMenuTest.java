package dev.skycore.tracking.parse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Lore lines copied from the real Commissions menu. */
class CommissionMenuTest {
    private static final List<String> DONE = List.of(
            "Commissions are tasks given directly", "by the king which give bountiful", "rewards.", "",
            "Royal Mines Mithril", "Mine 250 Mithril Ore in Royal Mines.", "", "Rewards",
            "- +990 HOTM Exp (Daily Bonus)", "- +440 HOTM Exp", "- 500 Mithril Powder", "- +7.5k Mining Exp", "", "COMPLETED", "", "Click to claim rewards!");
    private static final List<String> NEW = List.of(
            "Commissions are tasks given directly", "", "Upper Mines Mithril", "Mine 250 Mithril Ore in Upper Mines.", "", "Rewards",
            "- +990 HOTM Exp (Daily Bonus)", "- +440 HOTM Exp", "- 500 Mithril Powder", "- +7.5k Mining Exp", "", "Progress", "------ 0%");

    @Test
    void finishedCommissionIsReadWithBothHotmLines() {
        MenuParser.Commission c = MenuParser.commission("Commission #2", DONE);
        assertTrue(c.done());
        assertEquals(1430, c.hotmXp(), 0.001);
    }

    @Test
    void unfinishedCommissionAndOtherItemsAreNotDone() {
        assertFalse(MenuParser.commission("Commission #2 NEW", NEW).done());
        assertNull(MenuParser.commission("Commission Milestones", DONE));
        assertNull(MenuParser.commission("Close", List.of("COMPLETED")));
    }

    @Test
    void replacingAFinishedCommissionCountsAsClaimed() {
        Map<Integer, MenuParser.Commission> before = Map.of(11, MenuParser.commission("Commission #1", DONE), 12, MenuParser.commission("Commission #2", NEW));
        Map<Integer, MenuParser.Commission> after = Map.of(11, MenuParser.commission("Commission #1 NEW", NEW), 12, MenuParser.commission("Commission #2", NEW));
        assertEquals(1430, MenuParser.claimedHotmXp(before, after), 0.001);
    }

    @Test
    void nothingIsClaimedWhileItStaysFinishedOrWhenTheSlotIsJustEmpty() {
        Map<Integer, MenuParser.Commission> before = Map.of(11, MenuParser.commission("Commission #1", DONE));
        assertEquals(0, MenuParser.claimedHotmXp(before, before), 0.001);
        assertEquals(0, MenuParser.claimedHotmXp(before, Map.of()), 0.001, "menu still loading, not a claim");
        assertEquals(0, MenuParser.claimedHotmXp(Map.of(), before), 0.001, "first look");
    }
}
