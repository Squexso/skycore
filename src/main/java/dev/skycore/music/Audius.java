package dev.skycore.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Music source: Audius, an open streaming service whose public HTTP interface needs no account and no API key.
 * (YouTube and SoundCloud were tried first: both need a login or poToken, or serve a stream format LavaPlayer cannot decode.)
 * Everything here blocks, so it only runs on the music network thread, never on the client thread.
 */
public final class Audius {
    private Audius() {}

    static final String BASE = "https://api.audius.co";
    static final String APP = "skycore";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Songs matching a search phrase (title, artist, genre, tag). */
    public static List<MusicTrack> search(String query, int limit) throws MusicException {
        return parse(get("/v1/tracks/search?query=" + enc(query) + "&limit=" + limit));
    }

    /** What is currently popular in a genre ("Funk", "Classical", ...), by the source's genre label. */
    public static List<MusicTrack> trending(String genreLabel, int limit) throws MusicException {
        return parse(get("/v1/tracks/trending?genre=" + enc(genreLabel) + "&time=month&limit=" + limit));
    }

    /** The address LavaPlayer streams from. It redirects to a storage node on every request, so there is no link that can expire. */
    public static String streamUrl(MusicTrack t) { return BASE + "/v1/tracks/" + enc(t.id) + "/stream?app_name=" + APP; }

    /** Quick check whether the service can be reached at all (tells "no internet" from "this song is broken"). */
    public static boolean reachable() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE)).timeout(Duration.ofSeconds(4)).GET().build();
            return HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() < 500;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String get(String path) throws MusicException {
        String sep = path.contains("?") ? "&" : "?";
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path + sep + "app_name=" + APP))
                .timeout(Duration.ofSeconds(12)).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) throw new MusicException("music.err.service", "HTTP " + r.statusCode());
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MusicException("music.err.service", "interrupted");
        } catch (HttpTimeoutException e) {
            throw new MusicException("music.err.offline", "timeout");
        } catch (IOException e) { // no route, DNS failure, connection refused, reset
            throw new MusicException("music.err.offline", e.getClass().getSimpleName());
        }
    }

    /** Parses a search / trending answer. Unavailable and deleted songs are dropped. Package-visible for tests. */
    static List<MusicTrack> parse(String json) throws MusicException {
        List<MusicTrack> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("data") || !root.getAsJsonObject().get("data").isJsonArray())
                throw new MusicException("music.err.service", "unexpected answer");
            JsonArray arr = root.getAsJsonObject().getAsJsonArray("data");
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (bool(o, "is_delete") || bool(o, "is_unlisted") || (o.has("is_available") && !bool(o, "is_available"))) continue;
                String id = str(o, "id");
                if (id.isEmpty()) continue;
                String artist = o.has("user") && o.get("user").isJsonObject() ? str(o.getAsJsonObject("user"), "name") : "";
                List<String> tags = new ArrayList<>();
                for (String tg : str(o, "tags").split(",")) if (!tg.isBlank()) tags.add(tg.trim().toLowerCase(java.util.Locale.ROOT));
                long dur = o.has("duration") && o.get("duration").isJsonPrimitive() ? o.get("duration").getAsLong() * 1000L : 0;
                String title = str(o, "title").trim();
                out.add(new MusicTrack(id, title.isEmpty() ? "?" : title, artist, dur, str(o, "genre"), tags));
            }
        } catch (MusicException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MusicException("music.err.service", "parse " + e.getClass().getSimpleName());
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
