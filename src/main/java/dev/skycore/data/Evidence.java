package dev.skycore.data;

/**
 * The three evidence states the interface shows next to a figure. Deliberately coarser than {@link DataStatus}
 * (which stays the detailed record and is still shown in tooltips): only what the game itself confirmed counts as
 * {@link #OBSERVED}. Anything derived, calculated, event-based or entered by hand is {@link #ESTIMATED}, so an
 * estimate can never look verified.
 */
public enum Evidence {
    /** Read directly from the game. Drawn as a solid dot. */
    OBSERVED,
    /** Calculated, derived, partly known or entered by hand. Drawn as a hollow ring. */
    ESTIMATED,
    /** Nothing seen yet. Drawn as a subtle dash. */
    UNAVAILABLE;

    public static Evidence of(DataStatus s) {
        if (s == null) return UNAVAILABLE;
        return switch (s) {
            case VERIFIED -> OBSERVED;
            case UNAVAILABLE -> UNAVAILABLE;
            default -> ESTIMATED; // EVENT_TRACKED, MANUAL, ESTIMATED
        };
    }

    public String key() { return "evidence." + name().toLowerCase(); }
}
