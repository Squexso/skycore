package dev.skycore.tracking;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional live debugger (off by default). Keeps a small in-memory ring buffer of recognition decisions:
 * which rule matched, the extracted numbers, the confidence, dedupe and accept/reject reasons and what was
 * persisted. It never keeps raw chat text: unrecognised server lines are reduced to anonymous metadata
 * (length and a short hash), and player-written chat is counted without any content.
 * Nothing is written to disk unless the user exports diagnostics.
 */
public final class DebugLog {
    public enum Filter {
        CHAT, ACTION_BAR, SIDEBAR, TAB, MENU, DUNGEON, SLAYER, STORAGE;
        public String key() { return "debug.filter." + name().toLowerCase(); }
    }

    public static final int CAPACITY = 250;

    public static final class Entry {
        public final long t;
        public final Filter filter;
        /** Event type or "unmatched" / "player_chat". */
        public final String type;
        public final String rule;
        public final double confidence;
        /** Extracted values only (numbers, level names) – never the message. */
        public final Map<String, String> values;
        public final String profile;
        /** "new", "duplicate" or "-". */
        public final String dedupe;
        public final boolean accepted;
        /** Why it was accepted / rejected – a short machine-readable reason. */
        public final String reason;
        /** What was stored ("progress", "history", "-"). */
        public final String persisted;

        public Entry(long t, Filter filter, String type, String rule, double confidence, Map<String, String> values,
                     String profile, String dedupe, boolean accepted, String reason, String persisted) {
            this.t = t; this.filter = filter; this.type = type; this.rule = rule; this.confidence = confidence;
            this.values = values == null ? Map.of() : Map.copyOf(values);
            this.profile = profile; this.dedupe = dedupe; this.accepted = accepted; this.reason = reason; this.persisted = persisted;
        }
    }

    private static final ArrayDeque<Entry> BUFFER = new ArrayDeque<>();

    /** Recent recognition problems – always kept (20 entries, reason codes and rule/activity keys only, no text). */
    public record Problem(long t, String reason, String detail) {}
    private static final ArrayDeque<Problem> PROBLEMS = new ArrayDeque<>();

    public static synchronized void problem(String reason, String detail) {
        PROBLEMS.addLast(new Problem(System.currentTimeMillis(), reason, detail));
        while (PROBLEMS.size() > 20) PROBLEMS.removeFirst();
    }

    /** Newest first. */
    public static synchronized List<Problem> problems() {
        List<Problem> out = new ArrayList<>(PROBLEMS);
        java.util.Collections.reverse(out);
        return out;
    }
    private static volatile boolean enabled;
    private static long unmatched, playerChat;

    private DebugLog() {}

    public static boolean enabled() { return enabled; }

    /** Turning the debugger off also discards what it collected. */
    public static synchronized void setEnabled(boolean on) {
        enabled = on;
        if (!on) clear();
    }

    public static synchronized void clear() {
        BUFFER.clear();
        unmatched = playerChat = 0;
    }

    public static synchronized void add(Entry e) {
        if (!enabled || e == null) return;
        BUFFER.addLast(e);
        while (BUFFER.size() > CAPACITY) BUFFER.removeFirst();
    }

    /** A server line no rule recognised: only its length and an anonymous hash are kept. */
    public static synchronized void unmatched(Filter f, String text, String profile) {
        if (!enabled) return;
        unmatched++;
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("length", String.valueOf(text == null ? 0 : text.length()));
        meta.put("hash", anonymousHash(text));
        add(new Entry(System.currentTimeMillis(), f, "unmatched", null, 0, meta, profile, "-", false, "no_rule", "-"));
    }

    /** Player chat is rejected before parsing; it is only counted. */
    public static synchronized void playerChat() {
        if (!enabled) return;
        playerChat++;
    }

    public static synchronized long unmatchedCount() { return unmatched; }

    public static synchronized long playerChatCount() { return playerChat; }

    /** Newest first, limited, filtered. */
    public static synchronized List<Entry> recent(java.util.Set<Filter> filters, int max) {
        List<Entry> out = new ArrayList<>();
        var it = BUFFER.descendingIterator();
        while (it.hasNext() && out.size() < max) {
            Entry e = it.next();
            if (filters == null || filters.isEmpty() || filters.contains(e.filter)) out.add(e);
        }
        return out;
    }

    public static synchronized int size() { return BUFFER.size(); }

    /** 8 hex chars of a non-reversible digest – enough to see that the same line repeats, nothing more. */
    public static String anonymousHash(String text) {
        if (text == null) return "00000000";
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(("skycore:" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return String.format("%02x%02x%02x%02x", d[0], d[1], d[2], d[3]);
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /**
     * Diagnostic export. Profile names are replaced by stable aliases ("profile-1"), no player names, UUIDs,
     * chat or server addresses are included.
     */
    public static synchronized JsonObject export() {
        JsonObject root = new JsonObject();
        Map<String, String> alias = new LinkedHashMap<>();
        JsonArray arr = new JsonArray();
        for (Entry e : BUFFER) {
            JsonObject o = new JsonObject();
            o.addProperty("t", e.t);
            o.addProperty("filter", e.filter.name().toLowerCase());
            o.addProperty("type", e.type);
            if (e.rule != null) o.addProperty("rule", e.rule);
            o.addProperty("confidence", Math.round(e.confidence * 100) / 100.0);
            JsonObject v = new JsonObject();
            e.values.forEach(v::addProperty);
            o.add("values", v);
            if (e.profile != null) o.addProperty("profile", alias.computeIfAbsent(e.profile, k -> "profile-" + (alias.size() + 1)));
            o.addProperty("dedupe", e.dedupe);
            o.addProperty("accepted", e.accepted);
            o.addProperty("reason", e.reason);
            o.addProperty("persisted", e.persisted);
            arr.add(o);
        }
        root.add("entries", arr);
        root.addProperty("unmatched_lines", unmatched);
        root.addProperty("player_chat_lines_ignored", playerChat);
        return root;
    }
}
