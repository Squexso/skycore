package dev.skycore.share;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the exact request "!skycore" from another player in Party chat and prepares (never sends) a response.
 * Pure, unit-tested.
 */
public final class Community {
    private Community() {}

    public static final String TRIGGER = "!skycore";
    public static final String CATEGORY = "community";

    /** "Party > [MVP+] Name: !skycore" - only the party prefix of the game counts, never All Chat or private messages. */
    private static final Pattern PARTY = Pattern.compile("^Party > (?:\\[[^\\]\\s]{1,16}\\] ?)*([A-Za-z0-9_]{1,16}): (.*)$");

    /** The sender if the line is exactly "!skycore" (any capitalisation) in Party chat, from anyone including the local player, else null. */
    public static String requester(String plainLine, String localName) {
        if (plainLine == null) return null;
        Matcher m = PARTY.matcher(plainLine);
        if (!m.matches()) return null;
        String name = m.group(1);
        if (!TRIGGER.equalsIgnoreCase(m.group(2))) return null;
        return name;
    }

    /** A usable link: http(s), no spaces, a host, at most 200 characters. */
    public static boolean validUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty() || u.length() > 200 || u.chars().anyMatch(Character::isWhitespace)) return false;
        return u.matches("(?i)^https?://[^/\\s.]+(\\.[^/\\s.]+)+(:\\d+)?(/.*)?$");
    }

    /** The feature only works when it is enabled and a valid link was entered by the user. */
    public static boolean active(ShareSettings s) { return s.community.enabled && validUrl(s.community.installUrl); }

    public static boolean cooldownOver(ShareSettings s, ShareState st, long now) {
        return now - st.lastCommunity >= s.community.cooldownSeconds * 1000L;
    }

    /** Response text with the link filled in; the default wording comes from the caller (language dependent). */
    public static String response(ShareSettings s, String defaultText) {
        String tpl = s.community.response == null || s.community.response.isBlank() ? defaultText : s.community.response;
        return tpl.replace("{install_url}", s.community.installUrl.trim());
    }

    /**
     * Handle one chat line. Returns the prepared response, or null if nothing should happen (not a request, own
     * message, feature inactive, no valid link, cooldown running). The cooldown starts only when a response is prepared.
     */
    public static Prepared handle(ShareSettings s, ShareState st, String plainLine, String localName, long now, String defaultText) {
        if (!active(s)) return null;
        String from = requester(plainLine, localName);
        if (from == null) return null;
        if (!cooldownOver(s, st, now)) return null;
        st.lastCommunity = now;
        Prepared p = new Prepared();
        p.category = CATEGORY;
        p.kind = "community";
        p.title = from;
        p.text = response(s, defaultText);
        p.destination = Destination.PARTY.id();
        p.created = now;
        return p;
    }
}
