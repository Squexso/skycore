package dev.skycore.ui.theme;

/** ARGB colour helpers. */
public final class Col {
    private Col() {}

    public static int a(int c) { return c >>> 24; }
    public static int r(int c) { return (c >> 16) & 0xFF; }
    public static int g(int c) { return (c >> 8) & 0xFF; }
    public static int b(int c) { return c & 0xFF; }

    public static int argb(int a, int r, int g, int b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }

    /** Multiply alpha by f. */
    public static int alpha(int c, float f) {
        int na = Math.round(a(c) * Math.max(0, Math.min(1, f)));
        return (na << 24) | (c & 0xFFFFFF);
    }

    public static int withAlpha(int c, int alpha) { return (clamp(alpha) << 24) | (c & 0xFFFFFF); }

    public static int mix(int c1, int c2, float t) {
        t = Math.max(0, Math.min(1, t));
        return argb(Math.round(a(c1) + (a(c2) - a(c1)) * t), Math.round(r(c1) + (r(c2) - r(c1)) * t),
                Math.round(g(c1) + (g(c2) - g(c1)) * t), Math.round(b(c1) + (b(c2) - b(c1)) * t));
    }

    public static float luminance(int c) { return (0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * b(c)) / 255f; }

    /** Readable text colour on top of c. */
    public static int onColor(int c) { return luminance(c) > 0.6f ? 0xFF0B0E1A : 0xFFFFFFFF; }

    public static float[] toHsv(int c) {
        float r = r(c) / 255f, g = g(c) / 255f, b = b(c) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
            if (h < 0) h += 1;
        }
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    public static int fromHsv(float h, float s, float v, int alpha) {
        h = ((h % 1) + 1) % 1;
        float c = v * s, x = c * (1 - Math.abs((h * 6) % 2 - 1)), m = v - c;
        float r, g, b;
        int i = (int) (h * 6);
        switch (i) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return argb(alpha, Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }
}
