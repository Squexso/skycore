package dev.skycore.share;

import java.util.List;
import java.util.Map;
import java.util.Random;

/** Turns a template and values into the final message text (variant choice, signature, chat length). Pure, unit-tested. */
public final class MessageBuilder {
    private MessageBuilder() {}

    /** Chat input limit; the command prefix counts against it. */
    public static final int CHAT_LIMIT = 256;

    public record Built(String text, int variant, boolean estimated) {}

    /**
     * @param variants    the templates to choose from (user's or defaults)
     * @param lastVariant index used last time (avoids repeating the same wording), -1 if none
     * @return null if no variant produced a usable message
     */
    public static Built build(ShareSettings s, String category, Destination dest, List<String> variants, int lastVariant,
                              Map<String, Template.Value> vars, Random rnd) {
        if (variants == null || variants.isEmpty()) return null;
        int start = Template.pick(variants.size(), lastVariant, rnd);
        String body = null;
        int used = start;
        for (int n = 0; n < variants.size() && body == null; n++) { // try the others if this one lost every sentence
            used = (start + n) % variants.size();
            body = Template.render(variants.get(used), vars);
        }
        if (body == null) return null;
        boolean est = false;
        for (String name : Template.used(variants.get(used))) {
            Template.Value v = vars.get(name);
            if (v != null && v.usable() && v.uncertain()) est = true;
        }
        int room = CHAT_LIMIT - dest.prefix.length();
        String sig = Routing.watermark(s, category) ? " " + s.signature.trim() : "";
        String text = body + sig;
        if (text.length() > room) text = body; // drop the signature before shortening the message
        if (text.length() > room) text = body.substring(0, Math.max(0, room - 1)).stripTrailing() + "…";
        return new Built(text, used, est);
    }
}
