package dev.skycore.tracking;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skycore.core.Lang;
import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;
import dev.skycore.data.Progress;
import dev.skycore.data.StatDef;
import dev.skycore.data.XpTables;
import dev.skycore.notify.Notifier;
import dev.skycore.ui.gfx.Icon;
import dev.skycore.ui.theme.Theme;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Optional: if the player puts their own Hypixel API key into {@code config/skycore/hypixel-key.txt}, the exact Heart of the
 * Mountain XP is read from their profile every few minutes. The key stays on this computer, is only sent to api.hypixel.net,
 * and is never part of the mod. Without the file nothing is requested at all.
 */
public final class HypixelApi {
    private HypixelApi() {}

    public static final String KEY_FILE = "hypixel-key.txt";
    private static final Pattern KEY = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final long EVERY_MS = 3 * 60_000L;

    private static volatile boolean busy;
    private static long nextAt;
    private static boolean toldOk, toldBad;

    /** Every client tick; cheap. {@code ready}: on SkyBlock with the profile known. */
    public static void tick(boolean ready) {
        long now = System.currentTimeMillis();
        if (!ready || busy || now < nextAt) return;
        nextAt = now + EVERY_MS;
        String key = readKey();
        if (key == null) { nextAt = now + 60_000L; return; } // no key file: look again in a minute, request nothing
        final String profile = Progress.profileName();
        final UUID uuid = Minecraft.getInstance().getUser().getProfileId();
        busy = true;
        Thread t = new Thread(() -> {
            try { fetch(key, uuid, profile); }
            catch (Exception e) { Skycore.LOG.warn("Hypixel API request failed: {}", e.toString()); }
            finally { busy = false; }
        }, "SkyCore-Hypixel-Api");
        t.setDaemon(true);
        t.start();
    }

    /** The key from the local file, or null when there is none or it does not look like a key. */
    static String readKey() {
        try {
            Path p = Storage.dir().resolve(KEY_FILE);
            if (!Files.isRegularFile(p)) return null;
            String k = Files.readString(p).trim();
            return KEY.matcher(k).matches() ? k : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void fetch(String key, UUID uuid, String profileName) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + uuid))
                .timeout(Duration.ofSeconds(15)).header("API-Key", key).header("User-Agent", "SkyCore-Mod").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 403) { tell(false, "api.bad_key"); return; }
        if (r.statusCode() == 429) { nextAt = System.currentTimeMillis() + 5 * 60_000L; tell(false, "api.limit"); return; }
        if (r.statusCode() != 200) { Skycore.LOG.warn("Hypixel API answered {}", r.statusCode()); return; }
        Double xp = hotmXp(r.body(), uuid, profileName);
        if (xp == null) { Skycore.LOG.info("Hypixel API: no HOTM data in the answer"); return; }
        final double total = xp;
        Skycore.later(() -> { applyHotm(total); tell(true, "api.connected"); });
    }

    /** HOTM XP of the player in the named profile (else the selected one); null if it cannot be found. */
    static Double hotmXp(String json, UUID uuid, String profileName) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("profiles") || !root.get("profiles").isJsonArray()) return null;
        JsonObject chosen = null, selected = null;
        for (JsonElement e : root.getAsJsonArray("profiles")) {
            JsonObject p = e.getAsJsonObject();
            if (p.has("selected") && p.get("selected").getAsBoolean()) selected = p;
            if (profileName != null && p.has("cute_name") && p.get("cute_name").getAsString().equalsIgnoreCase(profileName)) chosen = p;
        }
        if (chosen == null) chosen = selected;
        if (chosen == null) return null;
        JsonObject members = chosen.getAsJsonObject("members");
        if (members == null) return null;
        JsonElement me = members.get(uuid.toString().replace("-", ""));
        if (me == null) return null;
        JsonObject core = me.getAsJsonObject().getAsJsonObject("mining_core");
        if (core == null || !core.has("experience")) return null;
        return core.get("experience").getAsDouble();
    }

    /** Sets the HOTM stat from the exact total, unless the value we hold is already further (the API lags behind by minutes). */
    static void applyHotm(double total) {
        if (!Progress.isIdentified()) return;
        StatDef def = StatDef.get("hotm");
        var st = Progress.real().stat("hotm");
        if (st.level >= 0 && st.need > 0 && HypixelTracker.hotmTotal(st.level, st.xp) > total + 0.5) return;
        int level = 1;
        double rest = total;
        while (level < def.maxLevel()) {
            long need = XpTables.need(XpTables.HOTM, level);
            if (need <= 0 || rest < need) break;
            rest -= need;
            level++;
        }
        long need = level >= def.maxLevel() ? -1 : XpTables.need(XpTables.HOTM, level);
        HypixelTracker.calibrateHotm(level, rest); // an exact read also teaches the commission boost
        Progress.observe(def, level, rest, need, "api");
    }

    private static void tell(boolean ok, String key) {
        Skycore.later(() -> {
            if (ok ? toldOk : toldBad) return; // once per session
            if (ok) toldOk = true; else toldBad = true;
            Notifier.push(null, Lang.t("api.title"), Lang.t(key), ok ? Icon.CHECK : Icon.WARNING, ok ? Theme.get().primary() : Theme.get().danger());
        });
    }
}
