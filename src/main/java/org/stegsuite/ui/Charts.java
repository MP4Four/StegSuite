package org.stegsuite.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Offscreen rendering of the Java2D chart components (report export, thumbnails). */
public final class Charts {
    private Charts() {}

    /** Paints a chart component at the given size into an RGB image (works headless). */
    public static BufferedImage render(JComponent chart, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        chart.setSize(width, height);
        chart.doLayout();
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        chart.paint(g);
        g.dispose();
        return img;
    }

    /** Scaled copy that fits into maxW x maxH (never enlarged). */
    public static BufferedImage thumbnail(BufferedImage src, int maxW, int maxH) {
        double s = Math.min(1.0, Math.min(maxW / (double) src.getWidth(), maxH / (double) src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * s)), h = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // progressive halving keeps large downscales smooth
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w * 2 && cur.getHeight() / 2 >= h * 2) {
            BufferedImage half = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D hg = half.createGraphics();
            hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            hg.drawImage(cur, 0, 0, half.getWidth(), half.getHeight(), null);
            hg.dispose();
            cur = half;
        }
        g.drawImage(cur, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static Font baseFont() {
        Font base = UIManager.getFont("Label.font");
        return base != null ? base : new Font(Font.SANS_SERIF, Font.PLAIN, UiScale.px(12));
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
}
