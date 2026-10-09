package org.stegsuite.ui;

import org.stegsuite.plugin.dct.DctAnalysis;
import org.stegsuite.report.FileAnalysis;
import org.stegsuite.report.Report;
import org.stegsuite.report.ReportBuilder;
import org.stegsuite.report.ReportWriter;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * File &gt; Export report: runs the automatic analysis of every tab on the open file (in the background,
 * cancelable) and writes one report with all conclusions as HTML (self-contained, charts and images as
 * base64 PNG, collapsible sections), Markdown or JSON.
 */
final class ExportReportDialog extends JDialog {
    private final JCheckBox autoScan = new JCheckBox("Quick auto scan (bit-plane LSB layouts)", true);
    private final JCheckBox fullCheck = new JCheckBox("Full decoder cross-check (JPEG; default: first rows)", false);
    private final JButton export = new JButton("Export...");
    private final JButton close = new JButton("Close");
    private final TaskRunner runner = new TaskRunner(this::updateButtons);
    private final JLabel status = new JLabel(" ");
    private final AppState state;

    ExportReportDialog(Window owner, AppState state) {
        super(owner, "Export report", ModalityType.MODELESS);
        this.state = state;
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(UiScale.px(10), UiScale.px(12), UiScale.px(10), UiScale.px(12)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(UiScale.px(2), 0, UiScale.px(2), 0);
        p.add(new JLabel("<html><b>" + esc(state.file() == null ? "input" : state.file().getName()) + "</b><br>"
                + "One report with the conclusions of every tab: file format, auto scan, bit-plane LSB images and<br>"
                + "heatmap, JPEG DCT (verdicts, chi-square attack, calibration, charts), RS/SPA, frames.<br>"
                + "Format by file extension: .html (self-contained), .md or .json.</html>"), c);
        p.add(autoScan, c);
        p.add(fullCheck, c);
        fullCheck.setEnabled(DctPanel.isJpeg(state.bytes()));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(4), 0));
        buttons.add(export);
        buttons.add(runner.component());
        buttons.add(close);
        p.add(buttons, c);
        p.add(status, c);
        export.addActionListener(e -> run());
        close.addActionListener(e -> { runner.cancel(); dispose(); });
        setContentPane(p);
        pack();
        setLocationRelativeTo(owner);
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }

    private void updateButtons() { export.setEnabled(!runner.running()); }

    private void run() {
        File f = Ui.chooseReport(this, Ui.baseName(state) + "_report");
        if (f == null) return;
        final ReportWriter.Format fmt = ReportWriter.Format.forFile(f.getName());
        final FileAnalysis.Options o = new FileAnalysis.Options();
        o.autoScan = autoScan.isSelected();
        o.keepImage = true;
        o.useDctCache = true;
        o.crossCheck = fullCheck.isSelected() ? DctAnalysis.CrossCheck.FULL : DctAnalysis.CrossCheck.SAMPLED;
        status.setText(" ");
        runner.start("Report for " + (state.file() == null ? "input" : state.file().getName()), progress -> {
            FileAnalysis.Result r = FileAnalysis.analyze(state.file(), state.bytes(), o, progress);
            Report rep = ReportBuilder.single(r);
            ReportWriter.write(rep, fmt, f.toPath());
            return f;
        }, file -> {
            status.setText("Written: " + file.getAbsolutePath());
            StatusBar.message("Report written to " + file);
        }, cause -> status.setText("Failed: " + DctPanel.describe(cause)));
    }

    JButton exportButton() { return export; }
}
