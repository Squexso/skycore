package dev.skycore.data;

/** Pure rate maths (no Minecraft, no stored state) so it can be unit-tested. */
public final class Rates {
    private Rates() {}

    /** Below this much recorded playtime a rate is not meaningful and stays unknown. */
    public static final double MIN_PLAY_SECONDS = 300;

    /**
     * XP per hour from recorded XP and recorded playtime.
     *
     * @return {@link Double#NaN} when there is not enough playtime (or invalid input) – never a made-up number
     */
    public static double perHour(double xp, double playSeconds) {
        if (!(playSeconds >= MIN_PLAY_SECONDS) || !(xp >= 0)) return Double.NaN;
        return xp / (playSeconds / 3600.0);
    }
}
