package dev.skycore.music;

import dev.skycore.core.Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalFilesTest {
    @TempDir Path dir;

    @BeforeEach
    void setUp() throws Exception {
        Storage.useDir(dir);
        Path local = Local.dir();
        Files.createDirectories(local.resolve("Phonk"));
        Files.writeString(local.resolve("VEIGEL - Dovezi.mp3"), "x");
        Files.writeString(local.resolve("Phonk").resolve("Night Drive.ogg"), "x");
        Files.writeString(local.resolve("notes.txt"), "not music");
    }

    @Test
    void onlyAudioFilesAreListedWithArtistAndTitleFromTheName() {
        List<MusicTrack> all = Local.scan();
        assertEquals(2, all.size());
        MusicTrack v = all.stream().filter(t -> t.title.equals("Dovezi")).findFirst().orElseThrow();
        assertEquals("VEIGEL", v.artist);
        assertTrue(Local.isLocal(v));
        MusicTrack n = all.stream().filter(t -> t.title.equals("Night Drive")).findFirst().orElseThrow();
        assertEquals("Phonk", n.artist, "the folder name stands in for a missing artist");
        assertEquals("Phonk", n.genre);
    }

    @Test
    void searchMatchesEveryWordInTitleArtistOrFolder() {
        assertEquals(1, Local.find("veigel").size());
        assertEquals(1, Local.find("phonk drive").size());
        assertTrue(Local.find("nothing here").isEmpty());
    }

    @Test
    void addressStaysInsideTheFolder() {
        MusicTrack ok = Local.scan().get(0);
        assertNotNull(Local.address(ok));
        assertNull(Local.address(new MusicTrack("local:../../secret.mp3", "x", "y", 0, "", null)));
        assertNull(Local.address(new MusicTrack("local:gone.mp3", "x", "y", 0, "", null)));
        assertNull(Local.address(new MusicTrack("abc", "x", "y", 0, "", null)));
    }

    @Test
    void theDecoderOpensARealFile() throws Exception {
        Path wav = Local.dir().resolve("tone.wav");
        int n = 8000; // one second of 8 kHz, 16 bit mono silence
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + n * 2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(n * 2);
        o.write(b.array());
        o.write(new byte[n * 2]);
        Files.write(wav, o.toByteArray());
        com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager m = new com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager();
        m.registerSourceManager(new com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager());
        java.util.concurrent.CompletableFuture<Long> got = new java.util.concurrent.CompletableFuture<>();
        MusicTrack t = Local.scan().stream().filter(x -> x.title.equals("tone")).findFirst().orElseThrow();
        m.loadItem(Local.address(t), new com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler() {
            public void trackLoaded(com.sedmelluq.discord.lavaplayer.track.AudioTrack tr) { got.complete(tr.getDuration()); }
            public void playlistLoaded(com.sedmelluq.discord.lavaplayer.track.AudioPlaylist p) { got.complete(-1L); }
            public void noMatches() { got.complete(-2L); }
            public void loadFailed(com.sedmelluq.discord.lavaplayer.tools.FriendlyException e) { got.completeExceptionally(e); }
        });
        assertEquals(1000L, got.get(10, java.util.concurrent.TimeUnit.SECONDS), 50);
        m.shutdown();
    }
}
