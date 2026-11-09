package dev.skycore.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reference list of SkyBlock collections, used only to show which ones SKYCORE has not seen yet.
 * It never creates values: a collection is "known" only after it was read from a menu or a level-up message.
 * Hypixel adds collections over time, so the list may lag behind – anything seen in game is shown regardless.
 */
public final class KnownCollections {
    public static final List<String> ALL = List.of(
            // farming
            "Wheat", "Carrot", "Potato", "Pumpkin", "Melon", "Seeds", "Mushroom", "Cocoa Beans", "Cactus", "Sugar Cane",
            "Feather", "Leather", "Raw Porkchop", "Raw Chicken", "Mutton", "Raw Rabbit", "Nether Wart",
            // mining
            "Cobblestone", "Coal", "Iron Ingot", "Gold Ingot", "Diamond", "Lapis Lazuli", "Emerald", "Redstone", "Nether Quartz",
            "Obsidian", "Glowstone Dust", "Gravel", "Ice", "Netherrack", "Sand", "Red Sand", "End Stone", "Mithril", "Hard Stone",
            "Gemstone", "Mycelium", "Sulphur", "Umber", "Tungsten", "Glacite",
            // combat
            "Rotten Flesh", "Bone", "String", "Spider Eye", "Gunpowder", "Ender Pearl", "Ghast Tear", "Slimeball", "Blaze Rod",
            "Magma Cream", "Chili Pepper",
            // foraging
            "Oak Wood", "Spruce Wood", "Birch Wood", "Dark Oak Wood", "Acacia Wood", "Jungle Wood",
            // fishing
            "Raw Fish", "Raw Salmon", "Clownfish", "Pufferfish", "Prismarine Shard", "Prismarine Crystals", "Clay", "Lily Pad",
            "Ink Sack", "Sponge", "Magmafish",
            // rift
            "Wilted Berberis", "Living Metal Heart", "Caducous Stem", "Agaricus Cap", "Hemovibe", "Half-Eaten Carrot", "Timite");

    private KnownCollections() {}

    /** Reference collections not seen for this profile (case-insensitive). */
    public static List<String> missing(Map<String, Integer> seen) {
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (String k : seen.keySet()) lower.add(k.toLowerCase());
        List<String> out = new ArrayList<>();
        for (String c : ALL) if (!lower.contains(c.toLowerCase())) out.add(c);
        return out;
    }
}
