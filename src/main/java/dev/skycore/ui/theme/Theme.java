package dev.skycore.ui.theme;

import dev.skycore.core.Config;
import dev.skycore.core.Skycore;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolved colour palette + effect settings. One instance is shared by every page and the HUD;
 * it is rebuilt whenever the user touches the Appearance page, so changes preview live.
 */
public final class Theme {
    public enum Slot {
        PRIMARY, SECONDARY, BACKGROUND, BACKGROUND_ALT, CARD, TEXT, TEXT_DIM, BORDER,
        PROGRESS, PROGRESS_END, CHART_1, CHART_2, CHART_3, CHART_4, SUCCESS, WARNING, DANGER
    }

    /** Slots the user can edit on the Appearance page (others are derived). */
    public static final Slot[] EDITABLE = {Slot.PRIMARY, Slot.SECONDARY, Slot.BACKGROUND, Slot.BACKGROUND_ALT, Slot.CARD,
            Slot.TEXT, Slot.TEXT_DIM, Slot.BORDER, Slot.PROGRESS, Slot.PROGRESS_END, Slot.CHART_1, Slot.CHART_2, Slot.CHART_3, Slot.CHART_4};

    public static final String[] PRESETS = {"skycore", "dark", "midnight", "purple_neon", "cyan_neon", "blue", "minimal_black", "custom"};

    private static final Map<String, EnumMap<Slot, Integer>> PRESET_COLORS = new LinkedHashMap<>();

    static {
        //                 primary    secondary  bg         bgAlt      card       text       textDim    border     prog       progEnd    c1         c2         c3         c4         ok         warn       bad
        // Default. Neutral graphite surfaces; the primary (interaction/selection) is a warm off-white and the only hue is
        // gold, reserved for XP progress and the XP-rate chart (PROGRESS / CHART_1). Other charts use neutral steps.
        preset("skycore", 0xFFE8E4DA, 0xFF8C8F98, 0xFF101113, 0xFF131416, 0xFF17181B, 0xFFEDEBE6, 0xFF9B9DA4, 0xFF2B2D32, 0xFFD9A63F, 0xFFD9A63F, 0xFFD9A63F, 0xFFA5A8B0, 0xFF73767E, 0xFF50535A, 0xFF7DB892, 0xFFCF8F55, 0xFFD6685E);
        preset("dark", 0xFF3BC9F5, 0xFF8B5CF6, 0xFF101218, 0xFF15171F, 0xFF1A1D27, 0xFFECEEF5, 0xFF8C92A6, 0xFF2A2E3B, 0xFF3BC9F5, 0xFF4F7BFF, 0xFF3BC9F5, 0xFF8B5CF6, 0xFF4F7BFF, 0xFF34D399, 0xFF34D399, 0xFFF5B13B, 0xFFF2557A);
        preset("midnight", 0xFF3BC9F5, 0xFF9B6BFF, 0xFF0A0D1C, 0xFF140F2B, 0xFF141A30, 0xFFE9EDFA, 0xFF8D96B8, 0xFF283152, 0xFF3BC9F5, 0xFF7C5CFF, 0xFF3BC9F5, 0xFF9B6BFF, 0xFF4F7BFF, 0xFF3DF2A4, 0xFF3DDC97, 0xFFF5B13B, 0xFFF2557A);
        preset("purple_neon", 0xFFC86BFF, 0xFF6B7BFF, 0xFF0E0820, 0xFF1C0B33, 0xFF1A1035, 0xFFF3ECFF, 0xFF9C8DC0, 0xFF3A2860, 0xFFC86BFF, 0xFF6B7BFF, 0xFFC86BFF, 0xFF6B7BFF, 0xFFFF6BD5, 0xFF3BC9F5, 0xFF3DDC97, 0xFFF5B13B, 0xFFFF5C8A);
        preset("cyan_neon", 0xFF22E4FF, 0xFF3DF2A4, 0xFF061318, 0xFF071C24, 0xFF0C2129, 0xFFE6FBFF, 0xFF7FA6B0, 0xFF16404A, 0xFF22E4FF, 0xFF3DF2A4, 0xFF22E4FF, 0xFF3DF2A4, 0xFF4F9BFF, 0xFFB37BFF, 0xFF3DF2A4, 0xFFF5C13B, 0xFFFF5C7A);
        preset("blue", 0xFF4F8BFF, 0xFF38BDF8, 0xFF0A1022, 0xFF0D1630, 0xFF121C38, 0xFFEAF0FF, 0xFF8796BF, 0xFF24325A, 0xFF4F8BFF, 0xFF38BDF8, 0xFF4F8BFF, 0xFF38BDF8, 0xFF818CF8, 0xFF34D399, 0xFF34D399, 0xFFF5B13B, 0xFFF2557A);
        preset("minimal_black", 0xFF3BB8F0, 0xFF9AA4B8, 0xFF000000, 0xFF050505, 0xFF1E1E1E, 0xFFFFFFFF, 0xFF9A9A9A, 0xFF2C2C2C, 0xFF3BB8F0, 0xFF3BB8F0, 0xFF3BB8F0, 0xFF34D399, 0xFFF5B13B, 0xFFB98BFF, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444);
    }

