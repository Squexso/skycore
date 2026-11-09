package dev.skycore.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Centralised localisation. SKYCORE's language is independent of Minecraft's language setting,
 * so strings are loaded from assets/skycore/i18n/&lt;code&gt;.json instead of the vanilla language system.
 * Add a language by dropping a new JSON file and adding its code to {@link #LANGUAGES}.
 */
public final class Lang {
    public static final String[] LANGUAGES = {"en", "de"};
    private static final Map<String, String> fallback = new HashMap<>();
    private static final Map<String, String> active = new HashMap<>();
    private static String code = "en";
    private static Locale locale = Locale.ENGLISH;
    private static NumberFormat intFmt, decFmt, dec2Fmt;

    private Lang() {}

    public static void load(String languageCode) {
        fallback.clear();
        fallback.putAll(read("en"));
        active.clear();
        code = has(languageCode) ? languageCode : "en";
        active.putAll(code.equals("en") ? fallback : read(code));
        locale = Locale.forLanguageTag(code);
        intFmt = NumberFormat.getIntegerInstance(locale);
        decFmt = NumberFormat.getNumberInstance(locale);
        decFmt.setMaximumFractionDigits(1);
        dec2Fmt = NumberFormat.getNumberInstance(locale);
        dec2Fmt.setMaximumFractionDigits(2);
        decFmt.setMinimumFractionDigits(0);
    }

    private static boolean has(String c) {
        for (String l : LANGUAGES) if (l.equals(c)) return true;
        return false;
    }

    private static Map<String, String> read(String c) {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = Lang.class.getResourceAsStream("/assets/skycore/i18n/" + c + ".json")) {
            if (in == null) return out;
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        } catch (Exception e) {
            Skycore.LOG.error("Failed to load language {}", c, e);
        }
        return out;
    }

    public static String code() { return code; }

    /** Translate a key; {0}, {1} … are replaced by args. Falls back to English, then to the key itself. */
    public static String t(String key, Object... args) {
        String s = active.get(key);
        if (s == null) s = fallback.get(key);
        if (s == null) return key;
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    public static boolean exists(String key) { return active.containsKey(key) || fallback.containsKey(key); }

    public static String num(double v) { return intFmt.format(Math.round(v)); }

    public static String dec(double v) { return decFmt.format(v); }

    /** Up to two decimals (exact rewards such as 6.25 XP). */
    public static String dec2(double v) { return dec2Fmt.format(v); }

    /** Compact number: 1.2K, 3.4M (locale-aware decimal separator). */
    public static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000_000) return dec(v / 1_000_000_000d) + t("unit.billion");
        if (a >= 1_000_000) return dec(v / 1_000_000d) + t("unit.million");
        if (a >= 10_000) return dec(v / 1_000d) + t("unit.thousand");
        return num(v);
    }

    public static String percent(double frac) { return Math.round(frac * 100) + "%"; }

    public static String duration(long millis) {
        long m = Math.max(0, millis) / 60000;
        long h = m / 60;
        m %= 60;
        if (h >= 24) return t("time.dh", h / 24, h % 24);
        if (h > 0) return t("time.hm", h, m);
        return t("time.m", m);
    }

    public static Locale locale() { return locale; }
}
