package dev.skycore.hud;

import com.google.gson.reflect.TypeToken;
import dev.skycore.core.Storage;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HUD layouts: multiple named profiles, persisted to hud.json, shareable as a clipboard string. */
public final class HudStore {
    public static final String[] WIDGETS = {"catacombs", "skill", "slayer", "session", "goals"};
    private static final String EXPORT_PREFIX = "SKYCORE-HUD:";

    public static class WidgetState {
        public String id;
        public boolean enabled = true;
        /** Top-left position as a fraction of the screen, so layouts survive resolution changes. */
        public float x, y;
        public float scale = 1f;
        public String background = "#E6101426";
        public String text = "#FFE9EDFA";
        public String accent = "";       // empty = follow theme primary
        public float opacity = 0.85f;
        public float padding = 6f;
        public float spacing = 2f;
        public boolean showTitle = true;
        public boolean showIcon = true;
        public boolean showBar = true;
        /** Text size relative to the widget (independent of the overall widget size). */
        public float textScale = 1f;
        /** Custom title ("" = automatic). */
        public String title = "";
        /** When the widget is shown: always, dungeon, mining. */
        public String visibility = "auto";
        public Map<String, Boolean> lines = new LinkedHashMap<>();

        public WidgetState copy() {
            WidgetState w = Storage.GSON.fromJson(Storage.GSON.toJson(this), WidgetState.class);
            return w;
        }

        public boolean line(String key) { return lines.getOrDefault(key, true); }
    }

    private static class Saved {
        String active = "Default";
        /** Set once the one-time switch of the activity widgets to "auto" visibility was applied. */
        boolean autoVisibility;
        Map<String, List<WidgetState>> profiles = new LinkedHashMap<>();
    }

    private static Saved saved = new Saved();
    private static boolean dirty;

    private HudStore() {}

    public static void load() {
        saved = Storage.load("hud.json", new TypeToken<Saved>() {}.getType(), Saved::new);
        if (saved.profiles == null) saved.profiles = new LinkedHashMap<>();
        if (saved.profiles.isEmpty()) saved.profiles.put("Default", defaults());
        if (!saved.profiles.containsKey(saved.active)) saved.active = saved.profiles.keySet().iterator().next();
        for (List<WidgetState> l : saved.profiles.values()) normalize(l);
        if (!saved.autoVisibility) { // one time: activity boxes only appear while their activity is going on
            for (List<WidgetState> l : saved.profiles.values())
                for (WidgetState w : l) if ("always".equals(w.visibility) && ("catacombs".equals(w.id) || "skill".equals(w.id) || "slayer".equals(w.id))) w.visibility = "auto";
            saved.autoVisibility = true;
            dirty = true;
        }
    }

    private static void normalize(List<WidgetState> list) {
        List<WidgetState> def = defaults();
        for (WidgetState d : def) if (list.stream().noneMatch(w -> d.id.equals(w.id))) list.add(d);
        list.removeIf(w -> w.id == null);
        for (WidgetState w : list) {
            if (w.lines == null) w.lines = new LinkedHashMap<>();
            w.x = Math.max(0, Math.min(0.98f, w.x));
            w.y = Math.max(0, Math.min(0.98f, w.y));
            w.scale = Math.max(0.5f, Math.min(3f, w.scale));
            w.textScale = w.textScale <= 0 ? 1f : Math.max(0.6f, Math.min(1.8f, w.textScale));
            if (w.title == null) w.title = "";
            if (w.visibility == null) w.visibility = "always";
            if (("session".equals(w.id) || "goals".equals(w.id)) && "auto".equals(w.visibility)) w.visibility = "always";
        }
    }

    public static List<WidgetState> defaults() {
        List<WidgetState> l = new ArrayList<>();
        // spaced for GUI scale 4 at 1080p (the tightest common case) so defaults never overlap
        l.add(make("catacombs", 0.006f, 0.22f, true));
        l.add(make("skill", 0.006f, 0.47f, true));
        l.add(make("slayer", 0.006f, 0.72f, false));
        l.add(make("session", 0.80f, 0.02f, true));
        l.add(make("goals", 0.80f, 0.30f, false));
        return l;
    }

