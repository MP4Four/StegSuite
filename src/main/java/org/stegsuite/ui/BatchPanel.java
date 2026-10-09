package org.stegsuite.ui;

import org.stegsuite.report.BatchScanner;
import org.stegsuite.report.FileAnalysis;
import org.stegsuite.report.Report;
import org.stegsuite.report.ReportBuilder;
import org.stegsuite.report.ReportWriter;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Batch folder scan tab: format warnings, quick auto scan, JPEG DCT verdict and RS/SPA for every image of a
 * folder, ranked by a suspicion score. Double-click opens a file in the other tabs.
 */
public class BatchPanel extends JPanel implements FileAware {
    private final JTextField folder = new JTextField(36);
    private final JCheckBox recursive = new JCheckBox("Recursive", true);
    private final JCheckBox png = new JCheckBox("PNG", true), jpeg = new JCheckBox("JPEG", true),
            bmp = new JCheckBox("BMP", true), gif = new JCheckBox("GIF", true), other = new JCheckBox("Other", false);
    private final JCheckBox autoScan = new JCheckBox("Quick auto scan", true);
    private final JButton start = new JButton("Scan folder");
    private final JButton export = new JButton("Export batch report...");
    private final TaskRunner runner = new TaskRunner(this::updateButtons);
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JTextArea details = new JTextArea();
    private final JLabel summary = new JLabel(" ");
    private final Consumer<File> opener;
    private File scannedFolder;
    private int generation;

    public BatchPanel(Consumer<File> opener) {
        super(new BorderLayout());
        this.opener = opener;
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> browse());
        start.addActionListener(e -> startScan());
        export.addActionListener(e -> exportReport());
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(new JLabel("Folder:"));
        row1.add(folder);
        row1.add(browse);
        row1.add(recursive);
        row1.add(new JLabel("  Types:"));
        for (JCheckBox c : new JCheckBox[]{png, jpeg, bmp, gif, other}) row1.add(c);
        other.setToolTipText("Any other file: format analysis only (trailing data, embedded signatures, flag strings)");
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        autoScan.setToolTipText("zsteg-style quick scan of the LSB layouts (slower on large images)");
        row2.add(autoScan);
        row2.add(start);
        row2.add(runner.component());
        row2.add(export);
        row2.add(summary);
        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(row1);
        top.add(row2);
        add(top, BorderLayout.NORTH);

