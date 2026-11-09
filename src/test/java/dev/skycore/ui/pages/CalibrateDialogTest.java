package dev.skycore.ui.pages;

import dev.skycore.data.StatDef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the CalibrateDialog crash (NPE "this.def is null" in the dialog body). Rendering itself needs a
 * running Minecraft, so these cover the state machine the render code relies on.
 */
class CalibrateDialogTest {
    @Test
    void startsClosed() {
        CalibrateDialog d = new CalibrateDialog();
        assertFalse(d.open());
        assertFalse(d.canSave());
        assertFalse(d.unavailable());
        assertNull(d.definition());
    }

    @Test
    void missingDefinitionOpensFallbackWithoutActions() {
        CalibrateDialog d = new CalibrateDialog();
        d.show((StatDef) null);
        assertTrue(d.open(), "the dialog explains the problem instead of silently doing nothing");
        assertTrue(d.unavailable());
        assertFalse(d.canSave(), "saving needs a definition");
        assertNull(d.definition(), "no unrelated stat is substituted");
    }

    @Test
    void unknownStatIdIsTheFallbackNotACrash() {
        CalibrateDialog d = new CalibrateDialog();
        d.show(StatDef.get("this_stat_does_not_exist"));
        assertTrue(d.unavailable());
        assertFalse(d.canSave());
        d.close();
        assertFalse(d.open());
    }

    @Test
    void validDefinitionIsUsable() {
        CalibrateDialog d = new CalibrateDialog();
        StatDef def = StatDef.get("catacombs");
        assertNotNull(def);
        d.show(def);
        assertTrue(d.open());
        assertTrue(d.canSave());
        assertFalse(d.unavailable());
        assertSame(def, d.definition());
    }

    @Test
    void closeResetsStateAndIsIdempotent() {
        CalibrateDialog d = new CalibrateDialog();
        d.show(StatDef.get("catacombs"));
        d.close();
        d.close();
        assertFalse(d.open());
        assertFalse(d.canSave());
        assertNull(d.definition());
    }

    @Test
    void openCloseReopenRepeatedly() {
        CalibrateDialog d = new CalibrateDialog();
        for (int i = 0; i < 200; i++) {
            d.show(StatDef.get("catacombs"));
            assertTrue(d.canSave());
            d.close();
            d.show((StatDef) null);
            assertTrue(d.unavailable() && !d.canSave());
            d.close();
            assertFalse(d.open());
        }
    }

    @Test
    void reopeningAfterFallbackClearsTheFallback() {
        CalibrateDialog d = new CalibrateDialog();
        d.show((StatDef) null);
        d.show(StatDef.get("catacombs"));
        assertFalse(d.unavailable());
        assertTrue(d.canSave());
    }

    @Test
    void everyDefinedStatCanBeOpened() {
        CalibrateDialog d = new CalibrateDialog();
        for (StatDef def : StatDef.ALL.values()) {
            d.show(def);
            assertTrue(d.canSave(), def.id());
            d.close();
        }
        for (String cls : StatDef.CLASSES) {
            // byClass can return null for an unknown class: that must lead to the fallback, never an exception
            d.show(StatDef.byClass(cls));
            assertTrue(d.open(), cls);
            d.close();
        }
    }

    @Test
    void xpInputIsParsedDefensively() {
        assertEquals(0, CalibrateDialog.parseXp(null));
        assertEquals(0, CalibrateDialog.parseXp(""));
        assertEquals(0, CalibrateDialog.parseXp("abc"));
        assertEquals(0, CalibrateDialog.parseXp("-5"));
        assertEquals(12345, CalibrateDialog.parseXp("12345"));
    }
}
