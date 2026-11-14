package dev.skycore.music;

import dev.skycore.core.Skycore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Playback control: queue, autoplay, error recovery. Every method is cheap and returns at once; searching, probing streams and
 * choosing the next song run on two daemon threads, decoding and output on the threads of {@link Engine}. State read by the
 * screen is volatile.
 * <p>
 * Seamless playback: while a song plays, the next one (the queue entry, otherwise the autoplay pick) is already probed. When the
 * song ends it starts immediately, while the output still holds about half a second of the old one.
 */
public final class Player {
    public enum Status { IDLE, LOADING, PLAYING, PAUSED }

    private record Next(MusicTrack track, Engine.Loaded loaded, int gen, boolean fromQueue) {}
    private record Cached(long at, List<MusicTrack> tracks) {}

    private static final long CACHE_MS = 10 * 60_000L;
    private static final int MAX_FAILS = 3, MAX_QUEUE = 120;

    private final Object lock = new Object();
    private final ExecutorService net = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SkyCore-Music-Net");
        t.setDaemon(true);
        return t;
    });
    private final Random rnd = new Random();
    private final Map<String, Cached> genreCache = new HashMap<>();
    private final List<MusicTrack> queue = new ArrayList<>();
    private final List<MusicTrack> history = new ArrayList<>();
    private int qi = -1;
    private int gen;
    private int fails;
    private int problemGen = -1;
    private boolean retried;
    private Next next;
    private Engine engine;
    private boolean engineFailed;

    private volatile MusicTrack current;
    private volatile Status status = Status.IDLE;
    private volatile long duration;
    private volatile boolean seekable;
    private volatile String errorKey;
    private volatile float volume = 0.6f;
    private volatile List<MusicTrack> pool = List.of();
    private volatile Autoplay.Taste taste = new Autoplay.Taste();
    private final Consumer<String> errorSink;

    /** @param errorSink gets the i18n key of every error that should be shown to the user (any thread). */
    public Player(Consumer<String> errorSink) { this.errorSink = errorSink; }

    // ------------------------------------------------------------------ state for the screen
    public MusicTrack current() { return current; }
    public Status status() { return status; }
    public long durationMs() { return duration; }
    public boolean seekable() { return seekable; }
    /** i18n key of the last error, or null. */
    public String errorKey() { return errorKey; }
    public float volume() { return volume; }
    public long positionMs() {
        Engine e = engine;
        Status s = status;
        return e == null || (s != Status.PLAYING && s != Status.PAUSED) ? 0 : Math.min(e.position(), Math.max(duration, 0) == 0 ? Long.MAX_VALUE : duration);
    }

    // ------------------------------------------------------------------ control
    /** Plays {@code t}; {@code context} (a playlist, the favourites) plays on after it, otherwise the autoplay takes over. */
    public void play(MusicTrack t, List<MusicTrack> context) {
        if (t == null || !t.valid()) return;
        synchronized (lock) {
            queue.clear();
            if (context != null) queue.addAll(context);
            qi = queue.indexOf(t);
            if (qi < 0) { queue.add(0, t); qi = 0; }
            fails = 0;
            retried = false;
        }
        start(t, true);
    }

    public void togglePause() {
        Engine e = engine;
        if (status == Status.PLAYING && e != null) { e.setPaused(true); status = Status.PAUSED; }
        else if (status == Status.PAUSED && e != null) { e.setPaused(false); status = Status.PLAYING; }
        else if (status == Status.IDLE && current != null) start(current, true);
    }

    public void next() {
        if (current != null) advance(true);
    }

    public void previous() {
        MusicTrack prev = null;
        synchronized (lock) {
            if (positionMs() <= 3000 && history.size() >= 2) {
                history.remove(history.size() - 1);
                prev = history.remove(history.size() - 1);
                int i = queue.indexOf(prev);
                if (i >= 0) qi = i; else { queue.add(prev); qi = queue.size() - 1; }
            }
        }
        if (prev != null) start(prev, true);
        else seek(0);
    }

    /** @param frac 0..1 of the song. */
    public void seek(float frac) {
        Engine e = engine;
        if (e != null && seekable && duration > 0 && (status == Status.PLAYING || status == Status.PAUSED))
            e.seek((long) (Math.max(0f, Math.min(1f, frac)) * duration));
    }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        Engine e = engine;
        if (e != null) e.setVolume(volume);
    }

    /** Songs from the latest search: they are the first candidates of the autoplay ("Funk" gives more Funk). */
    public void setPool(List<MusicTrack> tracks) { pool = List.copyOf(tracks); }

    public void setTaste(Autoplay.Taste t) { taste = t; }

    public void stop() {
        synchronized (lock) { gen++; next = null; status = Status.IDLE; }
        Engine e = engine;
        if (e != null) e.stop();
    }

    public void shutdown() {
        net.shutdownNow();
        Engine e;
        synchronized (lock) { e = engine; engine = null; gen++; }
        if (e != null) e.shutdown();
    }

    // ------------------------------------------------------------------ playback flow
    private void start(MusicTrack t, boolean cut) {
        final int g;
        synchronized (lock) {
            current = t;
            g = ++gen;
            status = Status.LOADING;
            errorKey = null;
            duration = t.durationMs;
            next = null;
        }
        net.execute(() -> {
            try {
                Engine e = engine();
                if (e == null) { problem(g, "music.err.engine", "engine unavailable"); return; }
                e.load(Audius.streamUrl(t), l -> loaded(g, t, l, cut), d -> loadFailed(g, t, d));
            } catch (Throwable x) {
                loadFailed(g, t, x.toString());
            }
        });
    }

    /** The audio engine (LavaPlayer, OpenAL) starts on first use, on a worker thread. */
    private Engine engine() {
        synchronized (lock) {
            if (engine != null || engineFailed) return engine;
            try {
                Engine e = new Engine(new Engine.Events() {
                    @Override public void ended() { onEnded(); }
                    @Override public void broke(String detail) { onBroke(detail); }
                });
                e.setVolume(volume);
                engine = e;
            } catch (Throwable x) { // missing native library, blocked class, ...
                engineFailed = true;
                Skycore.LOG.error("Music engine could not start", x);
            }
            return engine;
        }
    }

    private void loaded(int g, MusicTrack t, Engine.Loaded l, boolean cut) {
        Engine e;
        synchronized (lock) {
            if (g != gen) return;
            e = engine;
            if (e == null) return;
            status = Status.PLAYING;
            fails = 0;
            retried = false;
            seekable = l.seekable();
            if (l.durationMs() > 0) duration = l.durationMs();
            if (history.isEmpty() || !history.get(history.size() - 1).equals(t)) history.add(t);
            while (history.size() > 40) history.remove(0);
        }
        e.play(l, cut);
        prefetch(g);
    }

    private void loadFailed(int g, MusicTrack t, String detail) {
        synchronized (lock) { if (g != gen) return; }
        Skycore.LOG.warn("Music: could not open '{}': {}", t.title, detail);
        // no internet at all, or just this song? (a dead link is skipped, a missing connection is not)
        problem(g, Audius.reachable() ? "music.err.stream" : "music.err.offline", detail);
    }

    private void onBroke(String detail) {
        int g;
        synchronized (lock) { g = gen; }
        Skycore.LOG.warn("Music: playback broke: {}", detail);
        problem(g, Audius.reachable() ? "music.err.stream" : "music.err.offline", detail);
    }

    /**
     * Something went wrong with the current song: try the same song once more (a stale stream address is resolved again), then skip
     * it, and stop after a few failures in a row or when there is no connection.
     */
    private void problem(int g, String key, String detail) {
        MusicTrack t;
        boolean again;
        synchronized (lock) {
            if (g != gen || g == problemGen) return; // a failure is often reported twice (exception, then end reason)
            problemGen = g;
            t = current;
            again = !retried && t != null && "music.err.stream".equals(key);
            if (again) retried = true;
            else { fails++; errorKey = key; }
        }
        if (again) { start(t, true); return; }
        errorSink.accept(key);
        if ("music.err.offline".equals(key) || "music.err.engine".equals(key) || fails >= MAX_FAILS) {
            stop();
            synchronized (lock) { errorKey = key; }
        } else advance(true);
    }

    private void onEnded() {
        Next n;
        synchronized (lock) { n = next; next = null; }
        Engine e = engine;
        if (n != null && e != null) {
            synchronized (lock) {
                if (n.gen() != gen) { n = null; }
                else {
                    if (n.fromQueue()) qi++;
                    else { appendQueue(n.track()); }
                    current = n.track();
                    gen++;
                    status = Status.PLAYING;
                    fails = 0;
                    retried = false;
                    seekable = n.loaded().seekable();
                    duration = n.loaded().durationMs() > 0 ? n.loaded().durationMs() : n.track().durationMs;
                    history.add(n.track());
                    while (history.size() > 40) history.remove(0);
                }
            }
            if (n != null) {
                e.play(n.loaded(), false); // the old song's last audio is still queued, so there is no gap
                prefetch(gen);
                return;
            }
        }
        advance(false);
    }

    private void appendQueue(MusicTrack t) {
        queue.add(t);
        while (queue.size() > MAX_QUEUE) { queue.remove(0); if (qi > 0) qi--; }
        qi = queue.size() - 1;
    }

    /** Next song, on the user's request or because the last one ended without a probed successor. */
    private void advance(boolean cut) {
        MusicTrack n = null;
        synchronized (lock) {
            if (qi + 1 < queue.size()) { qi++; n = queue.get(qi); }
            else status = Status.LOADING;
        }
        if (n != null) { start(n, cut); return; }
        final int g;
        synchronized (lock) { g = ++gen; next = null; }
        net.execute(() -> {
            try {
                MusicTrack pick = pickAutoplay();
                synchronized (lock) { if (g != gen) return; }
                if (pick == null) { problem(g, "music.err.nonext", "no candidate"); return; }
                synchronized (lock) { appendQueue(pick); }
                start(pick, cut);
            } catch (MusicException e) {
                Skycore.LOG.warn("Music: autoplay failed: {}", e.getMessage());
                problem(g, e.key, e.getMessage());
            } catch (RuntimeException e) {
                Skycore.LOG.warn("Music: autoplay failed", e);
                problem(g, "music.err.service", e.toString());
            }
        });
    }

    /** Probes the song after the current one in the background so that it can start without a pause. */
    private void prefetch(int g) {
        net.execute(() -> {
            try {
                MusicTrack n;
                boolean fromQueue;
                synchronized (lock) {
                    if (g != gen) return;
                    fromQueue = qi + 1 < queue.size();
                    n = fromQueue ? queue.get(qi + 1) : null;
                }
                if (n == null) n = pickAutoplay();
                if (n == null) return;
                Engine e = engine;
                if (e == null) return;
                final MusicTrack target = n;
                final boolean fq = fromQueue;
                e.load(Audius.streamUrl(n), l -> { synchronized (lock) { if (g == gen) next = new Next(target, l, g, fq); } }, d -> {});
            } catch (MusicException | RuntimeException ex) {
                Skycore.LOG.debug("Music: could not prepare the next song: {}", ex.toString());
            }
        });
    }

    // ------------------------------------------------------------------ autoplay
    private MusicTrack pickAutoplay() throws MusicException {
        MusicTrack cur = current;
        List<MusicTrack> recent;
        synchronized (lock) { recent = new ArrayList<>(history); }
        List<MusicTrack> cands = new ArrayList<>(pool);
        Set<String> genres = new LinkedHashSet<>();
        if (cur != null) genres.addAll(Genres.of(cur));
        List<String> wanted = new ArrayList<>(genres);
        if (wanted.size() > 2) wanted = wanted.subList(0, 2);
        MusicException last = null;
        for (String gId : wanted) {
            try { cands.addAll(genreTracks(gId)); } catch (MusicException e) { last = e; }
        }
        if (wanted.isEmpty() && cur != null && !cur.genre.isBlank()) {
            try { cands.addAll(Audius.search(cur.genre, 30)); } catch (MusicException e) { last = e; }
        }
        if (cands.isEmpty() && last != null) throw last;
        return Autoplay.pick(cands, cur, recent, taste, rnd);
    }

    private List<MusicTrack> genreTracks(String genreId) throws MusicException {
        long now = System.currentTimeMillis();
        synchronized (genreCache) {
            Cached c = genreCache.get(genreId);
            if (c != null && now - c.at() < CACHE_MS) return c.tracks();
        }
        List<MusicTrack> all = new ArrayList<>();
        MusicException err = null;
        String label = Genres.label(genreId);
        try { if (label != null) all.addAll(Audius.trending(label, 40)); } catch (MusicException e) { err = e; }
        try { all.addAll(Audius.search(Genres.searchTerm(genreId), 30)); } catch (MusicException e) { err = e; }
        if (all.isEmpty() && err != null) throw err;
        synchronized (genreCache) { genreCache.put(genreId, new Cached(now, all)); }
        return all;
    }
}
