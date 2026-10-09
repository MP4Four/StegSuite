package org.stegsuite.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Bar chart of the quantized AC coefficient values per component (zeros excluded, they would dwarf every
 * other bar). JSteg-style LSB replacement makes the bars of each pair (2i, 2i+1) equal in height.
 */
public final class CoefficientHistogramChart extends JComponent {
    private static final Color[] COLORS = {new Color(0x1f77b4), new Color(0xff7f0e), new Color(0x2ca02c), new Color(0x9467bd)};
    /** Values shown: -RANGE..RANGE without 0. */
    public static final int RANGE = 12;
    private int[] ids;
    private long[][] hist;
    private int center;
    private String title = "AC coefficient histogram";

    public CoefficientHistogramChart() {
        setPreferredSize(UiScale.dim(640, 260));
        setOpaque(true);
        setBackground(Color.WHITE);
    }

    /**
     * @param histograms per component, counts of values -c..c at index v + c (c = histograms[i].length / 2)
     */
    public void setData(int[] componentIds, long[][] histograms, String fileName) {
        ids = componentIds;
        hist = histograms;
        center = histograms == null || histograms.length == 0 ? 0 : histograms[0].length / 2;
        title = "AC coefficient histogram (non-zero values)" + (fileName == null ? "" : " - " + fileName);
        repaint();
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Charts.hints(g);
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        Font base = Charts.baseFont();
        g.setFont(base);
        FontMetrics fm = g.getFontMetrics();
        int left = UiScale.px(64), right = UiScale.px(16), top = UiScale.px(30), bottom = 2 * fm.getHeight() + UiScale.px(14);
        int w = getWidth() - left - right, h = getHeight() - top - bottom;
        g.setColor(Color.DARK_GRAY);
        g.setFont(base.deriveFont(Font.BOLD));
        g.drawString(title, left, UiScale.px(18));
        g.setFont(base);
        if (w < 60 || h < 40) { g.dispose(); return; }
        g.setColor(Color.GRAY);
        g.drawRect(left, top, w, h);
        if (hist == null || hist.length == 0) {
            g.drawString("Run Analyze to compute the histogram", left + UiScale.px(8), top + h / 2);
            g.dispose();
            return;
        }
        int range = Math.min(RANGE, center);
        long max = 1;
        for (long[] c : hist) for (int v = -range; v <= range; v++) if (v != 0) max = Math.max(max, c[v + center]);
        int slots = 2 * range;               // values without 0
        double slotW = w / (double) slots;
        int nComp = hist.length;
        double barW = Math.max(1, (slotW - UiScale.px(4)) / nComp);
        // y grid
        for (int i = 0; i <= 4; i++) {
            int y = top + h - h * i / 4;
            g.setColor(new Color(0xe6e6e6));
            g.drawLine(left + 1, y, left + w - 1, y);
            g.setColor(Color.DARK_GRAY);
            String ys = compact(max * i / 4);
            g.drawString(ys, left - fm.stringWidth(ys) - UiScale.px(5), y + fm.getAscent() / 2 - 1);
        }
        int slot = 0;
        for (int v = -range; v <= range; v++) {
            if (v == 0) continue;
            double x0 = left + slot * slotW + UiScale.px(2);
            // pair shading: (2i, 2i+1) magnitude pairs share a background band
            int mag = Math.abs(v);
            if (mag >= 2 && (mag / 2) % 2 == 1) {
                g.setColor(new Color(0xf3f3f3));
                g.fillRect((int) (left + slot * slotW), top + 1, (int) Math.ceil(slotW), h - 1);
            }
            for (int c = 0; c < nComp; c++) {
                long n = hist[c][v + center];
                int bh = (int) Math.round(h * (double) n / max);
                g.setColor(COLORS[c % COLORS.length]);
                g.fillRect((int) (x0 + c * barW), top + h - bh, (int) Math.max(1, barW - 1), bh);
            }
            g.setColor(Color.DARK_GRAY);
            String xs = String.valueOf(v);
            g.drawString(xs, (int) (left + slot * slotW + slotW / 2 - fm.stringWidth(xs) / 2.0), top + h + fm.getAscent() + UiScale.px(2));
            slot++;
        }
        int lx = left, ly = top + h + fm.getHeight() + fm.getAscent() + UiScale.px(6);
        for (int c = 0; c < nComp; c++) {
            g.setColor(COLORS[c % COLORS.length]);
            g.fillRect(lx, ly - fm.getAscent() + 2, UiScale.px(12), fm.getAscent() - 2);
            g.setColor(Color.DARK_GRAY);
            String t = "component " + (ids != null && c < ids.length ? ids[c] : c + 1) + (c == 0 ? " (luminance)" : "");
            g.drawString(t, lx + UiScale.px(16), ly);
            lx += UiScale.px(28) + fm.stringWidth(t);
        }
        String note = "grey bands: magnitude pairs {2,3}, {6,7}, ...; equal neighbours inside a pair suggest LSB replacement";
        if (lx + UiScale.px(20) + fm.stringWidth(note) < left + w) g.drawString(note, lx + UiScale.px(12), ly);
        g.dispose();
    }

    static String compact(long v) {
        if (v >= 10_000_000) return (v / 1_000_000) + "M";
        if (v >= 10_000) return (v / 1000) + "k";
        return String.valueOf(v);
    }
}
