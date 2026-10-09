package org.stegsuite.ui;

import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.stat.LsbHeatmap;
import org.stegsuite.core.util.TextHeuristics;
import org.stegsuite.plugin.JpegStegoPlugin;
import org.stegsuite.plugin.PluginRegistry;
import org.stegsuite.plugin.dct.DctAnalysis;
import org.stegsuite.plugin.dct.DctHeatmap;
import org.stegsuite.plugin.dct.JpegDctPlugin;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * JPEG DCT-domain tab: runs a {@link JpegStegoPlugin} on the open file. Analyze shows the coefficient
 * report, the chi-square attack chart and the block heatmap (bundled plugin), Extract loads the recovered
 * payload into the hex viewer.
 */
public class DctPanel extends JPanel implements FileAware {
    private final List<JpegStegoPlugin> plugins = PluginRegistry.jpegPlugins();
    private final JComboBox<String> box = new JComboBox<>();
    private final ReportView out = new ReportView();
    private final CoefficientHistogramChart histogramChart = new CoefficientHistogramChart();
    private final QuantTableChart quantChart = new QuantTableChart();
    private final JButton fullCheck = new JButton("Full cross-check");
    private final HexViewer hex = new HexViewer();
    private final JTextField password = new JTextField(14);
    private final JLabel description = new JLabel(" ");
    private final JButton analyze = new JButton("Analyze");
    private final JButton extract = new JButton("Extract");
    private final TaskRunner runner = new TaskRunner(this::updateButtons);
    private final JTabbedPane views = new JTabbedPane();
    private final ChiSquareChart chart = new ChiSquareChart();
    private final ImageView heatView = new ImageView();
    private final HeatmapBar heatBar = new HeatmapBar("Show heatmap", new Integer[]{1, 2, 4, 8, 16}, 4,
            "tile (8x8 blocks):", this::refreshHeatmap);
    private AppState state;
    /** Decoded coefficients and preview of the last analysis (bundled plugin only). */
    private DctImage analyzedImage;
    private BufferedImage analyzedPreview;
    /** Last bundled analysis and the bytes it belongs to (for the full cross-check). */
    private DctAnalysis.Analysis lastAnalysis;
    private byte[] lastAnalysisData;
    private String lastFileName;

