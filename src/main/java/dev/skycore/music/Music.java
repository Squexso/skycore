package dev.skycore.music;

import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.notify.Notifier;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Theme;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point of the music feature for the rest of the mod: loads the library, owns the player and runs searches on a worker
 * thread. Nothing here blocks the client thread.
 */
public final class Music {
    private Music() {}

    /** What the search box shows. Replaced as a whole, so the screen always reads a consistent snapshot. */
    public record Search(String query, List<MusicTrack> results, boolean loading, String errorKey) {
        static final Search EMPTY = new Search("", List.of(), false, null);
    }

    private static Player player;
    private static volatile Search search = Search.EMPTY;
    private static int searchGen;
    private static long lastErrorToastAt;
    private static String lastErrorToastKey = "";
    private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SkyCore-Music-Search");
        t.setDaemon(true);
        return t;
    });

    /** Client start: reads playlists.json. The player itself (and LavaPlayer) is only created when the tab is first used. */
    public static void init() {
        Library.load();
        refreshTaste();
    }

    public static Player player() {
        if (player == null) {
            player = new Player(Music::showError);
            player.setVolume(Library.volume());
            refreshTaste();
        }
        return player;
    }

    /** True once something was played this session (so the tick / shutdown know whether there is anything to stop). */
    public static boolean started() { return player != null; }

    /** Hotkeys: pause/resume, next, previous. They do nothing while no song was started. */
    public static void hotkey(String action) {
        if (player == null) return;
        switch (action) {
            case "toggle" -> player.togglePause();
            case "next" -> player.next();
            case "previous" -> player.previous();
            default -> { }
        }
    }

    public static Search search() { return search; }

    /** Starts a search in the background. Also feeds the genre taste of the autoplay. */
    public static void search(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return;
        final int g;
        synchronized (SEARCH) { g = ++searchGen; }
        search = new Search(q, List.of(), true, null);
        Library.addSearch(q);
        refreshTaste();
        SEARCH.execute(() -> {
            Search result;
            try {
                List<MusicTrack> found = searchAll(q);
                result = new Search(q, found, false, found.isEmpty() ? "music.err.none" : null);
            } catch (MusicException e) {
                Skycore.LOG.warn("Music search failed: {}", e.getMessage());
                result = new Search(q, List.of(), false, e.key);
            } catch (RuntimeException e) {
                Skycore.LOG.warn("Music search failed", e);
                result = new Search(q, List.of(), false, "music.err.service");
            }
            synchronized (SEARCH) {
                if (g != searchGen) return; // a newer search replaced this one
                search = result;
            }
            if (player != null) player.setPool(result.results());
            if (result.errorKey() != null && !"music.err.none".equals(result.errorKey())) showError(result.errorKey());
        });
    }

    /** The phrase as typed, also in Latin letters for Cyrillic input; when that finds little, its single words as well. */
    private static List<MusicTrack> searchAll(String q) throws MusicException {
        List<List<MusicTrack>> lists = new java.util.ArrayList<>();
        MusicException err = null;
        int total = 0;
        for (int round = 0; round < 2 && total < 5; round++) {
            for (String phrase : round == 0 ? Translit.variants(q) : Translit.words(q)) {
                try {
                    List<MusicTrack> r = Audius.search(phrase, 40);
                    lists.add(r);
                    total += r.size();
                } catch (MusicException e) {
                    err = e;
                    if ("music.err.offline".equals(e.key)) throw e; // no point in trying more
                }
            }
        }
        if (total == 0 && err != null) throw err;
        // take turns, so that the typed phrase and its other spelling both show up near the top
        List<MusicTrack> found = new java.util.ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; found.size() < 60; i++) {
            boolean any = false;
            for (List<MusicTrack> l : lists) if (i < l.size()) { any = true; if (ids.add(l.get(i).id)) found.add(l.get(i)); }
            if (!any) break;
        }
        return found;
    }

    /** Screenshot run only: some songs to show the lists. Nothing is saved. */
    public static void devSample() {
        List<MusicTrack> l = List.of(
                new MusicTrack("s1", "Funk You", "Westside Zay", 268_000, "Funk", List.of("funky")),
                new MusicTrack("s2", "Slap Bass Sunday", "The Groove Collective with a very long name", 201_000, "Funk", List.of()),
                new MusicTrack("s3", "Requiem in D minor, K. 626 - Lacrimosa", "Mozart", 189_000, "Classical", List.of()),
                new MusicTrack("s4", "Midnight Disco", "Neon Parade", 232_000, "Electronic", List.of("disco")));
        search = new Search("funk", l, false, null);
        Library.favorites().addAll(l.subList(0, 2));
        Library.createPlaylist("Road trip").tracks.addAll(l.subList(1, 4));
    }

    public static void refreshTaste() {
        if (player != null) player.setTaste(Autoplay.Taste.from(Library.recentSearches(), Library.favorites()));
    }

    /** One toast per problem: the same message is not repeated within ten seconds. */
    private static void showError(String key) {
        long now = System.currentTimeMillis();
        synchronized (Music.class) {
            if (key.equals(lastErrorToastKey) && now - lastErrorToastAt < 10_000) return;
            lastErrorToastKey = key;
            lastErrorToastAt = now;
        }
        Skycore.later(() -> Notifier.push(null, Lang.t("music.title"), Lang.t(key), Icon.WARNING, Theme.get().danger()));
    }

    /** Game is closing: stop the audio threads and release the OpenAL source before Minecraft tears its audio system down. */
    public static void shutdown() {
        SEARCH.shutdownNow();
        if (player != null) player.shutdown();
    }
}
