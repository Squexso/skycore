package dev.skycore.ui.pages;

import dev.skycore.core.Lang;
import dev.skycore.data.ProfileData.StatProgress;
import dev.skycore.data.Progress;
import dev.skycore.data.StatDef;
import dev.skycore.data.XpTables;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.gfx.Icon;

/**
 * Lets the user enter a level / XP the mod cannot observe directly (e.g. Catacombs XP is only visible in
 * the profile viewer). After calibration every recorded gain moves the value forward automatically.
 */
public final class CalibrateDialog {
    private StatDef def;
    /** a calibration was requested but no stat definition exists for it: show a message, offer no actions */
    private boolean unavailable;
    private int level;
    private String xp = "";

    public boolean open() { return def != null || unavailable; }

    /** True only while a valid definition is loaded, i.e. when saving makes sense. */
    public boolean canSave() { return def != null; }

    public boolean unavailable() { return unavailable; }

    public StatDef definition() { return def; }

    public void show(Ui ui, StatDef d) {
        show(d);
        ui.resetModalAnim();
    }

    /** State transition without any rendering; a null definition opens the fallback state, never a stand-in stat. */
    public void show(StatDef d) {
        def = d;
        unavailable = d == null;
        level = 0;
        xp = "";
        if (d == null) return;
        StatProgress p = Progress.real().peek(d.id());
        level = Math.max(0, Math.min(d.maxLevel(), p.level));
        xp = p.known() && p.need > 0 ? String.valueOf(Math.round(p.xp)) : "";
    }

    public void close() {
        def = null;
        unavailable = false;
    }

    static double parseXp(String text) {
        try { return text == null || text.isEmpty() ? 0 : Math.max(0, Double.parseDouble(text)); }
        catch (NumberFormatException e) { return 0; }
    }

    public void render(Ui ui, SkycoreScreen screen) {
        // The modal's close button / Escape / outside click run close() in the middle of this frame, before the body
        // lambda executes. The body therefore only ever uses this per-frame snapshot, never the live field.
        final StatDef d = def;
        if (d == null && !unavailable) return;
        boolean hasTable = d != null && d.table() != null;
        String title = d == null ? Lang.t("calibrate.title", Lang.t("calibrate.unknown_stat")) : Lang.t("calibrate.title", Lang.t(d.nameKey()));
        ui.modal(screen.screenRect(), 320, d == null ? 130 : hasTable ? 210 : 160, title, body -> {
            var g = ui.g;
            float y = body.y();
            if (d == null) {
                for (String line : g.wrap(Lang.t("calibrate.unavailable"), body.w(), 0.9f)) {
                    g.text(line, body.x(), y, ui.t.textDim(), 0.9f);
                    y += 10;
                }
                if (ui.button("cal#cancel", new Rect(body.right() - 80, body.bottom() - 20, 80, 20), Lang.t("action.close"), null, Ui.Style.GHOST)) close();
                return;
            }
            for (String line : g.wrap(Lang.t(Progress.isSample() ? "calibrate.sample" : "calibrate.desc"), body.w(), 0.9f)) {
                g.text(line, body.x(), y, ui.t.textDim(), 0.9f);
                y += 10;
            }
            y += 8;
            g.text(Lang.t("calibrate.level"), body.x(), y + 5, ui.t.text());
            level = ui.stepper("cal#lvl", new Rect(body.right() - 110, y, 110, 20), level, 0, d.maxLevel());
            y += 28;
            if (hasTable) {
                long need = XpTables.need(d.table(), level);
                g.text(Lang.t("calibrate.xp"), body.x(), y + 5, ui.t.text());
                xp = ui.textField("cal#xp", new Rect(body.right() - 110, y, 110, 20), xp, "0", 12, Character::isDigit);
                y += 24;
                if (need > 0) g.text(Lang.t("calibrate.xp_hint", Lang.num(need)), body.x(), y, ui.t.textMute(), 0.85f);
            }
            Rect save = new Rect(body.right() - 100, body.bottom() - 20, 100, 20);
            if (ui.button("cal#save", save, Lang.t("action.save"), Icon.CHECK, Ui.Style.PRIMARY)) {
                if (!Progress.isSample()) Progress.calibrate(d, level, parseXp(xp));
                close();
            }
            if (ui.button("cal#cancel", new Rect(save.x() - 86, save.y(), 80, 20), Lang.t("action.cancel"), null, Ui.Style.GHOST)) close();
        }, this::close);
    }
}
