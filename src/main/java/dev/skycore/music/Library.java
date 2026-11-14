package dev.skycore.music;

import dev.skycore.core.Storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Favourites, playlists and the last searches, kept in {@code config/skycore/music/playlists.json}. Loaded at start; every
 * change is written at once (atomically, on the storage thread). Only the client thread touches it.
 */
public final class Library {
    private Library() {}

    public static final String FILE = "music/playlists.json";
    public static final int MAX_TRACKS = 500, MAX_PLAYLISTS = 50, MAX_SEARCHES = 12, MAX_NAME = 32;

    public static final class Playlist {
        public String name = "";
        public List<MusicTrack> tracks = new ArrayList<>();
    }

    public static final class Data {
        public int version = 1;
        /** Player volume 0..1. */
        public float volume = 0.6f;
        public List<MusicTrack> favorites = new ArrayList<>();
        public List<Playlist> playlists = new ArrayList<>();
        /** Newest last. */
        public List<String> recentSearches = new ArrayList<>();
        /** YouTube mode (yt-dlp) switched on by the player. */
        public boolean youtube;
    }

    private static Data data = new Data();

    public static void load() {
        Data d = Storage.load(FILE, Data.class, Data::new);
        data = sanitize(d);
    }

    /** Drops what a hand-edited or damaged file can contain: nulls, tracks without id, duplicates, absurd sizes. */
    static Data sanitize(Data d) {
        if (d == null) d = new Data();
        if (!(d.volume >= 0f && d.volume <= 1f)) d.volume = 0.6f;
        if (d.favorites == null) d.favorites = new ArrayList<>();
        if (d.playlists == null) d.playlists = new ArrayList<>();
        if (d.recentSearches == null) d.recentSearches = new ArrayList<>();
        d.favorites = clean(d.favorites);
        d.playlists.removeIf(p -> p == null);
        for (Playlist p : d.playlists) {
            if (p.name == null || p.name.isBlank()) p.name = "Playlist";
            p.tracks = clean(p.tracks == null ? new ArrayList<>() : p.tracks);
        }
        if (d.playlists.size() > MAX_PLAYLISTS) d.playlists = new ArrayList<>(d.playlists.subList(0, MAX_PLAYLISTS));
        d.recentSearches.removeIf(s -> s == null || s.isBlank());
        while (d.recentSearches.size() > MAX_SEARCHES) d.recentSearches.remove(0);
        return d;
    }

    private static List<MusicTrack> clean(List<MusicTrack> in) {
        List<MusicTrack> out = new ArrayList<>();
        for (MusicTrack t : in) {
            if (t == null || !t.valid() || out.contains(t) || out.size() >= MAX_TRACKS) continue;
            if (t.title == null) t.title = "";
            if (t.artist == null) t.artist = "";
            if (t.genre == null) t.genre = "";
            if (t.tags == null) t.tags = new ArrayList<>();
            out.add(t);
        }
        return out;
    }

    private static void save() { Storage.save(FILE, data); }

    public static List<MusicTrack> favorites() { return data.favorites; }
    public static List<Playlist> playlists() { return data.playlists; }
    public static List<String> recentSearches() { return data.recentSearches; }

    public static boolean isFavorite(MusicTrack t) { return t != null && data.favorites.contains(t); }

    /** @return true if the song is a favourite now. */
    public static boolean toggleFavorite(MusicTrack t) {
        if (t == null || !t.valid()) return false;
        boolean now;
        if (data.favorites.remove(t)) now = false;
        else if (data.favorites.size() >= MAX_TRACKS) return false;
        else { data.favorites.add(0, t); now = true; }
        save();
        return now;
    }

    /** Creates a playlist with a free name; null when the limit is reached. */
    public static Playlist createPlaylist(String wanted) {
        if (data.playlists.size() >= MAX_PLAYLISTS) return null;
        String base = wanted == null || wanted.isBlank() ? "Playlist" : wanted.trim();
        if (base.length() > MAX_NAME) base = base.substring(0, MAX_NAME);
        String name = base;
        for (int i = 2; byName(name) != null; i++) name = base + " " + i;
        Playlist p = new Playlist();
        p.name = name;
        data.playlists.add(p);
        save();
        return p;
    }

    public static Playlist byName(String name) {
        for (Playlist p : data.playlists) if (p.name.equalsIgnoreCase(name)) return p;
        return null;
    }

    public static boolean addToPlaylist(Playlist p, MusicTrack t) {
        if (p == null || t == null || !t.valid() || p.tracks.contains(t) || p.tracks.size() >= MAX_TRACKS) return false;
        p.tracks.add(t);
        save();
        return true;
    }

    public static void removeFromPlaylist(Playlist p, MusicTrack t) {
        if (p != null && p.tracks.remove(t)) save();
    }

    public static void deletePlaylist(Playlist p) {
        if (data.playlists.remove(p)) save();
    }

    /** Remembers a search for the genre taste of the autoplay. */
    public static void addSearch(String q) {
        if (q == null || q.isBlank()) return;
        data.recentSearches.remove(q);
        data.recentSearches.add(q);
        while (data.recentSearches.size() > MAX_SEARCHES) data.recentSearches.remove(0);
        save();
    }

    public static boolean youtube() { return data.youtube; }

    public static void setYoutube(boolean on) {
        data.youtube = on;
        save();
    }

    public static float volume() { return data.volume; }

    /** @param persist false while a slider is being dragged; true once it is released. */
    public static void setVolume(float v, boolean persist) {
        data.volume = Math.max(0f, Math.min(1f, v));
        if (persist) save();
    }

    /** Tests only. */
    static void reset() { data = new Data(); }
}
