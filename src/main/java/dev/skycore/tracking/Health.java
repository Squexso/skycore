package dev.skycore.tracking;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Tracking health monitor. Every data source reports successes, failures and whether it is currently
 * available; the status shown to the user is derived from those facts so that <i>inactivity</i>
 * ("nothing to read right now") is never confused with an <i>error</i> ("reading failed").
 * <p>
 * Thread-safe: the storage writer thread reports persistence results while the client thread reports the rest.
 */
public final class Health {
    public enum Source { CHAT, ACTION_BAR, SIDEBAR, TAB, MENU, PERSISTENCE, PROFILE, DUNGEON, SLAYER }

    public enum Status {
        OPERATIONAL, WAITING, UNAVAILABLE, ERROR, DISABLED;
        public String key() { return "health." + name().toLowerCase(); }
    }

    /** An error is "current" for this long unless a later success clears it. */
    public static final long ERROR_WINDOW = 10 * 60_000L;

    public static final class Stats {
        public long lastSuccess, lastActivity, lastError;
        public String lastErrorText;
        public long successes, failures;
        /** false = the source cannot deliver data right now (e.g. no sidebar, no menu open, not on SkyBlock). */
        public boolean available;
        /** Short reason shown when unavailable / waiting, a translation key. */
        public String note;
        public boolean disabled;

        Stats copy() {
            Stats s = new Stats();
            s.lastSuccess = lastSuccess; s.lastActivity = lastActivity; s.lastError = lastError; s.lastErrorText = lastErrorText;
            s.successes = successes; s.failures = failures; s.available = available; s.note = note; s.disabled = disabled;
            return s;
        }
    }

    private static final Map<Source, Stats> STATS = new EnumMap<>(Source.class);
    private static LongSupplier clock = System::currentTimeMillis;

    static { reset(); }

    private Health() {}

    /** Tests only. */
    public static synchronized void setClock(LongSupplier c) { clock = c == null ? System::currentTimeMillis : c; }

    public static synchronized void reset() {
        STATS.clear();
        for (Source s : Source.values()) STATS.put(s, new Stats());
    }

    /** The source produced usable data. */
    public static synchronized void success(Source s) {
        Stats st = STATS.get(s);
        long now = clock.getAsLong();
        st.lastSuccess = st.lastActivity = now;
        st.successes++;
        st.available = true;
        st.note = null;
    }

    /** The source was looked at / something arrived, but there was nothing to extract (not an error). */
    public static synchronized void activity(Source s) {
        STATS.get(s).lastActivity = clock.getAsLong();
    }

    /** Reading or applying failed. The text must not contain chat content. */
    public static synchronized void failure(Source s, String what) {
        Stats st = STATS.get(s);
        st.lastError = clock.getAsLong();
        st.lastErrorText = what == null ? "?" : what.length() > 120 ? what.substring(0, 120) : what;
        st.failures++;
    }

    public static synchronized void available(Source s, boolean available, String noteKey) {
        Stats st = STATS.get(s);
        st.available = available;
        st.note = available ? null : noteKey;
    }

    public static synchronized void disabled(Source s, boolean disabled) {
        STATS.get(s).disabled = disabled;
    }

    public static synchronized Stats stats(Source s) { return STATS.get(s).copy(); }

    public static synchronized Status status(Source s) { return statusOf(STATS.get(s), clock.getAsLong()); }

    /** Pure status rule (unit-tested). */
    public static Status statusOf(Stats st, long now) {
        if (st.disabled) return Status.DISABLED;
        boolean recentError = st.lastError > 0 && now - st.lastError < ERROR_WINDOW && st.lastError >= st.lastSuccess;
        if (recentError) return Status.ERROR;
        if (!st.available) return st.successes > 0 || st.note != null ? Status.UNAVAILABLE : Status.WAITING;
        if (st.successes == 0) return Status.WAITING;
        return Status.OPERATIONAL;
    }

    /** Worst status over all enabled sources – for a compact summary. */
    public static synchronized Status overall() {
        long now = clock.getAsLong();
        boolean anyOk = false, anyWaiting = false;
        for (Stats st : STATS.values()) {
            Status s = statusOf(st, now);
            if (s == Status.ERROR) return Status.ERROR;
            if (s == Status.OPERATIONAL) anyOk = true;
            if (s == Status.WAITING) anyWaiting = true;
        }
        return anyOk ? Status.OPERATIONAL : anyWaiting ? Status.WAITING : Status.UNAVAILABLE;
    }

    /** Numbers of sources in error state. */
    public static synchronized int errorCount() {
        long now = clock.getAsLong();
        int n = 0;
        for (Stats st : STATS.values()) if (statusOf(st, now) == Status.ERROR) n++;
        return n;
    }
}
