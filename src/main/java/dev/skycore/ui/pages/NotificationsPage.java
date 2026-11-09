package dev.skycore.ui.pages;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.skycore.core.Config;
import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.hud.HudRenderer;
import dev.skycore.notify.Notifier;
import dev.skycore.ui.Page;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.Widgets;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/** Progression alerts organised as General · Progression alerts · Appearance · Sound · Messages. */
public class NotificationsPage implements Page {
    private int newThreshold = 3;
    private String picking; // "accent" | "background"

    @Override public String id() { return "notifications"; }
    @Override public Icon icon() { return Icon.BELL; }

    private static final String[] POSITIONS = {"top_left", "top_center", "top_right", "bottom_left", "bottom_center", "bottom_right"};
    private static final String[] ANIMS = {"slide", "fade", "pop"};
    private static final String[] SOUNDS = {"pling", "toast", "orb", "none"};

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        Config.NotificationSettings n = Skycore.config().notifications;
        float y = area.y() + 6;
        y = general(ui, area, y, n);
        float prev = ui.g.alpha;
        if (!n.enabled) ui.g.alpha *= 0.5f;
        y = alerts(ui, area, y, n);
        y = appearance(ui, area, y, n);
        y = sound(ui, area, y, n);
        y = messages(ui, area, y, n);
        ui.g.alpha = prev;
        if (picking != null) pickerModal(ui, screen, n);
        return y - area.y();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    // ------------------------------------------------------------------ general
    private float general(Ui ui, Rect area, float y, Config.NotificationSettings n) {
        Ui.Section s = ui.beginSection("nt.general", area.x(), y, area.w(), Lang.t("notify.section.general"), Lang.t("notify.section.general_desc"), Icon.BELL, true);
        float cy = s.contentY();
        if (s.visible()) {
            boolean en = ui.toggleRow("nt#master", new Rect(s.x() - 8, cy, s.w() + 8, 30), Lang.t("notify.enabled"), Lang.t("notify.enabled_desc"), n.enabled, Lang.t("tip.notify_master"));
            if (en != n.enabled) { n.enabled = en; Skycore.markDirty(); }
            cy += 36;
            ui.caption(Lang.t("notify.categories"), s.x(), cy);
            cy += 14;
            int cols = s.w() >= 420 ? 2 : 1;
            Rect[] c = new Rect(s.x() - 8, cy, s.w() + 8, 0).columns(cols, 10);
            for (int i = 0; i < Notifier.CATEGORIES.length; i++) {
                String cat = Notifier.CATEGORIES[i];
                Rect col = c[i % cols];
                Rect row = new Rect(col.x(), cy + (i / cols) * 28, col.w(), 26);
                boolean v = ui.toggleRow("nt#c" + cat, row, Lang.t("notify.cat." + cat), null, n.categories.getOrDefault(cat, true), Lang.t("tip.notify." + cat));
                if (v != n.categories.getOrDefault(cat, true)) { n.categories.put(cat, v); Skycore.markDirty(); }
            }
            cy += ((Notifier.CATEGORIES.length + cols - 1) / cols) * 28;
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ thresholds
    private float alerts(Ui ui, Rect area, float y, Config.NotificationSettings n) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("nt.alerts", area.x(), y, area.w(), Lang.t("notify.thresholds"), Lang.t("notify.thresholds_desc"), Icon.DUNGEONS, false);
        float cy = s.contentY();
        if (s.visible()) {
            List<Integer> list = new ArrayList<>(n.runThresholds);
            list.sort((a, b) -> b - a);
            float cx = s.x();
            if (list.isEmpty()) { g.text(Lang.t("notify.no_thresholds"), s.x(), cy + 5, t.textMute(), 0.9f); cy += 22; }
            for (int v : list) {
                String label = Lang.t("notify.runs_away_n", v);
                float w = g.width(label, 1f, Weight.MEDIUM) + 32;
                if (cx + w > s.x() + s.w() && cx > s.x()) { cx = s.x(); cy += 26; }
                Rect chip = new Rect(cx, cy, w, 22);
                g.roundRect(chip.x(), chip.y(), chip.w(), chip.h(), 11, t.surface());
                g.text(label, chip.x() + 10, chip.cy() - 4.5f, t.text(), 1f, Weight.MEDIUM);
                if (ui.iconButton("nt#rm" + v, new Rect(chip.right() - 20, chip.y() + 3, 16, 16), Icon.CLOSE, Lang.t("notify.remove"))) {
                    n.runThresholds.remove(Integer.valueOf(v));
                    Skycore.markDirty();
                }
                cx += w + 6;
            }
            if (!list.isEmpty()) cy += 32;
            g.text(Lang.t("notify.add_label"), s.x(), cy + 6, t.text(), 1f, Weight.MEDIUM);
            float ax = s.x() + g.width(Lang.t("notify.add_label"), 1f, Weight.MEDIUM) + 12;
            newThreshold = ui.stepper("nt#new", new Rect(ax, cy, 96, 22), newThreshold, 1, 100);
            boolean can = !n.runThresholds.contains(newThreshold) && n.runThresholds.size() < 8;
            if (ui.button("nt#add", new Rect(ax + 104, cy, g.width(Lang.t("notify.add"), 1f, Weight.MEDIUM) + 30, 22), Lang.t("notify.add"), Icon.PLUS, Ui.Style.SECONDARY, can)) {
                n.runThresholds.add(newThreshold);
                Skycore.markDirty();
            }
            cy += 30;
            for (String l : g.wrap(Lang.t("notify.thresholds_note"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textMute(), 0.85f); cy += 10; }
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ appearance
    private float appearance(Ui ui, Rect area, float y, Config.NotificationSettings n) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("nt.look", area.x(), y, area.w(), Lang.t("notify.style"), Lang.t("notify.style_desc"), Icon.APPEARANCE, false);
        float cy = s.contentY();
        if (s.visible()) {
            boolean wide = s.w() >= 470;
            float lw = wide ? s.w() * 0.5f - 10 : s.w();
            // left: controls
            float ly = cy;
            ui.caption(Lang.t("notify.position"), s.x(), ly);
            Rect scr = new Rect(s.x(), ly + 14, 110, 62);
            g.roundRect(scr.x(), scr.y(), scr.w(), scr.h(), 6, t.surface());
            for (int i = 0; i < POSITIONS.length; i++) {
                float px = scr.x() + 6 + (i % 3) * ((scr.w() - 36) / 2f), py = i < 3 ? scr.y() + 6 : scr.bottom() - 16;
                Rect slot = new Rect(px, py, 24, 10);
                boolean sel = POSITIONS[i].equals(n.position);
                boolean hov = ui.hover(slot.expand(2));
                if (hov) { ui.setCursor(CursorTypes.POINTING_HAND); ui.tooltip(Lang.t("notify.pos." + POSITIONS[i])); }
                g.roundRect(slot.x(), slot.y(), slot.w(), slot.h(), 3, sel ? t.primary() : hov ? t.surfaceHover() : Col.alpha(t.textMute(), 0.35f));
                if (ui.pressedOn(slot.expand(2))) { n.position = POSITIONS[i]; Skycore.markDirty(); Ui.clickSound(); }
            }
            g.text(Lang.t("notify.pos." + n.position), scr.right() + 10, scr.cy() - 4.5f, t.text(), 1f, Weight.MEDIUM);
            ly = scr.bottom() + 12;
            String[] animLabels = new String[ANIMS.length];
            for (int i = 0; i < ANIMS.length; i++) animLabels[i] = Lang.t("notify.anim." + ANIMS[i]);
            ui.caption(Lang.t("notify.animation"), s.x(), ly);
            int ai = ui.segmented("nt#anim", new Rect(s.x(), ly + 13, Math.min(240, lw), 22), animLabels, indexOf(ANIMS, n.animation));
            if (!ANIMS[ai].equals(n.animation)) { n.animation = ANIMS[ai]; Skycore.markDirty(); }
            ly += 44;
            float dur = ui.sliderRow("nt#dur", new Rect(s.x(), ly, lw, 30), Lang.t("notify.duration"), n.duration, 2, 12, 0.5f, Lang.dec(n.duration) + " s", Lang.t("tip.notify_duration"));
            ly += 36;
            float ts = ui.sliderRow("nt#ts", new Rect(s.x(), ly, lw, 30), Lang.t("notify.text_size"), n.textScale, 0.75f, 1.5f, 0.05f, Lang.percent(n.textScale), Lang.t("tip.notify_text"));
            ly += 36;
            float bo = ui.sliderRow("nt#bgo", new Rect(s.x(), ly, lw, 30), Lang.t("notify.bg_opacity"), n.backgroundOpacity, 0.3f, 1f, 0.02f, Lang.percent(n.backgroundOpacity));
            ly += 38;
            if (dur != n.duration || ts != n.textScale || bo != n.backgroundOpacity) { n.duration = dur; n.textScale = ts; n.backgroundOpacity = bo; Skycore.markDirty(); }
            int accent = n.accent == null || n.accent.isEmpty() ? t.primary() : Theme.parse(n.accent);
            int bgc = n.background == null || n.background.isEmpty() ? t.card() : Theme.parse(n.background);
            Rect[] cc = new Rect(s.x(), ly, lw, 36).columns(2, 12);
            ui.caption(Lang.t("notify.accent"), cc[0].x(), cc[0].y());
            if (Widgets.swatch(ui, "nt#acc", new Rect(cc[0].x(), cc[0].y() + 13, 30, 18), accent)) { picking = "accent"; Widgets.resetPicker(ui, "ntpick" + picking); ui.resetModalAnim(); }
            g.text(n.accent.isEmpty() ? Lang.t("notify.per_type") : Theme.hex(accent).substring(3), cc[0].x() + 38, cc[0].y() + 18, t.textDim(), 0.9f);
            ui.caption(Lang.t("notify.background"), cc[1].x(), cc[1].y());
            if (Widgets.swatch(ui, "nt#bg", new Rect(cc[1].x(), cc[1].y() + 13, 30, 18), bgc)) { picking = "background"; Widgets.resetPicker(ui, "ntpick" + picking); ui.resetModalAnim(); }
            g.text(n.background.isEmpty() ? Lang.t("notify.theme_bg") : Theme.hex(bgc).substring(3), cc[1].x() + 38, cc[1].y() + 18, t.textDim(), 0.9f);
            ly += 42;
            // right (or below): live preview with the real toast renderer
            float px = wide ? s.x() + lw + 20 : s.x(), py = wide ? cy : ly + 4, pw = wide ? s.w() - lw - 20 : s.w();
            ui.caption(Lang.t("notify.preview_live"), px, py);
            Rect stage = new Rect(px, py + 14, pw, 120);
            g.roundRect(stage.x(), stage.y(), stage.w(), stage.h(), ui.radius(), Col.alpha(t.bg(), 0.6f));
            float scale = n.textScale;
            float tw = Math.min(stage.w() - 20, 170 * scale);
            Notifier.Toast demo = new Notifier.Toast(Lang.t("notify.runs.title", Lang.t("stat.catacombs"), 24),
                    Lang.t("notify.runs.body", "4–5", Lang.t("unit.runs")), Icon.DUNGEONS, accent, 0, n.duration);
            List<String> body = g.wrap(demo.body(), (tw - 42 * scale) / scale, 1f);
            float th = HudRenderer.toastHeight(g, demo, tw, scale);
            float tx = n.position.endsWith("left") ? stage.x() + 10 : n.position.endsWith("center") ? stage.cx() - tw / 2 : stage.right() - tw - 10;
            float ty = n.position.startsWith("top") ? stage.y() + 10 : stage.bottom() - th - 10;
            ui.pushClip(stage);
            HudRenderer.drawToastCard(g, demo, body, tx, ty, tw, th, scale, 0.65f);
            ui.popClip();
            if (ui.button("nt#test", new Rect(px, stage.bottom() + 8, g.width(Lang.t("notify.test"), 1f, Weight.MEDIUM) + 30, 20), Lang.t("notify.test"), Icon.PLAY, Ui.Style.SECONDARY))
                Notifier.test();
            cy = Math.max(ly, stage.bottom() + 32);
        }
        return ui.endSection(s, cy);
    }

    private void pickerModal(Ui ui, SkycoreScreen screen, Config.NotificationSettings n) {
        String key = picking;
        Theme t = Theme.get();
        int accent = n.accent.isEmpty() ? t.primary() : Theme.parse(n.accent);
        int bgc = n.background.isEmpty() ? t.card() : Theme.parse(n.background);
        int resetTo = key.equals("accent") ? t.primary() : t.card();
        ui.modal(screen.screenRect(), 400, 330, Lang.t(key.equals("accent") ? "notify.accent" : "notify.background"), body -> {
            int cur = key.equals("accent") ? accent : bgc;
            int nc = Widgets.colorPicker(ui, "ntpick" + key, body.withH(body.h() - 28), cur, false, resetTo);
            if (nc != cur) {
                String v = nc == resetTo ? "" : Theme.hex(nc);
                if (key.equals("accent")) n.accent = v; else n.background = v;
                Skycore.markDirty();
            }
            if (ui.button("ntpick#ok", new Rect(body.right() - 90, body.bottom() - 20, 90, 20), Lang.t("action.done"), Icon.CHECK, Ui.Style.PRIMARY)) picking = null;
        }, () -> picking = null);
    }

    // ------------------------------------------------------------------ sound
    private float sound(Ui ui, Rect area, float y, Config.NotificationSettings n) {
        Ui.Section s = ui.beginSection("nt.sound", area.x(), y, area.w(), Lang.t("notify.sound"), Lang.t("notify.sound_desc"), Icon.SOUND, false);
        float cy = s.contentY();
        if (s.visible()) {
            boolean snd = ui.toggleRow("nt#snd", new Rect(s.x() - 8, cy, s.w() + 8, 26), Lang.t("notify.sound_enabled"), null, n.sound, Lang.t("tip.notify_sound"));
            if (snd != n.sound) { n.sound = snd; Skycore.markDirty(); }
            cy += 32;
            String[] sl = new String[SOUNDS.length];
            for (int i = 0; i < SOUNDS.length; i++) sl[i] = Lang.t("notify.sound." + SOUNDS[i]);
            ui.g.text(Lang.t("notify.sound_type"), s.x(), cy + 6, ui.t.text(), 1f, Weight.MEDIUM);
            Rect dd = new Rect(s.x() + s.w() - 180, cy, 140, 22);
            int si = ui.dropdown("nt#sndtype", dd, sl, indexOf(SOUNDS, n.soundType));
            if (!SOUNDS[si].equals(n.soundType)) { n.soundType = SOUNDS[si]; Skycore.markDirty(); Notifier.playSound(n.soundType); }
            if (ui.iconButton("nt#sndplay", new Rect(dd.right() + 8, cy + 1, 20, 20), Icon.PLAY, Lang.t("notify.preview"))) Notifier.playSound(n.soundType);
            cy += 30;
        }
        return ui.endSection(s, cy);
    }

    // ------------------------------------------------------------------ messages
    private float messages(Ui ui, Rect area, float y, Config.NotificationSettings n) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Ui.Section s = ui.beginSection("nt.text", area.x(), y, area.w(), Lang.t("notify.text"), Lang.t("notify.text_desc"), Icon.EDIT, false);
        float cy = s.contentY();
        if (s.visible()) {
            for (String l : g.wrap(Lang.t("notify.placeholders"), s.w(), 0.85f)) { g.text(l, s.x(), cy, t.textMute(), 0.85f); cy += 10; }
            cy += 6;
            float labelW = Math.min(150, s.w() * 0.3f);
            for (String c : Notifier.CATEGORIES) {
                g.text(Lang.t("notify.cat." + c), s.x(), cy + 6, t.text(), 1f, Weight.MEDIUM);
                String cur = n.templates.getOrDefault(c, "");
                String nv = ui.textField("nt#tpl" + c, new Rect(s.x() + labelW, cy, s.w() - labelW - 28, 22), cur, Lang.t("notify.default_text"), 80, ch -> ch >= 32);
                if (!nv.equals(cur)) { n.templates.put(c, nv); Skycore.markDirty(); }
                if (ui.iconButton("nt#pv" + c, new Rect(s.x() + s.w() - 22, cy + 1, 20, 20), Icon.PLAY, Lang.t("notify.preview"))) Notifier.preview(c);
                cy += 28;
            }
        }
        return ui.endSection(s, cy);
    }
}
