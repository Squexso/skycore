package dev.skycore.music;

/** A failure the player can show to the user. {@code key} is an i18n key, {@code detail} goes to the log only. */
public final class MusicException extends Exception {
    public final String key;

    public MusicException(String key, String detail) {
        super(detail);
        this.key = key;
    }
}
