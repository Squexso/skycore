package dev.skycore.music;

import java.util.ArrayList;
import java.util.List;

/** One song as the music source describes it. Plain data, persisted inside playlists.json. */
public final class MusicTrack {
    /** Id on the music source (Audius); the stream address is derived from it, so nothing expires inside the file. */
    public String id = "";
    public String title = "";
    public String artist = "";
    public long durationMs;
    /** The source's own genre label ("Funk", "Hip-Hop/Rap", ...), may be empty. */
    public String genre = "";
    public List<String> tags = new ArrayList<>();

    public MusicTrack() {}

    public MusicTrack(String id, String title, String artist, long durationMs, String genre, List<String> tags) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.durationMs = Math.max(0, durationMs);
        this.genre = genre == null ? "" : genre;
        if (tags != null) this.tags = new ArrayList<>(tags);
    }

    public boolean valid() { return id != null && !id.isBlank(); }

    @Override public boolean equals(Object o) { return o instanceof MusicTrack t && t.id.equals(id); }
    @Override public int hashCode() { return id.hashCode(); }
}
