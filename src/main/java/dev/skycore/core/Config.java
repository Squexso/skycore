package dev.skycore.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All user preferences. Serialised with Gson to config/skycore/config.json. */
public class Config {
    public int version = 1;
    public String language = "en";
    /** UI units → physical pixels. 0 = follow Minecraft's GUI scale. */
    public float uiScale = 0;
    public boolean sampleData = false;
    /** Master switch for all passive data collection. */
    public boolean trackingEnabled = true;
    public boolean hudOnlySkyBlock = true;
    public boolean hudEnabled = true;
    /** Bundled Manrope typeface instead of the Minecraft pixel font. */
    public boolean modernFont = true;
    /** Modifier held while scrolling to resize HUD widgets: ctrl, alt or shift. */
    public String hudResizeModifier = "ctrl";
    /** Automatic config/data backups kept in config/skycore/backups (0 = off). */
    public int backupsToKeep = 5;
    public int lastPage = 0;

    public WindowState window = new WindowState();
    public ThemeSettings theme = new ThemeSettings();
    public NotificationSettings notifications = new NotificationSettings();
    /** Milestone sharing (prepared messages, routing, templates). */
    public dev.skycore.share.ShareSettings share = new dev.skycore.share.ShareSettings();
    public Map<String, Boolean> tracking = new LinkedHashMap<>();
    public List<CardState> overview = new ArrayList<>();
    /** Remembered open/closed state of collapsible settings sections. */
    public Map<String, Boolean> expanded = new LinkedHashMap<>();

    public static class WindowState {
        public float x = -1, y = -1, w = 0, h = 0; // UI units; -1/0 = auto (centered, default size)
        public float opacity = 0.94f;
        public float backgroundDim = 0.45f;
        public boolean blur = true;
    }

    public static class ThemeSettings {
        public String preset = "skycore";
        /** The preset a custom theme was derived from (used by colour reset). */
        public String basePreset = "skycore";
        /** Only used when preset == "custom". Keys = Theme.Slot names, values = #AARRGGBB. */
        public Map<String, String> custom = new LinkedHashMap<>();
        public float glow = 0f;
        public boolean borders = true;
        /** Off by default; gradients stay available for custom themes and the other presets. */
        public boolean gradients = false;
        public float radius = 8f;
        public boolean hoverEffects = true;
        public boolean animations = true;
        /** 0 = instant, 1 = default, 2 = slow & pronounced. */
        public float animationIntensity = 1f;
    }

    public static class NotificationSettings {
        public boolean enabled = true;
        public List<Integer> runThresholds = new ArrayList<>(List.of(10, 5, 1));
        public Map<String, Boolean> categories = new LinkedHashMap<>();
        public float duration = 5f;
        public String position = "top_right";
        public boolean sound = true;
        public String soundType = "pling";
        public String animation = "slide";
        public float textScale = 1f;
        /** Custom message per category ("" = default). Placeholders: {stat} {level} {n} {unit} {goal}. */
        public Map<String, String> templates = new LinkedHashMap<>();
        /** "" = use the category colour. */
        public String accent = "";
        /** "" = theme card colour. */
        public String background = "";
        public float backgroundOpacity = 0.96f;
    }

    public static class CardState {
        public String id;
        public boolean visible = true;
        public int span = 1; // columns

        public CardState() {}

        public CardState(String id, int span, boolean visible) {
            this.id = id;
            this.span = span;
            this.visible = visible;
        }
    }

    /** Fill in anything missing (new keys after updates, fresh installs). */
    public void normalize() {
        for (String c : new String[]{"dungeons", "slayer", "skills", "foraging", "bestiary", "mining", "collections"}) tracking.putIfAbsent(c, true);
        for (String c : new String[]{"level_up", "runs_away", "goals", "milestone", "best_run", "inactive"}) notifications.categories.putIfAbsent(c, true);
        if (notifications.templates == null) notifications.templates = new LinkedHashMap<>();
        if (share == null) share = new dev.skycore.share.ShareSettings();
        share.normalize();
        if (notifications.runThresholds == null) notifications.runThresholds = new ArrayList<>(List.of(10, 5, 1));
        // Default hierarchy: Catacombs progression first, the XP-per-hour chart second, then compact cards.
        // Existing users keep their saved order; this only seeds missing cards.
        String[][] defaults = {{"catacombs", "2"}, {"xp_chart", "2"}, {"session", "1"}, {"profile", "1"}, {"categories", "1"},
                {"skills", "1"}, {"slayer", "1"}, {"goals", "1"}, {"mining", "1"}, {"foraging", "1"}, {"bestiary", "1"}, {"activity", "2"}};
        for (String[] d : defaults) {
            boolean found = overview.stream().anyMatch(c -> d[0].equals(c.id));
            if (!found) overview.add(new CardState(d[0], Integer.parseInt(d[1]), true));
        }
        overview.removeIf(c -> c.id == null);
        if (theme.radius < 0) theme.radius = 0;
        if (expanded == null) expanded = new LinkedHashMap<>();
        modernFont = true; // SKYCORE always uses its own typeface
    }

    public boolean tracks(String category) { return tracking.getOrDefault(category, true); }
}
