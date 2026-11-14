package dev.skycore.music;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GenresAndAutoplayTest {
    private static MusicTrack track(String id, String title, String artist, String genre, String... tags) {
        return new MusicTrack(id, title, artist, 200_000, genre, List.of(tags));
    }

    @Test
    void searchTermsMapToGenres() {
        assertEquals(Set.of("funk"), Genres.detect("Funk"));
        assertTrue(Genres.detect("Mozart").contains("classical"));
        assertTrue(Genres.detect("mozart piano concerto").contains("classical"));
        assertTrue(Genres.detect("funky bass").contains("funk"));
        assertTrue(Genres.detect("deep house mix").contains("house"));
        assertTrue(Genres.detect("Lo-Fi beats to study").contains("lofi"));
        assertTrue(Genres.detect("Hip Hop").contains("hiphop"));
        assertTrue(Genres.detect("Beyoncé", "Schönberg").isEmpty());
    }

    @Test
    void wordsOnlyMatchWholeWordsOrPlainEndings() {
        assertTrue(Genres.detect("rocket launch").isEmpty());
        assertTrue(Genres.detect("household chores").isEmpty());
        assertTrue(Genres.detect("trapped in a box").isEmpty());
        assertTrue(Genres.detect("hard rocks").contains("rock"));
    }

    @Test
    void sourceLabelsAreRecognised() {
        assertEquals("hiphop", Genres.fromLabel("Hip-Hop/Rap"));
        assertEquals("rnb", Genres.fromLabel("R&B/Soul"));
        assertEquals("funk", Genres.fromLabel("funk"));
        assertEquals("dnb", Genres.fromLabel("Drum & Bass"));
        assertNull(Genres.fromLabel(""));
        assertNull(Genres.fromLabel(null));
    }

    @Test
    void trackGenresCombineLabelTagsAndTitle() {
        Set<String> g = Genres.of(track("1", "Late Night Groove", "Someone", "Electronic", "funky", "jazz"));
        assertTrue(g.contains("electronic"));
        assertTrue(g.contains("funk"));
        assertTrue(g.contains("jazz"));
        assertEquals("electronic", g.iterator().next()); // the source's own label comes first
    }

    @Test
    void autoplayStaysInTheGenreOfTheCurrentSong() {
        MusicTrack cur = track("c", "Current", "A", "Funk");
        List<MusicTrack> cands = List.of(track("1", "Symphony No. 5", "B", "Classical"), track("2", "Slap Bass", "C", "Funk", "funky"),
                track("3", "Chill", "D", "Lo-Fi"));
        for (int i = 0; i < 20; i++)
            assertEquals("2", Autoplay.pick(cands, cur, List.of(), new Autoplay.Taste(), new Random(i)).id);
    }

    @Test
    void recentSearchesTipTheBalanceBetweenEqualCandidates() {
        MusicTrack cur = track("c", "Current", "A", "Electronic");
        MusicTrack house = track("h", "Four on the floor", "B", "House");
        MusicTrack techno = track("t", "Warehouse", "C", "Techno");
        Autoplay.Taste taste = Autoplay.Taste.from(List.of("techno", "techno music"), List.of());
        assertTrue(Autoplay.score(techno, cur, List.of(), taste) > Autoplay.score(house, cur, List.of(), taste));
        Autoplay.Taste other = Autoplay.Taste.from(List.of("deep house"), List.of());
        assertTrue(Autoplay.score(house, cur, List.of(), other) > Autoplay.score(techno, cur, List.of(), other));
    }

    @Test
    void favouritesCountAsTaste() {
        Autoplay.Taste t = Autoplay.Taste.from(List.of(), List.of(track("f", "Fav", "X", "Jazz")));
        assertTrue(t.of("jazz") > 0);
        assertEquals(0, t.of("metal"), 0.0001);
    }

    @Test
    void playedSongsAndTheCurrentSongAreNeverPickedAgain() {
        MusicTrack cur = track("c", "Current", "A", "Funk");
        MusicTrack played = track("p", "Played", "B", "Funk");
        MusicTrack fresh = track("n", "New", "C", "Funk");
        assertEquals("n", Autoplay.pick(List.of(cur, played, fresh), cur, List.of(played), new Autoplay.Taste(), new Random(1)).id);
        assertNull(Autoplay.pick(List.of(cur, played), cur, List.of(played), new Autoplay.Taste(), new Random(1)));
        assertNull(Autoplay.pick(List.of(), cur, List.of(), new Autoplay.Taste(), new Random(1)));
    }

    @Test
    void sameArtistInARowAndSnippetsScoreLower() {
        MusicTrack cur = track("c", "Current", "Artist One", "Funk");
        MusicTrack same = track("s", "Same artist", "Artist One", "Funk");
        MusicTrack other = track("o", "Other artist", "Artist Two", "Funk");
        MusicTrack snippet = new MusicTrack("x", "Snippet", "Artist Three", 10_000, "Funk", List.of());
        Autoplay.Taste t = new Autoplay.Taste();
        assertTrue(Autoplay.score(other, cur, List.of(), t) > Autoplay.score(same, cur, List.of(), t));
        assertTrue(Autoplay.score(other, cur, List.of(), t) > Autoplay.score(snippet, cur, List.of(), t));
    }
}
