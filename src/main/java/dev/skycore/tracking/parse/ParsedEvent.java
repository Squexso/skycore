package dev.skycore.tracking.parse;

/** Structured facts extracted from Hypixel messages or menus. Pure data – no Minecraft types. */
public sealed interface ParsedEvent {
    /** Action bar "+12.5 Mining (1,234/3,100,000)". */
    record SkillProgress(String skill, double gain, double current, double need) implements ParsedEvent {}

    /** Action bar "+12.5 Mining (45.3%)" – absolute values unknown. */
    record SkillPercent(String skill, double gain, double percent) implements ParsedEvent {}

    /** End-of-run "+4,213 Catacombs Experience". */
    record CatacombsXp(double xp) implements ParsedEvent {}

    /** End-of-run "+3,100 Mage Experience" (optionally "(Team Bonus)"). */
    record ClassXp(String dungeonClass, double xp, boolean teamBonus) implements ParsedEvent {}

    /** "Team Score: 302 (S+)". */
    record TeamScore(int score, String rank) implements ParsedEvent {}

    /** "SLAYER QUEST COMPLETE!" */
    record SlayerQuestComplete() implements ParsedEvent {}

    /** "» Slay 2,750 Combat XP worth of Endermen." – the slayer type of a freshly started quest. */
    record SlayerRequirement(String mobs, double combatXp) implements ParsedEvent {}

    /** "SLAYER QUEST FAILED!" / "Your Slayer Quest has been cancelled!" – never a completion. */
    record SlayerQuestFailed() implements ParsedEvent {}

    /** Boss kills per tier of one slayer, read from a slayer menu ("enderman:4" → 123). */
    record SlayerKillCounts(String slayerId, java.util.Map<String, Long> counts) implements ParsedEvent {}

    /** Completions per floor read from a dungeon menu ("F6" → 42, "M1" → 7). */
    record FloorCompletionCounts(java.util.Map<String, Long> counts) implements ParsedEvent {}

    /** End-of-run "Master Mode Catacombs - Floor III" → floor "M3" (null if the line was not an exact floor). */
    record DungeonFloorSummary(String floor) implements ParsedEvent {}

    /**
     * A mayor or minister item in a menu the player opened.
     * @param slayerXpPercent bonus read from the perk description, -1 if none
     * @param slayerScope     slayer ids the bonus is limited to (empty = all)
     */
    record MayorInfo(String role, String name, java.util.List<String> perks, double slayerXpPercent, String slayerPerk,
                     java.util.List<String> slayerScope) implements ParsedEvent {}

    /** "Zombie Slayer LVL 7 - Next LVL in 28,750 XP!"  (remaining = -1 when maxed). */
    record SlayerLevel(String type, int level, double remaining) implements ParsedEvent {}

    /** "SKILL LEVEL UP Mining LVII ➜ LVIII". */
    record SkillLevelUp(String skill, int level) implements ParsedEvent {}

    record ProfileSwitch(String name) implements ParsedEvent {}

    record CollectionLevelUp(String name, int level) implements ParsedEvent {}

    record BestiaryMilestone(int level) implements ParsedEvent {}

    /** A single bestiary family tier increase ("BESTIARY Zombie III ➜ IV"). */
    record BestiaryFamilyTier(String family, int tier) implements ParsedEvent {}

    record HotmTier(int tier) implements ParsedEvent {}

    /** Dungeon run started (Mort's opening line). */
    record DungeonStart() implements ParsedEvent {}

    /** "You have selected the Mage Dungeon Class!" */
    record ClassSelected(String dungeonClass) implements ParsedEvent {}

    /** End-of-run summary marker ("> EXTRA STATS <") – raises confidence of the XP lines that follow. */
    record RunSummary() implements ParsedEvent {}

    record SlayerQuestStarted() implements ParsedEvent {}

    record KuudraDown() implements ParsedEvent {}

    /** Tab list skill line "Mining 57: 45.3%" (percent = -1 for MAX). */
    record SkillLevelPercent(String skill, int level, double percent) implements ParsedEvent {}

    /** A level-less reading (powder, motes …) from the tab list or sidebar. */
    record ValueReading(String key, double value) implements ParsedEvent {}

    /**
     * Exact progress read from a SkyBlock menu item (skills menu, collection menu, bestiary, HOTM …).
     *
     * @param name      item name without the trailing level numeral ("Mining", "Wheat", "Catacombs")
     * @param nextLevel the level shown in "Progress to … XXV"
     * @param current   current amount towards the next level (-1 if not shown)
     * @param need      amount required for the next level (-1 if not shown)
     * @param percent   progress percentage (-1 if not shown)
     */
    /** @param menu kind of menu the item was in: "collection", "bestiary", "garden" or "" */
    record MenuProgress(String name, int nextLevel, double current, double need, double percent, boolean collectionMenu, String menu) implements ParsedEvent {
        public MenuProgress(String name, int nextLevel, double current, double need, double percent, boolean collectionMenu) {
            this(name, nextLevel, current, need, percent, collectionMenu, collectionMenu ? "collection" : "");
        }
    }
}
