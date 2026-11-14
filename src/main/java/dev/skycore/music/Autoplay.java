package dev.skycore.music;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Picks the next song: same genre as the current one, weighted by what the user searched for and favoured lately. */
public final class Autoplay {
    private Autoplay() {}

    /** Genre interest built from the last searches (newest count most) and the favourites. */
    public static final class Taste {
        private final Map<String, Double> weight = new HashMap<>();

        /** Older interest fades a little with every new search. */
        public void onSearch(String query) {
            weight.replaceAll((k, v) -> v * 0.85);
            for (String g : Genres.detect(query)) weight.merge(g, 1.0, Double::sum);
        }

        public double of(String genre) { return weight.getOrDefault(genre, 0.0); }

        public static Taste from(List<String> searches, List<MusicTrack> favourites) {
            Taste t = new Taste();
            for (MusicTrack f : favourites) for (String g : Genres.of(f)) t.weight.merge(g, 0.25, Double::sum);
            for (String s : searches) t.onSearch(s); // oldest first
            return t;
        }
    }

    /** Score of one candidate; higher is a better follow-up. Without randomness, so it can be tested. */
    public static double score(MusicTrack cand, MusicTrack current, List<MusicTrack> recent, Taste taste) {
        Set<String> want = current == null ? Set.of() : Genres.of(current);
        Set<String> has = Genres.of(cand);
        int overlap = 0;
        for (String g : has) if (want.contains(g)) overlap++;
        double s = 4.0 * overlap;
        if (!want.isEmpty() && overlap == 0) s -= 2.5;
        for (String g : has) s += taste.of(g);
        if (current != null) for (String tg : cand.tags) if (current.tags.contains(tg)) s += 0.7;
        if (current != null && !cand.artist.isEmpty() && cand.artist.equalsIgnoreCase(current.artist)) s -= 1.0;
        int n = 0;
        for (int i = recent.size() - 1; i >= 0 && n < 4; i--, n++)
            if (!cand.artist.isEmpty() && cand.artist.equalsIgnoreCase(recent.get(i).artist)) s -= 1.5;
        if (cand.durationMs > 0 && (cand.durationMs < 30_000 || cand.durationMs > 15 * 60_000L)) s -= 3.0; // snippets and hour long mixes
        return s;
    }

    /** The best not yet played candidate, or null when everything was played. {@code recent} is the play history, oldest first. */
    public static MusicTrack pick(List<MusicTrack> candidates, MusicTrack current, List<MusicTrack> recent, Taste taste, Random rnd) {
        MusicTrack best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (MusicTrack c : candidates) {
            if (c == null || !c.valid() || c.equals(current) || recent.contains(c)) continue;
            double s = score(c, current, recent, taste) + rnd.nextDouble() * 0.8; // a little variety between equal songs
            if (s > bestScore) { bestScore = s; best = c; }
        }
        return best;
    }
}
