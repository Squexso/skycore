package dev.skycore.ui.pages;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.skycore.core.Lang;
import dev.skycore.music.Library;
import dev.skycore.music.Music;
import dev.skycore.music.MusicTrack;
import dev.skycore.music.Player;
import dev.skycore.ui.Page;
import dev.skycore.ui.Rect;
import dev.skycore.ui.SkycoreScreen;
import dev.skycore.ui.Ui;
import dev.skycore.ui.gfx.Gfx;
import dev.skycore.ui.gfx.Gfx.Weight;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Col;
import dev.skycore.ui.theme.Theme;

import java.util.List;

/**
 * Music: search, results, favourites and playlists, and the player bar. The page fills the window (no page scroll); the lists
 * scroll inside their cards and the player bar always stays at the bottom.
 */
public class MusicPage implements Page {
    private static final float ROW = 34;

    private String query = "";
    private int tab;               // narrow layout: 0 results, 1 favourites, 2 playlists; wide layout: 1 / 2 on the right
    private String openPlaylist;   // name of the playlist whose songs are shown
    private String newName = "";
    private MusicTrack addTrack;   // the song the "add to playlist" dialog is about
    private float seekFrac = -1;
    private boolean volumeDirty;

    @Override public String id() { return "music"; }
    @Override public Icon icon() { return Icon.SOUND; }

    @Override
    public float render(Ui ui, Rect area, SkycoreScreen screen) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Player pl = Music.player();
        float h = Math.max(300, area.h() - 12);
        boolean wide = area.w() >= 560;
        float barH = wide ? 74 : 106;
        float x = area.x(), w = area.w(), y = area.y() + 2;

