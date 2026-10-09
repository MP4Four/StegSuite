package org.stegsuite.report;

import org.stegsuite.core.extract.ScanHit;
import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.stat.LsbHeatmap;
import org.stegsuite.core.stat.SpatialDetection;
import org.stegsuite.plugin.dct.ChiSquareAttack;
import org.stegsuite.plugin.dct.DctAnalysis;
import org.stegsuite.ui.Charts;
import org.stegsuite.ui.ChiSquareChart;
import org.stegsuite.ui.CoefficientHistogramChart;
import org.stegsuite.ui.QuantTableChart;
import org.stegsuite.ui.ReportView;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds {@link Report}s from {@link FileAnalysis} results: one file (all tab conclusions) or a batch scan. */
public final class ReportBuilder {
    private ReportBuilder() {}

    /** Width of the charts rendered into reports. */
    static final int CHART_W = 900;

    static String now() {
        return LocalDateTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    // ------------------------------------------------------------------ single file

    /** Report of one file: conclusions of every tab plus charts and images. */
    public static Report single(FileAnalysis.Result r) {
        Report rep = new Report();
        rep.title = "StegSuite report - " + r.name;
        rep.subject = r.file == null ? r.name : r.file.getAbsolutePath();
        rep.generated = now();
        rep.score = r.score;
        rep.level = r.level();
        overview(rep, r);
        rep.findings.addAll(r.reasons);

        Report.Section sum = new Report.Section("Summary");
        sum.id = "summary";
        StringBuilder t = new StringBuilder();
        t.append(String.format("Suspicion score %d/100 (%s)%n", r.score, r.level()));
        for (String reason : r.reasons) t.append("  ").append(reason).append('\n');
        t.append("\nThe score adds a fixed weight per indicator (see the README); it ranks files for manual review,\n"
                + "it is not a probability. Always confirm with an extracted payload.");
        sum.text(t.toString()).put("score", r.score).put("level", r.level()).put("reasons", new ArrayList<>(r.reasons));
        rep.sections.add(sum);

        rep.sections.add(bitPlanes(r));
        rep.sections.add(format(r));
        rep.sections.add(scan(r));
        if (r.kind == FileAnalysis.Kind.JPEG) rep.sections.add(dct(r));
        rep.sections.add(detection(r));
        Report.Section frames = new Report.Section("Frames");
        frames.id = "frames";
        frames.text(r.frames < 0 ? "Not an animated GIF/APNG (or the frames could not be read)." : r.frames + " frame(s)")
                .put("frames", r.frames < 0 ? null : r.frames);
        frames.open = false;
        rep.sections.add(frames);
        Report.Section notes = new Report.Section("Not included");
        notes.id = "notes";
        notes.text("Combiner and Stereogram are interactive tools (they need a second image or a chosen offset) and"
                + " have no automatic conclusion. The bit-plane views are summarised by the LSB images above.");
        notes.open = false;
        rep.sections.add(notes);
        return rep;
    }

    private static void overview(Report rep, FileAnalysis.Result r) {
        rep.summary.add(new String[]{"File", rep.subject});
        rep.summary.add(new String[]{"Type", r.type});
        rep.summary.add(new String[]{"Size", r.size + " bytes"});
        if (r.width > 0) rep.summary.add(new String[]{"Dimensions", r.width + " x " + r.height});
        rep.summary.add(new String[]{"Suspicion score", r.score + "/100 (" + r.level() + ")"});
        if (r.format != null && r.format.jpegQuality > 0)
            rep.summary.add(new String[]{"JPEG quality", (r.format.jpegQualityExact ? "" : "~") + r.format.jpegQuality});
        rep.summary.add(new String[]{"Analysis time", r.millis + " ms"});
    }

    private static Report.Section bitPlanes(FileAnalysis.Result r) {
        Report.Section s = new Report.Section("Bit planes");
        s.id = "bitplanes";
        BufferedImage img = r.image;
        if (img == null) {
            s.text(r.thumbnail == null ? "The file could not be decoded as an image." : "Thumbnail only.");
            s.figure("Image", r.thumbnail);
            return s;
        }
        BufferedImage view = Charts.thumbnail(img, 480, 360);
        s.figure("Image (" + img.getWidth() + "x" + img.getHeight() + ")", view);
        s.figure("Least significant bits of R, G and B (random-looking noise in a region = possible LSB payload)",
                Charts.thumbnail(lsbImage(img), 480, 360));
        boolean lossless = r.kind == FileAnalysis.Kind.PNG || r.kind == FileAnalysis.Kind.BMP || r.kind == FileAnalysis.Kind.GIF;
        if (lossless && (long) img.getWidth() * img.getHeight() <= 50_000_000L) {
            int block = Math.max(8, Math.min(64, Math.max(img.getWidth(), img.getHeight()) / 24));
            double[][] p = LsbHeatmap.compute(img, block, null);
            BufferedImage over = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = over.createGraphics();
            g.drawImage(img, 0, 0, null);
            BufferedImage heat = LsbHeatmap.toOverlay(p, 0.55);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(heat, 0, 0, p.length == 0 ? 0 : p[0].length * block, p.length * block, null);
            g.dispose();
            s.figure("LSB heatmap, " + block + " px blocks (red = value pairs equalised, blue = natural)", Charts.thumbnail(over, 480, 360));
            s.text("LSB heatmap: per-block chi-square test of the value pairs (2i, 2i+1); mean p "
                    + String.format("%.2f", LsbHeatmap.mean(p, 0, p.length, 0, p.length == 0 ? 0 : p[0].length)) + ".");
            s.put("heatmapBlock", block).put("heatmapMeanP", LsbHeatmap.mean(p, 0, p.length, 0, p.length == 0 ? 0 : p[0].length));
        } else {
            s.text(lossless ? "Image too large for the heatmap." : "LSB heatmap not shown: lossy format (see JPEG DCT).");
        }
        return s;
    }

    static BufferedImage lsbImage(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                row[x] = ((p >> 16 & 1) * 0xff0000) | ((p >> 8 & 1) * 0xff00) | ((p & 1) * 0xff);
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    private static Report.Section format(FileAnalysis.Result r) {
        Report.Section s = new Report.Section("File format");
        s.id = "format";
        s.text(r.format == null ? "" : r.format.text.toString().trim());
        s.put("type", r.type).put("warnings", new ArrayList<>(r.formatWarnings)).put("embedded", new ArrayList<>(r.embedded))
                .put("rawFlags", new ArrayList<>(r.rawFlags));
        if (r.format != null) {
            s.put("trailingOffset", r.format.hasTrailing() ? r.format.trailingOffset : null)
                    .put("trailingLength", r.format.hasTrailing() ? r.format.trailingLength : 0L)
                    .put("jpegQuality", r.format.jpegQuality > 0 ? r.format.jpegQuality : null);
        }
        return s;
    }

    private static Report.Section scan(FileAnalysis.Result r) {
        Report.Section s = new Report.Section("Data extract / auto scan");
        s.id = "scan";
        List<Object> hits = new ArrayList<>();
        StringBuilder t = new StringBuilder();
        if (r.scanNote != null) t.append(r.scanNote).append('\n');
        else if (r.scanHits.isEmpty()) t.append("Quick auto scan: no flag, file signature or text found in the LSB layouts.\n");
        else t.append("Quick auto scan, best findings first:\n");
        for (ScanHit h : r.scanHits) {
            t.append(String.format("  %-5s %-28s %s%n", h.type(), h.config().label(), h.detail()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", h.type().name());
            m.put("config", h.config().label());
            m.put("detail", h.detail());
            m.put("offset", h.offset());
            m.put("score", h.score());
            hits.add(m);
        }
        s.text(t.toString().trim()).put("hits", hits).put("note", r.scanNote);
        return s;
    }

    private static Report.Section dct(FileAnalysis.Result r) {
        Report.Section s = new Report.Section("JPEG DCT");
        s.id = "dct";
        if (r.dct == null) {
            s.text(r.dctError == null ? "not analysed" : r.dctError);
            return s;
        }
        DctAnalysis.Analysis a = r.dct;
        StringBuilder t = new StringBuilder();
        t.append("Payload: ").append(a.payload == null ? "none found in the DCT LSB streams" : a.payload).append('\n');
        t.append("Histogram indicators: ").append(a.histogramVerdict).append('\n');
        t.append("Chi-square attack: ").append(a.attack.summary()).append('\n');
        t.append("Calibration: ").append(a.calibration == null ? "not available"
                : String.format("beta %.3f%s", a.calibration.beta, a.calibration.flagged() ? " - F5-style modification likely" : " - no sign of F5-style shrinkage")).append('\n');
        if (a.quality != null) t.append("Quality: ").append(a.quality).append('\n');
        if (a.crossCheck != null) t.append("Decoder cross-check: ").append(a.crossCheck).append('\n');
        s.text(t.toString().trim());
        ChiSquareAttack.Curve best = a.attack.best();
        s.put("payload", a.payload).put("histogramVerdict", a.histogramVerdict).put("histogramFlagged", a.histogramFlagged())
                .put("attackSummary", a.attack.summary()).put("attackEstimate", best.informative() ? best.estimate : null)
                .put("attackBytes", best.informative() && best.estimate > 0 ? best.estimatedBits / 8 : null)
                .put("calibrationBeta", a.calibration == null || Double.isNaN(a.calibration.beta) ? null : a.calibration.beta)
                .put("calibrationFlagged", a.calibration != null && a.calibration.flagged())
                .put("quality", a.quality == null ? null : a.quality.quality);
        ChiSquareChart chart = new ChiSquareChart();
        chart.setResult(a.attack, r.name);
        s.figure("Chi-square attack curve (Westfeld)", Charts.render(chart, CHART_W, 420));
        CoefficientHistogramChart hist = new CoefficientHistogramChart();
        hist.setData(a.componentIds, a.histograms, r.name);
        s.figure("AC coefficient histogram", Charts.render(hist, CHART_W, 300));
        List<int[]> tables = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (DctImage.QuantTable q : a.image.quantTables()) { tables.add(q.values()); labels.add("table " + q.id()); }
        QuantTableChart qc = new QuantTableChart();
        qc.setTables(tables, labels);
        s.figure("Quantization tables", Charts.render(qc, Math.max(CHART_W / 2, qc.getPreferredSize().width), qc.getPreferredSize().height));
        Report.Section full = new Report.Section("Full DCT report");
        full.open = false;
        for (ReportView.Section part : ReportView.parse(a.report)) {
            Report.Section c = new Report.Section(part.title).text(part.text);
            c.open = false;
            full.child(c);
        }
        s.child(full);
        return s;
    }

    private static Report.Section detection(FileAnalysis.Result r) {
        Report.Section s = new Report.Section("Detection (RS / SPA)");
        s.id = "detection";
        if (r.spatial == null) {
            s.text(r.spatialNote != null ? r.spatialNote : r.kind == FileAnalysis.Kind.JPEG
                    ? "Not applicable: RS/SPA model LSB replacement in uncompressed samples; see JPEG DCT."
                    : "Not computed (not a decodable lossless image).");
            s.open = r.kind != FileAnalysis.Kind.JPEG;
            return s;
        }
        StringBuilder t = new StringBuilder(r.spatial.verdict()).append("\n\n");
        t.append(String.format("  %-8s %10s %10s %10s%n", "channel", "RS", "SPA", "mean"));
        List<Object> ch = new ArrayList<>();
        for (SpatialDetection.ChannelResult c : r.spatial.channels) {
            t.append(String.format("  %-8s %9.1f%% %9.1f%% %9.1f%%%n", c.channel, 100 * c.rs.rate, 100 * c.spa.rate, 100 * c.combined()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", c.channel);
            m.put("rs", c.rs.rate);
            m.put("spa", c.spa.rate);
            m.put("mean", c.combined());
            ch.add(m);
        }
        for (String w : r.spatial.warnings) t.append("note: ").append(w).append('\n');
        s.text(t.toString().trim()).put("verdict", r.spatial.verdict()).put("channels", ch);
        return s;
    }

    // ------------------------------------------------------------------ batch

    /** Batch report: summary table (sorted by score) plus one detail section per file. */
    public static Report batch(File folder, List<FileAnalysis.Result> results, boolean thumbnails) {
        Report rep = new Report();
        rep.title = "StegSuite batch report - " + (folder == null ? "" : folder.getName());
        rep.subject = folder == null ? "" : folder.getAbsolutePath();
        rep.generated = now();
        List<FileAnalysis.Result> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> b.score != a.score ? Integer.compare(b.score, a.score) : a.name.compareTo(b.name));
        int high = 0, medium = 0, errors = 0;
        for (FileAnalysis.Result r : results) {
            if (r.error != null) errors++;
            else if (r.score >= 60) high++;
            else if (r.score >= 30) medium++;
        }
        rep.summary.add(new String[]{"Folder", rep.subject});
        rep.summary.add(new String[]{"Files", String.valueOf(results.size())});
        rep.summary.add(new String[]{"High (score >= 60)", String.valueOf(high)});
        rep.summary.add(new String[]{"Medium (30-59)", String.valueOf(medium)});
        rep.summary.add(new String[]{"Errors", String.valueOf(errors)});
        Report.Table table = new Report.Table();
        for (String c : new String[]{"File", "Type", "Size", "Score", "Level", "Findings", "Scan hit", "DCT", "RS/SPA"}) table.columns.add(c);
        int i = 0;
        for (FileAnalysis.Result r : sorted) {
            String id = "file" + (i++);
            String rel = BatchScanner.relative(folder, r.file);
            List<Object> row = new ArrayList<>();
            row.add(rel);
            row.add(r.kind.name());
            row.add(r.size);
            row.add(r.score);
            row.add(r.error != null ? "error" : r.level());
            row.add(String.join("; ", r.reasons));
            row.add(r.bestHit());
            row.add(r.kind == FileAnalysis.Kind.JPEG ? r.dctSummary() : null);
            double rate = r.spatialRate();
            row.add(Double.isNaN(rate) ? null : Math.round(rate * 1000) / 10.0);
            table.rows.add(row);
            table.rowLinks.add(id);
            table.thumbnails.add(thumbnails ? r.thumbnail : null);

            Report.Section s = new Report.Section(rel + " - score " + r.score);
            s.id = id;
            s.open = false;
            StringBuilder t = new StringBuilder();
            t.append(String.format("%s, %d bytes%s%n", r.type, r.size, r.width > 0 ? ", " + r.width + "x" + r.height : ""));
            if (r.error != null) t.append("Error: ").append(r.error).append('\n');
            t.append("Indicators:\n");
            for (String reason : r.reasons) t.append("  ").append(reason).append('\n');
            if (!r.formatWarnings.isEmpty()) {
                t.append("Format warnings:\n");
                for (String w : r.formatWarnings) t.append("  ").append(w).append('\n');
            }
            if (!r.embedded.isEmpty()) t.append("Embedded signatures: ").append(String.join(", ", r.embedded)).append('\n');
            if (!r.scanHits.isEmpty()) {
                t.append("Auto scan:\n");
                for (ScanHit h : r.scanHits) t.append(String.format("  %-5s %-24s %s%n", h.type(), h.config().label(), h.detail()));
            } else if (r.scanNote != null) t.append("Auto scan: ").append(r.scanNote).append('\n');
            if (r.dct != null) {
                t.append("DCT: ").append(r.dct.histogramVerdict).append('\n');
                t.append("Chi-square attack: ").append(r.dct.attack.summary()).append('\n');
                if (r.dct.calibration != null) t.append(String.format("Calibration beta: %.3f%n", r.dct.calibration.beta));
                if (r.dct.payload != null) t.append("DCT payload: ").append(r.dct.payload).append('\n');
            } else if (r.dctError != null) t.append(r.dctError).append('\n');
            if (r.spatial != null) t.append("RS/SPA: ").append(r.spatial.verdict()).append('\n');
            s.text(t.toString().trim());
            s.put("path", rel).put("kind", r.kind.name()).put("type", r.type).put("size", r.size).put("score", r.score)
                    .put("level", r.level()).put("error", r.error).put("reasons", new ArrayList<>(r.reasons))
                    .put("formatWarnings", new ArrayList<>(r.formatWarnings)).put("embedded", new ArrayList<>(r.embedded))
                    .put("scanHit", r.bestHit()).put("dct", r.kind == FileAnalysis.Kind.JPEG ? r.dctSummary() : null)
                    .put("spatialRate", Double.isNaN(rate) ? null : rate).put("millis", r.millis);
            rep.sections.add(s);
        }
        rep.table = table;
        return rep;
    }
}