        table.setAutoCreateRowSorter(false);
        TableRowSorter<Model> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(Math.max(table.getRowHeight(), UiScale.px(20)));
        table.setDefaultRenderer(Integer.class, new ScoreRenderer());
        int[] widths = {220, 60, 80, 70, 60, 380, 240, 240, 70, 60};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(UiScale.px(widths[i]));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) openSelected();
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> showDetails());
        table.setToolTipText("Double-click a file to open it in the other tabs");
        details.setEditable(false);
        details.setFont(UiScale.mono(12));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(details));
        split.setResizeWeight(0.72);
        add(split, BorderLayout.CENTER);
        details.setText("Pick a folder and press 'Scan folder'. Every file is classified by its content (PNG / JPEG / BMP /\n"
                + "GIF / other) and analysed: file-format warnings (CRC errors, trailing data, embedded signatures, flag\n"
                + "strings), the quick bit-plane auto scan, the JPEG DCT analysis (pairs test, chi-square attack,\n"
                + "calibration, JSteg extraction) and RS/SPA for lossless images. The suspicion score adds a fixed\n"
                + "weight per indicator (capped at 100) - a ranking aid, not a probability.\n"
                + "Double-click a row to open the file; 'Export batch report...' writes HTML, Markdown or JSON.");
        updateButtons();
    }

    private void updateButtons() {
        start.setEnabled(!runner.running());
        export.setEnabled(!runner.running() && model.rows.size() > 0);
    }

    void browse() {
        File f = Ui.chooseFolder(this, folder.getText().isEmpty() ? null : new File(folder.getText()));
        if (f != null) folder.setText(f.getAbsolutePath());
    }

    /** Folder field (for the menu / tests). */
    void setFolder(File f) { folder.setText(f == null ? "" : f.getAbsolutePath()); }

    void startScan() {
        File dir = new File(folder.getText().trim());
        if (!dir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Not a folder: " + dir, "Batch scan", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final BatchScanner.Options o = new BatchScanner.Options();
        o.recursive = recursive.isSelected();
        o.kinds = EnumSet.noneOf(FileAnalysis.Kind.class);
        if (png.isSelected()) o.kinds.add(FileAnalysis.Kind.PNG);
        if (jpeg.isSelected()) o.kinds.add(FileAnalysis.Kind.JPEG);
        if (bmp.isSelected()) o.kinds.add(FileAnalysis.Kind.BMP);
        if (gif.isSelected()) o.kinds.add(FileAnalysis.Kind.GIF);
        if (other.isSelected()) o.kinds.add(FileAnalysis.Kind.OTHER);
        o.analysis.autoScan = autoScan.isSelected();
        model.clear();
        scannedFolder = dir;
        final int gen = ++generation;
        summary.setText(" ");
        runner.start("Batch scan of " + dir.getName(), progress -> BatchScanner.scan(dir, o, (r, i, total) ->
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) return;
                    model.add(r);
                    summary.setText(String.format("  %d / %d files", i + 1, total));
                    StatusBar.message(String.format("Batch scan: %d/%d %s (score %d)", i + 1, total, r.name, r.score));
                    updateButtons();
                }), progress), results -> {
            int high = 0;
            for (FileAnalysis.Result r : results) if (r.score >= 60) high++;
            summary.setText(String.format("  %d files, %d with score >= 60", results.size(), high));
            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
            updateButtons();
        }, cause -> JOptionPane.showMessageDialog(this, DctPanel.describe(cause), "Batch scan failed", JOptionPane.ERROR_MESSAGE));
    }

    private FileAnalysis.Result selected() {
        int v = table.getSelectedRow();
        if (v < 0) return null;
        return model.rows.get(table.convertRowIndexToModel(v));
    }

    private void openSelected() {
        FileAnalysis.Result r = selected();
        if (r != null && r.file != null && opener != null) {
            opener.accept(r.file);
            StatusBar.message("Opened " + r.file.getName() + " from the batch scan");
        }
    }

    private void showDetails() {
        FileAnalysis.Result r = selected();
        if (r == null) return;
        Report rep = ReportBuilder.batch(scannedFolder, Collections.singletonList(r), false);
        details.setText(rep.sections.isEmpty() ? "" : rep.sections.get(0).title + "\n" + rep.sections.get(0).text);
        details.setCaretPosition(0);
    }

    void exportReport() {
        if (model.rows.isEmpty()) return;
        File f = Ui.chooseReport(this, "batch_report_" + (scannedFolder == null ? "scan" : scannedFolder.getName()));
        if (f == null) return;
        final List<FileAnalysis.Result> rows = new ArrayList<>(model.rows);
        final File dir = scannedFolder;
        final ReportWriter.Format fmt = ReportWriter.Format.forFile(f.getName());
        runner.start("Writing " + f.getName(), progress -> {
            Report rep = ReportBuilder.batch(dir, rows, fmt == ReportWriter.Format.HTML);
            ReportWriter.write(rep, fmt, f.toPath());
            return f;
        }, file -> StatusBar.message("Batch report written to " + file),
                cause -> JOptionPane.showMessageDialog(this, DctPanel.describe(cause), "Export failed", JOptionPane.ERROR_MESSAGE));
    }

    /** Results currently in the table (scan order). */
    List<FileAnalysis.Result> results() { return Collections.unmodifiableList(model.rows); }

    JTable table() { return table; }

    TaskRunner runner() { return runner; }

    @Override public void onFile(AppState s) {
        if (folder.getText().trim().isEmpty() && s != null && s.file() != null && s.file().getParentFile() != null)
            folder.setText(s.file().getParentFile().getAbsolutePath());
    }

    private final class Model extends AbstractTableModel {
        final List<FileAnalysis.Result> rows = new ArrayList<>();
        final String[] cols = {"File", "Type", "Size", "Score", "Level", "Findings", "Scan hit", "DCT", "RS/SPA %", "ms"};
        final Class<?>[] types = {String.class, String.class, Long.class, Integer.class, String.class, String.class,
                String.class, String.class, Double.class, Long.class};

        void add(FileAnalysis.Result r) {
            rows.add(r);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return types[c]; }

        @Override public Object getValueAt(int row, int c) {
            FileAnalysis.Result r = rows.get(row);
            switch (c) {
                case 0: return BatchScanner.relative(scannedFolder, r.file);
                case 1: return r.kind.name();
                case 2: return r.size;
                case 3: return r.score;
                case 4: return r.error != null ? "error" : r.level();
                case 5: return r.error != null ? r.error : String.join("; ", r.reasons);
                case 6: return r.bestHit();
                case 7: return r.kind == FileAnalysis.Kind.JPEG ? r.dctSummary() : null;
                case 8: { double v = r.spatialRate(); return Double.isNaN(v) ? null : Math.round(v * 1000) / 10.0; }
                default: return r.millis;
            }
        }
    }

    /** Colours the score cell by level. */
    private static final class ScoreRenderer extends DefaultTableCellRenderer {
        ScoreRenderer() { setHorizontalAlignment(RIGHT); }

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            if (!sel && v instanceof Integer) {
                int s = (Integer) v;
                c.setBackground(s >= 60 ? new Color(0xf8c9c4) : s >= 30 ? new Color(0xfde9b8) : t.getBackground());
            }
            return c;
        }
    }
}
