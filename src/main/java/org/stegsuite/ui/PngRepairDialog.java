package org.stegsuite.ui;

import org.stegsuite.core.format.PngSizeRepair;
import org.stegsuite.core.image.ImageUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * PNG size repair (merged from AabyssZG/Deformed-Image-Restorer): 2D CRC search over configurable
 * width/height ranges, IDAT-size inference for files whose CRC was changed too, manual width/height with
 * CRC recomputation, and a live preview of the repaired image.
 */
public class PngRepairDialog extends JDialog {
    /** Candidates larger than this are not previewed (decoding would take too much memory). */
    static final long MAX_PREVIEW_PIXELS = 40_000_000L;

    private final byte[] png;
    private final String baseName;
    private final PngSizeRepair.Ihdr ihdr;
    private final JSpinner minW = spinner(1), maxW = spinner(20000), minH = spinner(1), maxH = spinner(20000);
    private final JSpinner threads = new JSpinner(new SpinnerNumberModel(Runtime.getRuntime().availableProcessors(), 1, 64, 1));
    private final JSpinner manualW, manualH;
    private final DefaultTableModel crcModel = table("Width", "Height", "Change");
    private final DefaultTableModel idatModel = table("Width", "Height", "Filter bytes", "Note");
    private final JTable crcTable = new JTable(crcModel), idatTable = new JTable(idatModel);
    private final JLabel crcInfo = new JLabel(" "), idatInfo = new JLabel(" "), previewInfo = new JLabel(" ");
    private final ImageView preview = new ImageView();
    private final JButton search = new JButton("Search CRC matches"), infer = new JButton("Infer from IDAT size");
    private final JButton save = new JButton("Save repaired PNG...");
    private final TaskRunner searchRunner = new TaskRunner(this::updateButtons);
    private final TaskRunner previewRunner = new TaskRunner(null);
    private byte[] repaired;

