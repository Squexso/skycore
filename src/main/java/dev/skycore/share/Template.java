package dev.skycore.share;

import dev.skycore.data.DataStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message templates with placeholders. Pure (no Minecraft types), unit-tested.
 * <p>
 * Honesty rules: a placeholder without a value (or with an unavailable one, or an unknown placeholder name) removes the
 * whole sentence it stands in, so a message never contains a gap or an invented number. A value that is only an
 * estimate is written with a leading "~".
 */
public final class Template {
    private Template() {}

    public static final List<String> PLACEHOLDERS = List.of("milestone", "category", "previous_level", "current_level",
            "runs_total", "runs_since", "remaining", "next_goal", "player");

    /** A value together with how reliable it is. */
    public record Value(String text, DataStatus status) {
        public static Value of(String text) { return new Value(text, DataStatus.VERIFIED); }

        public static Value tracked(String text) { return new Value(text, DataStatus.EVENT_TRACKED); }

        public static Value estimated(String text) { return new Value(text, DataStatus.ESTIMATED); }

        public boolean usable() { return text != null && !text.isBlank() && status != DataStatus.UNAVAILABLE && status != null; }

        /** Estimates and hand-entered values are marked; observed and event-tracked ones are not. */
        public boolean uncertain() { return status == DataStatus.ESTIMATED || status == DataStatus.MANUAL; }

        public String shown() { return uncertain() ? "~" + text : text; }
    }

    private static final Pattern PH = Pattern.compile("\\{([A-Za-z_]+)\\}");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?…])\\s+");

    /** Placeholder names in the template that SKYCORE does not know. */
    public static List<String> unknown(String tpl) {
        List<String> out = new ArrayList<>();
        if (tpl == null) return out;
        Matcher m = PH.matcher(tpl);
        while (m.find()) if (!PLACEHOLDERS.contains(m.group(1)) && !out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    /** Placeholder names (known or not) used by the template. */
    public static Set<String> used(String tpl) {
        Set<String> out = new LinkedHashSet<>();
        if (tpl == null) return out;
        Matcher m = PH.matcher(tpl);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * Fill in the template. Returns null if nothing usable is left (every sentence needed a missing value).
     */
    public static String render(String tpl, Map<String, Value> vars) {
        if (tpl == null || tpl.isBlank()) return null;
        List<String> kept = new ArrayList<>();
        for (String sentence : SENTENCE_BREAK.split(tpl.trim())) {
            String out = fill(sentence, vars);
            if (out != null && !out.isBlank()) kept.add(out.trim());
        }
        String joined = String.join(" ", kept).trim();
        return joined.isEmpty() ? null : joined;
    }

    private static String fill(String sentence, Map<String, Value> vars) {
        Matcher m = PH.matcher(sentence);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Value v = vars.get(m.group(1));
            if (v == null || !v.usable()) return null; // unknown name, missing or unavailable value: drop the sentence
            m.appendReplacement(sb, Matcher.quoteReplacement(v.shown()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Index of a variant that differs from the previous one whenever there is more than one. */
    public static int pick(int size, int last, Random rnd) {
        if (size <= 1) return 0;
        int i = rnd.nextInt(size - 1);
        return i >= last && last >= 0 ? i + 1 : i;
    }
}
