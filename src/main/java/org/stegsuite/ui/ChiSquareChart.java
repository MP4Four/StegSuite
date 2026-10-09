package org.stegsuite.ui;

import org.stegsuite.plugin.dct.ChiSquareAttack;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

/** Java2D chart of the chi-square attack: p-value vs. percentage of coefficients in read order. */
public final class ChiSquareChart extends JComponent {
    private static final Color TC = new Color(0x1f77b4), MAG = new Color(0xff7f0e), EST = new Color(0xd62728);
    private ChiSquareAttack.Result result;
    private String title = "Chi-square attack (Westfeld)";

    public ChiSquareChart() {
        setPreferredSize(UiScale.dim(640, 360));
        setOpaque(true);
        setBackground(Color.WHITE);
    }

    public void setResult(ChiSquareAttack.Result r, String fileName) {
        result = r;
        title = "Chi-square attack (Westfeld)" + (fileName == null ? "" : " - " + fileName);
        repaint();
    }

    ChiSquareAttack.Result result() { return result; }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, UiScale.px(12));
        g.setFont(base);
        FontMetrics fm = g.getFontMetrics();
        int left = UiScale.px(56), right = UiScale.px(20), top = UiScale.px(34), bottom = 4 * fm.getHeight() + UiScale.px(12);
        int w = getWidth() - left - right, h = getHeight() - top - bottom;
        g.setColor(Color.DARK_GRAY);
        g.setFont(base.deriveFont(Font.BOLD));
        g.drawString(title, left, UiScale.px(20));
        g.setFont(base);
        if (w < 50 || h < 50) { g.dispose(); return; }
        // grid and axes
        for (int i = 0; i <= 10; i++) {
            int x = left + w * i / 10, y = top + h - h * i / 10;
            g.setColor(new Color(0xe6e6e6));
            g.drawLine(x, top, x, top + h);
            g.drawLine(left, y, left + w, y);
            g.setColor(Color.DARK_GRAY);
            String xs = (i * 10) + "%";
            g.drawString(xs, x - fm.stringWidth(xs) / 2, top + h + fm.getAscent() + UiScale.px(3));
            if (i % 2 == 0) {
                String ys = String.format("%.1f", i / 10.0);
                g.drawString(ys, left - fm.stringWidth(ys) - UiScale.px(5), y + fm.getAscent() / 2 - 1);
            }
        }
        g.setColor(Color.GRAY);
        g.drawRect(left, top, w, h);
        g.setColor(Color.DARK_GRAY);
        String xl = "non-zero AC coefficients in JSteg read order";
        g.drawString(xl, left + (w - fm.stringWidth(xl)) / 2, top + h + 2 * fm.getHeight() + UiScale.px(2));
        Graphics2D gy = (Graphics2D) g.create();
        gy.rotate(-Math.PI / 2);
        gy.drawString("p-value", -(top + h / 2 + fm.stringWidth("p-value") / 2), UiScale.px(14));
        gy.dispose();
        if (result == null) {
            String msg = "Run Analyze on a JPEG to compute the curve";
            g.drawString(msg, left + (w - fm.stringWidth(msg)) / 2, top + h / 2);
            g.dispose();
            return;
        }
        ChiSquareAttack.Curve best = result.best();
        if (best.estimate > 0 && best.informative()) {
            int x = left + (int) Math.round(w * best.estimate);
            g.setColor(new Color(EST.getRed(), EST.getGreen(), EST.getBlue(), 40));
            g.fillRect(left, top, x - left, h);
            g.setColor(EST);
            g.setStroke(new BasicStroke(UiScale.px(1), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                    new float[]{UiScale.px(5), UiScale.px(4)}, 0));
            g.drawLine(x, top, x, top + h);
            String est = String.format("est. end %.1f%% (~%d bytes)", 100 * best.estimate, best.estimatedBits / 8);
            int tx = Math.min(x + UiScale.px(4), left + w - fm.stringWidth(est) - UiScale.px(4));
            g.drawString(est, tx, top + fm.getAscent() + UiScale.px(4));
        }
        g.setStroke(new BasicStroke(UiScale.px(2)));
        drawCurve(g, result.twosComplement, TC, left, top, w, h);
        drawCurve(g, result.magnitude, MAG, left, top, w, h);
        // legend + summary
        int ly = top + h + 3 * fm.getHeight() + UiScale.px(2);
        int lx = left;
        lx = legend(g, fm, lx, ly, TC, "two's complement pairs (original JSteg)");
        legend(g, fm, lx + UiScale.px(16), ly, MAG, "magnitude pairs (sign kept)");
        g.setColor(Color.DARK_GRAY);
        g.drawString(result.summary(), left, ly + fm.getHeight());
        g.dispose();
    }

    private static int legend(Graphics2D g, FontMetrics fm, int x, int y, Color c, String text) {
        g.setColor(c);
        g.setStroke(new BasicStroke(UiScale.px(3)));
        g.drawLine(x, y - fm.getAscent() / 2, x + UiScale.px(18), y - fm.getAscent() / 2);
        g.setColor(Color.DARK_GRAY);
        g.drawString(text, x + UiScale.px(22), y);
        return x + UiScale.px(22) + fm.stringWidth(text);
    }

    private static void drawCurve(Graphics2D g, ChiSquareAttack.Curve c, Color color, int left, int top, int w, int h) {
        Path2D.Double path = new Path2D.Double();
        boolean started = false;
        for (int i = 0; i < c.p.length; i++) {
            if (Double.isNaN(c.p[i])) { started = false; continue; }
            double x = left + w * c.fraction[i], y = top + h - h * c.p[i];
            if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
        }
        g.setColor(color);
        g.draw(path);
    }
}
