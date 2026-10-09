package org.stegsuite.ui;

import org.stegsuite.core.jpeg.JpegQuality;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** 8x8 heatmaps of the JPEG quantization tables (natural order), with the step size in each cell. */
public final class QuantTableChart extends JComponent {
    private final List<int[]> tables = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    public QuantTableChart() {
        setPreferredSize(UiScale.dim(640, 230));
        setOpaque(true);
        setBackground(Color.WHITE);
    }

    /** @param tables natural-order tables; labels such as "table 0" */
    public void setTables(List<int[]> tables, List<String> labels) {
        this.tables.clear();
        this.labels.clear();
        if (tables != null) this.tables.addAll(tables);
        if (labels != null) this.labels.addAll(labels);
        int cell = UiScale.px(24);
        setPreferredSize(new Dimension(Math.max(UiScale.px(480), this.tables.size() * (8 * cell + UiScale.px(40)) + UiScale.px(20)),
                8 * cell + UiScale.px(70)));
        revalidate();
        repaint();
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Charts.hints(g);
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        Font base = Charts.baseFont();
        FontMetrics fm = g.getFontMetrics(base);
        g.setFont(base.deriveFont(Font.BOLD));
        g.setColor(Color.DARK_GRAY);
        g.drawString("Quantization tables (step per DCT frequency; top-left = DC, darker = coarser)", UiScale.px(10), UiScale.px(18));
        if (tables.isEmpty()) { g.dispose(); return; }
        int top = UiScale.px(30) + fm.getHeight();
        int avail = getWidth() - UiScale.px(20);
        int cell = Math.max(UiScale.px(12), Math.min((getHeight() - top - UiScale.px(10)) / 8,
                (avail / tables.size() - UiScale.px(40)) / 8));
        Font small = base.deriveFont(base.getSize2D() * Math.min(1f, cell / (float) UiScale.px(24)));
        int x0 = UiScale.px(10);
        for (int t = 0; t < tables.size(); t++) {
            int[] q = tables.get(t);
            int max = 1;
            for (int v : q) max = Math.max(max, v);
            g.setFont(base);
            g.setColor(Color.DARK_GRAY);
            String label = (t < labels.size() ? labels.get(t) + ": " : "") + JpegQuality.of(q);
            g.drawString(label, x0, top - UiScale.px(6));
            g.setFont(small);
            FontMetrics sm = g.getFontMetrics();
            for (int i = 0; i < 64; i++) {
                int r = i / 8, c = i % 8;
                double f = Math.sqrt(q[i] / (double) max);
                Color col = heat(f);
                g.setColor(col);
                g.fillRect(x0 + c * cell, top + r * cell, cell, cell);
                g.setColor(f > 0.55 ? Color.WHITE : Color.BLACK);
                String s = String.valueOf(q[i]);
                g.drawString(s, x0 + c * cell + (cell - sm.stringWidth(s)) / 2, top + r * cell + (cell + sm.getAscent()) / 2 - 2);
            }
            g.setColor(Color.GRAY);
            g.drawRect(x0, top, 8 * cell, 8 * cell);
            x0 += 8 * cell + Math.max(UiScale.px(40), fm.stringWidth(label) - 8 * cell + UiScale.px(20));
        }
        g.dispose();
    }

    /** White -> yellow -> red -> dark red. */
    static Color heat(double f) {
        f = Math.max(0, Math.min(1, f));
        if (f < 0.33) return blend(new Color(0xfffbe6), new Color(0xfdd36a), f / 0.33);
        if (f < 0.66) return blend(new Color(0xfdd36a), new Color(0xe34a33), (f - 0.33) / 0.33);
        return blend(new Color(0xe34a33), new Color(0x7f0000), (f - 0.66) / 0.34);
    }

    private static Color blend(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
