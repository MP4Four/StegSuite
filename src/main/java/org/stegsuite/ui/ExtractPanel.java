package org.stegsuite.ui;

import org.stegsuite.core.extract.AutoScanner;
import org.stegsuite.core.extract.BitExtractor;
import org.stegsuite.core.extract.ExtractConfig;
import org.stegsuite.core.extract.ScanHit;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** Unified data extractor: manual Stegsolve-style parameters plus zsteg-style auto scan. */
public class ExtractPanel extends JPanel implements FileAware {
    private static final String[] CH = {"R", "G", "B", "A"};
    private final JCheckBox[][] bits = new JCheckBox[4][8];
    private final JComboBox<String> order = new JComboBox<>(new String[]{"RGB", "RBG", "GRB", "GBR", "BRG", "BGR", "RGBA", "ARGB", "ABGR", "BGRA"});
    private final JRadioButton sampleLsb = new JRadioButton("LSB first", true), sampleMsb = new JRadioButton("MSB first");
    private final JRadioButton rowOrder = new JRadioButton("Row (xy)", true), colOrder = new JRadioButton("Column (yx)");
    private final JRadioButton packMsb = new JRadioButton("MSB of byte first", true), packLsb = new JRadioButton("LSB of byte first");
    private final JSpinner maxKb = new JSpinner(new SpinnerNumberModel(1024, 1, 1 << 20, 64));
    private final HexViewer hex = new HexViewer();
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Config", "Type", "Score", "Offset", "Detail"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JProgressBar progress = new JProgressBar();
    private final JButton scanBtn = new JButton("Auto scan");
    private final JComboBox<AutoScanner.Mode> scanMode = new JComboBox<>(AutoScanner.Mode.values());
    private final List<ScanHit> hits = new ArrayList<>();
    private SwingWorker<List<ScanHit>, Void> worker;
    private AppState state;

