package dev.skycore.music;

import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Optional YouTube mode. It is off until the player switches it on, and then uses the open-source tool yt-dlp (which keeps up with
 * YouTube's changes) to search and to resolve a stream address. No account and no API key are involved. yt-dlp is downloaded once
 * from its official release page, checked against the published SHA-256 sum, and kept in {@code config/skycore/music/bin}.
 * A copy of yt-dlp that is already on the PATH is used as it is.
 */
public final class YtDlp {
    private YtDlp() {}

    public static final String PREFIX = "yt:";
    private static final String RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final long URL_TTL_MS = 20 * 60_000L;

    private static volatile boolean installing;
    private static volatile Path exe;           // resolved tool, or null
    private static volatile boolean updated;    // self-update tried once this session
    private static final Map<String, long[]> urlAge = new ConcurrentHashMap<>();
    private static final Map<String, String> urls = new ConcurrentHashMap<>();

    public static boolean isYt(MusicTrack t) { return t != null && t.id.startsWith(PREFIX); }
    public static boolean installing() { return installing; }
    /** On, and the tool is known to be there. */
    public static boolean active() { return Library.youtube() && (exe != null || locate() != null); }

    // ------------------------------------------------------------------ switching on
    /**
     * Switches the mode on: finds yt-dlp or downloads it. Runs on its own thread; {@code done} is called on the client thread with
     * null on success, or an i18n key.
     */
    public static void enable(Consumer<String> done) {
        if (installing) return;
        installing = true;
        Thread th = new Thread(() -> {
            String err = null;
            try { ensureTool(); }
            catch (MusicException e) { err = e.key; Skycore.LOG.warn("YouTube mode could not be enabled: {}", e.getMessage()); }
            catch (RuntimeException e) { err = "music.err.ytinstall"; Skycore.LOG.warn("YouTube mode could not be enabled", e); }
            finally { installing = false; }
            final String fe = err;
            Skycore.later(() -> { if (fe == null) Library.setYoutube(true); done.accept(fe); });
        }, "SkyCore-Music-Install");
        th.setDaemon(true);
        th.start();
    }

    private static Path managed() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "yt-dlp.exe" : "yt-dlp";
        return Storage.dir().resolve("music").resolve("bin").resolve(n);
    }

    /** The tool that is already there: the managed copy, else one on the PATH. */
    private static Path locate() {
        Path m = managed();
        if (Files.isRegularFile(m) && works(m.toString())) { exe = m; return m; }
        if (works("yt-dlp")) { exe = Path.of("yt-dlp"); return exe; }
        return null;
    }

    private static boolean works(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    static void ensureTool() throws MusicException {
        if (locate() != null) return;
        String asset = asset();
        if (asset == null) throw new MusicException("music.err.ytinstall", "no yt-dlp build for this system");
        Path target = managed();
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(15)).build();
            String sums = new String(get(http, RELEASE + "SHA2-256SUMS", 30).readAllBytes(), StandardCharsets.UTF_8);
            String want = null;
            for (String line : sums.split("\\R")) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 2 && p[1].replace("*", "").equals(asset)) want = p[0].toLowerCase(Locale.ROOT);
            }
            if (want == null) throw new MusicException("music.err.ytinstall", "no checksum for " + asset);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = get(http, RELEASE + asset, 300)) {
                byte[] buf = new byte[1 << 16];
                try (var out = Files.newOutputStream(tmp)) {
                    for (int n; (n = in.read(buf)) > 0; ) { md.update(buf, 0, n); out.write(buf, 0, n); }
                }
            }
            if (!HexFormat(md.digest()).equals(want)) throw new MusicException("music.err.ytinstall", "checksum mismatch");
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setExecutable(true);
        } catch (MusicException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        } catch (IOException | java.security.GeneralSecurityException | InterruptedException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new MusicException("music.err.ytinstall", e.toString());
        }
        if (!works(target.toString())) throw new MusicException("music.err.ytinstall", "downloaded tool does not start");
        exe = target;
    }

    private static String HexFormat(byte[] b) { return java.util.HexFormat.of().formatHex(b); }

    private static InputStream get(HttpClient http, String url, int timeoutSec) throws IOException, InterruptedException, MusicException {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSec)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new MusicException("music.err.ytinstall", "HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    private static String asset() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) return arm ? null : "yt-dlp.exe";
        if (os.contains("mac")) return "yt-dlp_macos";
        if (os.contains("linux")) return arm ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
        return null;
    }

    // ------------------------------------------------------------------ search and stream address
    /** Songs for a phrase (at most {@code n}); long mixes and clips are dropped. */
    public static List<MusicTrack> search(String query, int n) throws MusicException {
        String q = query.trim().replace('\n', ' ');
        if (q.length() > 100) q = q.substring(0, 100);
        List<String> lines = run(List.of("--flat-playlist", "--print", "%(id)s\t%(title)s\t%(uploader,channel)s\t%(duration)s",
                "ytsearch" + Math.max(1, Math.min(n, 40)) + ":" + q), 25);
        return parse(lines);
    }

    static List<MusicTrack> parse(List<String> lines) {
        List<MusicTrack> out = new ArrayList<>();
        for (String l : lines) {
            String[] p = l.split("\t", -1);
            if (p.length < 4 || !ID.matcher(p[0]).matches()) continue;
            double d;
            try { d = Double.parseDouble(p[3].trim()); } catch (NumberFormatException e) { continue; }
            if (d < 45 || d > 900) continue; // clips and long mixes
            String artist = p[2].trim().replaceAll("(?i)\\s*-\\s*topic$", "");
            if (artist.equals("NA")) artist = "";
            String title = p[1].trim();
            if (title.isEmpty() || title.equals("NA")) continue;
            out.add(new MusicTrack(PREFIX + p[0], title, artist, Math.round(d * 1000), "", List.of()));
        }
        return out;
    }

    /** A stream address for a song (valid for a few hours; kept for 20 minutes so that prefetching and playing share one lookup). */
    public static String streamUrl(String trackId) throws MusicException {
        String vid = trackId.startsWith(PREFIX) ? trackId.substring(PREFIX.length()) : trackId;
        if (!ID.matcher(vid).matches()) throw new MusicException("music.err.yt", "bad id");
        long now = System.currentTimeMillis();
        long[] age = urlAge.get(vid);
        if (age != null && now - age[0] < URL_TTL_MS && urls.containsKey(vid)) return urls.get(vid);
        List<String> out;
        try {
            out = resolve(vid);
        } catch (MusicException first) {
            if ("music.err.ytbot".equals(first.key) || !selfUpdate()) throw first; // YouTube changes often; a newer yt-dlp usually fixes it (not a bot check)
            out = resolve(vid);
        }
        urls.put(vid, out.get(0));
        urlAge.put(vid, new long[]{now});
        if (urls.size() > 60) { urls.clear(); urlAge.clear(); }
        return out.get(0);
    }

    private static List<String> resolve(String vid) throws MusicException {
        List<String> out = run(List.of("--no-playlist", "-f", "bestaudio[ext=webm]/bestaudio/best", "-g", "https://www.youtube.com/watch?v=" + vid), 40);
        if (out.isEmpty() || !out.get(0).startsWith("https://")) throw new MusicException("music.err.yt", "no stream address");
        return out;
    }

    /** Updates a managed yt-dlp once per session. */
    private static synchronized boolean selfUpdate() {
        if (updated || exe == null || !exe.startsWith(Storage.dir())) return false;
        updated = true;
        try { run(List.of("-U"), 90); return true; } catch (MusicException e) { return false; }
    }

    /** The message of the exception that means "YouTube wants a sign-in check from this connection". */
    public static final String BOT = "youtube bot check";

    /** True if yt-dlp's error output says YouTube blocked the request as automated. */
    static boolean botCheck(String stderr) {
        return stderr != null && stderr.toLowerCase(Locale.ROOT).contains("confirm you");
    }

    private static List<String> run(List<String> args, int timeoutSec) throws MusicException {
        Path tool = exe != null ? exe : locate();
        if (tool == null) throw new MusicException("music.err.yt", "yt-dlp not available");
        List<String> cmd = new ArrayList<>();
        cmd.add(tool.toString());
        cmd.addAll(List.of("--no-warnings", "--ignore-config", "--encoding", "utf-8"));
        Path cookies = Storage.dir().resolve("music").resolve("cookies.txt"); // optional, supplied by the player; never created or read by SKYCORE itself
        if (Files.isRegularFile(cookies)) cmd.addAll(List.of("--cookies", cookies.toString()));
        cmd.addAll(args);
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).start();
            p.getOutputStream().close();
            final Process proc = p;
            final StringBuilder err = new StringBuilder();
            Thread errReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    for (String l; (l = r.readLine()) != null; ) synchronized (err) { if (err.length() < 4000) err.append(l).append('\n'); }
                } catch (IOException ignored) {}
            }, "SkyCore-Music-Yt-Err");
            errReader.setDaemon(true);
            errReader.start();
            List<String> lines = new ArrayList<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String l; (l = r.readLine()) != null; ) synchronized (lines) { if (lines.size() < 200) lines.add(l); }
                } catch (IOException ignored) {}
            }, "SkyCore-Music-Yt-Out");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new MusicException("music.err.yt", "timeout"); }
            reader.join(2000);
            if (p.exitValue() != 0) {
                errReader.join(1000);
                String e;
                synchronized (err) { e = err.toString(); }
                if (botCheck(e)) throw new MusicException("music.err.ytbot", BOT);
                throw new MusicException("music.err.yt", "exit " + p.exitValue());
            }
            synchronized (lines) { return new ArrayList<>(lines); }
        } catch (IOException e) {
            throw new MusicException("music.err.yt", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            throw new MusicException("music.err.yt", "interrupted");
        }
    }
}