    public DctPanel() {
        super(new BorderLayout());
        fullCheck.setToolTipText("Compare the whole reconstruction with the JDK decoder (Analyze compares the first "
                + DctAnalysis.SAMPLED_ROWS + " rows)");
        fullCheck.addActionListener(e -> runFullCrossCheck());
        for (JpegStegoPlugin p : plugins) box.addItem(p.name());
        analyze.addActionListener(e -> run(false));
        extract.addActionListener(e -> run(true));
        box.addActionListener(e -> showDescription());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Plugin:")); controls.add(box);
        JLabel pwLabel = new JLabel("Key (custom order):");
        String pwTip = "Optional key for the custom keyed order - not compatible with F5/OutGuess passwords";
        pwLabel.setToolTipText(pwTip);
        password.setToolTipText(pwTip);
        controls.add(pwLabel); controls.add(password);
        controls.add(analyze); controls.add(extract); controls.add(fullCheck);
        controls.add(runner.component());
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(controls, BorderLayout.NORTH);
        bar.add(description, BorderLayout.SOUTH);
        add(bar, BorderLayout.NORTH);
        out.setPlainText(initialText());
        hex.setBorder(BorderFactory.createTitledBorder("Extracted bytes"));
        JSplitPane reportSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, out, hex);
        reportSplit.setResizeWeight(0.7);
        views.addTab("Report", reportSplit);
        views.addTab("Chi-square attack", chart);
        JPanel heat = new JPanel(new BorderLayout());
        JPanel heatControls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        heatBar.enabled.setSelected(true);
        heatControls.add(heatBar);
        heatControls.add(new JLabel("Zoom:"));
        heatControls.add(ImageView.zoomBox(heatView));
        heat.add(heatControls, BorderLayout.NORTH);
        heat.add(new JScrollPane(heatView), BorderLayout.CENTER);
        heat.add(new JLabel(" Luminance blocks; red = coefficient pairs equalised (high chi-square p), blue = natural"
                + " histogram, none = too few coefficients. Hover shows the tile's p."), BorderLayout.SOUTH);
        views.addTab("Block heatmap", heat);
        add(views, BorderLayout.CENTER);
        showDescription();
        updateButtons();
    }

    /** True when the bytes start with a JPEG SOI marker followed by another marker. */
    static boolean isJpeg(byte[] d) {
        return d != null && d.length >= 3 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8 && (d[2] & 0xff) == 0xff;
    }

    private void updateButtons() {
        boolean enabled = !plugins.isEmpty() && !runner.running() && state != null && isJpeg(state.bytes());
        analyze.setEnabled(enabled);
        extract.setEnabled(enabled);
        String tip = plugins.isEmpty() ? "No JPEG DCT plugin installed"
                : runner.running() ? "A task is running"
                : state == null ? "Open a JPEG file first"
                : !isJpeg(state.bytes()) ? "The open file is not a JPEG" : null;
        fullCheck.setEnabled(enabled && lastAnalysis != null);
        analyze.setToolTipText(tip);
        extract.setToolTipText(tip);
    }

    private String initialText() {
        if (plugins.isEmpty()) {
            return "No JPEG DCT plugins installed.\n"
                    + "\n"
                    + "This tab is an extension point. Implement org.stegsuite.plugin.JpegStegoPlugin:\n"
                    + "\n"
                    + "    public interface JpegStegoPlugin {\n"
                    + "        String name();\n"
                    + "        default String description() { return \"\"; }\n"
                    + "        String analyze(byte[] jpegBytes) throws Exception;          // report text\n"
                    + "        byte[] extract(byte[] jpegBytes, String password) throws Exception; // payload or null\n"
                    + "    }\n"
                    + "\n"
                    + "Register it in META-INF/services/org.stegsuite.plugin.JpegStegoPlugin and start with:\n"
                    + "    java -cp stegsuite-1.0.0-all.jar:your-plugin.jar org.stegsuite.Main\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(plugins.size()).append(" plugin(s) loaded. Open a JPEG (drag & drop anywhere in the window), then:\n\n");
        for (JpegStegoPlugin p : plugins) sb.append("  ").append(p.name()).append(" - ").append(p.description()).append('\n');
        sb.append("\n  Analyze  decodes the Huffman-coded DCT coefficients and reports the frame and table\n")
          .append("           structure, coefficient histograms, LSB-replacement steganalysis (chi-square over\n")
          .append("           coefficient value pairs, parity balance) and the payloads found in the different\n")
          .append("           LSB extraction layouts.\n")
          .append("  Extract  returns the best payload found by those layouts (JSteg and variants), or the raw\n")
          .append("           JSteg LSB stream when nothing is recognised; a key additionally tries a custom\n")
          .append("           keyed order of the coefficient positions (not compatible with F5/OutGuess).\n")
          .append("\n  The Chi-square attack tab plots Westfeld's p-value curve with the estimated payload end;\n")
          .append("  the Block heatmap tab shows the pairs test per tile of 8x8 blocks over the image.\n")
          .append("\nArithmetic-coded and 12-bit JPEGs are reported as unsupported.\n");
        return sb.toString();
    }

    private void showDescription() {
        if (plugins.isEmpty()) { description.setText(" "); return; }
        JpegStegoPlugin p = plugins.get(box.getSelectedIndex());
        description.setText(" " + (p.description().isEmpty() ? p.name() : p.description()));
    }

    /** Result of an Analyze run. */
    private static final class AnalyzeResult {
        String report;
        DctAnalysis.Analysis analysis;
        BufferedImage preview;
    }

    private void run(boolean extracting) {
        if (state == null || plugins.isEmpty() || runner.running() || !isJpeg(state.bytes())) return;
        JpegStegoPlugin plugin = plugins.get(box.getSelectedIndex());
        // capture everything the task and its result need now: the file may change while it runs
        final byte[] data = state.bytes();
        final String fileName = state.file() == null ? "input" : state.file().getName();
        final String saveName = Ui.baseName(state) + "_" + plugin.name().replaceAll("[^A-Za-z0-9._-]+", "_") + ".bin";
        final String pw = password.getText();
        final String verb = extracting ? "Extracting" : "Analyzing";
        out.setPlainText(verb + " " + fileName + " with " + plugin.name() + "...");
        hex.setData(new byte[0], saveName);
        if (extracting) {
            runner.start(verb + " " + fileName, progress -> plugin.extract(data, pw), payload -> {
                if (payload == null || payload.length == 0) {
                    hex.setData(new byte[0], saveName);
                    out.setPlainText("No payload found. The Analyze report lists the DCT LSB streams that were\n"
                            + "searched, including the offsets at which flags, file headers or text appeared.\n");
                    return;
                }
                hex.setData(payload, saveName);
                String preview = TextHeuristics.isPrintable(payload[0] & 0xff)
                        ? "First bytes: \"" + TextHeuristics.preview(payload, 0, 120) + "\""
                        : "Content is not printable text; see the hex view below.";
                out.setPlainText(String.format("Extracted %d bytes from %s with %s (key %s).%n%s%n",
                        payload.length, fileName, plugin.name(), pw.isEmpty() ? "not used" : "used", preview));
            }, cause -> fail("Extraction", fileName, plugin, cause));
            return;
        }
        final boolean bundled = plugin instanceof JpegDctPlugin;
        runner.start(verb + " " + fileName, progress -> {
            AnalyzeResult r = new AnalyzeResult();
            if (bundled) {
                r.analysis = DctAnalysis.analyze(data, progress);      // decode shared via DctImageCache
                r.report = r.analysis.report;
                r.preview = org.stegsuite.core.jpeg.DctImageCache.preview(data);
            } else {
                r.report = plugin.analyze(data);
            }
            return r;
        }, r -> {
            lastAnalysis = r.analysis;
            lastAnalysisData = r.analysis == null ? null : data;
            lastFileName = fileName;
            showReport(String.valueOf(r.report));
            analyzedImage = r.analysis == null ? null : r.analysis.image;
            analyzedPreview = r.preview;
            chart.setResult(r.analysis == null ? null : r.analysis.attack, fileName);
            heatView.setImage(analyzedPreview, analyzedPreview);
            refreshHeatmap();
        }, cause -> fail("Analysis", fileName, plugin, cause));
    }

    private void fail(String what, String fileName, JpegStegoPlugin plugin, Throwable cause) {
        String msg = describe(cause);
        out.setPlainText(what + " of " + fileName + " failed:\n  " + msg + "\n");
        JOptionPane.showMessageDialog(this, msg, plugin.name() + " - error", JOptionPane.ERROR_MESSAGE);
    }

    /** Shows a report with the histogram and quantization charts under their sections. */
    private void showReport(String report) {
        java.util.Map<String, java.util.List<JComponent>> extras = ReportView.extras();
        if (lastAnalysis != null) {
            histogramChart.setData(lastAnalysis.componentIds, lastAnalysis.histograms, lastFileName);
            java.util.List<int[]> tables = new java.util.ArrayList<>();
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (DctImage.QuantTable q : lastAnalysis.image.quantTables()) {
                tables.add(q.values());
                labels.add("table " + q.id());
            }
            quantChart.setTables(tables, labels);
            extras.put("JPEG DCT analysis", java.util.Collections.<JComponent>singletonList(quantChart));
            extras.put("DCT coefficients", java.util.Collections.<JComponent>singletonList(histogramChart));
        }
        out.setReport(report, extras);
    }

    /** Re-runs the decoder cross-check over the whole image and replaces that report section. */
    private void runFullCrossCheck() {
        if (lastAnalysis == null || lastAnalysisData == null || runner.running()) return;
        final DctAnalysis.Analysis a = lastAnalysis;
        final byte[] data = lastAnalysisData;
        runner.start("Full decoder cross-check", progress -> {
            StringBuilder sb = new StringBuilder();
            DctAnalysis.crossCheck(sb, a.image, data, true);
            return sb.toString();
        }, section -> {
            if (lastAnalysis != a) return;
            String report = a.report;
            int i = report.indexOf("\n== Decoder cross-check ==");
            report = (i >= 0 ? report.substring(0, i) : report) + section;
            showReport(report);
            out.setExpanded("Decoder cross-check", true);
        }, cause -> JOptionPane.showMessageDialog(this, describe(cause), "Cross-check failed", JOptionPane.ERROR_MESSAGE));
    }

    /** Last bundled Analyze result (null if none / another plugin / another file). */
    DctAnalysis.Analysis lastAnalysis() { return lastAnalysis; }

    ReportView reportView() { return out; }

    /** Recomputes the DCT heatmap overlay (cheap: one pass over the luminance coefficients). */
    private void refreshHeatmap() {
        if (analyzedImage == null || analyzedPreview == null || !heatBar.enabled.isSelected()) {
            heatView.setOverlay(null, 1, 1, null);
            return;
        }
        int tile = heatBar.cellSize();
        long t0 = System.nanoTime();
        double[][] p = DctHeatmap.compute(analyzedImage, 0, tile, null);
        int[] cell = DctHeatmap.cellSize(analyzedImage, 0, tile);
        heatView.setOverlay(LsbHeatmap.toOverlay(p, heatBar.alpha()), cell[0], cell[1], p);
        StatusBar.message(String.format("DCT heatmap %dx%d tiles of %dx%d blocks in %d ms", p.length == 0 ? 0 : p[0].length,
                p.length, tile, tile, (System.nanoTime() - t0) / 1_000_000));
    }

    JTabbedPane views() { return views; }

    /** Unwraps ExecutionException (and similar wrappers) to the exception the plugin actually threw. */
    static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while ((c instanceof ExecutionException || c instanceof java.lang.reflect.InvocationTargetException)
                && c.getCause() != null) c = c.getCause();
        return c;
    }

    /** User facing text: the message for ordinary errors, the type for programming errors and OOM. */
    static String describe(Throwable t) {
        if (t instanceof OutOfMemoryError) return "Out of memory - the file is too large for the available heap (try -Xmx).";
        String m = t.getMessage();
        if (t instanceof java.io.IOException && m != null && !m.isEmpty()) return m;
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    @Override public void onFile(AppState s) {
        state = s;
        runner.cancel();                       // results of a running task belong to the previous file
        analyzedImage = null;
        analyzedPreview = null;
        lastAnalysis = null;
        lastAnalysisData = null;
        chart.setResult(null, null);
        heatView.setImage(null, null);
        heatView.setOverlay(null, 1, 1, null);
        hex.setData(new byte[0], Ui.baseName(s) + ".bin");
        out.setPlainText(!isJpeg(s == null ? null : s.bytes())
                ? "The open file is not a JPEG - the DCT tools only work on JPEG files.\n"
                : initialText());
        updateButtons();
    }
}