    public PngRepairDialog(Window owner, byte[] png, String baseName) {
        super(owner, "PNG size repair", ModalityType.MODELESS);
        this.png = png;
        this.baseName = baseName;
        this.ihdr = PngSizeRepair.ihdr(png);
        manualW = new JSpinner(new SpinnerNumberModel(Math.max(1, ihdr.width), 1, Integer.MAX_VALUE, 1));
        manualH = new JSpinner(new SpinnerNumberModel(Math.max(1, ihdr.height), 1, Integer.MAX_VALUE, 1));
        setLayout(new BorderLayout());
        JLabel head = new JLabel("<html><b>IHDR:</b> " + ihdr + "</html>");
        head.setBorder(BorderFactory.createEmptyBorder(UiScale.px(6), UiScale.px(8), UiScale.px(6), UiScale.px(8)));
        add(head, BorderLayout.NORTH);

        JTabbedPane modes = new JTabbedPane();
        modes.addTab("CRC search", crcPanel());
        modes.addTab("IDAT size inference", idatPanel());
        modes.addTab("Manual", manualPanel());

        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createTitledBorder("Preview"));
        JScrollPane ps = new JScrollPane(preview);
        ps.setPreferredSize(UiScale.dim(420, 380));
        right.add(ps, BorderLayout.CENTER);
        JPanel pbottom = new JPanel(new BorderLayout());
        pbottom.add(previewInfo, BorderLayout.CENTER);
        JPanel pb = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        pb.add(previewRunner.component());
        pb.add(save);
        pbottom.add(pb, BorderLayout.SOUTH);
        right.add(pbottom, BorderLayout.SOUTH);
        save.setEnabled(false);
        save.addActionListener(e -> {
            if (repaired != null) Ui.saveBytes(this, repaired, baseName + "_repaired.png");
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, modes, right);
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);
        add(new JLabel(" Width/height search and 'reverse' mode based on AabyssZG/Deformed-Image-Restorer;"
                + " the IHDR CRC is always recomputed for the saved file."), BorderLayout.SOUTH);
        setSize(UiScale.windowSize(1100, 640));
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { searchRunner.cancel(); previewRunner.cancel(); }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        updateButtons();
        showCandidate(ihdr.width, ihdr.height);
    }

    private static JSpinner spinner(int v) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(v, 0, Integer.MAX_VALUE, 1));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(6);
        return s;
    }

    private static DefaultTableModel table(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private JPanel crcPanel() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel f = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(UiScale.px(2), UiScale.px(4), UiScale.px(2), UiScale.px(4));
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;
        f.add(new JLabel("Width from"), c); f.add(minW, c); f.add(new JLabel("to"), c); f.add(maxW, c);
        c.gridy = 1;
        f.add(new JLabel("Height from"), c); f.add(minH, c); f.add(new JLabel("to"), c); f.add(maxH, c);
        c.gridy = 2;
        f.add(new JLabel("Threads"), c); f.add(threads, c);
        c.gridy = 3;
        c.gridwidth = 4;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(4), 0));
        row.add(search);
        row.add(searchRunner.component());
        f.add(row, c);
        c.gridy = 4;
        f.add(crcInfo, c);
        p.add(f, BorderLayout.NORTH);
        p.add(new JScrollPane(crcTable), BorderLayout.CENTER);
        search.addActionListener(e -> runSearch());
        selectRow(crcTable, crcModel);
        return p;
    }

    private JPanel idatPanel() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel top = new JPanel(new BorderLayout());
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.add(infer);
        top.add(row, BorderLayout.NORTH);
        top.add(idatInfo, BorderLayout.CENTER);
        JTextArea help = new JTextArea("For when the CRC was altered as well: the decompressed IDAT size must equal\n"
                + "h * (1 + ceil(w * bitsPerPixel / 8)) for a non-interlaced PNG. Every width whose row length divides\n"
                + "the size gives a candidate; rows must start with a filter byte 0..4. The stored width comes first.");
        help.setEditable(false);
        help.setOpaque(false);
        help.setFont(UIManager.getFont("Label.font"));
        top.add(help, BorderLayout.SOUTH);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(idatTable), BorderLayout.CENTER);
        infer.addActionListener(e -> runInfer());
        selectRow(idatTable, idatModel);
        return p;
    }

    private JPanel manualPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(UiScale.px(4), UiScale.px(4), UiScale.px(4), UiScale.px(4));
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;
        p.add(new JLabel("Width"), c); p.add(manualW, c);
        c.gridy = 1;
        p.add(new JLabel("Height"), c); p.add(manualH, c);
        c.gridy = 2;
        c.gridwidth = 2;
        JButton apply = new JButton("Apply (rewrite IHDR + CRC) and preview");
        apply.addActionListener(e -> showCandidate((Integer) manualW.getValue(), (Integer) manualH.getValue()));
        p.add(apply, c);
        c.gridy = 3;
        c.weighty = 1;
        c.anchor = GridBagConstraints.NORTHWEST;
        p.add(new JLabel("<html>Writes the given size into IHDR and recomputes its CRC<br>(the script's 'reverse' mode)."
                + " Selecting a row in the other tabs fills these fields.</html>"), c);
        return p;
    }

    private void selectRow(JTable t, DefaultTableModel m) {
        t.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int r = t.getSelectedRow();
            if (r < 0) return;
            showCandidate((Integer) m.getValueAt(r, 0), (Integer) m.getValueAt(r, 1));
        });
    }

    private void updateButtons() {
        boolean busy = searchRunner.running();
        search.setEnabled(!busy);
        infer.setEnabled(!busy);
    }

    private void runSearch() {
        final int w0 = (Integer) minW.getValue(), w1 = (Integer) maxW.getValue();
        final int h0 = (Integer) minH.getValue(), h1 = (Integer) maxH.getValue();
        final int n = (Integer) threads.getValue();
        crcModel.setRowCount(0);
        crcInfo.setText(" ");
        searchRunner.start("CRC search", progress -> PngSizeRepair.search(png, ihdr.storedCrc, w0, w1, h0, h1, 1000, n, progress), r -> {
            for (int[] m : r.matches)
                crcModel.addRow(new Object[]{m[0], m[1], m[0] == ihdr.width && m[1] == ihdr.height ? "unchanged"
                        : m[0] == ihdr.width ? "height" : m[1] == ihdr.height ? "width" : "both"});
            crcInfo.setText(String.format("%d match(es) in %,d combinations%s", r.matches.size(), r.combinations,
                    r.truncated ? " (list truncated)" : ""));
            int[] best = PngSizeRepair.best(r.matches, ihdr.width, ihdr.height);
            if (best != null) {
                int idx = r.matches.indexOf(best);
                crcTable.setRowSelectionInterval(idx, idx);
            }
        }, this::error);
    }

    private void runInfer() {
        idatModel.setRowCount(0);
        searchRunner.start("IDAT size inference", progress -> PngSizeRepair.inferFromIdat(png, 65535, 500, progress), r -> {
            for (PngSizeRepair.Candidate c : r.candidates)
                idatModel.addRow(new Object[]{c.width, c.height, c.filtersValid ? "valid" : "not checked", c.note});
            idatInfo.setText("<html>" + r.message + (r.heightForStoredWidth > 0
                    ? "<br>stored width " + ihdr.width + " -> height " + r.heightForStoredWidth : "") + "<br>"
                    + r.candidates.size() + " candidate(s)</html>");
            if (!r.candidates.isEmpty()) idatTable.setRowSelectionInterval(0, 0);
        }, this::error);
    }

    private void error(Throwable cause) {
        JOptionPane.showMessageDialog(this, DctPanel.describe(cause), "PNG size repair", JOptionPane.ERROR_MESSAGE);
    }

    /** Rewrites IHDR with (w, h), fills the manual fields and decodes the preview in the background. */
    void showCandidate(int w, int h) {
        manualW.setValue(Math.max(1, w));
        manualH.setValue(Math.max(1, h));
        final byte[] fixed = PngSizeRepair.rewrite(png, w, h);
        repaired = fixed;
        save.setEnabled(true);
        if ((long) w * h > MAX_PREVIEW_PIXELS || w <= 0 || h <= 0) {
            preview.setImage(null, null);
            previewInfo.setText(" " + w + "x" + h + ": too large to preview (can still be saved)");
            return;
        }
        previewInfo.setText(" decoding " + w + "x" + h + "...");
        previewRunner.start("Preview " + w + "x" + h, progress -> ImageUtil.decode(fixed), img -> {
            preview.setImage(img, img);
            previewInfo.setText(img == null ? " " + w + "x" + h + ": the JDK decoder could not read this size"
                    : " " + w + "x" + h + " (IHDR CRC recomputed)");
        }, cause -> {
            preview.setImage(null, null);
            previewInfo.setText(" " + w + "x" + h + ": " + DctPanel.describe(cause));
        });
    }

    /** Test / automation hooks. */
    List<?> crcRows() { return crcModel.getDataVector(); }
    JButton searchButton() { return search; }
    JButton inferButton() { return infer; }
}
