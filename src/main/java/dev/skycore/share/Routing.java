package dev.skycore.share;

/** Decides where a prepared message of a category is meant to go. Pure, unit-tested. */
public final class Routing {
    private Routing() {}

    /** Custom rules first (first match wins), then the category's own setting, then the built-in default. */
    public static Destination resolve(ShareSettings s, String category, String stat) {
        for (ShareSettings.RouteRule r : s.rules) {
            if (!category.equals(r.category)) continue;
            if (r.stat != null && !r.stat.isEmpty() && !r.stat.equals(stat)) continue;
            Destination d = Destination.parse(r.destination, null);
            if (d != null) return d;
        }
        ShareSettings.Category c = s.categories.get(category);
        Destination d = c == null ? null : Destination.parse(c.destination, null);
        return d != null ? d : ShareSettings.defaultDestination(category);
    }

    public static boolean enabled(ShareSettings s, String category) {
        if (!s.enabled) return false;
        ShareSettings.Category c = s.categories.get(category);
        return c == null || c.enabled;
    }

    public static boolean watermark(ShareSettings s, String category) {
        ShareSettings.Category c = s.categories.get(category);
        return c != null && c.watermark && s.signature != null && !s.signature.isBlank();
    }
}
