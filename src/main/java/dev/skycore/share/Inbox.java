package dev.skycore.share;

import java.util.function.Consumer;

/**
 * Prepared messages and the clipboard. The clipboard is only written by {@link #copy} (a click or key press of the
 * player) or, if the player turned it on, by {@link #add} with auto-copy. Never silently by default.
 */
public final class Inbox {
    private final ShareState state;
    private final Consumer<String> clipboard;
    private long copiedAt;
    private String copiedId;

    public Inbox(ShareState state, Consumer<String> clipboard) {
        this.state = state;
        this.clipboard = clipboard;
    }

    /** Add a message. Copies it only if auto-copy is enabled. */
    public void add(ShareSettings s, Prepared p) {
        state.add(p);
        if (s.autoCopy) copy(p, p.destination() == Destination.MANUAL ? Destination.CLIPBOARD : p.destination());
    }

    /** Copy a message for a destination. This is the only place that writes the clipboard. */
    public void copy(Prepared p, Destination d) {
        clipboard.accept(p.clipboardText(d));
        copiedAt = System.currentTimeMillis();
        copiedId = p.id;
    }

    /** True for 2 s after the message was copied (visual confirmation). */
    public boolean justCopied(Prepared p) { return p.id.equals(copiedId) && System.currentTimeMillis() - copiedAt < 2000; }

    public void remove(Prepared p) { state.inbox.remove(p); }

    public Prepared latest() { return state.inbox.isEmpty() ? null : state.inbox.get(0); }
}
