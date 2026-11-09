import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates SKYCORE's GUI textures (logo, icon atlases, soft glow) with Java2D anti-aliasing.
 * Run once: java tools/AssetGen.java src/main/resources/assets/skycore
 *
 * Icons are drawn white on a 24-unit grid and tinted at runtime.
 * Icon order MUST match dev.skycore.ui.gfx.Icon.
 */
public class AssetGen {
    static final int COLS = 8, ROWS = 6;

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : "src/main/resources/assets/skycore");
        File gui = new File(root, "textures/gui");
        gui.mkdirs();
        for (int cell : new int[]{24, 32, 48, 64}) {
            ImageIO.write(iconAtlas(cell), "png", new File(gui, "icons_" + cell + ".png"));
        }
        for (int s : new int[]{32, 64, 128, 256}) {
            ImageIO.write(logo(s), "png", new File(gui, "logo_" + s + ".png"));
        }
        ImageIO.write(glow(128, 40), "png", new File(gui, "glow.png"));
        ImageIO.write(logo(128), "png", new File(root, "icon.png"));
        System.out.println("assets written to " + root.getAbsolutePath());
    }

    // ------------------------------------------------------------------ glow 9-slice
    static BufferedImage glow(int size, int blur) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double c = size / 2.0;
        double inner = c - blur; // flat region half-size
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            double dx = Math.max(0, Math.abs(x + 0.5 - c) - inner);
            double dy = Math.max(0, Math.abs(y + 0.5 - c) - inner);
            double d = Math.sqrt(dx * dx + dy * dy) / blur; // 0..1+
            double a = d >= 1 ? 0 : Math.pow(1 - d, 2.2);
            img.setRGB(x, y, ((int) Math.round(a * 255) << 24) | 0xFFFFFF);
        }
        return img;
    }

    // ------------------------------------------------------------------ logo
    static BufferedImage logo(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        aa(g);
        double c = s / 2.0;
        boolean small = s <= 32;
        Color cyan = new Color(0x3BD8FF), blue = new Color(0x4F7BFF), purple = new Color(0x9B6BFF), green = new Color(0x3DF2A4);

        // soft core glow
        RadialGradientPaint halo = new RadialGradientPaint(new Point2D.Double(c, c), (float) (s * 0.48),
                new float[]{0f, 0.25f, 0.6f, 1f},
                new Color[]{new Color(0x9AF0FF), new Color(59, 216, 255, 170), new Color(79, 123, 255, 40), new Color(79, 123, 255, 0)});
        g.setPaint(halo);
        g.fill(new Ellipse2D.Double(0, 0, s, s));

        double sw = Math.max(1.4, s * (small ? 0.075 : 0.045));
        // outer segmented ring: cyan -> blue
        ring(g, c, s * 0.42, sw, cyan, blue, small ? new double[][]{{20, 140}, {200, 140}} :
                new double[][]{{15, 70}, {100, 55}, {170, 80}, {265, 55}, {335, 30}});
        // middle ring purple partial
        if (!small) {
            ring(g, c, s * 0.31, sw * 0.9, purple, new Color(0x6A4CFF), new double[][]{{60, 120}, {220, 95}});
            ring(g, c, s * 0.31, sw * 0.9, green, green.darker(), new double[][]{{190, 22}, {330, 18}});
        } else {
            ring(g, c, s * 0.27, sw, purple, purple, new double[][]{{90, 200}});
        }
        // inner thin ring
        if (!small) {
            g.setStroke(new BasicStroke((float) (sw * 0.45)));
            g.setColor(new Color(59, 216, 255, 150));
            g.draw(circle(c, c, s * 0.21));
        }
        // core
        double cr = s * (small ? 0.14 : 0.11);
        RadialGradientPaint core = new RadialGradientPaint(new Point2D.Double(c, c), (float) cr,
                new float[]{0f, 0.55f, 1f}, new Color[]{Color.WHITE, new Color(0xB8F6FF), cyan});
        g.setPaint(core);
        g.fill(circle(c, c, cr));
        // orbital nodes
        if (!small) {
            node(g, c, s * 0.42, -35, s * 0.03, cyan);
            node(g, c, s * 0.42, 150, s * 0.025, blue);
            node(g, c, s * 0.31, 245, s * 0.028, purple);
            node(g, c, s * 0.31, 40, s * 0.022, green);
        }
        g.dispose();
        return img;
    }

    static void ring(Graphics2D g, double c, double r, double w, Color a, Color b, double[][] arcs) {
        g.setStroke(new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(new GradientPaint((float) (c - r), (float) (c - r), a, (float) (c + r), (float) (c + r), b));
        for (double[] arc : arcs) g.draw(new Arc2D.Double(c - r, c - r, r * 2, r * 2, arc[0], arc[1], Arc2D.OPEN));
    }

    static void node(Graphics2D g, double c, double r, double deg, double nr, Color col) {
        double a = Math.toRadians(deg);
        double x = c + Math.cos(a) * r, y = c - Math.sin(a) * r;
        g.setPaint(new Color(col.getRed(), col.getGreen(), col.getBlue(), 90));
        g.fill(circle(x, y, nr * 2.2));
        g.setPaint(Color.WHITE);
        g.fill(circle(x, y, nr));
    }

    // ------------------------------------------------------------------ icons
    interface Drawer { void draw(Graphics2D g); }

    static final Drawer[] ICONS = {
            /* 0 OVERVIEW */ g -> { rr(g, 3, 3, 7.5, 7.5, 2); rr(g, 13.5, 3, 7.5, 7.5, 2); rr(g, 3, 13.5, 7.5, 7.5, 2); rr(g, 13.5, 13.5, 7.5, 7.5, 2); },
            /* 1 PROGRESSION */ g -> { line(g, 4, 20, 4, 16); line(g, 9, 20, 9, 13); line(g, 14, 20, 14, 15); line(g, 19, 20, 19, 11);
                poly(g, false, 3, 11, 9, 6, 13, 9, 20, 3); poly(g, false, 16, 3, 20, 3, 20, 7); },
            /* 2 HUD */ g -> { rr(g, 2, 4, 20, 13, 2); line(g, 8, 21, 16, 21); line(g, 12, 17, 12, 21); },
            /* 3 SESSIONS */ g -> poly(g, false, 2, 12, 6, 12, 9, 4, 15, 20, 18, 12, 22, 12),
            /* 4 GOALS */ g -> { g.draw(circle(12, 12, 9.5)); g.draw(circle(12, 12, 5.5)); g.fill(circle(12, 12, 2)); },
            /* 5 APPEARANCE */ g -> { Path2D p = new Path2D.Double(); p.moveTo(12, 2.5); p.curveTo(6, 2.5, 2.5, 7, 2.5, 12);
                p.curveTo(2.5, 17.5, 7, 21.5, 12, 21.5); p.curveTo(14, 21.5, 14.5, 19.5, 13.5, 18); p.curveTo(12.5, 16.5, 13.5, 14.5, 15.5, 14.5);
                p.lineTo(18, 14.5); p.curveTo(20, 14.5, 21.5, 13, 21.5, 11); p.curveTo(21.5, 6, 17.5, 2.5, 12, 2.5); g.draw(p);
                g.fill(circle(7.5, 11, 1.5)); g.fill(circle(10, 6.8, 1.5)); g.fill(circle(15, 6.8, 1.5)); },
            /* 6 BELL */ g -> { Path2D p = new Path2D.Double(); p.moveTo(6, 16.5); p.lineTo(6, 10.5); p.curveTo(6, 7, 8.7, 4, 12, 4);
                p.curveTo(15.3, 4, 18, 7, 18, 10.5); p.lineTo(18, 16.5); p.lineTo(20, 18.5); p.lineTo(4, 18.5); p.closePath(); g.draw(p);
                line(g, 10, 21.5, 14, 21.5); line(g, 12, 2, 12, 4); },
            /* 7 SETTINGS */ g -> { g.draw(circle(12, 12, 3)); g.draw(circle(12, 12, 7));
                for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; line(g, 12 + Math.cos(a) * 7, 12 + Math.sin(a) * 7, 12 + Math.cos(a) * 10, 12 + Math.sin(a) * 10); } },
            /* 8 DUNGEONS */ g -> { line(g, 4, 4, 15, 15); line(g, 20, 4, 9, 15); line(g, 12.5, 19.5, 19.5, 12.5); line(g, 4.5, 12.5, 11.5, 19.5);
                line(g, 16, 16, 20, 20); line(g, 8, 16, 4, 20); },
            /* 9 SLAYER */ g -> { Path2D p = new Path2D.Double(); p.moveTo(8, 17); p.curveTo(4.5, 15.5, 3.5, 12.5, 3.5, 10); p.curveTo(3.5, 5.5, 7.5, 2.5, 12, 2.5);
                p.curveTo(16.5, 2.5, 20.5, 5.5, 20.5, 10); p.curveTo(20.5, 12.5, 19.5, 15.5, 16, 17); p.lineTo(16, 21); p.lineTo(8, 21); p.closePath(); g.draw(p);
                g.fill(circle(8.8, 10.5, 2)); g.fill(circle(15.2, 10.5, 2)); line(g, 12, 17, 12, 21); },
            /* 10 SKILLS */ g -> star(g, 12, 12.5, 10, 4.2, 5),
            /* 11 BESTIARY */ g -> { g.fill(new Ellipse2D.Double(7, 12, 10, 8.5)); g.fill(circle(5, 9, 2.1)); g.fill(circle(9.3, 5.3, 2.1)); g.fill(circle(14.7, 5.3, 2.1)); g.fill(circle(19, 9, 2.1)); },
            /* 12 MINING */ g -> { AffineTransform t = g.getTransform(); g.rotate(Math.toRadians(-45), 12, 12);
                Path2D p = new Path2D.Double(); p.moveTo(2.5, 8.5); p.quadTo(12, 0.5, 21.5, 8.5); g.draw(p); line(g, 12, 4.8, 12, 23); g.setTransform(t); },
            /* 13 COLLECTIONS */ g -> { poly(g, true, 12, 2.5, 21.5, 7.5, 12, 12.5, 2.5, 7.5); poly(g, false, 2.5, 12, 12, 17, 21.5, 12); poly(g, false, 2.5, 16.5, 12, 21.5, 21.5, 16.5); },
            /* 14 PLUS */ g -> { line(g, 12, 5, 12, 19); line(g, 5, 12, 19, 12); },
            /* 15 CLOSE */ g -> { line(g, 6, 6, 18, 18); line(g, 18, 6, 6, 18); },
            /* 16 EDIT */ g -> { poly(g, true, 4, 20, 4.5, 16, 16, 4.5, 19.5, 8, 8, 19.5); line(g, 13.5, 7, 17, 10.5); },
            /* 17 TRASH */ g -> { line(g, 3.5, 6, 20.5, 6); poly(g, false, 9, 6, 9, 3, 15, 3, 15, 6); poly(g, false, 5.5, 6, 6.5, 21, 17.5, 21, 18.5, 6); line(g, 10, 10, 10, 17); line(g, 14, 10, 14, 17); },
            /* 18 PAUSE */ g -> { g.fill(new RoundRectangle2D.Double(6, 4, 4, 16, 1.5, 1.5)); g.fill(new RoundRectangle2D.Double(14, 4, 4, 16, 1.5, 1.5)); },
            /* 19 PLAY */ g -> { Path2D p = new Path2D.Double(); p.moveTo(7, 4); p.lineTo(19, 12); p.lineTo(7, 20); p.closePath(); g.fill(p); g.draw(p); },
            /* 20 CHECK */ g -> poly(g, false, 4, 12.5, 9.5, 18, 20, 6.5),
            /* 21 DRAG */ g -> { for (int i = 0; i < 3; i++) { g.fill(circle(9, 6 + i * 6, 1.6)); g.fill(circle(15, 6 + i * 6, 1.6)); } },
            /* 22 WARNING */ g -> { poly(g, true, 12, 3, 22, 20.5, 2, 20.5); line(g, 12, 9.5, 12, 14); g.fill(circle(12, 17.3, 1.3)); },
            /* 23 CLOCK */ g -> { g.draw(circle(12, 12, 9.5)); poly(g, false, 12, 6.5, 12, 12, 16, 14); },
            /* 24 CHEVRON_DOWN */ g -> poly(g, false, 6, 9, 12, 15, 18, 9),
            /* 25 CHEVRON_RIGHT */ g -> poly(g, false, 9, 6, 15, 12, 9, 18),
            /* 26 EYE */ g -> { eye(g); g.draw(circle(12, 12, 3)); },
            /* 27 EYE_OFF */ g -> { eye(g); line(g, 3, 3, 21, 21); },
            /* 28 RESET */ g -> { g.draw(new Arc2D.Double(4, 4, 16, 16, 100, 290, Arc2D.OPEN)); poly(g, false, 4, 3.5, 4, 8.5, 9, 8.5); },
            /* 29 SAVE */ g -> { poly(g, true, 4, 3.5, 17, 3.5, 20.5, 7, 20.5, 20.5, 3.5, 20.5, 3.5, 3.5); poly(g, false, 7.5, 3.5, 7.5, 8.5, 15.5, 8.5, 15.5, 3.5); rr(g, 7.5, 13, 9, 7.5, 1); },
            /* 30 IMPORT */ g -> { line(g, 12, 3, 12, 15); poly(g, false, 7, 10, 12, 15, 17, 10); poly(g, false, 3.5, 15, 3.5, 20.5, 20.5, 20.5, 20.5, 15); },
            /* 31 EXPORT */ g -> { line(g, 12, 15, 12, 3); poly(g, false, 7, 8, 12, 3, 17, 8); poly(g, false, 3.5, 15, 3.5, 20.5, 20.5, 20.5, 20.5, 15); },
            /* 32 COPY */ g -> { rr(g, 8.5, 8.5, 12, 12, 2); poly(g, false, 5, 15.5, 3.5, 15.5, 3.5, 3.5, 15.5, 3.5, 15.5, 5); },
            /* 33 PROFILE */ g -> { g.draw(circle(12, 8, 4.5)); g.draw(new Arc2D.Double(4, 14, 16, 14, 0, 180, Arc2D.OPEN)); },
            /* 34 SPARK */ g -> { Path2D p = new Path2D.Double(); p.moveTo(12, 2.5); p.quadTo(13, 11, 21.5, 12); p.quadTo(13, 13, 12, 21.5); p.quadTo(11, 13, 2.5, 12); p.quadTo(11, 11, 12, 2.5); p.closePath(); g.fill(p); },
            /* 35 FORAGING */ g -> { Path2D p = new Path2D.Double(); p.moveTo(12, 2.5); p.lineTo(19.5, 13); p.lineTo(4.5, 13); p.closePath(); g.draw(p);
                Path2D q = new Path2D.Double(); q.moveTo(12, 7.5); q.lineTo(20.5, 18); q.lineTo(3.5, 18); q.closePath(); g.draw(q); line(g, 12, 18, 12, 22); },
            /* 36 MOVE */ g -> { line(g, 12, 2.5, 12, 21.5); line(g, 2.5, 12, 21.5, 12); poly(g, false, 9, 5.5, 12, 2.5, 15, 5.5); poly(g, false, 9, 18.5, 12, 21.5, 15, 18.5);
                poly(g, false, 5.5, 9, 2.5, 12, 5.5, 15); poly(g, false, 18.5, 9, 21.5, 12, 18.5, 15); },
            /* 37 GLOBE */ g -> { g.draw(circle(12, 12, 9.5)); g.draw(new Ellipse2D.Double(8, 2.5, 8, 19)); line(g, 2.5, 12, 21.5, 12); },
            /* 38 FLAME */ g -> { Path2D p = new Path2D.Double(); p.moveTo(12, 21.5); p.curveTo(7, 21.5, 4.5, 18, 4.5, 14.5); p.curveTo(4.5, 10, 9, 8, 9, 3);
                p.curveTo(13, 5, 14, 8, 13.5, 11); p.curveTo(15, 10.5, 16, 9.5, 16.5, 8); p.curveTo(18.5, 10, 19.5, 12.5, 19.5, 14.5); p.curveTo(19.5, 18.5, 16.5, 21.5, 12, 21.5); g.draw(p); },
            /* 39 CHART_BAR */ g -> { rr(g, 3.5, 11, 4, 9.5, 1); rr(g, 10, 4, 4, 16.5, 1); rr(g, 16.5, 8, 4, 12.5, 1); },
            /* 40 GRIP */ g -> { line(g, 20, 9, 9, 20); line(g, 20, 14, 14, 20); line(g, 20, 19, 19, 20); },
            /* 41 INFO */ g -> { g.draw(circle(12, 12, 9.5)); line(g, 12, 11, 12, 17); g.fill(circle(12, 7.5, 1.3)); },
            /* 42 MINUS */ g -> line(g, 5, 12, 19, 12),
            /* 43 LIST */ g -> { line(g, 8.5, 6, 20.5, 6); line(g, 8.5, 12, 20.5, 12); line(g, 8.5, 18, 20.5, 18); g.fill(circle(4, 6, 1.4)); g.fill(circle(4, 12, 1.4)); g.fill(circle(4, 18, 1.4)); },
            /* 44 CALIBRATE */ g -> { g.draw(circle(12, 12, 7)); line(g, 12, 2, 12, 6.5); line(g, 12, 17.5, 12, 22); line(g, 2, 12, 6.5, 12); line(g, 17.5, 12, 22, 12); g.fill(circle(12, 12, 1.6)); },
            /* 45 TROPHY */ g -> { poly(g, false, 7, 3.5, 17, 3.5, 17, 9.5); g.draw(new Arc2D.Double(7, 2.5, 10, 13, 180, 180, Arc2D.OPEN)); line(g, 7, 3.5, 7, 9);
                line(g, 12, 15.5, 12, 19); line(g, 8, 20.5, 16, 20.5); g.draw(new Arc2D.Double(2.5, 4.5, 5.5, 6, 90, 180, Arc2D.OPEN)); g.draw(new Arc2D.Double(16, 4.5, 5.5, 6, 270, 180, Arc2D.OPEN)); },
            /* 46 KEYBOARD */ g -> { rr(g, 2, 6, 20, 12.5, 2); for (int i = 0; i < 4; i++) g.fill(circle(6.5 + i * 3.7, 10, 0.95)); line(g, 8, 14.5, 16, 14.5); },
            /* 47 SOUND */ g -> { poly(g, true, 3.5, 9.5, 7.5, 9.5, 12.5, 5, 12.5, 19, 7.5, 14.5, 3.5, 14.5); g.draw(new Arc2D.Double(11, 8, 6, 8, -60, 120, Arc2D.OPEN)); g.draw(new Arc2D.Double(11, 4.5, 10, 15, -60, 120, Arc2D.OPEN)); },
    };

    static void eye(Graphics2D g) {
        Path2D p = new Path2D.Double(); p.moveTo(2, 12); p.quadTo(12, 1.5, 22, 12); p.quadTo(12, 22.5, 2, 12); p.closePath(); g.draw(p);
    }

    static BufferedImage iconAtlas(int cell) {
        BufferedImage img = new BufferedImage(COLS * cell, ROWS * cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        aa(g);
        g.setColor(Color.WHITE);
        double k = cell / 24.0;
        float stroke = (float) (cell <= 24 ? 2.0 : 1.85);
        for (int i = 0; i < ICONS.length; i++) {
            AffineTransform t = g.getTransform();
            g.translate((i % COLS) * cell, (i / COLS) * cell);
            g.scale(k, k);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setClip(new Rectangle2D.Double(0, 0, 24, 24));
            ICONS[i].draw(g);
            g.setClip(null);
            g.setTransform(t);
        }
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ helpers
    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    static Ellipse2D circle(double cx, double cy, double r) { return new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2); }

    static void rr(Graphics2D g, double x, double y, double w, double h, double r) { g.draw(new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2)); }

    static void line(Graphics2D g, double x1, double y1, double x2, double y2) { g.draw(new Line2D.Double(x1, y1, x2, y2)); }

    static void poly(Graphics2D g, boolean closed, double... pts) {
        Path2D p = new Path2D.Double();
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        if (closed) p.closePath();
        g.draw(p);
    }

    static void star(Graphics2D g, double cx, double cy, double ro, double ri, int n) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < n * 2; i++) {
            double r = i % 2 == 0 ? ro : ri;
            double a = -Math.PI / 2 + i * Math.PI / n;
            double x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        g.draw(p);
    }
}
