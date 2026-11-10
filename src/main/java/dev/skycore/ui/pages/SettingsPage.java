package dev.skycore.ui.pages;

import com.mojang.blaze3d.platform.InputConstants;
import dev.skycore.core.Backups;
import dev.skycore.core.Config;
import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;
import dev.skycore.data.Progress;
import dev.skycore.data.SessionLog;
import dev.skycore.data.StatDef;
import dev.skycore.hud.HudEditorScreen;
import dev.skycore.notify.Notifier;
import dev.skycore.ui.Page;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** General · Interface · Shortcuts · Tracking · HUD · Data – each a collapsible section. */
public class SettingsPage implements Page {
    private String confirm; // "profile" | "sessions" | "settings" | "history"
    private KeyMapping capturing;
    private final ReliabilitySections rel = new ReliabilitySections();

    @Override public String id() { return "settings"; }
    @Override public Icon icon() { return Icon.SETTINGS; }

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        Config cfg = Skycore.config();
        float y = area.y() + 6;
        y = general(ui, area, y, cfg);
        y = interfaceSection(ui, area, y, cfg, screen);
        y = shortcuts(ui, area, y);
        y = tracking(ui, area, y, cfg);
        y = dataTracking(ui, area, y, cfg);
        y = rel.profiles(ui, area, y);
        y = rel.backups(ui, area, y, this);
        y = rel.integrity(ui, area, y);
        y = rel.patterns(ui, area, y);
        y = rel.debugger(ui, area, y);
        y = hud(ui, area, y, cfg, screen);
        y = data(ui, area, y, cfg);
        Gfx g = ui.g;
        Theme t = ui.t;
        String ver = FabricLoader.getInstance().getModContainer("skycore").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        g.logo(area.x(), y + 2, 20);
        g.text("SKYCORE", area.x() + 26, y + 5, t.text(), 1f, Weight.BOLD);
        g.text(Lang.t("settings.about", ver), area.x() + 26, y + 16, t.textMute(), 0.85f);
        y += 32;
        confirmDialog(ui, screen);
        rel.dialogs(ui, screen);
        return y - area.y();
    }

    // ------------------------------------------------------------------ general
    private float general(Ui ui, Rect area, float y, Config cfg) {
        Ui.Section s = ui.beginSection("set.general", area.x(), y, area.w(), Lang.t("settings.general"), Lang.t("settings.general_desc"), Icon.GLOBE, true);
        float cy = s.contentY();
        if (s.visible()) {
            Gfx g = ui.g;
            String[] names = new String[Lang.LANGUAGES.length];
            int cur = 0;
            for (int i = 0; i < names.length; i++) { names[i] = Lang.t("lang." + Lang.LANGUAGES[i]); if (Lang.LANGUAGES[i].equals(Lang.code())) cur = i; }
            g.text(Lang.t("settings.language"), s.x(), cy + 6, ui.t.text(), 1f, Weight.MEDIUM);
            Rect seg = new Rect(s.x() + s.w() - 200, cy, 200, 22);
            if (ui.hover(seg)) ui.tooltip(Lang.t("settings.language_desc"));
            int sel = ui.segmented("set#lang", seg, names, cur);
            if (sel != cur) Skycore.setLanguage(Lang.LANGUAGES[sel]);
            cy += 32;
            boolean sample = ui.toggleRow("set#sample", new Rect(s.x() - 8, cy, s.w() + 8, 32), Lang.t("settings.sample"), Lang.t("settings.sample_desc"), cfg.sampleData, Lang.t("tip.sample"));
            if (sample != cfg.sampleData) { cfg.sampleData = sample; Skycore.markDirty(); }
            cy += 36;
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ interface
    private float interfaceSection(Ui ui, Rect area, float y, Config cfg, SkycoreScreen screen) {
        Ui.Section s = ui.beginSection("set.interface", area.x(), y, area.w(), Lang.t("settings.interface"), Lang.t("settings.interface_desc"), Icon.MOVE, false);
        float cy = s.contentY();
        if (s.visible()) {
            String sv = cfg.uiScale <= 0 ? Lang.t("settings.scale_auto", Minecraft.getInstance().getWindow().getGuiScale()) : Lang.dec(cfg.uiScale) + "×";
            float ns = ui.sliderRow("set#scale", new Rect(s.x(), cy, s.w(), 30), Lang.t("settings.scale"), cfg.uiScale, 0, 4, 0.5f, sv, Lang.t("tip.scale"));
            if (ns != cfg.uiScale) { cfg.uiScale = ns < 1 ? 0 : ns; Skycore.markDirty(); }
            cy += 36;
            Config.WindowState w = cfg.window;
            float dim = ui.sliderRow("set#dim", new Rect(s.x(), cy, s.w(), 30), Lang.t("appearance.dim"), w.backgroundDim, 0, 0.9f, 0.05f, Lang.percent(w.backgroundDim), Lang.t("tip.dim"));
            if (dim != w.backgroundDim) { w.backgroundDim = dim; Skycore.markDirty(); }
            cy += 38;
            String lr = Lang.t("settings.reset_window");
            if (ui.button("set#resetwin", new Rect(s.x(), cy, ui.g.width(lr, 1f, Weight.MEDIUM) + 30, 20), lr, Icon.RESET, Ui.Style.SECONDARY)) screen.resetWindow();
            cy += 26;
            ui.g.text(ui.g.ellipsize(Lang.t("settings.typography_hint"), s.w() / 0.85f, 1f), s.x(), cy, ui.t.textMute(), 0.85f);
            cy += 12;
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ shortcuts
    private float shortcuts(Ui ui, Rect area, float y) {
        Ui.Section s = ui.beginSection("set.keys", area.x(), y, area.w(), Lang.t("settings.shortcuts"), Lang.t("settings.shortcuts_desc"), Icon.KEYBOARD, false);
        float cy = s.contentY();
        handleCapture(ui);
        if (s.visible()) {
            cy = keyRow(ui, s, cy, Skycore.openKey(), Lang.t("key.skycore.open.label"), Lang.t("tip.key_open"));
            cy = keyRow(ui, s, cy, Skycore.hudKey(), Lang.t("key.skycore.hud.label"), Lang.t("tip.key_hud"));
            for (String l : ui.g.wrap(Lang.t("settings.shortcuts_note"), s.w(), 0.85f)) { ui.g.text(l, s.x(), cy, ui.t.textMute(), 0.85f); cy += 10; }
        }
        return ui.endSection(s, cy);
    }

    private float keyRow(Ui ui, Ui.Section s, float cy, KeyMapping km, String label, String tip) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Rect row = new Rect(s.x(), cy, s.w(), 24);
        if (ui.hover(row.withW(s.w() * 0.4f))) ui.tooltip(tip);
        g.text(label, s.x(), cy + 7, t.text(), 1f, Weight.MEDIUM);
        boolean cap = capturing == km;
        String keyText = cap ? Lang.t("settings.press_key") : km.isUnbound() ? Lang.t("settings.not_bound") : km.getTranslatedKeyMessage().getString();
        float bw = 74;
        float kx = s.x() + s.w() - (bw * 3 + 12) - 130;
        Rect chip = new Rect(kx, cy, 120, 24);
        float pulse = cap ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 180.0) : 0;
        g.roundRect(chip.x(), chip.y(), chip.w(), chip.h(), Math.min(ui.radius(), 6), cap ? Col.alpha(t.primary(), 0.12f + 0.08f * pulse) : t.surface());
        if (cap) g.roundOutline(chip.x(), chip.y(), chip.w(), chip.h(), Math.min(ui.radius(), 6), 1.5f / g.s * 2, Col.alpha(t.primary(), 0.7f));
        g.textCentered(g.ellipsize(keyText, chip.w() - 10, 1f), chip.cx(), chip.cy() - 4.5f, km.isUnbound() && !cap ? t.textMute() : t.text(), 1f, Weight.SEMIBOLD);
        float bx = chip.right() + 10;
        if (ui.button("key#c" + km.getName(), new Rect(bx, cy + 2, bw, 20), Lang.t(cap ? "action.cancel" : "settings.key_change"), null, Ui.Style.SECONDARY)) {
            if (cap) { capturing = null; ui.capturingKey = false; }
            else { capturing = km; ui.capturingKey = true; }
        }
        boolean isDefault = km.getDefaultKey().equals(keyOf(km));
        if (ui.button("key#r" + km.getName(), new Rect(bx + bw + 6, cy + 2, bw, 20), Lang.t("settings.key_default"), null, Ui.Style.GHOST, !isDefault && !cap))
            apply(km, km.getDefaultKey());
        if (ui.button("key#d" + km.getName(), new Rect(bx + (bw + 6) * 2, cy + 2, bw, 20), Lang.t("settings.key_disable"), null, Ui.Style.GHOST, !km.isUnbound() && !cap))
            apply(km, InputConstants.UNKNOWN);
        cy += 30;
        List<String> clash = conflicts(km);
        if (!clash.isEmpty()) {
            g.icon(Icon.WARNING, s.x(), cy - 2, 10, t.warning());
            g.text(g.ellipsize(Lang.t("settings.key_conflict", String.join(", ", clash)), (s.w() - 16) / 0.85f, 1f), s.x() + 15, cy - 1, t.warning(), 0.85f);
            cy += 14;
        }
        return cy + 4;
    }

    private static InputConstants.Key keyOf(KeyMapping km) {
        return InputConstants.getKey(km.saveString());
    }

    /** Other bindings (vanilla or mods) that use the same key. */
    private static List<String> conflicts(KeyMapping km) {
        List<String> out = new ArrayList<>();
        if (km.isUnbound()) return out;
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
            if (other == km || other.isUnbound()) continue;
            if (other.same(km)) out.add(Component.translatable(other.getName()).getString());
        }
        return out;
    }

    private void handleCapture(Ui ui) {
        if (capturing == null) return;
        if (!ui.capturingKey) {
            KeyEvent e = ui.takeCapturedKey();
            KeyMapping km = capturing;
            capturing = null;
            if (e == null || e.key() == InputConstants.KEY_ESCAPE) return;
            apply(km, InputConstants.getKey(e));
        } else if (ui.pressedThisFrame() && !ui.clickConsumed()) {
            // clicking anywhere else cancels the capture
            ui.capturingKey = false;
            capturing = null;
        }
    }

    private static void apply(KeyMapping km, InputConstants.Key key) {
        km.setKey(key);
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
        Theme t = Theme.get();
        List<String> clash = conflicts(km);
        String name = km.isUnbound() ? Lang.t("settings.not_bound") : km.getTranslatedKeyMessage().getString();
        if (km.isUnbound()) Notifier.push(null, Lang.t("settings.key_disabled_title"), Lang.t("settings.key_disabled_body"), Icon.KEYBOARD, t.textDim());
        else if (clash.isEmpty()) Notifier.push(null, Lang.t("settings.key_changed_title"), Lang.t("settings.key_changed_body", name), Icon.KEYBOARD, t.success());
        else Notifier.push(null, Lang.t("settings.key_changed_title"), Lang.t("settings.key_conflict", String.join(", ", clash)), Icon.WARNING, t.warning());
    }

    // ------------------------------------------------------------------ tracking
    private float tracking(Ui ui, Rect area, float y, Config cfg) {
        Ui.Section s = ui.beginSection("set.tracking", area.x(), y, area.w(), Lang.t("settings.tracking"), Lang.t("settings.tracking_desc"), Icon.PROGRESSION, false);
        float cy = s.contentY();
        if (s.visible()) {
            int cols = s.w() >= 420 ? 2 : 1;
            Rect[] c = new Rect(s.x() - 8, cy, s.w() + 8, 0).columns(cols, 10);
            for (int i = 0; i < StatDef.CATEGORIES.length; i++) {
                String cat = StatDef.CATEGORIES[i];
                Rect col = c[i % cols];
                Rect row = new Rect(col.x(), cy + (i / cols) * 28, col.w(), 26);
                boolean v = ui.toggleRow("set#trk" + cat, row, Lang.t("cat." + cat), null, cfg.tracks(cat), Lang.t("tip.track." + cat));
                if (v != cfg.tracks(cat)) { cfg.tracking.put(cat, v); Skycore.markDirty(); }
            }
            cy += ((StatDef.CATEGORIES.length + cols - 1) / cols) * 28;
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ data tracking
    private float dataTracking(Ui ui, Rect area, float y, Config cfg) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("set.datatrack", area.x(), y, area.w(), Lang.t("settings.datatrack"), Lang.t("settings.datatrack_desc"), Icon.SESSIONS, false);
        float cy = s.contentY();
        if (s.visible()) {
            boolean en = ui.toggleRow("set#trackall", new Rect(s.x() - 8, cy, s.w() + 8, 32), Lang.t("settings.track_enabled"), Lang.t("settings.track_enabled_desc"), cfg.trackingEnabled, Lang.t("tip.track_enabled"));
            if (en != cfg.trackingEnabled) { cfg.trackingEnabled = en; Skycore.markDirty(); }
            cy += 40;

            // health of every source (status, last success, errors – inactivity is not an error)
            var hist = dev.skycore.data.History.data();
            dev.skycore.data.History.Event last = dev.skycore.data.History.lastEvent();
            long now = System.currentTimeMillis();
            cy = rel.health(ui, s, cy);
            g.text(Lang.t("settings.last_event"), s.x(), cy + 3, t.textDim(), 0.9f);
            String le = last == null ? Lang.t("settings.never") : describe(last);
            g.textRight(g.ellipsize(le, (s.w() * 0.7f) / 0.9f, 1f), s.x() + s.w(), cy + 3, t.text(), 0.9f, Weight.REGULAR);
            if (last != null && ui.hover(new Rect(s.x(), cy, s.w(), 16)))
                ui.tooltip(Lang.t("settings.event_tip", last.rule == null ? "—" : last.rule, Math.round(last.confidence * 100), Lang.t("source." + last.source)));
            cy += 22;
            ui.separator(s.x(), cy, s.w());
            cy += 10;

            // coverage / missing information
            ui.caption(Lang.t("settings.coverage"), s.x(), cy);
            cy += 14;
            var data = Progress.real();
            for (String cat : StatDef.CATEGORIES) {
                if (cat.equals("collections")) continue;
                List<StatDef> defs = StatDef.inCategory(cat);
                int known = 0;
                List<String> missing = new ArrayList<>();
                for (StatDef d : defs) { if (data.peek(d.id()).known()) known++; else missing.add(Lang.t(d.nameKey())); }
                Rect row = new Rect(s.x(), cy, s.w(), 18);
                boolean complete = missing.isEmpty();
                g.text(Lang.t("cat." + cat), s.x(), cy + 3, t.text(), 1f, Weight.MEDIUM);
                String status = complete ? Lang.t("settings.complete") : Lang.t("settings.known_of", known, defs.size());
                g.textRight(status, s.x() + s.w(), cy + 3, complete ? t.success() : t.textDim(), 0.9f, Weight.REGULAR);
                if (!complete && ui.hover(row)) ui.tooltip(Lang.t("settings.missing", String.join(", ", missing)) + "\n" + Lang.t("settings.howto." + cat));
                cy += 18;
            }
            cy = rel.otherCoverage(ui, s, cy);
            ui.separator(s.x(), cy, s.w());
            cy += 10;

            // reconciliation
            long weekAgo = now - 7L * 24 * 3600_000L;
            long corr = hist.corrections.stream().filter(c -> c.t > weekAgo).count();
            g.text(Lang.t("settings.corrections"), s.x(), cy + 3, t.textDim(), 0.9f);
            g.textRight(Lang.t("settings.corrections_n", corr), s.x() + s.w(), cy + 3, t.text(), 0.9f, Weight.REGULAR);
            if (ui.hover(new Rect(s.x(), cy, s.w(), 16))) {
                var lc = hist.corrections.isEmpty() ? null : hist.corrections.get(hist.corrections.size() - 1);
                ui.tooltip(Lang.t("settings.corrections_tip") + (lc == null ? "" : "\n" + Lang.t("settings.last_correction",
                        Lang.t("stat." + lc.stat), (lc.delta >= 0 ? "+" : "") + Lang.num(lc.delta), dev.skycore.ui.Widgets.ago(lc.t))));
            }
            cy += 26;

            // actions
            String l1 = Lang.t("settings.rescan"), l3 = Lang.t("settings.clear_history");
            float w1 = g.width(l1, 1f, Weight.MEDIUM) + 30;
            if (ui.button("set#rescan", new Rect(s.x(), cy, w1, 22), l1, Icon.RESET, Ui.Style.SECONDARY, cfg.trackingEnabled)) {
                dev.skycore.tracking.HypixelTracker.rescan();
                Notifier.push(null, Lang.t("settings.rescan_done"), Lang.t("settings.rescan_done_body"), Icon.CHECK, t.success());
            }
            if (ui.hover(new Rect(s.x(), cy, w1, 22))) ui.tooltip(Lang.t("tip.rescan"));
            if (ui.button("set#clearhist", new Rect(s.x() + w1 + 8, cy, g.width(l3, 1f, Weight.MEDIUM) + 30, 22), l3, Icon.TRASH, Ui.Style.DANGER)) { confirm = "history"; ui.resetModalAnim(); }
            cy += 32;

            // legend
            ui.caption(Lang.t("settings.status_legend"), s.x(), cy);
            cy += 14;
            for (dev.skycore.data.DataStatus st : dev.skycore.data.DataStatus.values()) {
                int c = dev.skycore.ui.Widgets.statusColor(t, st);
                switch (st) {
                    case VERIFIED -> g.circle(s.x() + 3, cy + 6, 3, c);
                    case EVENT_TRACKED -> { g.circle(s.x() + 3, cy + 6, 3, Col.alpha(c, 0.35f)); g.circle(s.x() + 3, cy + 6, 1.6f, c); }
                    default -> g.ringOutline(s.x() + 3, cy + 6, 3, 1.1f, c);
                }
                g.text(Lang.t(st.key()), s.x() + 12, cy + 2, t.text(), 0.9f, Weight.MEDIUM);
                g.text(g.ellipsize(Lang.t(st.key() + ".desc"), (s.w() - 130) / 0.85f, 1f), s.x() + 120, cy + 2.5f, t.textDim(), 0.85f);
                cy += 16;
            }
            cy += 4;
            for (String l : g.wrap(Lang.t("settings.fair_play"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textMute(), 0.85f); cy += 10; }
        }
        return ui.endSection(s, cy);
    }

    private static String describe(dev.skycore.data.History.Event e) {
        String stat = e.stat == null ? "" : e.stat.startsWith("collection:") ? e.stat.substring(11) : Lang.exists("stat." + e.stat) ? Lang.t("stat." + e.stat) : e.stat;
        String amount = e.amount > 0 && !e.kind.equals("level") && !e.kind.equals("milestone") ? " +" + Lang.num(e.amount) : e.amount > 0 ? " " + Lang.num(e.amount) : "";
        return Lang.t("event." + e.kind) + (stat.isEmpty() ? "" : " · " + stat) + amount + " · " + dev.skycore.ui.Widgets.ago(e.t);
    }

    // ------------------------------------------------------------------ hud
    private float hud(Ui ui, Rect area, float y, Config cfg, SkycoreScreen screen) {
        Ui.Section s = ui.beginSection("set.hud", area.x(), y, area.w(), Lang.t("settings.hud"), Lang.t("settings.hud_desc"), Icon.HUD, false);
        float cy = s.contentY();
        if (s.visible()) {
            boolean he = ui.toggleRow("set#hud", new Rect(s.x() - 8, cy, s.w() + 8, 26), Lang.t("settings.hud_enabled"), null, cfg.hudEnabled, Lang.t("tip.hud_enabled"));
            boolean hs = ui.toggleRow("set#hudsb", new Rect(s.x() - 8, cy + 28, s.w() + 8, 26), Lang.t("settings.hud_skyblock"), null, cfg.hudOnlySkyBlock, Lang.t("tip.hud_skyblock"));
            if (he != cfg.hudEnabled || hs != cfg.hudOnlySkyBlock) { cfg.hudEnabled = he; cfg.hudOnlySkyBlock = hs; Skycore.markDirty(); }
            cy += 62;
            ui.g.text(Lang.t("settings.resize_mod"), s.x(), cy + 6, ui.t.text(), 1f, Weight.MEDIUM);
            String[] mods = {"ctrl", "alt", "shift"};
            String[] modL = {Lang.t("key.ctrl"), Lang.t("key.alt"), Lang.t("key.shift")};
            int mi = Math.max(0, java.util.Arrays.asList(mods).indexOf(cfg.hudResizeModifier));
            Rect seg = new Rect(s.x() + s.w() - 180, cy, 180, 22);
            if (ui.hover(seg)) ui.tooltip(Lang.t("tip.resize_mod"));
            int nmi = ui.segmented("set#mod", seg, modL, mi);
            if (nmi != mi) { cfg.hudResizeModifier = mods[nmi]; Skycore.markDirty(); }
            cy += 32;
            String ed = Lang.t("settings.open_editor");
            if (ui.button("set#editor", new Rect(s.x(), cy, ui.g.width(ed, 1f, Weight.MEDIUM) + 30, 22), ed, Icon.MOVE, Ui.Style.SECONDARY))
                dev.skycore.core.Skycore.later(() -> Minecraft.getInstance().gui.setScreen(new HudEditorScreen(screen)));
            cy += 28;
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ data
    private float data(Ui ui, Rect area, float y, Config cfg) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("set.data", area.x(), y, area.w(), Lang.t("settings.data"), Lang.t("settings.data_desc"), Icon.SAVE, false);
        float cy = s.contentY();
        if (s.visible()) {
            g.text(Lang.t("settings.profile", Progress.profileName()), s.x(), cy, t.text(), 1f, Weight.MEDIUM);
            g.text(g.ellipsize(Lang.t("settings.folder", FabricLoader.getInstance().getGameDir().relativize(Storage.dir()).toString()), s.w() / 0.85f, 1f), s.x(), cy + 13, t.textMute(), 0.85f);
            cy += 32;
            ui.separator(s.x(), cy, s.w());
            cy += 10;
            ui.caption(Lang.t("settings.danger_zone"), s.x(), cy);
            cy += 14;
            String l1 = Lang.t("settings.reset_profile"), l2 = Lang.t("settings.reset_sessions"), l3 = Lang.t("settings.reset_settings");
            float w1 = g.width(l1, 1f, Weight.MEDIUM) + 30, w2 = g.width(l2, 1f, Weight.MEDIUM) + 30, w3 = g.width(l3, 1f, Weight.MEDIUM) + 30;
            float bx = s.x();
            if (bx + w1 + w2 + w3 + 16 > s.x() + s.w()) { w1 = w2 = w3 = (s.w() - 16) / 3; }
            if (ui.button("set#rp", new Rect(bx, cy, w1, 22), l1, Icon.TRASH, Ui.Style.DANGER)) { confirm = "profile"; ui.resetModalAnim(); }
            if (ui.button("set#rs", new Rect(bx + w1 + 8, cy, w2, 22), l2, Icon.TRASH, Ui.Style.DANGER)) { confirm = "sessions"; ui.resetModalAnim(); }
            if (ui.button("set#rset", new Rect(bx + w1 + w2 + 16, cy, w3, 22), l3, Icon.RESET, Ui.Style.DANGER)) { confirm = "settings"; ui.resetModalAnim(); }
            cy += 30;
        }
        return ui.endSection(s, cy);
    }

    /** Automatic backup count (shown in the backups section). */
    float keepSlider(Ui ui, Ui.Section s, float cy) {
        Config cfg = Skycore.config();
        float kb = ui.sliderRow("set#keep", new Rect(s.x(), cy, Math.min(320, s.w()), 30), Lang.t("settings.backups_keep"),
                cfg.backupsToKeep, 0, 20, 1, cfg.backupsToKeep == 0 ? Lang.t("settings.off") : String.valueOf(cfg.backupsToKeep), Lang.t("tip.backups"));
        if ((int) kb != cfg.backupsToKeep) { cfg.backupsToKeep = (int) kb; Skycore.markDirty(); }
        return cy + 38;
    }

    private void confirmDialog(Ui ui, SkycoreScreen screen) {
        if (confirm == null) return;
        String c = confirm;
        Gfx g = ui.g;
        Theme t = ui.t;
        ui.modal(screen.screenRect(), 360, 170, Lang.t("settings.confirm_title"), body -> {
            float ly = body.y();
            for (String l : g.wrap(Lang.t("settings.confirm_" + c), body.w(), 1f)) { g.text(l, body.x(), ly, t.textDim()); ly += 11; }
            for (String l : g.wrap(Lang.t("backup.safety_note"), body.w(), 0.85f)) { g.text(l, body.x(), ly + 4, t.textMute(), 0.85f); ly += 10; }
            Rect ok = new Rect(body.right() - 110, body.bottom() - 22, 110, 22);
            if (ui.button("set#ok", ok, Lang.t("action.reset"), Icon.TRASH, Ui.Style.DANGER)) {
                // every destructive action is preceded by a verified safety backup; no backup → nothing is changed
                if (!Backups.safety("reset-" + c)) {
                    Notifier.push(null, Lang.t("backup.safety_failed"), Lang.t("backup.safety_failed_body"), Icon.WARNING, t.danger());
                } else switch (c) {
                    case "profile" -> Progress.resetProfile();
                    case "sessions" -> SessionLog.reset();
                    case "settings" -> Skycore.resetSettings();
                    case "history" -> dev.skycore.data.History.clear();
                    default -> {}
                }
                confirm = null;
            }
            if (ui.button("set#cancel", new Rect(ok.x() - 88, ok.y(), 80, 22), Lang.t("action.cancel"), null, Ui.Style.GHOST)) confirm = null;
        }, () -> confirm = null);
    }
}
