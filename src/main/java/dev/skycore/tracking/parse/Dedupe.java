package dev.skycore.tracking.parse;

import java.util.HashMap;
import java.util.Map;

/** Rejects the same event key seen again within a time window (repeated / echoed server lines). */
public final class Dedupe {
    private final Map<String, Long> seen = new HashMap<>();
    private final long windowMs;

    public Dedupe(long windowMs) { this.windowMs = windowMs; }

    /** @return true if key was already seen within the window (and should be ignored). */
    public boolean duplicate(String key, long now) {
        if (seen.size() > 256) seen.entrySet().removeIf(e -> now - e.getValue() > windowMs);
        Long last = seen.put(key, now);
        return last != null && now - last < windowMs;
    }

    public void clear() { seen.clear(); }
}
