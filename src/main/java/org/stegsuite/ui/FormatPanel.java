package org.stegsuite.ui;

import org.stegsuite.core.format.FormatAnalyzer;
import org.stegsuite.core.format.FormatReport;
import org.stegsuite.core.format.PngAnalyzer;
import org.stegsuite.core.format.PngSizeRepair;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/** File structure report + raw hex view of the whole file. */
public class FormatPanel extends JPanel implements FileAware {
    private final JTextArea report = new JTextArea();
    private final HexViewer hex = new HexViewer();
    private final JButton exportTrailing = new JButton("Export trailing data...");
    private final JButton saveFixed = new JButton("Save fixed PNG...");
    private final JButton repair = new JButton("PNG size repair...");
    private final JLabel quality = new JLabel(" ");
    private AppState state;
    private FormatReport last;

    public FormatPanel() {
        super(new BorderLayout());
        report.setEditable(false);
        report.setFont(UiScale.mono(12));
        exportTrailing.setEnabled(false);
        saveFixed.setEnabled(false);
        exportTrailing.addActionListener(e -> {
            if (last == null || !last.hasTrailing()) return;
            Ui.saveBytes(this, Arrays.copyOfRange(state.bytes(), (int) last.trailingOffset, state.bytes().length), Ui.baseName(state) + "_trailing.bin");
        });
        saveFixed.addActionListener(e -> {
            if (last == null || last.suggestedSize == null) return;
            Ui.saveBytes(this, PngAnalyzer.patchIhdr(state.bytes(), last.suggestedSize[0], last.suggestedSize[1]), Ui.baseName(state) + "_fixed.png");
        });
        repair.setEnabled(false);
        repair.setToolTipText("Width/height CRC search, IDAT-size inference, manual size with CRC recomputation, preview");
        repair.addActionListener(e -> {
            if (state == null || !PngSizeRepair.isPng(state.bytes())) return;
            new PngRepairDialog(SwingUtilities.getWindowAncestor(this), state.bytes(), Ui.baseName(state)).setVisible(true);
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(exportTrailing); bar.add(saveFixed); bar.add(repair); bar.add(quality);
        quality.setToolTipText("IJG quality of the luminance quantization table (exact for libjpeg/Annex K tables, else estimated)");
        JPanel left = new JPanel(new BorderLayout());
        left.add(bar, BorderLayout.NORTH);
        left.add(new JScrollPane(report), BorderLayout.CENTER);
        hex.setBorder(BorderFactory.createTitledBorder("Raw file"));
        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, hex);
        sp.setResizeWeight(0.55);
        add(sp, BorderLayout.CENTER);
    }

    @Override public void onFile(AppState s) {
        state = s;
        last = null;
        exportTrailing.setEnabled(false); saveFixed.setEnabled(false);
        repair.setEnabled(PngSizeRepair.isPng(s.bytes()));
        report.setText("Analyzing...");
        quality.setText(" ");
        hex.setData(s.bytes(), s.file() == null ? "file.bin" : s.file().getName());
        new SwingWorker<FormatReport, Void>() {
            @Override protected FormatReport doInBackground() { return FormatAnalyzer.analyze(s.bytes()); }
            @Override protected void done() {
                if (state != s) return;
                try {
                    last = get();
                    report.setText(last.text.toString());
                    report.setCaretPosition(0);
                    exportTrailing.setEnabled(last.hasTrailing());
                    saveFixed.setEnabled(last.suggestedSize != null);
                    quality.setText(last.jpegQuality > 0 ? "  JPEG quality: " + (last.jpegQualityExact ? "" : "~") + last.jpegQuality
                            + (last.jpegQualityExact ? " (standard tables)" : " (estimated)") : " ");
                    if (!last.warnings.isEmpty()) StatusBar.message("Format: " + last.warnings.size() + " warning(s)");
                } catch (Exception e) { report.setText("Error: " + e); }
            }
        }.execute();
    }
}
