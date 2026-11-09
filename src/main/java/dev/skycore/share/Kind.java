package dev.skycore.share;

/** The five message templates the editor offers. */
public enum Kind {
    NEARLY, COMPLETED, CHECKPOINT, MAJOR, REPORT;

    public String id() { return name().toLowerCase(); }

    public static Kind parse(String s, Kind def) {
        if (s != null) for (Kind k : values()) if (k.id().equalsIgnoreCase(s)) return k;
        return def;
    }
}
