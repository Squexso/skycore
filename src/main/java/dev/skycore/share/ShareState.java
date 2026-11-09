package dev.skycore.share;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What was already announced (persisted in share.json, so nothing repeats after a restart) plus the prepared messages.
 * Keys carry the profile, so every SkyBlock profile has its own history.
 */
public class ShareState {
    public static final int MAX_KEYS = 3000, MAX_INBOX = 30;

    public Set<String> fired = new LinkedHashSet<>();
    /** Last seen total of completed dungeon runs per profile. */
    public Map<String, Integer> runsTotal = new LinkedHashMap<>();
    /** Run total at the last milestone of a category ("profile|catacombs") - for {runs_since}. */
    public Map<String, Integer> runsAtMilestone = new LinkedHashMap<>();
    public List<Prepared> inbox = new ArrayList<>();
    public long lastCommunity;

    public boolean has(String key) { return fired.contains(key); }

    /** Remember a key. True if it was new (so the caller announces exactly once). */
    public boolean fire(String key) {
        if (!fired.add(key)) return false;
        while (fired.size() > MAX_KEYS) fired.remove(fired.iterator().next());
        return true;
    }

    public void add(Prepared p) {
        inbox.add(0, p);
        while (inbox.size() > MAX_INBOX) inbox.remove(inbox.size() - 1);
    }

    /** Fix up after loading (Gson leaves missing fields null). */
    public void normalize() {
        if (fired == null) fired = new LinkedHashSet<>();
        if (runsTotal == null) runsTotal = new LinkedHashMap<>();
        if (runsAtMilestone == null) runsAtMilestone = new LinkedHashMap<>();
        if (inbox == null) inbox = new ArrayList<>();
        inbox.removeIf(p -> p == null || p.text == null);
    }
}
