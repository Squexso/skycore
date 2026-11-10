package dev.skycore.ui.pages;

import dev.skycore.core.Lang;
import dev.skycore.data.Progress;
import dev.skycore.hud.HudCanvas;
import dev.skycore.hud.HudEditorScreen;
import dev.skycore.hud.HudInspector;
import dev.skycore.hud.HudStore;
import dev.skycore.hud.HudWidgets;
import dev.skycore.notify.Notifier;
import dev.skycore.ui.Page;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.Widgets;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Visual editor for every in-game widget: layout preview, properties and layout profiles. */
public class HudStudioPage implements Page {
    private final HudCanvas canvas = new HudCanvas();
    private final HudInspector inspector = new HudInspector();
    private boolean sample = true;
    private boolean renaming;
    private String newName = "";

    @Override public String id() { return "hud"; }
    @Override public Icon icon() { return Icon.HUD; }

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Minecraft mc = Minecraft.getInstance();
        float sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        boolean wide = area.w() >= 700;
        float leftW = wide ? 196 : area.w();
        float y = area.y() + 6;

        // ---------------- left column: widgets + profiles
        Rect list = new Rect(area.x(), y, leftW, 34 + HudStore.WIDGETS.length * 28);
        ui.card(list);
        Widgets.sectionTitle(ui, list.x() + 12, list.y() + 12, Lang.t("hud.widgets"));
        float ry = list.y() + 30;
        for (String id : HudStore.WIDGETS) {
            HudStore.WidgetState w = HudStore.widget(id);
            Rect row = new Rect(list.x() + 6, ry, list.w() - 12, 26);
            boolean sel = id.equals(canvas.selected);
            boolean hov = ui.hover(row);
            if (sel || hov) g.roundRect(row.x(), row.y(), row.w(), row.h(), ui.radius(), sel ? Col.alpha(t.primary(), 0.14f) : Col.alpha(t.surface(), 0.8f));
            g.icon(HudWidgets.icon(id), row.x() + 8, row.y() + 7, 12, sel ? t.primary() : t.textDim());
            g.text(Lang.t(HudWidgets.nameKey(id)), row.x() + 26, row.y() + 9, w.enabled ? t.text() : t.textDim(), 1f, sel);
            boolean en = ui.toggle("hs#en" + id, new Rect(row.right() - 34, row.y() + 6, 30, 14), w.enabled);
            if (en != w.enabled) { w.enabled = en; HudStore.changed(); }
            if (ui.pressedOn(row)) canvas.selected = id;
            ry += 28;
        }
        Rect prof = wide ? new Rect(area.x(), list.bottom() + 12, leftW, 170) : null;
        // ---------------- canvas
        float cx = wide ? area.x() + leftW + 14 : area.x();
        float cw = wide ? area.w() - leftW - 14 : area.w();
        float ch = cw * sh / sw;
        float cy = wide ? y : list.bottom() + 12;
        Rect head = new Rect(cx, cy, cw, 22);
        g.text(Lang.t("hud.preview"), head.x(), head.y() + 6, t.text(), 1.1f, true);
        String[] modes = {Lang.t("hud.mode.live"), Lang.t("hud.mode.sample")};
        int m = ui.segmented("hs#mode", new Rect(head.right() - 150, head.y(), 150, 20), modes, sample ? 1 : 0);
        sample = m == 1;
        Rect cv = new Rect(cx, cy + 28, cw, ch);
        Progress.previewOverride = sample;
        try {
            canvas.render(ui, "hs#canvas", cv, sw, sh, true);
        } finally {
            Progress.previewOverride = false;
        }
        g.text(g.ellipsize(Lang.t("hud.canvas_hint", HudCanvas.modifierName()), cv.w(), 0.85f), cv.x(), cv.bottom() + 6, t.textMute(), 0.85f);
        String open = Lang.t("hud.open_editor");
        float ow = g.width(open) + 30;
        if (ui.button("hs#open", new Rect(cv.right() - ow, cv.bottom() + 18, ow, 20), open, Icon.MOVE, Ui.Style.PRIMARY)) {
            dev.skycore.core.Skycore.later(() -> Minecraft.getInstance().gui.setScreen(new HudEditorScreen(screen)));
        }
        float afterCanvas = cv.bottom() + 46;
        if (!wide) prof = new Rect(area.x(), afterCanvas, area.w(), 170);
        modalArea = screen.screenRect();
        profiles(ui, prof);
        // ---------------- inspector
        HudStore.WidgetState sel = HudStore.widget(canvas.selected);
        float iy = wide ? Math.max(afterCanvas, 0) : prof.bottom() + 12;
        Rect insp = new Rect(cx, iy, cw, 400);
        if (sel != null) {
            float used = inspector.render(ui, new Rect(insp.x(), insp.y(), insp.w(), 0), sel, screen.screenRect());
            insp = insp.withH(used);
        }
        float bottom = Math.max(insp.bottom(), prof.bottom());
        return bottom - area.y();
    }

    private Rect modalArea;

    private void profiles(Ui ui, Rect r) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ui.card(r);
        Widgets.sectionTitle(ui, r.x() + 12, r.y() + 12, Lang.t("hud.profiles"));
        List<String> names = HudStore.profiles();
        int idx = Math.max(0, names.indexOf(HudStore.activeProfile()));
        int nsel = ui.dropdown("hs#prof", new Rect(r.x() + 12, r.y() + 32, r.w() - 24, 20), names.toArray(new String[0]), idx);
        if (nsel != idx) HudStore.setActive(names.get(nsel));
        Rect[] b1 = new Rect(r.x() + 12, r.y() + 60, r.w() - 24, 20).columns(3, 6);
        if (ui.button("hs#new", b1[0], Lang.t("hud.new_profile"), Icon.PLUS, Ui.Style.SECONDARY)) HudStore.newProfile(Lang.t("hud.profile_name"));
        if (ui.button("hs#ren", b1[1], Lang.t("hud.rename"), Icon.EDIT, Ui.Style.SECONDARY)) { renaming = true; newName = HudStore.activeProfile(); ui.resetModalAnim(); }
        if (ui.button("hs#del", b1[2], Lang.t("action.delete"), Icon.TRASH, Ui.Style.SECONDARY) && names.size() > 1) HudStore.deleteActive();
        Rect[] b2 = new Rect(r.x() + 12, r.y() + 86, r.w() - 24, 20).columns(2, 6);
        if (ui.button("hs#imp", b2[0], Lang.t("hud.import"), Icon.IMPORT, Ui.Style.SECONDARY)) {
            String name = HudStore.importLayout(Minecraft.getInstance().keyboardHandler.getClipboard());
            if (name != null) Notifier.push(null, Lang.t("hud.imported"), Lang.t("hud.imported_body", name), Icon.IMPORT, t.success());
            else Notifier.push(null, Lang.t("hud.import_failed"), Lang.t("hud.import_failed_body"), Icon.WARNING, t.warning());
        }
        if (ui.button("hs#exp", b2[1], Lang.t("hud.export"), Icon.EXPORT, Ui.Style.SECONDARY)) {
            Minecraft.getInstance().keyboardHandler.setClipboard(HudStore.export());
            Notifier.push(null, Lang.t("hud.exported"), Lang.t("hud.exported_body"), Icon.COPY, t.primary());
        }
        Rect[] b3 = new Rect(r.x() + 12, r.y() + 112, r.w() - 24, 20).columns(2, 6);
        if (ui.button("hs#save", b3[0], Lang.t("action.save"), Icon.SAVE, Ui.Style.SECONDARY)) {
            HudStore.save();
            Notifier.push(null, Lang.t("hud.saved"), Lang.t("hud.saved_body", HudStore.activeProfile()), Icon.SAVE, t.success());
        }
        if (ui.button("hs#reset", b3[1], Lang.t("action.reset"), Icon.RESET, Ui.Style.SECONDARY)) HudStore.resetActive();
        g.text(Lang.t("hud.autosave"), r.x() + 12, r.y() + 142, t.textMute(), 0.8f);
        if (renaming) {
            ui.modal(modalArea, 300, 130, Lang.t("hud.rename_title"), body -> {
                newName = ui.textField("hs#name", new Rect(body.x(), body.y(), body.w(), 20), newName, Lang.t("hud.profile_name"), 32, c -> c >= 32);
                if (ui.focus == null) ui.focus = "hs#name";
                Rect ok = new Rect(body.right() - 100, body.bottom() - 20, 100, 20);
                boolean taken = !newName.trim().equals(HudStore.activeProfile()) && HudStore.profiles().contains(newName.trim());
                if (taken) g.text(Lang.t("hud.name_taken"), body.x(), body.y() + 26, t.danger(), 0.85f);
                if (ui.button("hs#renok", ok, Lang.t("action.save"), Icon.CHECK, Ui.Style.PRIMARY) && !taken && HudStore.renameActive(newName)) renaming = false;
                if (ui.button("hs#rencancel", new Rect(ok.x() - 86, ok.y(), 80, 20), Lang.t("action.cancel"), null, Ui.Style.GHOST)) renaming = false;
            }, () -> renaming = false);
        }
    }
}
