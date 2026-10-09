package org.stegsuite.ui;

import org.stegsuite.core.stat.SpatialDetection;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

/**
 * Spatial-domain LSB steganalysis: RS analysis and Sample Pair Analysis per colour channel, with
 * embedding-rate estimates. Meant for lossless images (PNG, BMP, ...).
 */
public class DetectionPanel extends JPanel implements FileAware {
    private final JButton analyze = new JButton("Run RS + SPA");
    private final TaskRunner runner = new TaskRunner(this::updateButtons);
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{
            "Channel", "RS estimate", "SPA estimate", "Mean", "R_M", "S_M", "R_-M", "S_-M", "R_M (flipped)", "S_M (flipped)"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JTextArea text = new JTextArea();
    private final JLabel verdict = new JLabel(" ");
    private AppState state;

    public DetectionPanel() {
        super(new BorderLayout());
        analyze.addActionListener(e -> run());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(analyze);
        controls.add(runner.component());
        JPanel north = new JPanel(new BorderLayout());
        north.add(controls, BorderLayout.NORTH);
        verdict.setFont(verdict.getFont().deriveFont(Font.BOLD));
        verdict.setBorder(BorderFactory.createEmptyBorder(UiScale.px(2), UiScale.px(8), UiScale.px(4), UiScale.px(8)));
        north.add(verdict, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        table.setDefaultRenderer(Object.class, new RateRenderer());
        table.setFillsViewportHeight(true);
        text.setEditable(false);
        text.setFont(UiScale.mono(12));
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setText(help());
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(UiScale.dim(600, 140));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, new JScrollPane(text));
        split.setResizeWeight(0.35);
        add(split, BorderLayout.CENTER);
        updateButtons();
    }

    private static String help() {
        return "RS analysis (Fridrich, Goljan, Du 2001) and Sample Pair Analysis (Dumitrescu, Wu, Wang 2003) estimate the\n"
                + "rate of LSB replacement per colour channel: the fraction of samples whose least significant bit carries\n"
                + "message bits (100% = every LSB replaced, i.e. about half of them flipped). Natural covers usually give\n"
                + "estimates below " + Math.round(100 * SpatialDetection.DETECTION_THRESHOLD) + "%; both methods agreeing on a higher value is strong evidence.\n\n"
                + "Assumptions: lossless 8-bit samples (PNG, BMP, TIFF); random LSB replacement (not LSB matching / +-1\n"
                + "embedding, which RS and SPA do not detect); a payload spread over the image - a short sequential payload\n"
                + "only raises the estimate in proportion to its share of the samples (use the Bit planes heatmap to localise it).\n"
                + "JPEG files: use the JPEG DCT tab; RS/SPA on decompressed JPEG pixels are not meaningful.\n";
    }

    private void updateButtons() {
        analyze.setEnabled(state != null && state.image() != null && !runner.running());
    }

    private void run() {
        if (state == null || state.image() == null || runner.running()) return;
        final java.awt.image.BufferedImage img = state.image();
        final boolean lossy = DctPanel.isJpeg(state.bytes());
        final String name = state.file() == null ? "image" : state.file().getName();
        model.setRowCount(0);
        verdict.setText(" ");
        runner.start("RS + SPA on " + name, progress -> SpatialDetection.analyze(img, lossy, progress), r -> show(r, name),
                cause -> {
                    text.setText("RS/SPA failed: " + DctPanel.describe(cause));
                    JOptionPane.showMessageDialog(this, DctPanel.describe(cause), "Detection - error", JOptionPane.ERROR_MESSAGE);
                });
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.1f%%", 100 * v);
    }

    private static String frac(double v) {
        return String.format("%.4f", v);
    }

    void show(SpatialDetection.Report r, String name) {
        model.setRowCount(0);
        for (SpatialDetection.ChannelResult c : r.channels)
            model.addRow(new Object[]{c.channel, pct(c.rs.rate), pct(c.spa.rate), pct(c.combined()),
                    frac(c.rs.rm), frac(c.rs.sm), frac(c.rs.rmNeg), frac(c.rs.smNeg), frac(c.rs.rmFlip), frac(c.rs.smFlip)});
        verdict.setText(r.verdict());
        verdict.setForeground(r.verdict().startsWith("LSB replacement likely") ? new Color(0xb00020) : new Color(0x1b5e20));
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(": ").append(r.width).append('x').append(r.height).append('\n');
        for (String w : r.warnings) sb.append("[!] ").append(w).append('\n');
        sb.append('\n');
        for (SpatialDetection.ChannelResult c : r.channels)
            sb.append(String.format("%-4s RS %6s (raw %+.4f, %d groups)   SPA %6s (raw %+.4f, %d pairs, X %d, Y %d, gamma %d)%n",
                    c.channel, pct(c.rs.rate), c.rs.rawRate, c.rs.groups, pct(c.spa.rate), c.spa.rawRate,
                    c.spa.pairs, c.spa.x, c.spa.y, c.spa.gamma));
        sb.append('\n').append(help());
        text.setText(sb.toString());
        text.setCaretPosition(0);
    }

    /** Colours the estimate columns: red above the detection threshold. */
    private static final class RateRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            setHorizontalAlignment(col == 0 ? LEFT : RIGHT);
            if (!sel) {
                c.setForeground(Color.BLACK);
                if (col >= 1 && col <= 3 && v instanceof String && ((String) v).endsWith("%")) {
                    double d = Double.parseDouble(((String) v).replace("%", "")) / 100;
                    if (d >= SpatialDetection.DETECTION_THRESHOLD) c.setForeground(new Color(0xb00020));
                }
            }
            return c;
        }
    }

    @Override public void onFile(AppState s) {
        state = s;
        runner.cancel();
        model.setRowCount(0);
        verdict.setText(s == null || s.image() == null ? "The open file is not a decodable image." : " ");
        text.setText(help());
        updateButtons();
    }
}