    private static void preset(String id, int... c) {
        EnumMap<Slot, Integer> m = new EnumMap<>(Slot.class);
        Slot[] s = Slot.values();
        for (int i = 0; i < s.length; i++) m.put(s[i], c[i]);
        PRESET_COLORS.put(id, m);
    }

    private final EnumMap<Slot, Integer> colors;
    public final float glow, radius;
    public final boolean borders, gradients, hover, animations;
    /**
     * True for the default "skycore" design (and custom themes derived from it): the accent is kept for XP progress
     * only, everything else in lists and charts stays neutral. Other presets keep their original, more colourful use.
     */
    public final boolean restrained;
    public final float opacity;
    /** 0 = instant … 2 = slow. */
    public final float animIntensity;

    private Theme(EnumMap<Slot, Integer> colors, Config.ThemeSettings ts, float opacity) {
        this.colors = colors;
        this.glow = ts.glow;
        this.radius = ts.radius;
        this.borders = ts.borders;
        this.gradients = ts.gradients;
        this.hover = ts.hoverEffects;
        this.animations = ts.animations;
        this.restrained = "skycore".equals(ts.preset) || ("custom".equals(ts.preset) && "skycore".equals(ts.basePreset));
        this.opacity = opacity;
        this.animIntensity = ts.animations ? Math.max(0, ts.animationIntensity) : 0;
    }

    private static Theme current;

    public static Theme get() {
        if (current == null) rebuild();
        return current;
    }

    public static void rebuild() {
        Config cfg = Skycore.config();
        current = new Theme(resolveColors(cfg.theme), cfg.theme, cfg.window.opacity);
    }

    public static EnumMap<Slot, Integer> presetColors(String id) {
        EnumMap<Slot, Integer> p = PRESET_COLORS.get(id);
        return new EnumMap<>(p == null ? PRESET_COLORS.get("midnight") : p);
    }

    public static EnumMap<Slot, Integer> resolveColors(Config.ThemeSettings ts) {
        if (!"custom".equals(ts.preset)) return presetColors(ts.preset);
        EnumMap<Slot, Integer> m = presetColors("midnight");
        for (var e : ts.custom.entrySet()) {
            try { m.put(Slot.valueOf(e.getKey()), parse(e.getValue())); } catch (Exception ignored) {}
        }
        return m;
    }

    /** Switch to the custom preset (seeded from the current palette) and set one colour. */
    public static void setCustomColor(Slot slot, int argb) {
        Config.ThemeSettings ts = Skycore.config().theme;
        if (!"custom".equals(ts.preset)) {
            EnumMap<Slot, Integer> base = resolveColors(ts);
            ts.basePreset = ts.preset;
            ts.custom.clear();
            for (var e : base.entrySet()) ts.custom.put(e.getKey().name(), hex(e.getValue()));
            ts.preset = "custom";
        }
        ts.custom.put(slot.name(), hex(argb));
        Skycore.markDirty();
        rebuild();
    }

    public static String hex(int argb) { return String.format("#%08X", argb); }

    public static int parse(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        long v = Long.parseLong(s, 16);
        if (s.length() <= 6) v |= 0xFF000000L;
        return (int) v;
    }

    public int c(Slot s) { return colors.get(s); }

    public int primary() { return c(Slot.PRIMARY); }
    public int secondary() { return c(Slot.SECONDARY); }
    public int bg() { return c(Slot.BACKGROUND); }
    public int bgAlt() { return c(Slot.BACKGROUND_ALT); }
    public int card() { return c(Slot.CARD); }
    public int text() { return c(Slot.TEXT); }
    public int textDim() { return c(Slot.TEXT_DIM); }
    public int textMute() { return Col.mix(c(Slot.TEXT_DIM), c(Slot.CARD), 0.35f); }
    public int border() { return c(Slot.BORDER); }
    public int success() { return c(Slot.SUCCESS); }
    public int warning() { return c(Slot.WARNING); }
    public int danger() { return c(Slot.DANGER); }
    public int progress() { return c(Slot.PROGRESS); }
    public int progressEnd() { return gradients ? c(Slot.PROGRESS_END) : c(Slot.PROGRESS); }
    /** The XP highlight colour: Catacombs level, the main XP bar and the XP-rate line. */
    public int xp() { return c(Slot.PROGRESS); }
    /** Fill for secondary progress bars in lists: neutral in the restrained design, the progress colour otherwise. */
    public int listBar() { return restrained ? Col.mix(textDim(), text(), 0.2f) : progress(); }
    public int chart(int i) {
        return switch (Math.floorMod(i, 4)) {
            case 0 -> c(Slot.CHART_1);
            case 1 -> c(Slot.CHART_2);
            case 2 -> c(Slot.CHART_3);
            default -> c(Slot.CHART_4);
        };
    }

    /** Raised surface (inputs, inner tiles). */
    public int surface() { return Col.mix(card(), text(), 0.045f); }
    public int surfaceHover() { return Col.mix(card(), text(), 0.09f); }
    public int track() { return Col.mix(card(), text(), 0.08f); }
    public int cardHover() { return Col.mix(card(), primary(), 0.06f); }
    public int separator() { return Col.alpha(border(), 0.7f); }
}
