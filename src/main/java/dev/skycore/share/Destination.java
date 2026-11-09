package dev.skycore.share;

/**
 * Where a prepared message is meant to go. SKYCORE never sends anything itself: the prefix is only part of the text
 * that lands on the clipboard, so the player pastes it and presses Enter on their own.
 */
public enum Destination {
    PARTY("/pc "), ALL("/ac "), CLIPBOARD(""), MANUAL("");

    public final String prefix;

    Destination(String prefix) { this.prefix = prefix; }

    public String id() { return name().toLowerCase(); }

    public static Destination parse(String s, Destination def) {
        if (s != null) for (Destination d : values()) if (d.id().equalsIgnoreCase(s)) return d;
        return def;
    }

    /** True if the copied text starts with a chat command prefix the player has to confirm. */
    public boolean hasPrefix() { return !prefix.isEmpty(); }
}
