package dev.skycore.tracking;

import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Passive helper: chat and action-bar lines about reputation, HOTM, powder, commissions or shards (game text, colour codes
 * stripped) are appended to config/skycore/message-dump.txt, so it can be seen where those numbers really show up.
 * Private messages are skipped, other chat is not kept. Nothing is sent anywhere.
 */
final class MessageDump {
    private MessageDump() {}

    private static final List<String> WORDS = List.of("reputation", "hotm", "heart of the", "powder", "commission", "shard", "hunting", "faction", "mithril");
    private static final long CAP = 80_000;

    /** True if this line is worth keeping. */
    static boolean wanted(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase();
        if (t.startsWith("from ") || t.startsWith("to ") || t.startsWith("party >") || t.startsWith("guild >") || t.startsWith("co-op >")) return false;
        for (String w : WORDS) if (t.contains(w)) return true;
        return false;
    }

    static void write(String text, boolean actionBar) {
        if (!wanted(text)) return;
        String line = java.time.LocalTime.now().withNano(0) + (actionBar ? " [action bar] " : " [chat] ") + text + "\n";
        Path path = Storage.dir().resolve("message-dump.txt");
        Storage.async(() -> {
            try {
                if (Files.exists(path) && Files.size(path) > CAP) Files.deleteIfExists(path);
                Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                Skycore.LOG.warn("SKYCORE message dump failed", e);
            }
        });
    }
}
