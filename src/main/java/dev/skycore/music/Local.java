package dev.skycore.music;

import dev.skycore.core.Storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Songs the player brings along: every audio file inside {@code config/skycore/music/local/} (subfolders allowed) shows up next to
 * the online results. Nothing is uploaded or looked up, so this works without a connection and without any account.
 */
public final class Local {
    private Local() {}

    public static final String PREFIX = "local:";
    private static final Set<String> EXT = Set.of("mp3", "ogg", "flac", "wav", "m4a", "aac", "mp4");
    private static final int MAX_FILES = 5000;

    public static Path dir() { return Storage.dir().resolve("music").resolve("local"); }

    public static boolean isLocal(MusicTrack t) { return t != null && t.id.startsWith(PREFIX); }

    /** Creates the folder on first use, so people can see where to put their files. */
    public static void ensureDir() {
        try { Files.createDirectories(dir()); } catch (IOException ignored) {}
    }

    /** All audio files in the folder, sorted by name. Never throws. */
    public static List<MusicTrack> scan() {
        Path root = dir();
        List<MusicTrack> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.walk(root, 4)) {
            s.filter(Files::isRegularFile).filter(p -> EXT.contains(ext(p))).limit(MAX_FILES).forEach(p -> out.add(track(root, p)));
        } catch (IOException | RuntimeException ignored) {}
        out.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    /** Local files whose title, artist or folder contain every word of the query. */
    public static List<MusicTrack> find(String query) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<MusicTrack> out = new ArrayList<>();
        for (MusicTrack t : scan()) {
            String hay = (t.title + " " + t.artist + " " + t.genre).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!hay.contains(w)) { all = false; break; }
            if (all) out.add(t);
        }
        return out;
    }

    /** The file behind a local song, or null when the id points outside the folder or the file is gone. */
    public static String address(MusicTrack t) {
        if (!isLocal(t)) return null;
        Path root = dir().toAbsolutePath().normalize();
        Path p = root.resolve(t.id.substring(PREFIX.length())).normalize();
        return p.startsWith(root) && Files.isRegularFile(p) ? p.toString() : null;
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static MusicTrack track(Path root, Path p) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        String name = p.getFileName().toString();
        name = name.substring(0, name.lastIndexOf('.'));
        String artist = "";
        int dash = name.indexOf(" - ");
        if (dash > 0 && dash < name.length() - 3) { artist = name.substring(0, dash).trim(); name = name.substring(dash + 3).trim(); }
        Path parent = p.getParent();
        String folder = parent != null && !parent.equals(root) ? parent.getFileName().toString() : "";
        if (artist.isEmpty()) artist = folder.isEmpty() ? "Local file" : folder;
        return new MusicTrack(PREFIX + rel, name, artist, 0, folder, List.of());
    }
}