        searchRow(ui, new Rect(x, y, w, 28), pl, screen);
        Rect body = new Rect(x, y + 38, w, h - 38 - barH - 10 - 2);
        if (wide) {
            float lw = Math.round(w * 0.56f);
            resultsCard(ui, new Rect(body.x(), body.y(), lw, body.h()), pl);
            libraryCard(ui, new Rect(body.x() + lw + 10, body.y(), w - lw - 10, body.h()), pl, true);
        } else {
            Rect seg = new Rect(body.x(), body.y(), body.w(), 24);
            tab = ui.segmented("mu#tabs", seg, new String[]{Lang.t("music.results"), Lang.t("music.favorites"), Lang.t("music.playlists")}, tab);
            Rect card = new Rect(body.x(), body.y() + 30, body.w(), body.h() - 30);
            if (tab == 0) resultsCard(ui, card, pl); else libraryCard(ui, card, pl, false);
        }
        playerBar(ui, new Rect(x, y + h - barH - 2, w, barH), pl, wide);
        if (addTrack != null) addDialog(ui, screen);
        return h + 4;
    }

    // ------------------------------------------------------------------ search
    private void searchRow(Ui ui, Rect r, Player pl, SkycoreScreen screen) {
        boolean wasFocused = "mu#q".equals(ui.focus);
        float bw = ui.g.width(Lang.t("music.search"), 1f, Weight.MEDIUM) + 40;
        Rect field = new Rect(r.x(), r.y(), r.w() - bw - 8 - 68, r.h());
        // own audio files: lists everything in config/skycore/music/local
        if (ui.iconButton("mu#local", new Rect(r.right() - bw - 8 - 62, r.y(), 28, r.h()), Icon.IMPORT, Lang.t("music.local_tip"))) { Music.showLocal(); tab = 0; }
        // the music keys are changed in Settings, in the Shortcuts section
        if (ui.iconButton("mu#keys", new Rect(r.right() - bw - 8 - 28, r.y(), 28, r.h()), Icon.KEYBOARD, Lang.t("music.keys"))) {
            dev.skycore.core.Skycore.config().expanded.put("set.keys", true);
            screen.openPage(screen.pageIndex(SettingsPage.class));
        }
        query = ui.textField("mu#q", field, query, Lang.t("music.search_ph"), 80, null);
        boolean enter = wasFocused && (ui.keyThisFrame(257) || ui.keyThisFrame(335));
        boolean go = ui.button("mu#go", new Rect(r.right() - bw, r.y(), bw, r.h()), Lang.t("music.search"), Icon.SPARK, Ui.Style.PRIMARY, !query.isBlank());
        if ((enter || go) && !query.isBlank()) { Music.search(query); tab = 0; }
    }

    // ------------------------------------------------------------------ results
    private void resultsCard(Ui ui, Rect r, Player pl) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ui.card(r);
        Music.Search s = Music.search();
        String title = s.query().isEmpty() ? Lang.t("music.results") : Lang.t("music.results_for", s.query());
        g.text(g.ellipsize(title, r.w() - 28, 1f), r.x() + 14, r.y() + 12, t.text(), 1f, Weight.SEMIBOLD);
        Rect view = new Rect(r.x() + 6, r.y() + 32, r.w() - 12, r.h() - 38);
        if (s.loading()) { centered(ui, view, Lang.t("music.searching")); return; }
        if (s.errorKey() != null) { centered(ui, view, Lang.t(s.errorKey())); return; }
        if (s.results().isEmpty()) { centered(ui, view, Lang.t("music.empty_results")); return; }
        trackList(ui, "mu#res", view, s.results(), s.results().subList(0, 0), null, pl);
    }

    // ------------------------------------------------------------------ favourites / playlists
    private void libraryCard(Ui ui, Rect r, Player pl, boolean withTabs) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ui.card(r);
        float cy = r.y() + 10;
        if (withTabs) {
            int sel = Math.max(0, Math.min(1, tab - 1));
            sel = ui.segmented("mu#lib", new Rect(r.x() + 10, cy, r.w() - 20, 24), new String[]{Lang.t("music.favorites"), Lang.t("music.playlists")}, sel);
            tab = sel + 1;
            cy += 32;
        } else if (tab < 1) tab = 1;
        Rect inner = new Rect(r.x() + 6, cy, r.w() - 12, r.bottom() - cy - 6);
        if (tab == 1) favourites(ui, inner, pl); else playlists(ui, inner, pl);
    }

    private void favourites(Ui ui, Rect r, Player pl) {
        List<MusicTrack> favs = Library.favorites();
        Rect head = new Rect(r.x() + 8, r.y(), r.w() - 16, 24);
        ui.g.text(Lang.t("music.songs_n", favs.size()), head.x(), head.y() + 7, ui.t.textDim(), 0.95f, Weight.MEDIUM);
        playAll(ui, "mu#favall", head, favs, pl);
        Rect view = new Rect(r.x(), r.y() + 28, r.w(), r.h() - 28);
        if (favs.isEmpty()) centered(ui, view, Lang.t("music.no_favs"));
        else trackList(ui, "mu#fav", view, favs, favs, null, pl);
    }

    private void playlists(Ui ui, Rect r, Player pl) {
        Gfx g = ui.g;
        Theme t = ui.t;
        Library.Playlist open = openPlaylist == null ? null : Library.byName(openPlaylist);
        if (open == null) openPlaylist = null;
        if (open != null) {
            Rect head = new Rect(r.x() + 4, r.y(), r.w() - 8, 24);
            if (ui.button("mu#back", new Rect(head.x(), head.y(), 24, 24), null, Icon.LIST, Ui.Style.GHOST)) openPlaylist = null;
            g.text(g.ellipsize(open.name, head.w() - 150, 1f), head.x() + 32, head.y() + 7, t.text(), 1f, Weight.SEMIBOLD);
            if (ui.iconButton("mu#pdel", new Rect(head.right() - 24, head.y(), 24, 24), Icon.TRASH, Lang.t("music.delete"))) { Library.deletePlaylist(open); openPlaylist = null; return; }
            playAll(ui, "mu#pall", new Rect(head.x(), head.y(), head.w() - 28, 24), open.tracks, pl);
            Rect view = new Rect(r.x(), r.y() + 28, r.w(), r.h() - 28);
            if (open.tracks.isEmpty()) centered(ui, view, Lang.t("music.playlist_empty"));
            else trackList(ui, "mu#pl", view, open.tracks, open.tracks, open, pl);
            return;
        }
        // create row
        float bw = g.width(Lang.t("music.create"), 1f, Weight.MEDIUM) + 36;
        Rect field = new Rect(r.x() + 6, r.y(), r.w() - bw - 18, 24);
        boolean wasFocused = "mu#np".equals(ui.focus);
        newName = ui.textField("mu#np", field, newName, Lang.t("music.playlist_name"), Library.MAX_NAME, null);
        boolean enter = wasFocused && (ui.keyThisFrame(257) || ui.keyThisFrame(335));
        boolean create = ui.button("mu#mk", new Rect(r.right() - bw - 6, r.y(), bw, 24), Lang.t("music.create"), Icon.PLUS, Ui.Style.SECONDARY);
        if (create || enter) {
            Library.Playlist p = Library.createPlaylist(newName);
            if (p != null) { newName = ""; openPlaylist = p.name; }
        }
        Rect view = new Rect(r.x(), r.y() + 30, r.w(), r.h() - 30);
        List<Library.Playlist> all = Library.playlists();
        if (all.isEmpty()) { centered(ui, view, Lang.t("music.no_playlists")); return; }
        float off = ui.scroll("mu#pls", view, all.size() * ROW);
        ui.pushClip(view);
        for (int i = 0; i < all.size(); i++) {
            Library.Playlist p = all.get(i);
            Rect row = new Rect(view.x() + 2, view.y() + i * ROW - off, view.w() - 10, ROW - 2);
            if (row.bottom() < view.y() || row.y() > view.bottom()) continue;
            boolean hov = ui.hover(row);
            if (hov) { g.roundRect(row.x(), row.y(), row.w(), row.h(), 8, t.surfaceHover()); ui.setCursor(CursorTypes.POINTING_HAND); }
            Rect trash = new Rect(row.right() - 28, row.cy() - 11, 22, 22);
            if (ui.iconButton("mu#pd" + i, trash, Icon.TRASH, Lang.t("music.delete"))) { Library.deletePlaylist(p); break; }
            g.text(g.ellipsize(p.name, row.w() - 120, 1f), row.x() + 10, row.y() + 6, t.text(), 1f, Weight.MEDIUM);
            g.text(Lang.t("music.songs_n", p.tracks.size()), row.x() + 10, row.y() + 18, t.textMute(), 0.85f);
            if (ui.pressedOn(new Rect(row.x(), row.y(), row.w() - 32, row.h()))) ui.active = "mu#po" + i;
            if (ui.releasedThisFrame() && ("mu#po" + i).equals(ui.active) && hov) { ui.active = null; Ui.clickSound(); openPlaylist = p.name; }
        }
        ui.popClip();
    }

    private void playAll(Ui ui, String id, Rect head, List<MusicTrack> tracks, Player pl) {
        String label = Lang.t("music.play_all");
        float bw = ui.g.width(label, 1f, Weight.MEDIUM) + 34;
        if (ui.button(id, new Rect(head.right() - bw, head.y(), bw, 24), label, Icon.PLAY, Ui.Style.SECONDARY, !tracks.isEmpty()))
            pl.play(tracks.get(0), tracks);
    }

    // ------------------------------------------------------------------ song list
    /**
     * @param context what keeps playing after the clicked song (empty = autoplay takes over)
     * @param owner   set for a playlist: the second button removes the song from it instead of adding it to a playlist
     */
    private void trackList(Ui ui, String id, Rect view, List<MusicTrack> tracks, List<MusicTrack> context, Library.Playlist owner, Player pl) {
        Gfx g = ui.g;
        Theme t = ui.t;
        float off = ui.scroll(id, view, tracks.size() * ROW);
        ui.pushClip(view);
        MusicTrack cur = pl.current();
        for (int i = 0; i < tracks.size(); i++) {
            MusicTrack tr = tracks.get(i);
            Rect row = new Rect(view.x() + 2, view.y() + i * ROW - off, view.w() - 10, ROW - 2);
            if (row.bottom() < view.y() || row.y() > view.bottom()) continue;
            boolean now = tr.equals(cur);
            boolean hov = ui.hover(row);
            if (now) g.roundRect(row.x(), row.y(), row.w(), row.h(), 8, Col.alpha(t.primary(), 0.12f));
            else if (hov) g.roundRect(row.x(), row.y(), row.w(), row.h(), 8, t.surfaceHover());
            String rid = id + i;
            // buttons first so that they take the click before the row does
            Rect heart = new Rect(row.right() - 26, row.cy() - 11, 22, 22);
            Rect second = new Rect(heart.x() - 24, heart.y(), 22, 22);
            boolean fav = Library.isFavorite(tr);
            if (ui.button(rid + "f", heart, null, null, Ui.Style.GHOST)) { Library.toggleFavorite(tr); Music.refreshTaste(); }
            heart(g, heart.cx(), heart.cy() - 0.5f, 11, fav ? t.primary() : Col.alpha(t.textMute(), 0.8f));
            if (hov && ui.hover(heart)) ui.tooltip(Lang.t(fav ? "music.fav_remove" : "music.fav_add"));
            boolean secondClicked;
            if (owner != null) secondClicked = ui.iconButton(rid + "r", second, Icon.TRASH, Lang.t("music.remove"));
            else secondClicked = ui.iconButton(rid + "a", second, Icon.PLUS, Lang.t("music.add_to"));
            if (secondClicked) {
                if (owner != null) { Library.removeFromPlaylist(owner, tr); break; }
                addTrack = tr;
            }
            float tx = row.x() + 26;
            float dw = tr.durationMs > 0 ? g.width(time(tr.durationMs), 0.9f) + 8 : 0;
            float textW = second.x() - tx - dw - 4;
            if (now) g.icon(pl.status() == Player.Status.PAUSED ? Icon.PAUSE : Icon.PLAY, row.x() + 8, row.y() + 11, 10, t.primary());
            else g.text(String.valueOf(i + 1), row.x() + 8, row.y() + 12, t.textMute(), 0.85f);
            g.text(g.ellipsize(tr.title, textW, 1f), tx, row.y() + 6, now ? t.primary() : t.text(), 1f, Weight.MEDIUM);
            String sub = tr.artist + (tr.genre.isBlank() ? "" : "  ·  " + tr.genre);
            g.text(g.ellipsize(sub, textW, 0.85f), tx, row.y() + 18, t.textMute(), 0.85f);
            if (dw > 0) g.textRight(time(tr.durationMs), second.x() - 6, row.cy() - 4, t.textDim(), 0.9f, Weight.REGULAR);
            Rect body = new Rect(row.x(), row.y(), second.x() - row.x() - dw - 6, row.h());
            if (hov && ui.hover(body)) ui.setCursor(CursorTypes.POINTING_HAND);
            if (ui.pressedOn(body)) ui.active = rid;
            if (ui.releasedThisFrame() && rid.equals(ui.active) && ui.hover(body)) {
                ui.active = null;
                Ui.clickSound();
                pl.play(tr, context.isEmpty() ? null : context);
            }
        }
        ui.popClip();
    }

    // ------------------------------------------------------------------ add to playlist
    private void addDialog(Ui ui, SkycoreScreen screen) {
        MusicTrack tr = addTrack;
        List<Library.Playlist> all = Library.playlists();
        int shown = Math.min(5, all.size());
        float hh = 96 + shown * 30 + (all.isEmpty() ? 18 : 0);
        ui.modal(screen.screenRect(), 320, hh, Lang.t("music.add_to_title", tr.title.length() > 26 ? tr.title.substring(0, 25) + "…" : tr.title), b -> {
            Gfx g = ui.g;
            float y = b.y();
            if (all.isEmpty()) { g.text(Lang.t("music.no_playlists"), b.x(), y + 2, ui.t.textDim(), 1f); y += 18; }
            Rect list = new Rect(b.x(), y, b.w(), shown * 30);
            float off = ui.scroll("mu#adl", list, all.size() * 30);
            ui.pushClip(list);
            for (int i = 0; i < all.size(); i++) {
                Library.Playlist p = all.get(i);
                Rect row = new Rect(list.x(), list.y() + i * 30 - off, list.w() - 8, 26);
                if (row.bottom() < list.y() || row.y() > list.bottom()) continue;
                boolean has = p.tracks.contains(tr);
                if (ui.button("mu#ad" + i, row, p.name, has ? Icon.CHECK : Icon.PLUS, Ui.Style.SECONDARY, !has)) { Library.addToPlaylist(p, tr); addTrack = null; }
            }
            ui.popClip();
            y += shown * 30 + 6;
            float bw = g.width(Lang.t("music.create"), 1f, Weight.MEDIUM) + 36;
            boolean wasFocused = "mu#an".equals(ui.focus);
            newName = ui.textField("mu#an", new Rect(b.x(), y, b.w() - bw - 8, 24), newName, Lang.t("music.playlist_name"), Library.MAX_NAME, null);
            boolean enter = wasFocused && (ui.keyThisFrame(257) || ui.keyThisFrame(335));
            if (ui.button("mu#am", new Rect(b.right() - bw, y, bw, 24), Lang.t("music.create"), Icon.PLUS, Ui.Style.PRIMARY) || enter) {
                Library.Playlist p = Library.createPlaylist(newName);
                if (p != null) { Library.addToPlaylist(p, tr); newName = ""; addTrack = null; }
            }
        }, () -> addTrack = null);
    }

    // ------------------------------------------------------------------ player bar
    private void playerBar(Ui ui, Rect r, Player pl, boolean wide) {
        Gfx g = ui.g;
        Theme t = ui.t;
        ui.card(r);
        MusicTrack cur = pl.current();
        Player.Status st = pl.status();
        float pad = 14;
        // ---- what is playing
        float infoW = wide ? Math.min(240, r.w() * 0.30f) : r.w() - 2 * pad;
        Rect info = new Rect(r.x() + pad, r.y() + 12, infoW, 36);
        String line1 = cur == null ? Lang.t("music.nothing_playing") : cur.title;
        g.text(g.ellipsize(line1, info.w(), 1f), info.x(), info.y(), cur == null ? t.textDim() : t.text(), 1f, Weight.SEMIBOLD);
        String sub; int subColor = t.textMute();
        if (pl.errorKey() != null && st == Player.Status.IDLE) { sub = Lang.t(pl.errorKey()); subColor = t.danger(); }
        else if (st == Player.Status.LOADING) sub = Lang.t("music.loading");
        else if (cur != null) sub = cur.artist;
        else sub = Lang.t("music.source");
        g.text(g.ellipsize(sub, info.w(), 0.9f), info.x(), info.y() + 13, subColor, 0.9f);
        if (cur != null && st != Player.Status.IDLE) {
            String hint = Lang.t("music.autoplay_hint");
            if (wide) g.text(g.ellipsize(hint, info.w(), 0.8f), info.x(), info.y() + 25, Col.alpha(t.textMute(), 0.8f), 0.8f);
        }
        // ---- transport
        float ctrlY = wide ? r.y() + 8 : r.y() + 44;
        float cx = wide ? r.cx() : r.x() + pad + 70;
        Rect prev = new Rect(cx - 52, ctrlY + 2, 26, 26);
        Rect play = new Rect(cx - 16, ctrlY, 32, 30);
        Rect next = new Rect(cx + 26, ctrlY + 2, 26, 26);
        if (ui.button("mu#prev", prev, null, null, Ui.Style.GHOST, cur != null)) pl.previous();
        skip(g, prev.cx(), prev.cy(), false, cur != null ? t.text() : t.textMute());
        boolean playing = st == Player.Status.PLAYING;
        if (ui.button("mu#play", play, null, null, Ui.Style.PRIMARY, cur != null)) pl.togglePause();
        Icon pi = st == Player.Status.LOADING ? Icon.CLOCK : playing ? Icon.PAUSE : Icon.PLAY;
        g.icon(pi, play.cx() - 6, play.cy() - 6, 12, Col.onColor(t.primary()));
        if (ui.button("mu#next", next, null, null, Ui.Style.GHOST, cur != null)) pl.next();
        skip(g, next.cx(), next.cy(), true, cur != null ? t.text() : t.textMute());
        if (ui.hover(prev)) ui.tooltip(Lang.t("music.prev"));
        if (ui.hover(next)) ui.tooltip(Lang.t("music.next"));
        // ---- volume
        float volW = 100;
        Rect vol = wide ? new Rect(r.right() - pad - volW, r.y() + 20, volW, 14) : new Rect(r.right() - pad - volW, ctrlY + 11, volW, 14);
        g.icon(Icon.SOUND, vol.x() - 18, vol.cy() - 6, 12, t.textDim());
        float vNow = ui.slider("mu#vol", vol, pl.volume(), 0f, 1f, 0.01f);
        if (Math.abs(vNow - pl.volume()) > 0.0005f) { pl.setVolume(vNow); Library.setVolume(vNow, false); volumeDirty = true; }
        if (volumeDirty && !ui.mouseDown()) { Library.setVolume(pl.volume(), true); volumeDirty = false; }
        if (ui.hover(vol.expand(4))) ui.tooltip(Lang.t("music.volume") + ": " + Math.round(pl.volume() * 100) + "%");
        // ---- progress
        long dur = pl.durationMs(), pos = pl.positionMs();
        float by = r.bottom() - 18;
        float bx = r.x() + pad + 34, bw = r.w() - 2 * pad - 68;
        Rect bar = new Rect(bx, by, bw, 6);
        Rect hit = new Rect(bx, by - 6, bw, 18);
        boolean canSeek = cur != null && pl.seekable() && dur > 0 && (st == Player.Status.PLAYING || st == Player.Status.PAUSED);
        float frac = dur > 0 ? Math.max(0, Math.min(1, pos / (float) dur)) : 0;
        if (canSeek) {
            if (ui.hover(hit)) ui.setCursor(CursorTypes.POINTING_HAND);
            if (ui.pressedOn(hit)) { ui.active = "mu#seek"; seekFrac = frac; }
            if ("mu#seek".equals(ui.active)) {
                seekFrac = Math.max(0, Math.min(1, (ui.mx - bx) / bw));
                if (!ui.mouseDown()) { pl.seek(seekFrac); ui.active = null; seekFrac = -1; }
            }
        } else if ("mu#seek".equals(ui.active)) { ui.active = null; seekFrac = -1; }
        float shown = seekFrac >= 0 ? seekFrac : frac;
        g.roundRect(bar.x(), bar.y(), bar.w(), bar.h(), 3, t.track());
        if (shown > 0.001f) g.roundRect(bar.x(), bar.y(), Math.max(6, bar.w() * shown), bar.h(), 3, t.primary());
        if (canSeek && (ui.hover(hit) || seekFrac >= 0)) g.circle(bar.x() + bar.w() * shown, bar.cy(), 5, 0xFFFFFFFF);
        long showPos = seekFrac >= 0 ? (long) (seekFrac * dur) : pos;
        g.text(time(showPos), r.x() + pad, by - 1.5f, t.textDim(), 0.9f);
        g.textRight(dur > 0 ? time(dur) : "--:--", r.right() - pad, by - 1.5f, t.textDim(), 0.9f, Weight.REGULAR);
    }

    // ------------------------------------------------------------------ small drawing helpers
    private static void centered(Ui ui, Rect view, String text) {
        Gfx g = ui.g;
        List<String> lines = g.wrap(text, Math.max(80, view.w() - 40), 0.95f);
        float y = view.cy() - lines.size() * 6;
        for (String l : lines) { g.textCentered(l, view.cx(), y, ui.t.textMute(), 0.95f, Weight.REGULAR); y += 12; }
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static void heart(Gfx g, float cx, float cy, float s, int c) {
        float r = s * 0.27f;
        g.circle(cx - s * 0.23f, cy - s * 0.12f, r, c);
        g.circle(cx + s * 0.23f, cy - s * 0.12f, r, c);
        int rows = 9;
        float top = cy - s * 0.1f, bot = cy + s * 0.5f, rh = (bot - top) / rows;
        for (int i = 0; i < rows; i++) {
            float hw = Math.max(0.4f, s * 0.5f * (1f - (i + 0.5f) / rows));
            g.rect(cx - hw, top + i * rh, hw * 2, rh + 0.25f, c);
        }
    }

    /** Skip glyph: a bar and a triangle. */
    private static void skip(Gfx g, float cx, float cy, boolean forward, int c) {
        int n = 7;
        float tw = 8, cw = tw / n, x0 = forward ? cx - 5 : cx - 3;
        for (int i = 0; i < n; i++) {
            float k = forward ? 1f - (i + 0.5f) / n : (i + 0.5f) / n;
            float hh = 10 * k;
            g.rect(x0 + i * cw, cy - hh / 2, cw + 0.2f, hh, c);
        }
        g.rect(forward ? cx + 3.6f : cx - 5.4f, cy - 5, 1.8f, 10, c);
    }
}