    public ExtractPanel() {
        super(new BorderLayout());
        JPanel bitsPanel = new JPanel(new GridLayout(5, 9, UiScale.px(2), 0));
        bitsPanel.add(new JLabel("bit"));
        for (int b = 7; b >= 0; b--) bitsPanel.add(new JLabel(String.valueOf(b), SwingConstants.CENTER));
        for (int c = 0; c < 4; c++) {
            bitsPanel.add(new JLabel(CH[c]));
            for (int b = 7; b >= 0; b--) { bits[c][b] = new JCheckBox(); bitsPanel.add(bits[c][b]); }
        }
        for (int c = 0; c < 3; c++) bits[c][0].setSelected(true);
        bitsPanel.setBorder(BorderFactory.createTitledBorder("Bit planes per channel"));

        JPanel opts = new JPanel(new GridLayout(0, 1));
        opts.setBorder(BorderFactory.createTitledBorder("Order"));
        opts.add(row(new JLabel("Channel order:"), order));
        opts.add(row(new JLabel("Bits in sample:"), group(sampleLsb, sampleMsb)));
        opts.add(row(new JLabel("Pixel walk:"), group(rowOrder, colOrder)));
        opts.add(row(new JLabel("Byte packing:"), group(packMsb, packLsb)));
        opts.add(row(new JLabel("Max KiB:"), maxKb));

        JButton preview = new JButton("Preview / extract");
        preview.addActionListener(e -> manualExtract());
        JPanel act = new JPanel(new GridLayout(0, 1, 0, UiScale.px(4)));
        act.setBorder(BorderFactory.createTitledBorder("Manual"));
        act.add(preview);
        JButton lsbPreset = new JButton("Preset: b1,rgb,lsb,xy");
        lsbPreset.addActionListener(e -> applyConfig(ExtractConfig.uniform("RGB", 1, false, false, false)));
        act.add(lsbPreset);
        scanBtn.addActionListener(e -> toggleScan());
        scanMode.setToolTipText("QUICK: common zsteg set (b1-b4, single planes 0-3/7). DEEP: + b5-b8, all planes, RGB permutations, mixed masks (b1r+b2g ...)");
        act.add(row(scanBtn, scanMode));
        progress.setStringPainted(true);
        act.add(progress);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(bitsPanel); top.add(opts); top.add(act);

        table.setAutoCreateRowSorter(true);
        table.setRowHeight(Math.max(table.getRowHeight(), table.getFontMetrics(table.getFont()).getHeight() + UiScale.px(2)));
        table.getColumnModel().getColumn(0).setPreferredWidth(UiScale.px(160));
        table.getColumnModel().getColumn(1).setPreferredWidth(UiScale.px(50));
        table.getColumnModel().getColumn(2).setPreferredWidth(UiScale.px(50));
        table.getColumnModel().getColumn(3).setPreferredWidth(UiScale.px(60));
        table.getColumnModel().getColumn(4).setPreferredWidth(UiScale.px(600));
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int vr = table.getSelectedRow();
                if (vr < 0) return;
                ScanHit h = hits.get(table.convertRowIndexToModel(vr));
                if (e.getClickCount() == 2) showHit(h);
                else applyConfig(h.config());
            }
        });
        JScrollPane ts = new JScrollPane(table);
        ts.setBorder(BorderFactory.createTitledBorder("Auto-scan results (click = load config, double-click = preview/save)"));
        hex.setBorder(BorderFactory.createTitledBorder("Extracted data"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, hex, ts);
        split.setResizeWeight(0.55);
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    private static JPanel row(JComponent a, JComponent b) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(4), 0)); p.add(a); p.add(b); return p;
    }

    private static JPanel group(JRadioButton a, JRadioButton b) {
        ButtonGroup g = new ButtonGroup(); g.add(a); g.add(b);
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); p.add(a); p.add(b); return p;
    }

    public ExtractConfig currentConfig() {
        int[] masks = new int[4];
        for (int c = 0; c < 4; c++) for (int b = 0; b < 8; b++) if (bits[c][b].isSelected()) masks[c] |= 1 << b;
        return new ExtractConfig((String) order.getSelectedItem(), masks, sampleMsb.isSelected(), colOrder.isSelected(), packLsb.isSelected());
    }

    private void applyConfig(ExtractConfig c) {
        for (int ch = 0; ch < 4; ch++) for (int b = 0; b < 8; b++) bits[ch][b].setSelected(((c.masks()[ch] >> b) & 1) != 0);
        String o = c.order();
        boolean found = false;
        for (int i = 0; i < order.getItemCount(); i++) if (order.getItemAt(i).equals(o)) found = true;
        if (!found) order.addItem(o);
        order.setSelectedItem(o);
        (c.msbFirstInSample() ? sampleMsb : sampleLsb).setSelected(true);
        (c.columnOrder() ? colOrder : rowOrder).setSelected(true);
        (c.packLsbFirst() ? packLsb : packMsb).setSelected(true);
    }

    private void manualExtract() {
        if (state == null || state.image() == null) { StatusBar.message("Open an image first"); return; }
        ExtractConfig c = currentConfig();
        if (c.bitsPerPixel() == 0) { StatusBar.message("Select at least one bit of a channel in the order"); return; }
        byte[] d = BitExtractor.extract(state.image(), c, (Integer) maxKb.getValue() * 1024);
        hex.setData(d, Ui.baseName(state) + "_" + c.label().replace(',', '_') + ".bin");
        java.util.List<ScanHit> an = AutoScanner.analyze(c, d);
        StatusBar.message(c.label() + ": " + d.length + " bytes" + (an.isEmpty() ? "" : "  -> " + an.get(0).type() + ": " + an.get(0).detail()));
    }

    private void toggleScan() {
        if (worker != null && !worker.isDone()) { worker.cancel(true); return; }
        if (state == null || state.image() == null) { StatusBar.message("Open an image first"); return; }
        model.setRowCount(0); hits.clear();
        scanBtn.setText("Cancel scan");
        final java.awt.image.BufferedImage img = state.image();
        final AutoScanner.Mode mode = (AutoScanner.Mode) scanMode.getSelectedItem();
        final long t0 = System.nanoTime();
        progress.setValue(0);
        worker = new SwingWorker<List<ScanHit>, Void>() {
            @Override protected List<ScanHit> doInBackground() {
                return AutoScanner.scan(img, mode,
                        (i, n) -> SwingUtilities.invokeLater(() -> { progress.setMaximum(n); progress.setValue(i); }),
                        this::isCancelled);
            }
            @Override protected void done() {
                scanBtn.setText("Auto scan");
                if (isCancelled()) { StatusBar.message("Scan cancelled"); return; }
                try {
                    hits.addAll(get());
                    for (ScanHit h : hits) model.addRow(new Object[]{h.config().label(), h.type(), h.score(), "0x" + Integer.toHexString(h.offset()), h.detail()});
                    StatusBar.message(String.format("%s auto scan finished: %d hits from %d combinations in %d ms", mode, hits.size(),
                            progress.getMaximum(), (System.nanoTime() - t0) / 1_000_000));
                } catch (Exception e) { Ui.error(ExtractPanel.this, e); }
            }
        };
        worker.execute();
    }

    private void showHit(ScanHit h) {
        HexViewer hv = new HexViewer();
        hv.setData(h.data(), Ui.baseName(state) + "_" + h.config().label().replace(',', '_') + ".bin");
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), h.config().label() + " - " + h.type(), Dialog.ModalityType.MODELESS);
        d.add(new JLabel(" " + h.detail() + "   (finding at offset 0x" + Integer.toHexString(h.offset()) + ")"), BorderLayout.NORTH);
        d.add(hv, BorderLayout.CENTER);
        d.setSize(UiScale.windowSize(820, 500));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    @Override public void onFile(AppState s) {
        state = s;
        if (worker != null) worker.cancel(true);
        model.setRowCount(0); hits.clear(); progress.setValue(0);
        hex.setData(new byte[0], "data.bin");
    }
}
