package dev.skycore.ui;

/** Immutable rectangle in UI units with small layout helpers. */
public record Rect(float x, float y, float w, float h) {
    public float right() { return x + w; }
    public float bottom() { return y + h; }
    public float cx() { return x + w / 2f; }
    public float cy() { return y + h / 2f; }

    public boolean contains(float px, float py) { return px >= x && py >= y && px < x + w && py < y + h; }

    public Rect inset(float d) { return new Rect(x + d, y + d, Math.max(0, w - 2 * d), Math.max(0, h - 2 * d)); }
    public Rect inset(float dx, float dy) { return new Rect(x + dx, y + dy, Math.max(0, w - 2 * dx), Math.max(0, h - 2 * dy)); }
    public Rect expand(float d) { return new Rect(x - d, y - d, w + 2 * d, h + 2 * d); }
    public Rect offset(float dx, float dy) { return new Rect(x + dx, y + dy, w, h); }
    public Rect withH(float nh) { return new Rect(x, y, w, nh); }
    public Rect withW(float nw) { return new Rect(x, y, nw, h); }
    public Rect withY(float ny) { return new Rect(x, ny, w, h); }

    public Rect left(float lw) { return new Rect(x, y, lw, h); }
    public Rect rightPart(float rw) { return new Rect(x + w - rw, y, rw, h); }
    public Rect top(float th) { return new Rect(x, y, w, th); }
    public Rect bottomPart(float bh) { return new Rect(x, y + h - bh, w, bh); }
    public Rect cutTop(float th) { return new Rect(x, y + th, w, Math.max(0, h - th)); }
    public Rect cutLeft(float lw) { return new Rect(x + lw, y, Math.max(0, w - lw), h); }
    public Rect cutRight(float rw) { return new Rect(x, y, Math.max(0, w - rw), h); }

    public Rect intersect(Rect o) {
        float nx = Math.max(x, o.x), ny = Math.max(y, o.y);
        float nr = Math.min(right(), o.right()), nb = Math.min(bottom(), o.bottom());
        return new Rect(nx, ny, Math.max(0, nr - nx), Math.max(0, nb - ny));
    }

    /** Split horizontally into n equal columns with a gap. */
    public Rect[] columns(int n, float gap) {
        Rect[] out = new Rect[n];
        float cw = (w - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) out[i] = new Rect(x + i * (cw + gap), y, cw, h);
        return out;
    }

    public Rect[] rows(int n, float gap) {
        Rect[] out = new Rect[n];
        float rh = (h - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) out[i] = new Rect(x, y + i * (rh + gap), w, rh);
        return out;
    }
}
