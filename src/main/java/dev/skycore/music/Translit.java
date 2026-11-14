package dev.skycore.music;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Search helpers for names that are not written in Latin letters. The music source matches words literally, and the same song is
 * often listed under its Cyrillic or its Latin spelling, so a Cyrillic search is also run in Latin letters, and a search without
 * a hit falls back to its single words.
 */
public final class Translit {
    private Translit() {}

    private static final String[] CYR = {"а", "б", "в", "г", "д", "е", "ё", "ж", "з", "и", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь", "э", "ю", "я", "і", "ї", "є", "ґ"};
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "yo", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya", "i", "yi", "ye", "g"};

    public static boolean hasCyrillic(String s) {
        return s.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC);
    }

    /** Cyrillic text in Latin letters ("Группа крови" to "Gruppa krovi"); other characters stay as they are. */
    public static String toLatin(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char lower = Character.toLowerCase(c);
            int idx = -1;
            for (int k = 0; k < CYR.length; k++) if (CYR[k].charAt(0) == lower) { idx = k; break; }
            if (idx < 0) { out.append(c); continue; }
            String l = LAT[idx];
            out.append(Character.isUpperCase(c) && !l.isEmpty() ? Character.toUpperCase(l.charAt(0)) + l.substring(1) : l);
        }
        return out.toString();
    }

    /** The phrases to search for, the typed one first. */
    public static List<String> variants(String query) {
        Set<String> out = new LinkedHashSet<>();
        String q = query.trim();
        out.add(q);
        if (hasCyrillic(q)) out.add(toLatin(q));
        return new ArrayList<>(out);
    }

    /** Single words (and their Latin spelling) to try when the whole phrase finds too little; longest first, at most three. */
    public static List<String> words(String query) {
        List<String> ws = new ArrayList<>();
        for (String w : query.trim().split("\\s+")) if (w.length() >= 3) ws.add(w);
        if (ws.size() < 2) return List.of(); // a single word was already searched as it is
        ws.sort((a, b) -> b.length() - a.length());
        Set<String> out = new LinkedHashSet<>();
        for (String w : ws.subList(0, Math.min(3, ws.size()))) {
            out.add(w.toLowerCase(Locale.ROOT));
            if (hasCyrillic(w)) out.add(toLatin(w).toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(out);
    }
}
