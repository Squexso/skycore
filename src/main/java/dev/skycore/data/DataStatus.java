package dev.skycore.data;

/**
 * How trustworthy a displayed value is. Shown as a subtle indicator everywhere a value appears,
 * so an estimate is never presented as if it were verified.
 */
public enum DataStatus {
    /** Read directly from the game (menu lore, action bar "cur/need", slayer "next LVL in"). */
    VERIFIED,
    /** A verified or calibrated base plus XP events recorded since then. */
    EVENT_TRACKED,
    /** Entered by the player and not yet confirmed by the game. */
    MANUAL,
    /** Partly known: e.g. level from a level-up message with XP unknown, or XP derived from a rounded percentage. */
    ESTIMATED,
    /** Nothing observed yet. */
    UNAVAILABLE;

    public String key() { return "status." + name().toLowerCase(); }
}
