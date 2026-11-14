package dev.skycore.music;

import dev.skycore.core.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LibraryAndSourceTest {
    @TempDir Path dir;

    @BeforeEach
    void setUp() {
        Storage.useDir(dir);
        Library.reset();
    }

    @AfterEach
    void waitForWrites() { Storage.flush(); } // the temp folder is deleted right after the test

    private static MusicTrack t(String id) { return new MusicTrack(id, "Song " + id, "Artist", 180_000, "Funk", List.of("funky")); }

    @Test
    void favouritesAreSavedAtOnceAndComeBackAfterARestart() throws Exception {
        assertTrue(Library.toggleFavorite(t("a")));
        Storage.flush();
        Path file = dir.resolve("music").resolve("playlists.json");
        assertTrue(Files.exists(file), "the file and its folder are created on the first change");
        assertTrue(Files.readString(file).contains("\"a\""));
        Library.reset();
        assertTrue(Library.favorites().isEmpty());
        Library.load();
        assertEquals(1, Library.favorites().size());
        assertTrue(Library.isFavorite(t("a")));
        assertFalse(Library.toggleFavorite(t("a")));
        Storage.flush();
        Library.load();
        assertTrue(Library.favorites().isEmpty());
    }

    @Test
    void playlistsKeepTheirSongsAndNamesStayUnique() {
        Library.Playlist a = Library.createPlaylist("Road trip");
        Library.Playlist b = Library.createPlaylist("road trip");
        assertEquals("Road trip", a.name);
        assertEquals("road trip 2", b.name);
        assertTrue(Library.addToPlaylist(a, t("1")));
        assertFalse(Library.addToPlaylist(a, t("1")), "no duplicates");
        assertTrue(Library.addToPlaylist(a, t("2")));
        Library.removeFromPlaylist(a, t("1"));
        Storage.flush();
        Library.load();
        assertEquals(2, Library.playlists().size());
        assertEquals(List.of(t("2")), Library.byName("Road trip").tracks);
        Library.deletePlaylist(Library.byName("road trip 2"));
        assertEquals(1, Library.playlists().size());
    }

    @Test
    void emptyNameGetsADefaultAndLongNamesAreCut() {
        assertEquals("Playlist", Library.createPlaylist("  ").name);
        assertEquals(Library.MAX_NAME, Library.createPlaylist("x".repeat(100)).name.length());
    }

    @Test
    void damagedFileContentIsCleanedNotTrusted() {
        Library.Data d = new Library.Data();
        d.favorites = new ArrayList<>(java.util.Arrays.asList(t("a"), null, new MusicTrack("", "no id", "", 0, "", null), t("a")));
        d.playlists = new ArrayList<>(java.util.Arrays.asList(null, new Library.Playlist()));
        d.playlists.get(1).name = null;
        d.playlists.get(1).tracks = null;
        d.recentSearches = new ArrayList<>(java.util.Arrays.asList("funk", " ", null));
        d.volume = 7f;
        Library.Data c = Library.sanitize(d);
        assertEquals(1, c.favorites.size());
        assertEquals(1, c.playlists.size());
        assertEquals("Playlist", c.playlists.get(0).name);
        assertTrue(c.playlists.get(0).tracks.isEmpty());
        assertEquals(List.of("funk"), c.recentSearches);
        assertEquals(0.6f, c.volume, 0.0001f);
        assertNotNull(Library.sanitize(null));
    }

    @Test
    void lastSearchesAreLimitedAndDeduplicated() {
        for (int i = 0; i < 20; i++) Library.addSearch("q" + i);
        Library.addSearch("q19");
        Library.addSearch("q5");
        assertEquals(Library.MAX_SEARCHES, Library.recentSearches().size());
        assertEquals("q5", Library.recentSearches().get(Library.recentSearches().size() - 1));
        assertEquals(1, Library.recentSearches().stream().filter("q19"::equals).count());
    }

    @Test
    void volumeIsKeptInRange() {
        Library.setVolume(3f, true);
        assertEquals(1f, Library.volume(), 0.0001f);
        Library.setVolume(-1f, false);
        assertEquals(0f, Library.volume(), 0.0001f);
    }

    // ------------------------------------------------------------------ the music source
    private static final String ANSWER = "{\"data\":[" +
            "{\"id\":\"aZ0wE\",\"title\":\"Funk You \",\"genre\":\"Funk\",\"tags\":\"Funky, hiphop,\",\"duration\":268,\"user\":{\"name\":\"Westside Zay\"},\"is_available\":true}," +
            "{\"id\":\"gone\",\"title\":\"Deleted\",\"is_delete\":true}," +
            "{\"id\":\"hidden\",\"title\":\"Unlisted\",\"is_unlisted\":true}," +
            "{\"id\":\"na\",\"title\":\"Unavailable\",\"is_available\":false}," +
            "{\"title\":\"no id\"}," +
            "{\"id\":\"b2\",\"title\":\"\",\"duration\":null,\"user\":null}]}";

    @Test
    void searchAnswerIsParsedAndUnusableSongsAreDropped() throws Exception {
        List<MusicTrack> r = Audius.parse(ANSWER);
        assertEquals(2, r.size());
        MusicTrack a = r.get(0);
        assertEquals("aZ0wE", a.id);
        assertEquals("Funk You", a.title.trim());
        assertEquals("Westside Zay", a.artist);
        assertEquals(268_000, a.durationMs);
        assertEquals("Funk", a.genre);
        assertEquals(List.of("funky", "hiphop"), a.tags);
        assertEquals("?", r.get(1).title);
        assertEquals(0, r.get(1).durationMs);
    }

    @Test
    void brokenAnswersBecomeAShowableError() {
        for (String bad : new String[]{"", "<html>502</html>", "{}", "{\"data\":5}", "[1,2]"}) {
            MusicException e = assertThrows(MusicException.class, () -> Audius.parse(bad), bad);
            assertEquals("music.err.service", e.key);
        }
    }

    @Test
    void streamAddressIsDerivedFromTheIdOnly() {
        String u = Audius.streamUrl(t("a b"));
        assertTrue(u.startsWith("https://api.audius.co/v1/tracks/a+b/stream"));
        assertTrue(u.contains("app_name=skycore"));
    }
}
