package dev.skycore.tracking;

import dev.skycore.core.Skycore;
import dev.skycore.core.Storage;
import dev.skycore.tracking.parse.MenuParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Passive helper: when the player opens the Heart of the Mountain or Heart of the Forest menu, the visible item names
 * and lore lines (game UI text, colour codes stripped) are written to config/skycore/menu-dump-*.txt. It exists so the
 * exact layout of those menus can be checked instead of guessed. Nothing is sent anywhere.
 */
final class MenuDump {
    private MenuDump() {}

    /** The dump file name for a menu title, or null if this menu is not dumped. */
    static String fileFor(String title) {
        if (title == null) return null;
        String t = title.toLowerCase();
        if (t.contains("heart of the mountain")) return "menu-dump-hotm.txt";
        if (t.contains("heart of the forest")) return "menu-dump-hotf.txt";
        if (t.equals("commissions")) return "menu-dump-commissions.txt";
        if (t.contains("shard") || t.contains("hunting")) return "menu-dump-shards.txt";
        return null;
    }

    static void write(String title, List<MenuParser.Item> items) {
        String file = fileFor(title);
        if (file == null || items.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append(" - ").append(items.size()).append(" items with lore\n");
        for (MenuParser.Item it : items) {
            sb.append("\n== ").append(it.name()).append('\n');
            for (String l : it.lore()) sb.append("   ").append(l).append('\n');
        }
        String text = sb.toString();
        Path path = Storage.dir().resolve(file);
        boolean append = file.equals("menu-dump-commissions.txt") || file.equals("menu-dump-shards.txt"); // every change is kept (several shard menus share one file)
        Storage.async(() -> {
            try {
                if (append) {
                    String stamp = "\n######## " + java.time.LocalTime.now().withNano(0) + "\n";
                    if (Files.exists(path) && Files.size(path) > 80_000) Files.deleteIfExists(path);
                    Files.writeString(path, stamp + text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } else Files.writeString(path, text, StandardCharsets.UTF_8);
            } catch (Exception e) {
                Skycore.LOG.warn("SKYCORE menu dump failed", e);
            }
        });
    }
}
