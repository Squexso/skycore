package dev.skycore.music;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local genre detection, no network and no model: a keyword table (genre names, sub-genres, well known artists and composers)
 * is matched against search terms, titles, tags and the source's genre label.
 */
public final class Genres {
    private Genres() {}

    /** Genre id to the source's genre label, used for "more like this" lookups. */
    private static final Map<String, String> SOURCE_LABEL = new LinkedHashMap<>();
    /** Genre id to keywords (lower case, matched as whole words). */
    private static final Map<String, String[]> WORDS = new LinkedHashMap<>();

    private static void g(String id, String label, String... words) { SOURCE_LABEL.put(id, label); WORDS.put(id, words); }

    static {
        g("funk", "Funk", "funk", "groove", "p-funk", "boogie", "james brown", "parliament", "funkadelic", "sly stone", "earth wind", "kool & the gang", "prince", "slap bass");
        g("classical", "Classical", "classical", "classic", "symphony", "sonata", "concerto", "requiem", "orchestra", "orchestral", "baroque", "opera", "string quartet", "mozart", "beethoven", "bach", "chopin", "vivaldi", "brahms", "schubert", "tchaikovsky", "debussy", "haydn", "handel", "liszt", "verdi", "wagner", "mahler", "satie", "rachmaninoff", "rachmaninov", "paganini", "schumann", "dvorak");
        g("jazz", "Jazz", "jazz", "bebop", "swing", "saxophone", "miles davis", "coltrane", "ella fitzgerald", "louis armstrong", "duke ellington", "bossa", "bossa nova");
        g("rock", "Rock", "rock", "hard rock", "guitar solo", "led zeppelin", "queen", "ac/dc", "nirvana", "beatles", "rolling stones", "pink floyd");
        g("metal", "Metal", "metal", "thrash", "death metal", "black metal", "metallica", "megadeth", "iron maiden", "djent");
        g("punk", "Punk", "punk", "pop punk", "hardcore", "ramones", "green day");
        g("hiphop", "Hip-Hop/Rap", "hip hop", "hip-hop", "hiphop", "rap", "boom bap", "trap", "drill", "freestyle", "eminem", "kendrick", "drake", "snoop", "tupac", "2pac");
        g("rnb", "R&B/Soul", "rnb", "r&b", "soul", "neo soul", "motown", "aretha", "marvin gaye", "stevie wonder", "otis redding");
        g("electronic", "Electronic", "electronic", "edm", "electro", "synthwave", "synth", "idm", "daft punk", "deadmau5", "skrillex", "dubstep", "future bass");
        g("house", "House", "house", "deep house", "tech house", "progressive house", "future house", "tropical house");
        g("techno", "Techno", "techno", "hardstyle", "acid");
        g("trance", "Trance", "trance", "psytrance");
        g("dnb", "Drum & Bass", "drum and bass", "drum & bass", "dnb", "d&b", "jungle");
        g("lofi", "Lo-Fi", "lofi", "lo-fi", "lo fi", "chillhop", "study beats", "chill beats");
        g("ambient", "Ambient", "ambient", "drone", "soundscape", "meditation", "sleep music", "new age");
        g("pop", "Pop", "pop", "synthpop", "dance pop", "k-pop", "kpop", "j-pop", "taylor swift", "ariana", "dua lipa", "billie eilish");
        g("reggae", "Reggae", "reggae", "dub", "ska", "rocksteady", "bob marley", "dancehall");
        g("blues", "Blues", "blues", "delta blues", "bb king", "muddy waters", "slide guitar");
        g("country", "Country", "country", "bluegrass", "honky tonk", "nashville", "johnny cash", "dolly parton");
        g("folk", "Folk", "folk", "acoustic", "singer songwriter", "indie folk", "bob dylan");
        g("latin", "Latin", "latin", "salsa", "reggaeton", "bachata", "cumbia", "merengue", "tango", "samba", "flamenco");
        g("world", "World", "world", "afrobeat", "afrobeats", "african", "celtic", "balkan", "gamelan", "bollywood", "arabic");
        g("soundtrack", "Soundtrack", "soundtrack", "ost", "film score", "movie score", "game music", "video game", "hans zimmer", "john williams", "cinematic");
        g("vaporwave", "Vaporwave", "vaporwave", "future funk", "mallsoft");
    }

    /** Source genre label to genre id ("Hip-Hop/Rap" to "hiphop"), or null if unknown. */
    public static String fromLabel(String label) {
        if (label == null || label.isBlank()) return null;
        String l = norm(label);
        for (var e : SOURCE_LABEL.entrySet()) if (norm(e.getValue()).equals(l)) return e.getKey();
        Set<String> d = detect(label);
        return d.isEmpty() ? null : d.iterator().next();
    }

    /** The source's genre label for a genre id, used to ask for more of the same. */
    public static String label(String genreId) { return SOURCE_LABEL.get(genreId); }

    /** A search phrase that finds that genre. */
    public static String searchTerm(String genreId) {
        String l = SOURCE_LABEL.get(genreId);
        return l == null ? genreId : l.replace("/Rap", "").replace("/Soul", " soul");
    }

    /** All genres whose keywords appear in any of the texts, in table order. */
    public static Set<String> detect(String... texts) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : texts) {
            if (raw == null || raw.isBlank()) continue;
            String t = " " + norm(raw) + " ";
            for (var e : WORDS.entrySet()) {
                if (out.contains(e.getKey())) continue;
                for (String w : e.getValue()) if (matches(t, w)) { out.add(e.getKey()); break; }
            }
        }
        return out;
    }

    /** Whole-word match; a plain ending ("funk" in "funky", "rock" in "rocks") still counts. */
    private static boolean matches(String padded, String word) {
        int i = padded.indexOf(word);
        while (i >= 0) {
            boolean left = i == 0 || !Character.isLetterOrDigit(padded.charAt(i - 1));
            int end = i + word.length();
            boolean right = end >= padded.length() || !Character.isLetterOrDigit(padded.charAt(end)) || (word.length() >= 4 && plainEnding(padded, end));
            if (left && right) return true;
            i = padded.indexOf(word, i + 1);
        }
        return false;
    }

    private static boolean plainEnding(String p, int from) {
        int sp = p.indexOf(' ', from);
        String rest = sp < 0 ? p.substring(from) : p.substring(from, sp);
        return rest.equals("y") || rest.equals("s") || rest.equals("es") || rest.equals("ers");
    }

    /** Genres of a track: the source label first, then what tags, title and artist say. */
    public static Set<String> of(MusicTrack t) {
        Set<String> out = new LinkedHashSet<>();
        String fromLabel = fromLabel(t.genre);
        if (fromLabel != null) out.add(fromLabel);
        out.addAll(detect(String.join(" ", t.tags), t.title, t.artist));
        return out;
    }

    static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9&/' -]+", " ").replaceAll("\\s+", " ").trim();
    }
}
