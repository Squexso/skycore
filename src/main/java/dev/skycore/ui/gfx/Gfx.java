package dev.skycore.ui.gfx;

import dev.skycore.core.Skycore;
import dev.skycore.ui.theme.Col;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SKYCORE's drawing layer.
 * <p>
 * Callers work in "UI units" (floats). Internally the pose is scaled so that one draw unit equals one
 * physical framebuffer pixel, which lets us rasterise anti-aliased rounded rectangles, rings and smooth
 * chart curves that stay crisp at every GUI scale, instead of Minecraft's chunky GUI-pixel geometry.
 * {@code s} = physical pixels per UI unit (the user's interface scale).
 */
public final class Gfx {
    public final GuiGraphicsExtractor g;
    public final Font font;
    public final float s;
    private final int guiScale;
    /** Global alpha multiplier (fade animations). */
    public float alpha = 1f;

    private static final Identifier GLOW = id("textures/gui/glow.png");
    private static final int[] ICON_CELLS = {24, 32, 48, 64};
    private static final Identifier[] ICON_ATLAS = {id("textures/gui/icons_24.png"), id("textures/gui/icons_32.png"),
            id("textures/gui/icons_48.png"), id("textures/gui/icons_64.png")};
    private static final int[] LOGO_SIZES = {32, 64, 128, 256};
    private static final Identifier[] LOGOS = {id("textures/gui/logo_32.png"), id("textures/gui/logo_64.png"),
            id("textures/gui/logo_128.png"), id("textures/gui/logo_256.png")};

    static Identifier id(String p) { return Identifier.fromNamespaceAndPath("skycore", p); }

    public Gfx(GuiGraphicsExtractor g, float uiScale) {
        this.g = g;
        this.font = Minecraft.getInstance().font;
        this.s = uiScale;
        this.guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        g.pose().pushMatrix();
        g.pose().scale(1f / guiScale, 1f / guiScale);
        depth = 1;
    }

    /** Matrix pushes made through this wrapper that are still open (used to recover after a failed frame). */
    private int depth;
    private int scissors;

    public int depth() { return depth; }

    public int scissors() { return scissors; }

    /** Pops any matrices / scissors left open by an exception, down to the given levels. */
    public void unwind(int poseDepth, int scissorDepth) {
        while (scissors > scissorDepth) endScissor();
        while (depth > poseDepth) pop();
    }

    public void end() { unwind(0, 0); }

    /** Convert a GUI-scaled mouse coordinate into UI units. */
    public static float toUi(double gui, float uiScale) {
        return (float) (gui * Minecraft.getInstance().getWindow().getGuiScale() / uiScale);
    }

    public int px(float v) { return Math.round(v * s); }

    private int col(int c) { return alpha >= 1f ? c : Col.alpha(c, alpha); }

    // ------------------------------------------------------------------ transforms
    public void push() { g.pose().pushMatrix(); depth++; }
    public void pop() { g.pose().popMatrix(); depth--; }
    public void translate(float x, float y) { g.pose().translate(x * s, y * s); }
    /** Scale around a UI-space pivot. */
    public void scaleAround(float px, float py, float k) {
        g.pose().translate(px * s, py * s);
        g.pose().scale(k, k);
        g.pose().translate(-px * s, -py * s);
    }

    public void scissor(float x, float y, float w, float h) { g.enableScissor(px(x), px(y), px(x + w), px(y + h)); scissors++; }
    public void endScissor() { g.disableScissor(); scissors--; }

    /** Start a new render layer so subsequent elements draw above everything before (popups, tooltips). */
    public void layer() { g.nextStratum(); shapesInLayer = 0; }

    /** Rounded shapes drawn since the last layer began. */
    private int shapesInLayer;

    /**
     * Minecraft's GUI render state checks every new element against the elements already in the current layer (quadratic).
     * A rounded shape is dozens of small fills, so a new layer is started every few shapes. Layers draw in creation order,
     * so nothing changes visually; it only keeps the per-layer element count small.
     */
    private void shapeBudget() {
        if (++shapesInLayer > SHAPES_PER_LAYER) layer();
    }

    private static final int COLUMNS_PER_LAYER = 40;
    private static final int SHAPES_PER_LAYER = Integer.getInteger("skycore.shapesPerLayer", 4);

    // ------------------------------------------------------------------ rectangles
    public void rect(float x, float y, float w, float h, int c) {
        if (Col.a(c) == 0) return;
        g.fill(px(x), px(y), px(x + w), px(y + h), col(c));
    }

    public void gradientV(float x, float y, float w, float h, int top, int bottom) {
        g.fillGradient(px(x), px(y), px(x + w), px(y + h), col(top), col(bottom));
    }

    public void gradientH(float x, float y, float w, float h, int left, int right) {
        int X0 = px(x), X1 = px(x + w), Y0 = px(y), Y1 = px(y + h);
        int n = X1 - X0;
        if (n <= 0) return;
        int step = Math.max(1, n / 96);
        for (int X = X0; X < X1; X += step) {
            float t = (X - X0 + step * 0.5f) / n;
            g.fill(X, Y0, Math.min(X1, X + step), Y1, col(Col.mix(left, right, t)));
        }
    }

    public void roundRect(float x, float y, float w, float h, float r, int c) { roundRectV(x, y, w, h, r, c, c); }

    /** Anti-aliased rounded rectangle with a vertical gradient. */
    public void roundRectV(float x, float y, float w, float h, float r, int top, int bottom) {
        int X0 = px(x), Y0 = px(y), X1 = px(x + w), Y1 = px(y + h);
        int W = X1 - X0, H = Y1 - Y0;
        if (W <= 0 || H <= 0) return;
        float R = Math.min(r * s, Math.min(W, H) / 2f);
        if (R < 0.75f) { g.fillGradient(X0, Y0, X1, Y1, col(top), col(bottom)); return; }
        shapeBudget();
        FillProfile p = fillProfile(R);
        int B = p.size;
        int topRows = Math.min(B, (H + 1) / 2), botRows = Math.min(B, H / 2);
        for (int i = 0; i < topRows; i++) row(p, i, X0, X1, Y0 + i, rowColor(top, bottom, i, H));
        for (int i = 0; i < botRows; i++) row(p, i, X0, X1, Y1 - 1 - i, rowColor(top, bottom, H - 1 - i, H));
        if (H - 2 * B > 0) {
            if (top == bottom) g.fill(X0, Y0 + B, X1, Y1 - B, col(top));
            else g.fillGradient(X0, Y0 + B, X1, Y1 - B, col(rowColor(top, bottom, B, H)), col(rowColor(top, bottom, H - B, H)));
        }
    }

    private static int rowColor(int top, int bottom, int row, int H) {
        return top == bottom ? top : Col.mix(top, bottom, H <= 1 ? 0 : row / (float) (H - 1));
    }

    private void row(FillProfile p, int i, int X0, int X1, int Y, int c) {
        int full = p.full[i];
        if (X1 - full > X0 + full) g.fill(X0 + full, Y, X1 - full, Y + 1, col(c));
        int[] js = p.partialJ[i];
        float[] cs = p.partialC[i];
        for (int k = 0; k < js.length; k++) {
            int pc = col(Col.alpha(c, cs[k]));
            int j = js[k];
            if (X0 + j >= X1 - 1 - j) { if (X0 + j == X1 - 1 - j) g.fill(X0 + j, Y, X0 + j + 1, Y + 1, pc); continue; }
            g.fill(X0 + j, Y, X0 + j + 1, Y + 1, pc);
            g.fill(X1 - 1 - j, Y, X1 - j, Y + 1, pc);
        }
    }

    /** Anti-aliased rounded outline; thickness t in UI units (min 1 physical pixel). */
    public void roundOutline(float x, float y, float w, float h, float r, float t, int c) {
        int X0 = px(x), Y0 = px(y), X1 = px(x + w), Y1 = px(y + h);
        int W = X1 - X0, H = Y1 - Y0;
        if (W <= 1 || H <= 1 || Col.a(c) == 0) return;
        shapeBudget();
        float T = Math.max(1f, t * s);
        float R = Math.min(r * s, Math.min(W, H) / 2f);
        OutlineProfile p = outlineProfile(Math.max(R, 0), T);
        int B = Math.min(p.size, Math.min(W / 2, H / 2));
        int Ti = Math.max(1, Math.round(T));
        int cc = col(c);
        // straight edges
        g.fill(X0 + B, Y0, X1 - B, Y0 + Ti, cc);
        g.fill(X0 + B, Y1 - Ti, X1 - B, Y1, cc);
        g.fill(X0, Y0 + B, X0 + Ti, Y1 - B, cc);
        g.fill(X1 - Ti, Y0 + B, X1, Y1 - B, cc);
        // corners
        for (int i = 0; i < B; i++) {
            int[][] runs = p.runs[i];
            for (int[] run : runs) {
                int j0 = run[0], j1 = Math.min(run[1], B);
                if (j0 >= j1) continue;
                int rc = run[2] >= 1000 ? cc : col(Col.alpha(c, run[2] / 1000f));
                g.fill(X0 + j0, Y0 + i, X0 + j1, Y0 + i + 1, rc);
                g.fill(X1 - j1, Y0 + i, X1 - j0, Y0 + i + 1, rc);
                g.fill(X0 + j0, Y1 - 1 - i, X0 + j1, Y1 - i, rc);
                g.fill(X1 - j1, Y1 - 1 - i, X1 - j0, Y1 - i, rc);
            }
        }
    }

    public void circle(float cx, float cy, float r, int c) { roundRect(cx - r, cy - r, r * 2, r * 2, r, c); }

    public void ringOutline(float cx, float cy, float r, float t, int c) { roundOutline(cx - r, cy - r, r * 2, r * 2, r, t, c); }

    /**
     * Soft outer glow using a 9-slice texture. {@code size} = glow spread in UI units.
     * The texture's falloff band is 40px of each 64px quadrant.
     */
    public void glow(float x, float y, float w, float h, float size, int c) {
        if (Col.a(c) == 0 || size <= 0) return;
        int E = Math.max(2, px(size));
        int Q = Math.round(E * 64f / 40f); // screen size of a texture quadrant
        int X0 = px(x) - E, Y0 = px(y) - E, X1 = px(x + w) + E, Y1 = px(y + h) + E;
        int W = X1 - X0, H = Y1 - Y0;
        int q = Math.min(Q, Math.min(W / 2, H / 2));
        int src = Math.round(64f * q / Q);
        int cc = col(c);
        // corners
        blit(GLOW, X0, Y0, 0, 0, q, q, src, src, 128, cc);
        blit(GLOW, X1 - q, Y0, 128 - src, 0, q, q, src, src, 128, cc);
        blit(GLOW, X0, Y1 - q, 0, 128 - src, q, q, src, src, 128, cc);
        blit(GLOW, X1 - q, Y1 - q, 128 - src, 128 - src, q, q, src, src, 128, cc);
        // edges (stretch the 1px middle line of the texture)
        if (W - 2 * q > 0) {
            blit(GLOW, X0 + q, Y0, 63, 0, W - 2 * q, q, 1, src, 128, cc);
            blit(GLOW, X0 + q, Y1 - q, 63, 128 - src, W - 2 * q, q, 1, src, 128, cc);
        }
        if (H - 2 * q > 0) {
            blit(GLOW, X0, Y0 + q, 0, 63, q, H - 2 * q, src, 1, 128, cc);
            blit(GLOW, X1 - q, Y0 + q, 128 - src, 63, q, H - 2 * q, src, 1, 128, cc);
        }
    }

    private void blit(Identifier tex, int x, int y, float u, float v, int w, int h, int rw, int rh, int texSize, int color) {
        if (w <= 0 || h <= 0) return;
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, rw, rh, texSize, texSize, color);
    }

    // ------------------------------------------------------------------ images
    public void icon(Icon icon, float x, float y, float size, int c) {
        int P = px(size);
        int idx = ICON_CELLS.length - 1;
        for (int i = 0; i < ICON_CELLS.length; i++) if (ICON_CELLS[i] >= P) { idx = i; break; }
        int cell = ICON_CELLS[idx];
        int o = icon.ordinal();
        g.blit(RenderPipelines.GUI_TEXTURED, ICON_ATLAS[idx], px(x), px(y), (o % Icon.COLS) * cell, (o / Icon.COLS) * cell,
                P, P, cell, cell, Icon.COLS * cell, Icon.ROWS * cell, col(c));
    }

    public void logo(float x, float y, float size) {
        int P = px(size);
        int idx = LOGO_SIZES.length - 1;
        for (int i = 0; i < LOGO_SIZES.length; i++) if (LOGO_SIZES[i] >= P) { idx = i; break; }
        int t = LOGO_SIZES[idx];
        g.blit(RenderPipelines.GUI_TEXTURED, LOGOS[idx], px(x), px(y), 0, 0, P, P, t, t, t, t, col(0xFFFFFFFF));
    }

    // ------------------------------------------------------------------ text
    public static final float LINE = 9f;

    /** Glyphs are rasterised at several sizes; the one closest to the on-screen pixel size is used. */
    static final int[] FONT_SIZES = {2, 3, 4, 6};

    /** Manrope weights (bundled, SIL OFL). SKYCORE never falls back to Minecraft's pixel font. */
    public enum Weight {
        REGULAR("regular"), MEDIUM("medium"), SEMIBOLD("semibold"), BOLD("bold");
        final FontDescription[] fonts = new FontDescription[FONT_SIZES.length];
        @SuppressWarnings("unchecked")
        final java.util.Map<String, Component>[] cache = new java.util.Map[FONT_SIZES.length];

        Weight(String id) {
            for (int i = 0; i < FONT_SIZES.length; i++) {
                fonts[i] = new FontDescription.Resource(Gfx.id("ui_" + id + "_" + FONT_SIZES[i]));
                cache[i] = new java.util.HashMap<>();
            }
        }
    }

    /**
     * Smallest rasterisation size at or above {@code pixelsPerUnit} (physical pixels per font pixel): glyphs are
     * only ever shrunk slightly, never by large factors that would drop strokes.
     */
    private static int level(float pixelsPerUnit) {
        for (int i = 0; i < FONT_SIZES.length; i++) if (FONT_SIZES[i] >= pixelsPerUnit - 0.35f) return i;
        return FONT_SIZES.length - 1;
    }

    /** Styled components are cached per weight and size – no per-frame allocation for repeated strings. */
    private static Component styled(String s, Weight w, int level) {
        var map = w.cache[level];
        Component c = map.get(s);
        if (c == null) {
            if (map.size() > 4096) map.clear();
            FontDescription f = w.fonts[level];
            c = Component.literal(s).withStyle(st -> st.withFont(f));
            map.put(s, c);
        }
        return c;
    }

    /** Small text is set one step heavier so thin strokes stay legible (applied to measuring and drawing alike). */
    private static Weight legible(Weight w, float pixelsPerUnit) {
        return w == Weight.REGULAR && pixelsPerUnit < 2.6f ? Weight.MEDIUM : w;
    }

    private static Component styledAt(String s, Weight w, float pixelsPerUnit) {
        return styled(s, legible(w, pixelsPerUnit), level(pixelsPerUnit));
    }

    /** Static measuring (HUD layout) assumes Minecraft's GUI scale as pixel density. */
    private static Component styled(String s, Weight w) {
        return styledAt(s, w, Math.max(1, Minecraft.getInstance().getWindow().getGuiScale()));
    }

    /** Legacy flag: bold = SemiBold (headings), otherwise Regular. */
    private static Weight w(boolean bold) { return bold ? Weight.SEMIBOLD : Weight.REGULAR; }

    /** Text width in units (scale 1) without needing a Gfx instance – used for layout measurement. */
    public static float measure(String str, boolean bold) { return Minecraft.getInstance().font.width(styled(str, w(bold))); }

    public static float measure(String str, Weight weight) { return Minecraft.getInstance().font.width(styled(str, weight)); }

    public float width(String str) { return font.width(styledAt(str, Weight.REGULAR, s)); }
    public float width(String str, float scale) { return font.width(styledAt(str, Weight.REGULAR, s * scale)) * scale; }
    public float widthBold(String str, float scale) { return font.width(styledAt(str, Weight.SEMIBOLD, s * scale)) * scale; }
    public float width(String str, float scale, Weight weight) { return font.width(styledAt(str, weight, s * scale)) * scale; }

    public void text(String str, float x, float y, int c) { text(str, x, y, c, 1f, Weight.REGULAR); }

    public void text(String str, float x, float y, int c, float scale) { text(str, x, y, c, scale, Weight.REGULAR); }

    public void text(String str, float x, float y, int c, float scale, boolean bold) { text(str, x, y, c, scale, w(bold)); }

    public void text(String str, float x, float y, int c, float scale, Weight weight) {
        if (str == null || str.isEmpty()) return;
        int cc = col(c);
        if (Col.a(cc) < 6) return;
        g.pose().pushMatrix();
        g.pose().translate(x * s, y * s);
        g.pose().scale(s * scale, s * scale);
        g.text(font, styledAt(str, weight, s * scale), 0, 0, cc, false);
        g.pose().popMatrix();
    }

    public void textCentered(String str, float cx, float y, int c, float scale, boolean bold) { textCentered(str, cx, y, c, scale, w(bold)); }

    public void textCentered(String str, float cx, float y, int c, float scale, Weight weight) {
        text(str, cx - width(str, scale, weight) / 2f, y, c, scale, weight);
    }

    public void textRight(String str, float rx, float y, int c, float scale, boolean bold) { textRight(str, rx, y, c, scale, w(bold)); }

    public void textRight(String str, float rx, float y, int c, float scale, Weight weight) {
        text(str, rx - width(str, scale, weight), y, c, scale, weight);
    }

    /**
     * Kept for API compatibility. Per-character gradients were removed from the design language
     * (purposeless colour); this now draws solid text in the first colour, Bold weight for numbers.
     */
    public void gradientText(String str, float x, float y, int c1, int c2, float scale, boolean bold) {
        text(str, x, y, c1, scale, bold ? Weight.BOLD : Weight.REGULAR);
    }

    public String ellipsize(String str, float maxW, float scale) {
        if (width(str, scale) <= maxW) return str;
        String e = "…";
        float ew = width(e, scale);
        int n = str.length();
        while (n > 0 && width(str.substring(0, n), scale) + ew > maxW) n--;
        return str.substring(0, n) + e;
    }

    /** Word-wrap into lines no wider than maxW. */
    public List<String> wrap(String str, float maxW, float scale) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : str.split(" ")) {
            String tryLine = line.isEmpty() ? word : line + " " + word;
            if (width(tryLine, scale) > maxW && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tryLine);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    // ------------------------------------------------------------------ charts
    /**
     * Smooth anti-aliased curve through points (UI units). Uses monotone cubic interpolation so the
     * curve never overshoots the data (important: no fake peaks in statistics).
     */
    public void curve(float[] xs, float[] ys, float thickness, int c) {
        if (xs.length < 2) return;
        Spline sp = new Spline(xs, ys, s);
        float T = Math.max(1.2f, thickness * s);
        int X0 = Math.round(xs[0] * s), X1 = Math.round(xs[xs.length - 1] * s);
        int cc = col(c);
        for (int X = X0; X < X1; X++) {
            if ((X - X0) % COLUMNS_PER_LAYER == COLUMNS_PER_LAYER - 1) layer(); // see shapeBudget(): keeps layers small
            float a = sp.at(X), b = sp.at(X + 1);
            float top = Math.min(a, b) - T / 2f, bot = Math.max(a, b) + T / 2f;
            int it = (int) Math.ceil(top), ib = (int) Math.floor(bot);
            if (ib > it) g.fill(X, it, X + 1, ib, cc);
            float ct = it - top, cb = bot - ib;
            if (ct > 0.02f) g.fill(X, it - 1, X + 1, it, col(Col.alpha(c, ct)));
            if (cb > 0.02f) g.fill(X, ib, X + 1, ib + 1, col(Col.alpha(c, cb)));
        }
    }

    /** Gradient area under a curve down to baseY. Colour runs from topC at areaTop to bottomC at baseY. */
    public void area(float[] xs, float[] ys, float areaTop, float baseY, int topC, int bottomC) {
        if (xs.length < 2) return;
        Spline sp = new Spline(xs, ys, s);
        int X0 = Math.round(xs[0] * s), X1 = Math.round(xs[xs.length - 1] * s);
        float T = areaTop * s, Bt = baseY * s;
        int base = Math.round(Bt);
        for (int X = X0; X < X1; X++) {
            if ((X - X0) % COLUMNS_PER_LAYER == COLUMNS_PER_LAYER - 1) layer();
            float y = sp.at(X + 0.5f);
            int iy = Math.round(y);
            if (iy >= base) continue;
            float t = (y - T) / Math.max(1, Bt - T);
            g.fillGradient(X, iy, X + 1, base, col(Col.mix(topC, bottomC, t)), col(bottomC));
        }
    }

    /** Curve y (UI units) at UI x – used for hover markers. */
    public static float curveY(float[] xs, float[] ys, float x) { return new Spline(xs, ys, 1f).at(x); }

    /** Donut / ring chart. fracs sum ≤ 1; drawn clockwise from 12 o'clock with small gaps between segments. */
    public void donut(float cx, float cy, float ro, float ri, float[] fracs, int[] colors, int trackColor) {
        float Ro = ro * s, Ri = ri * s, Cx = cx * s, Cy = cy * s;
        int y0 = (int) Math.floor(Cy - Ro), y1 = (int) Math.ceil(Cy + Ro);
        int x0 = (int) Math.floor(Cx - Ro), x1 = (int) Math.ceil(Cx + Ro);
        float[] ends = new float[fracs.length];
        float acc = 0;
        for (int i = 0; i < fracs.length; i++) { acc += fracs[i]; ends[i] = acc; }
        float gap = fracs.length > 1 ? (1.2f / Math.max(1, Ro)) / (float) (Math.PI * 2) * 2.2f : 0;
        for (int Y = y0; Y < y1; Y++) {
            int runStart = -1, runColor = 0;
            for (int X = x0; X <= x1; X++) {
                int pc = 0;
                if (X < x1) {
                    float dx = X + 0.5f - Cx, dy = Y + 0.5f - Cy;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float cov = clamp01(Ro - d + 0.5f) - clamp01(Ri - d + 0.5f);
                    if (cov > 0.01f) {
                        float ang = (float) (Math.atan2(dx, -dy) / (Math.PI * 2));
                        if (ang < 0) ang += 1;
                        int base = trackColor;
                        float prev = 0;
                        for (int i = 0; i < ends.length; i++) {
                            if (ang < ends[i]) {
                                if (ang - prev > gap / 2 && ends[i] - ang > gap / 2) base = colors[i];
                                break;
                            }
                            prev = ends[i];
                        }
                        pc = cov >= 0.99f ? base : Col.alpha(base, cov);
                    }
                }
                if (pc != runColor || X == x1) {
                    if (runStart >= 0 && Col.a(runColor) > 0) g.fill(runStart, Y, X, Y + 1, col(runColor));
                    runStart = X;
                    runColor = pc;
                }
            }
        }
    }

    private static float clamp01(float v) { return v < 0 ? 0 : Math.min(1, v); }

    // ------------------------------------------------------------------ profiles
    private static final class FillProfile {
        int size;
        int[] full;
        int[][] partialJ;
        float[][] partialC;
    }

    private static final class OutlineProfile {
        int size;
        int[][][] runs; // per row: {j0, j1, coverage*1000}
    }

    private static final Map<Integer, FillProfile> FILL_CACHE = new HashMap<>();
    private static final Map<Long, OutlineProfile> OUTLINE_CACHE = new HashMap<>();

    private static FillProfile fillProfile(float R) {
        int key = Math.round(R * 4);
        return FILL_CACHE.computeIfAbsent(key, k -> {
            float r = k / 4f;
            int B = (int) Math.ceil(r);
            FillProfile p = new FillProfile();
            p.size = B;
            p.full = new int[B];
            p.partialJ = new int[B][];
            p.partialC = new float[B][];
            for (int i = 0; i < B; i++) {
                float dy = Math.max(0, r - (i + 0.5f));
                int full = B;
                List<float[]> parts = new ArrayList<>();
                for (int j = 0; j < B; j++) {
                    float dx = Math.max(0, r - (j + 0.5f));
                    float cov = clamp01(r - (float) Math.sqrt(dx * dx + dy * dy) + 0.5f);
                    if (cov >= 0.995f) { full = j; break; }
                    if (cov > 0.004f) parts.add(new float[]{j, cov});
                }
                p.full[i] = full;
                p.partialJ[i] = new int[parts.size()];
                p.partialC[i] = new float[parts.size()];
                for (int q = 0; q < parts.size(); q++) {
                    p.partialJ[i][q] = (int) parts.get(q)[0];
                    p.partialC[i][q] = parts.get(q)[1];
                }
            }
            return p;
        });
    }

    private static OutlineProfile outlineProfile(float R, float T) {
        long key = ((long) Math.round(R * 4) << 32) | Math.round(T * 4);
        return OUTLINE_CACHE.computeIfAbsent(key, k -> {
            int B = Math.max((int) Math.ceil(R), (int) Math.ceil(T));
            OutlineProfile p = new OutlineProfile();
            p.size = B;
            p.runs = new int[B][][];
            float ri = R - T;
            for (int i = 0; i < B; i++) {
                List<int[]> runs = new ArrayList<>();
                float dy = Math.max(0, R - (i + 0.5f));
                int start = -1, cur = 0;
                for (int j = 0; j <= B; j++) {
                    int cv = 0;
                    if (j < B) {
                        float dx = Math.max(0, R - (j + 0.5f));
                        float d = (float) Math.sqrt(dx * dx + dy * dy);
                        float cov = clamp01(R - d + 0.5f) - (ri > 0 ? clamp01(ri - d + 0.5f) : (d <= R - T + 0.5f && R - T <= 0 ? 0 : 0));
                        // pixels within T of the edge when the radius is smaller than the thickness
                        if (ri <= 0) {
                            float edge = Math.min(i + 0.5f, j + 0.5f);
                            cov = edge < T ? clamp01(R - d + 0.5f) : 0;
                            if (R < 0.5f) cov = edge < T ? 1 : 0;
                        }
                        cv = cov >= 0.99f ? 1000 : Math.round(cov * 1000);
                        if (cv < 5) cv = 0;
                    }
                    if (cv != cur || j == B) {
                        if (start >= 0 && cur > 0) runs.add(new int[]{start, j, cur});
                        start = j;
                        cur = cv;
                    }
                }
                p.runs[i] = runs.toArray(new int[0][]);
            }
            return p;
        });
    }

    /** Fritsch–Carlson monotone cubic interpolation in physical pixel space. */
    private static final class Spline {
        final float[] x, y, m;

        Spline(float[] xs, float[] ys, float scale) {
            int n = xs.length;
            x = new float[n];
            y = new float[n];
            for (int i = 0; i < n; i++) { x[i] = xs[i] * scale; y[i] = ys[i] * scale; }
            float[] d = new float[n - 1];
            for (int i = 0; i < n - 1; i++) d[i] = (y[i + 1] - y[i]) / Math.max(1e-4f, x[i + 1] - x[i]);
            m = new float[n];
            m[0] = d[0];
            m[n - 1] = d[n - 2];
            for (int i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
            for (int i = 0; i < n - 1; i++) {
                if (d[i] == 0) { m[i] = 0; m[i + 1] = 0; continue; }
                float a = m[i] / d[i], b = m[i + 1] / d[i];
                float h = a * a + b * b;
                if (h > 9) {
                    float t = 3 / (float) Math.sqrt(h);
                    m[i] = t * a * d[i];
                    m[i + 1] = t * b * d[i];
                }
            }
        }

        float at(float X) {
            int n = x.length;
            if (X <= x[0]) return y[0];
            if (X >= x[n - 1]) return y[n - 1];
            int i = 0;
            while (i < n - 2 && X > x[i + 1]) i++;
            float h = x[i + 1] - x[i];
            float t = (X - x[i]) / h, t2 = t * t, t3 = t2 * t;
            return (2 * t3 - 3 * t2 + 1) * y[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * y[i + 1] + (t3 - t2) * h * m[i + 1];
        }
    }
}