    private static WidgetState make(String id, float x, float y, boolean enabled) {
        WidgetState w = new WidgetState();
        w.id = id;
        w.x = x;
        w.y = y;
        w.enabled = enabled;
        return w;
    }

    public static List<WidgetState> widgets() { return saved.profiles.get(saved.active); }

    public static WidgetState widget(String id) {
        for (WidgetState w : widgets()) if (w.id.equals(id)) return w;
        return null;
    }

    public static String activeProfile() { return saved.active; }

    public static List<String> profiles() { return new ArrayList<>(saved.profiles.keySet()); }

    public static void setActive(String name) {
        if (saved.profiles.containsKey(name)) { saved.active = name; dirty = true; }
    }

    public static String newProfile(String base) {
        String name = base;
        int i = 2;
        while (saved.profiles.containsKey(name)) name = base + " " + i++;
        List<WidgetState> copy = new ArrayList<>();
        for (WidgetState w : widgets()) copy.add(w.copy());
        saved.profiles.put(name, copy);
        saved.active = name;
        dirty = true;
        return name;
    }

    /** @return false if the name is empty or already taken. */
    public static boolean renameActive(String name) {
        if (name == null || name.isBlank()) return false;
        name = name.trim();
        if (saved.profiles.containsKey(name)) return name.equals(saved.active);
        Map<String, List<WidgetState>> re = new LinkedHashMap<>();
        for (var e : saved.profiles.entrySet()) re.put(e.getKey().equals(saved.active) ? name : e.getKey(), e.getValue());
        saved.profiles = re;
        saved.active = name;
        dirty = true;
        return true;
    }

    public static Object snapshot() { return saved; }

    public static void restore(com.google.gson.JsonElement json) {
        Saved s = Storage.GSON.fromJson(json, Saved.class);
        if (s == null || s.profiles == null || s.profiles.isEmpty()) return;
        s.profiles.values().removeIf(java.util.Objects::isNull);
        for (List<WidgetState> l : s.profiles.values()) normalize(l);
        if (s.active == null || !s.profiles.containsKey(s.active)) s.active = s.profiles.keySet().iterator().next();
        saved = s;
        dirty = true;
    }

    public static void deleteActive() {
        if (saved.profiles.size() <= 1) return;
        saved.profiles.remove(saved.active);
        saved.active = saved.profiles.keySet().iterator().next();
        dirty = true;
    }

    public static void resetActive() {
        saved.profiles.put(saved.active, defaults());
        dirty = true;
    }

    public static void changed() { dirty = true; }

    public static void save() {
        Storage.save("hud.json", saved);
        dirty = false;
    }

    public static void saveIfDirty() { if (dirty) save(); }

    public static String export() {
        String json = Storage.GSON.toJson(widgets());
        return EXPORT_PREFIX + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** @return the new profile's name, or null if the clipboard did not contain a SKYCORE layout. */
    public static String importLayout(String data) {
        if (data == null) return null;
        data = data.trim();
        if (!data.startsWith(EXPORT_PREFIX)) return null;
        try {
            String json = new String(Base64.getDecoder().decode(data.substring(EXPORT_PREFIX.length())), StandardCharsets.UTF_8);
            Type t = new TypeToken<List<WidgetState>>() {}.getType();
            List<WidgetState> list = Storage.GSON.fromJson(json, t);
            if (list == null || list.isEmpty()) return null;
            normalize(list);
            String name = "Imported";
            int i = 2;
            while (saved.profiles.containsKey(name)) name = "Imported " + i++;
            saved.profiles.put(name, list);
            saved.active = name;
            dirty = true;
            return name;
        } catch (Exception e) {
            return null;
        }
    }
}
