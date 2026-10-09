package org.stegsuite.plugin.dct;

import org.stegsuite.core.image.ImageUtil;
import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.DctImageCache;
import org.stegsuite.core.jpeg.JpegDctDecoder;
import org.stegsuite.core.jpeg.JpegQuality;
import org.stegsuite.core.jpeg.StandardTables;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.stegsuite.core.stat.Progress;

/**
 * Human readable DCT-domain report: frame/table structure, quantized coefficient statistics,
 * LSB-replacement steganalysis (Westfeld/Pfitzmann chi-square over coefficient pairs, parity balance),
 * the payload search over the LSB extraction modes and a cross-check of our own reconstruction against
 * the JDK decoder.
 */
public final class DctAnalysis {
    private DctAnalysis() {}

    /** Histogram window for the signed coefficient values (larger magnitudes are counted in the tail). */
    private static final int BIN = 32;

    /** Number of leading coefficients used by the sliding-window variant of the chi-square test. */
    static final int PREFIX_WINDOW = 300;

    public static String report(byte[] jpeg) throws IOException {
        return analyze(jpeg, Progress.NONE).report;
    }

    /** How the reconstruction is compared with the JDK decoder. */
    public enum CrossCheck {
        /** Skipped (batch scans). */
        NONE,
        /** First rows only (at most {@link #SAMPLED_ROWS}); the JDK decoder stops early. Default of Analyze. */
        SAMPLED,
        /** The whole image (on demand). */
        FULL
    }

    /** Rows compared by the sampled decoder cross-check. */
    public static final int SAMPLED_ROWS = 256;

    /** Everything the DCT tab shows: the text report plus the data behind the charts and the heatmap. */
    public static final class Analysis {
        public final String report;
        public final DctImage image;
        public final ChiSquareAttack.Result attack;
        /** null when the calibration could not be computed (see the report). */
        public final Calibration.Result calibration;
        /** Frame component ids, parallel to {@link #histograms}. */
        public final int[] componentIds;
        /** Per component: counts of the AC coefficient values -HISTOGRAM_RANGE..HISTOGRAM_RANGE (index v + range). */
        public final long[][] histograms;
        /** Conclusion of the pairs test (Summary "Histogram indicators"). */
        public final String histogramVerdict;
        /** Best payload found by the extraction layouts ("FLAG ... via ..."), or null. */
        public final String payload;
        /** Score of that payload (flag 1000+, file 300-600, text 60-400), 0 if none. */
        public final int payloadScore;
        /** Quality estimate of the luminance quantization table, or null. */
        public final JpegQuality.Estimate quality;
        /** Decoder cross-check line, or null when skipped. */
        public final String crossCheck;

        Analysis(String report, DctImage image, ChiSquareAttack.Result attack, Calibration.Result calibration,
                 int[] componentIds, long[][] histograms, String histogramVerdict, String payload, int payloadScore,
                 JpegQuality.Estimate quality, String crossCheck) {
            this.report = report;
            this.image = image;
            this.attack = attack;
            this.calibration = calibration;
            this.componentIds = componentIds;
            this.histograms = histograms;
            this.histogramVerdict = histogramVerdict;
            this.payload = payload;
            this.payloadScore = payloadScore;
            this.quality = quality;
            this.crossCheck = crossCheck;
        }

        /** True when the pairs test says the histogram is equalised like JSteg output. */
        public boolean histogramFlagged() { return histogramVerdict.contains("consistent with JSteg-style"); }

        public boolean histogramWeak() { return histogramVerdict.startsWith("weak sign"); }
    }

    /** Value range of {@link Analysis#histograms}. */
    public static final int HISTOGRAM_RANGE = BIN;

    /**
     * Full analysis with progress reporting and cancellation; the coefficients come from (and stay in) the
     * shared {@link DctImageCache}, the reconstruction is cross-checked on the first rows.
     *
     * @throws Progress.Cancelled when {@code progress} requests cancellation
     */
    public static Analysis analyze(byte[] jpeg, Progress progress) throws IOException {
        DctImage img = DctImageCache.get(jpeg);
        return analyze(jpeg, img, CrossCheck.SAMPLED, progress);
    }

    /**
     * Full analysis of already decoded coefficients.
     *
     * @param img   the decoded file (e.g. from {@link DctImageCache} or {@link JpegDctDecoder#decode})
     * @param check decoder cross-check mode
     */
    public static Analysis analyze(byte[] jpeg, final DctImage img, CrossCheck check, Progress progress) throws IOException {
        if (progress == null) progress = Progress.NONE;
        progress.update(0.15);
        Progress.check(progress);
        // the calibration (recompression through ImageIO) is independent of everything else: run it alongside
        CompletableFuture<Object> calibrationTask = CompletableFuture.supplyAsync(() -> {
            try {
                return Calibration.analyze(img);
            } catch (IOException | RuntimeException e) {
                return e.getMessage() == null ? e.toString() : e.getMessage();
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append("== JPEG DCT analysis ==\n");
        structure(sb, img, jpeg.length);
        Stats all = new Stats();
        Stats[] perComponent = new Stats[img.components().size()];
        for (int i = 0; i < perComponent.length; i++) perComponent[i] = new Stats();
        collect(img, all, perComponent);
        coefficients(sb, img, all, perComponent);
        steganalysis(sb, img, all, perComponent);
        progress.update(0.25);
        Progress.check(progress);
        final Progress outer = progress;
        ChiSquareAttack.Result attack = ChiSquareAttack.analyze(img, new Progress() {
            @Override public void update(double f) { outer.update(0.25 + 0.25 * f); }
            @Override public boolean cancelled() { return outer.cancelled(); }
        });
        attackSection(sb, attack);
        progress.update(0.5);
        Progress.check(progress);
        StringBuilder payloadText = new StringBuilder();
        DctPayloadSearch.Hit best = payloads(payloadText, img);
        progress.update(0.75);
        Progress.check(progress);
        Calibration.Result calibration = null;
        String calibrationError = null;
        Object cal;
        try {
            cal = calibrationTask.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Progress.Cancelled();
        } catch (ExecutionException e) {
            cal = String.valueOf(e.getCause());
        }
        if (cal instanceof Calibration.Result) calibration = (Calibration.Result) cal;
        else calibrationError = String.valueOf(cal);
        calibrationSection(sb, calibration, calibrationError);
        sb.append(payloadText);
        progress.update(0.85);
        Progress.check(progress);
        String histogram = summary(sb, img, all, perComponent, best, attack, calibration);
        String cross = null;
        if (check != CrossCheck.NONE) cross = crossCheck(sb, img, jpeg, check == CrossCheck.FULL);
        progress.update(1.0);
        int[] ids = new int[perComponent.length];
        long[][] hist = new long[perComponent.length][];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = img.components().get(i).id();
            hist[i] = perComponent[i].histogram.clone();
        }
        JpegQuality.Estimate quality = null;
        if (!img.quantTables().isEmpty()) {
            DctImage.QuantTable q0 = img.quantTableOf(img.components().get(0));
            quality = JpegQuality.of((q0 != null ? q0 : img.quantTables().get(0)).values());
        }
        return new Analysis(sb.toString(), img, attack, calibration, ids, hist, histogram,
                best == null ? null : best.kind + " " + best.detail + " via " + best.mode.label(),
                best == null ? 0 : best.score, quality, cross);
    }

    // ------------------------------------------------------------------ chi-square attack and calibration

    private static void attackSection(StringBuilder sb, ChiSquareAttack.Result attack) {
        sb.append("\n== Chi-square attack (Westfeld) ==\n");
        sb.append("p-value of the pairs test over the first x% of the non-zero AC coefficients in JSteg read order;\n");
        sb.append("p stays near 1 inside a sequential payload and drops to 0 after it (see the chart tab).\n");
        sb.append(String.format("  %-18s %8s %8s %8s %8s %8s   estimate%n", "pairing", "10%", "25%", "50%", "75%", "100%"));
        for (ChiSquareAttack.Curve c : new ChiSquareAttack.Curve[]{attack.twosComplement, attack.magnitude}) {
            sb.append(String.format("  %-18s", c.pairing == ChiSquareAttack.Pairing.TWOS_COMPLEMENT ? "two's complement" : "magnitude"));
            for (double x : new double[]{0.10, 0.25, 0.50, 0.75, 1.0}) {
                int i = Math.max(0, Math.min(c.p.length - 1, (int) Math.round(x * c.p.length) - 1));
                sb.append(String.format(" %8s", Double.isNaN(c.p[i]) ? "-" : String.format("%.4f", c.p[i])));
            }
            sb.append(String.format("   %.1f%% (~%d bytes)%s%n", 100 * c.estimate, c.estimatedBits / 8,
                    c.informative() ? "" : String.format(", control p %.2f: flat histogram", c.controlP)));
        }
        sb.append("Result: ").append(attack.summary()).append('\n');
    }

    private static void calibrationSection(StringBuilder sb, Calibration.Result cal, String error) {
        sb.append("\n== Calibration (Fridrich et al., F5 detection) ==\n");
        if (cal == null) {
            sb.append("  not available: ").append(error).append('\n');
            return;
        }
        sb.append("Luminance decompressed, cropped by 4x4 pixels and recompressed with the same quantization table;\n");
        sb.append("the result estimates the cover histogram. beta = estimated fraction of non-zero AC coefficients\n");
        sb.append("modified by F5-style shrinkage (clean images: close to 0).\n");
        String[] names = {"(0,1)", "(1,0)", "(1,1)"};
        for (int m = 0; m < names.length; m++)
            sb.append(String.format("  mode %s: h(0..2) %.0f / %.0f / %.0f, calibrated %.0f / %.0f / %.0f, beta %s%n", names[m],
                    cal.observed[m][0], cal.observed[m][1], cal.observed[m][2],
                    cal.calibrated[m][0], cal.calibrated[m][1], cal.calibrated[m][2],
                    Double.isNaN(cal.betaPerMode[m]) ? "-" : String.format("%.3f", cal.betaPerMode[m])));
        sb.append(String.format("  beta = %s -> %s%n", Double.isNaN(cal.beta) ? "-" : String.format("%.3f", cal.beta),
                calibrationVerdict(cal)));
        PairTest ref = cal.calibratedStats.best();
        sb.append(String.format("  calibrated luminance pairs test (cover reference): %s pairs, chi2/df %.2f%n", ref.name, ref.perDf));
        if (cal.note != null) sb.append("  note: ").append(cal.note).append('\n');
    }

    static String calibrationVerdict(Calibration.Result cal) {
        if (Double.isNaN(cal.beta)) return "no estimate (too few low-frequency coefficients)";
        if (cal.flagged())
            return String.format("F5-style modification likely (~%.0f%% of the non-zero AC coefficients changed)", 100 * cal.beta);
        return "no sign of F5-style shrinkage";
    }

    // ------------------------------------------------------------------ structure

    private static void structure(StringBuilder sb, DctImage img, int fileSize) {
        sb.append(String.format("File: %d bytes, %s%s, %d-bit, %dx%d, %d component(s)%n",
                fileSize, img.frameType(), img.progressive() ? " (" + img.scanCount() + " scans)" : "",
                img.precision(), img.width(), img.height(), img.components().size()));
        for (DctImage.Component c : img.components())
            sb.append(String.format("  component %d: sampling %dx%d, quant table %d, %d blocks, plane %dx%d (%d x %d used)%n",
                    c.id(), c.h(), c.v(), c.quantId(), c.blockCount(), c.sampleWidth(), c.sampleHeight(),
                    c.downsampledWidth(), c.downsampledHeight()));
        List<DctImage.QuantTable> tables = img.quantTables();
        sb.append("Quantization tables: ").append(tables.size()).append('\n');
        for (DctImage.QuantTable q : tables) {
            sb.append(String.format("  table %d (%d-bit): %s%n", q.id(), q.precision() == 0 ? 8 : 16, qualityText(q.values())));
            int[] v = q.values();
            for (int row = 0; row < 8; row++) {
                sb.append("   ");
                for (int col = 0; col < 8; col++) sb.append(String.format("%5d", v[row * 8 + col]));
                sb.append('\n');
            }
        }
        sb.append("Huffman tables: ").append(img.huffmanTables().size()).append('\n');
        for (DctImage.HuffmanInfo h : img.huffmanTables())
            sb.append(String.format("  %s %d: %s%s%n", h.isDc() ? "DC" : "AC", h.id(), h.table().describe(),
                    h.standardName() == null ? "" : " (" + h.standardName() + ")"));
        sb.append("Restart interval: ").append(img.restartInterval() == 0 ? "none" : img.restartInterval() + " MCU").append('\n');
        if (img.jfifVersion() != null) sb.append("JFIF version: ").append(img.jfifVersion()).append('\n');
        if (img.adobeTransform() >= 0) sb.append("Adobe APP14 colour transform: ").append(img.adobeTransform()).append('\n');
        for (String comment : img.comments()) sb.append("Comment: ").append(comment).append('\n');
        for (String warning : img.warnings()) sb.append("[!] ").append(warning).append('\n');
    }

    /** Says whether a table is an Annex K table at a specific IJG quality, or estimates the quality. */
    private static String qualityText(int[] table) {
        return org.stegsuite.core.jpeg.JpegQuality.of(table).toString();
    }

    /** Median of the quality implied by each coefficient relative to the Annex K luminance table. */
    static int estimateQuality(int[] table) {
        return org.stegsuite.core.jpeg.JpegQuality.estimate(table);
    }

    // ------------------------------------------------------------------ coefficient statistics

    private static void collect(DctImage img, Stats all, Stats[] perComponent) {
        for (int ci = 0; ci < img.components().size(); ci++) {
            DctImage.Component c = img.components().get(ci);
            for (int b = 0; b < c.blockCount(); b++) {
                perComponent[ci].add(c.coefficient(b, 0), true);
                all.add(c.coefficient(b, 0), true);
                for (int k = 1; k < 64; k++) {
                    int v = c.zigZagCoefficient(b, k);
                    perComponent[ci].add(v, false);
                    all.add(v, false);
                }
            }
        }
        for (Stats s : perComponent) s.finish();
        all.finish();
    }

    private static void coefficients(StringBuilder sb, DctImage img, Stats all, Stats[] perComponent) {
        sb.append("\n== DCT coefficients ==\n");
        for (int ci = 0; ci < perComponent.length; ci++) {
            DctImage.Component c = img.components().get(ci);
            Stats s = perComponent[ci];
            sb.append(String.format("component %d (%dx%d sampling):%n", c.id(), c.h(), c.v()));
            sb.append(String.format("  %d coefficients, %s zero, %s non-zero%n",
                    s.total, percent(s.zeros, s.total), percent(s.total - s.zeros, s.total)));
            sb.append(String.format("  AC non-zero: %d of %d (%s), DC non-zero: %d%n",
                    s.acNonZero, s.total - c.blockCount(), percent(s.acNonZero, s.total - c.blockCount()), s.dcNonZero));
            if (s.acNonZero > 0)
                sb.append(String.format("  mean |value| of non-zero AC coefficients: %.2f, coefficients of +-1: %d%n",
                        s.sumAbs / (double) s.acNonZero, s.ones));
            histogram(sb, s);
        }
        sb.append(String.format("all components: %d coefficients, %s zero, AC non-zero %d (capacity %.1f KiB, %.1f KiB without +-1)%n",
                all.total, percent(all.zeros, all.total), all.acNonZero,
                all.acNonZero / 8.0 / 1024, (all.acNonZero - all.ones) / 8.0 / 1024));
    }

    private static void histogram(StringBuilder sb, Stats s) {
        long[] counts = new long[9];
        long max = 1;
        long rest = s.acNonZero;
        for (int m = 1; m <= 8; m++) {
            counts[m] = s.magnitude(m);
            rest -= counts[m];
            max = Math.max(max, counts[m]);
        }
        max = Math.max(max, rest);
        sb.append("  non-zero AC value histogram (per magnitude, both signs):\n");
        for (int m = 1; m <= 8; m++) bar(sb, "    |v| = " + m, counts[m], max);
        bar(sb, "    |v| > 8", rest, max);
        sb.append(String.format("    signed: %d/%d/%d at v=-1/0/+1 (zeros are excluded from LSB replacement)%n",
                s.count(-1), s.zeros, s.count(1)));
    }

    private static void bar(StringBuilder sb, String label, long count, long max) {
        int width = max <= 0 ? 0 : (int) Math.round(40.0 * count / max);
        sb.append(String.format("%-12s %8d |%s%n", label, count, repeat('#', width)));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    // ------------------------------------------------------------------ steganalysis

    private static void steganalysis(StringBuilder sb, DctImage img, Stats all, Stats[] perComponent) {
        sb.append("\n== Steganalysis (DCT domain) ==\n");
        sb.append("Westfeld/Pfitzmann chi-square over pairs of values of the non-zero AC coefficients:\n");
        sb.append("LSB replacement makes the two counts of a pair equal, so a low chi2/df (about 1) means the\n");
        sb.append("histogram is consistent with hidden random LSBs. Only pairs with at least " + MIN_PAIR_COUNT
                + " coefficients are\n");
        sb.append("used (sparse pairs inflate chi2). This test detects JSteg-style sequential LSB replacement only;\n");
        sb.append("F5 and OutGuess are designed to resist it. The control column repeats the test on shifted pairs\n");
        sb.append("(2i+1, 2i+2); when those are equal too the histogram is just flat and the test is inconclusive.\n");
        sb.append("magnitude pairs {|v|=2i, 2i+1} (LSB of the magnitude, sign kept; JSteg variants skipping +-1):\n");
        for (int ci = 0; ci < perComponent.length; ci++)
            appendPairLine(sb, "component " + img.components().get(ci).id(), perComponent[ci].magnitudePairs);
        appendPairLine(sb, "all components", all.magnitudePairs);
        sb.append("two's complement pairs (-4,-3), (-2,-1), (2,3), (4,5) ... (original JSteg: LSB of the stored\n");
        sb.append("value, skipping only 0 and +1):\n");
        for (int ci = 0; ci < perComponent.length; ci++)
            appendPairLine(sb, "component " + img.components().get(ci).id(), perComponent[ci].twosComplementPairs);
        appendPairLine(sb, "all components", all.twosComplementPairs);
        sb.append(String.format("LSB parity of the non-zero AC coefficients with |v| > 1: even %s, odd %s (z = %.2f, 0 is balanced)%n",
                percent(all.even, all.even + all.odd), percent(all.odd, all.even + all.odd), all.parityZ()));
        sb.append(String.format("F5 (decrement) shape check: coefficients of |v|=1 vs |v|=2: %d vs %d (ratio %s)%n",
                all.magnitude(1), all.magnitude(2),
                all.magnitude(2) == 0 ? "-" : String.format("%.2f", all.magnitude(1) / (double) all.magnitude(2))));
        sb.append("  ").append(onesRatioNote(all.magnitude(1), all.magnitude(2))).append('\n');
        Stats prefix = prefixStats(img);
        if (prefix != null) {
            sb.append(String.format("leading window (first %d usable coefficients, where sequential embedding starts):%n",
                    PREFIX_WINDOW));
            PairTest t = prefix.best();
            sb.append(String.format("  %s pairs %d, chi2/df %s, parity z %s%s%n", t.name, t.pairs,
                    String.format("%.2f", t.perDf), String.format("%.2f", prefix.parityZ()),
                    t.perDf < 2.5 && Math.abs(prefix.parityZ()) < 2.5
                            ? " - equalised: a short sequential payload would look like this, but so can a locally smooth"
                            + " coefficient distribution, so treat it as a hint, not as a result" : ""));
        }
    }

    private static void appendPairLine(StringBuilder sb, String label, PairTest t) {
        sb.append(String.format("  %s: pairs %d, chi2 %.1f, chi2/df %s, p %.4f, control chi2/df %s%s%n",
                label, t.pairs, t.chi2, t.pairs == 0 ? "-" : String.format("%.2f", t.perDf), t.p,
                t.control == null || t.control.pairs == 0 ? "-" : String.format("%.2f", t.control.perDf),
                t.informative() ? "" : " (flat histogram - inconclusive)"));
    }

    /** Short interpretation of the |v|=1 / |v|=2 ratio (no cover estimate is available, so it is only a hint). */
    static String onesRatioNote(long ones, long twos) {
        if (ones + twos < 200) return "(too few coefficients to interpret the ratio)";
        if (twos == 0) return "(no coefficients of |v|=2 - unusual, inspect the histogram)";
        double r = ones / (double) twos;
        String base = "natural JPEGs usually show ratio > 1; F5 decrements 2 -> 1 and raises it; only a hint"
                + " without a calibrated cover estimate";
        if (r < 1.0) return "(ratio < 1 is atypical for camera images - flat/noisy or synthetic content; " + base + ")";
        if (r > 4.0) return "(high ratio: possible F5-style decrement, or just a smooth image / coarse quantization; " + base + ")";
        return "(usual range; " + base + ")";
    }

    /** Statistics of the first coefficients in extraction order, i.e. the sliding-window variant of the test. */
    private static Stats prefixStats(DctImage img) {
        Stats prefix = new Stats();
        int n = 0;
        for (int ci = 0; ci < img.components().size() && n < PREFIX_WINDOW; ci++) {
            DctImage.Component c = img.components().get(ci);
            for (int b = 0; b < c.blockCount() && n < PREFIX_WINDOW; b++)
                for (int k = 1; k < 64 && n < PREFIX_WINDOW; k++) {
                    int v = c.zigZagCoefficient(b, k);
                    if (v == 0) continue;               // the layouts that can carry a payload skip zeros
                    prefix.add(v, false);
                    n++;
                }
        }
        prefix.finish();
        return prefix.best().pairs >= 3 ? prefix : null;
    }

    /** Conclusion of the report: what the extraction found and what the histogram says about it. */
    private static String summary(StringBuilder sb, DctImage img, Stats all, Stats[] perComponent, DctPayloadSearch.Hit best,
                                ChiSquareAttack.Result attack, Calibration.Result calibration) {
        String histogram = verdict(img, all, perComponent, calibration == null ? null : calibration.calibratedStats);
        sb.append("\n== Summary ==\n");
        sb.append("Payload: ").append(best == null ? "none found in the DCT LSB streams"
                : best.kind + " " + best.detail + " via " + best.mode.label()).append('\n');
        sb.append("Histogram indicators: ").append(histogram).append('\n');
        if (best != null && !histogram.contains("consistent with JSteg-style"))
            sb.append("  (a payload of only a few bytes barely moves the coefficient histogram, so the indicators\n"
                    + "   can stay inconspicuous even though the extraction above found something)\n");
        sb.append("Chi-square attack: ").append(attack.summary()).append('\n');
        sb.append("Calibration: ").append(calibration == null ? "not available" : String.format("beta %s - %s",
                Double.isNaN(calibration.beta) ? "-" : String.format("%.3f", calibration.beta), calibrationVerdict(calibration))).append('\n');
        JstegHeader.Header header = DctExtraction.jstegHeader(img);
        sb.append("JSteg length header: ").append(header == null ? "not plausible"
                : "plausible (" + header + ") - only meaningful if a payload is recognised behind it").append('\n');
        return histogram;
    }

    /**
     * Conservative wording: the histogram indicators are evidence, not proof, and a payload hidden in one
     * component only would be diluted in the frame wide numbers, so the strongest component wins.
     */
    static String verdict(DctImage img, Stats all, Stats[] perComponent) {
        return verdict(img, all, perComponent, null);
    }

    /**
     * @param calibrated statistics of the calibrated luminance plane (estimate of the cover), or null; when
     *                   the cover estimate is just as equalised, equal pairs are not evidence of embedding
     */
    static String verdict(DctImage img, Stats all, Stats[] perComponent, Stats calibrated) {
        Stats strongest = all;
        String where = "all components";
        for (int i = 0; i < perComponent.length; i++) {
            Stats s = perComponent[i];
            PairTest cand = s.best(), cur = strongest.best();
            boolean better = cand.informative() != cur.informative() ? cand.informative() : cand.perDf < cur.perDf;
            if (cand.pairs >= 8 && s.acNonZero >= 200 && better) {
                strongest = s;
                where = "component " + img.components().get(i).id();
            }
        }
        if (strongest.acNonZero < 200) return "too few non-zero AC coefficients for a meaningful histogram test";
        PairTest t = strongest.best();
        String detail = where + ", " + t.name + " pairs, chi2/df " + String.format("%.2f", t.perDf);
        if (!t.informative())
            return "inconclusive - the coefficient histogram is flat (shifted control pairs are equal too, " + detail
                    + ", control chi2/df " + String.format("%.2f", t.control.perDf) + "), so the pairs test cannot"
                    + " detect JSteg-style LSB replacement here";
        PairTest ref = calibrated == null || calibrated.acNonZero < 200 ? null : calibrated.best();
        String refText = ref == null || ref.pairs == 0 ? "" : "; calibrated cover estimate chi2/df " + String.format("%.2f", ref.perDf);
        boolean equalised = t.pairs >= 8 && t.perDf < 2.5 && Math.abs(strongest.parityZ()) < 2.5;
        if (equalised && ref != null && ref.pairs >= 8 && ref.perDf < 2.5)
            return "inconclusive - the pairs are equalised (" + detail + ") but so is the calibrated cover estimate"
                    + refText + ", so the equal pairs come from the content, not from embedding";
        detail += refText;
        if (equalised)
            return "coefficient pairs are equalised (" + detail + ", parity z " + String.format("%.2f", strongest.parityZ())
                    + ") - consistent with JSteg-style sequential LSB replacement (this test does not detect"
                    + " F5 or OutGuess, which are designed to resist it)";
        if (t.pairs >= 8 && t.perDf < 5.0)
            return "weak sign of JSteg-style LSB replacement (" + detail + ")";
        return "no sign of JSteg-style LSB replacement in the coefficient histogram (" + detail
                + "; F5/OutGuess are not ruled out by this test)";
    }

    // ------------------------------------------------------------------ extraction results

    private static DctPayloadSearch.Hit payloads(StringBuilder sb, DctImage img) {
        sb.append("\n== DCT LSB extraction attempts ==\n");
        final DctExtraction.Mode[] modes = DctExtraction.SEQUENTIAL_MODES;
        final int[] lengths = new int[modes.length];
        @SuppressWarnings("unchecked")
        final List<DctPayloadSearch.Hit>[] found = new List[modes.length];
        // the layouts are independent: extract and search them in parallel, report in the fixed order
        java.util.stream.IntStream.range(0, modes.length).parallel().forEach(i -> {
            byte[] stream = DctExtraction.extract(img, modes[i], "");
            lengths[i] = stream.length;
            found[i] = DctPayloadSearch.search(modes[i], stream);
        });
        DctPayloadSearch.Hit best = null;
        for (int i = 0; i < modes.length; i++) {
            appendAttempt(sb, modes[i], lengths[i], found[i]);
            for (DctPayloadSearch.Hit h : found[i]) if (best == null || h.score > best.score) best = h;
        }
        sb.append(String.format("  %-62s %7s        only tried by Extract with a password%n",
                DctExtraction.Mode.KEYED.label(), "-"));
        return best;
    }

    private static void appendAttempt(StringBuilder sb, DctExtraction.Mode mode, int length, List<DctPayloadSearch.Hit> hits) {
        String result = "no flag/file/text found";
        if (!hits.isEmpty()) {
            StringBuilder s = new StringBuilder();
            for (DctPayloadSearch.Hit h : hits) {
                if (s.length() > 0) s.append("; ");
                s.append(h.kind).append(' ').append(h.detail);
            }
            result = s.toString();
        }
        sb.append(String.format("  %-62s %7d bytes  %s%n", mode.label(), length, result));
    }

    // ------------------------------------------------------------------ decoder cross-check

    /**
     * Compares our reconstruction with the JDK decoder: the whole image, or only the first
     * {@link #SAMPLED_ROWS} rows (the JDK decoder is asked for that region only). Returns the result line.
     */
    public static String crossCheck(StringBuilder sb, DctImage img, byte[] jpeg, boolean full) {
        sb.append("\n== Decoder cross-check ==\n");
        String line;
        if (img.precision() != 8 || (img.components().size() != 1 && img.components().size() != 3)) {
            line = "not available for this component layout (only 8-bit grayscale and YCbCr/RGB are reconstructed)";
        } else {
            BufferedImage mine = DctImageCache.previewOf(jpeg, img);
            if (mine == null) {
                line = "not available (missing quantization table)";
            } else {
                int rows = full ? mine.getHeight() : Math.min(mine.getHeight(), SAMPLED_ROWS);
                BufferedImage ref = jdkDecode(jpeg, full ? -1 : rows);
                if (ref == null) line = "the JDK JPEG decoder could not read this file, our coefficient decoder could";
                else if (ref.getWidth() != mine.getWidth() || ref.getHeight() < rows) line = "size mismatch against the JDK decode";
                else line = compare(mine, ref, rows, full);
            }
        }
        sb.append("  ").append(line).append('\n');
        return line;
    }

    private static String compare(BufferedImage mine, BufferedImage ref, int rows, boolean full) {
        int w = mine.getWidth();
        int[] a = new int[w], b = new int[w];
        long sum = 0, n = 0;
        int max = 0;
        for (int y = 0; y < rows; y++) {
            mine.getRGB(0, y, w, 1, a, 0, w);           // bulk row reads, no per-pixel getRGB
            ref.getRGB(0, y, w, 1, b, 0, w);
            for (int x = 0; x < w; x++) {
                int pa = a[x], pb = b[x];
                for (int shift = 16; shift >= 0; shift -= 8) {
                    int d = Math.abs(((pa >> shift) & 0xff) - ((pb >> shift) & 0xff));
                    sum += d;
                    if (d > max) max = d;
                }
                n += 3;
            }
        }
        double mean = sum / (double) n;
        return String.format("inverse DCT vs the JDK decoder (%s): mean |delta| %.3f, max %d over %d samples%s",
                full ? "whole image" : "first " + rows + " rows, use 'Full cross-check' for all", mean, max, n,
                mean < 1.5 ? " (decoders agree)" : mean < 6.0 ? " (chroma upsampling/rounding only)"
                        : " (DECODERS DISAGREE - the file may be malformed)");
    }

    /** JDK decode converted to ARGB like {@link ImageUtil#decode}; only the first rows when rows &gt; 0. */
    private static BufferedImage jdkDecode(byte[] jpeg, int rows) {
        if (rows <= 0) return ImageUtil.decode(jpeg);
        try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(jpeg))) {
            java.util.Iterator<javax.imageio.ImageReader> it = javax.imageio.ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            javax.imageio.ImageReader r = it.next();
            try {
                r.setInput(in);
                int w = r.getWidth(0);
                javax.imageio.ImageReadParam p = r.getDefaultReadParam();
                p.setSourceRegion(new java.awt.Rectangle(0, 0, w, Math.min(rows, r.getHeight(0))));
                return ImageUtil.toArgb(r.read(0, p));
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String percent(long part, long total) {
        return total == 0 ? "0.00%" : String.format("%.2f%%", 100.0 * part / total);
    }

    /** Minimum a + b of a value pair to enter the chi-square sum (Westfeld/Pfitzmann). */
    static final int MIN_PAIR_COUNT = 10;

    /** Minimum chi2/df of the shifted control pairs for the pairs test to be meaningful. */
    static final double CONTROL_MIN_PER_DF = 5.0;

    /** Chi-square pairs-of-values test for one pairing convention. */
    static final class PairTest {
        final String name;
        double chi2;
        int pairs;
        double perDf;
        double p;

        /**
         * The same test over the shifted pairs (2i+1, 2i+2). LSB replacement equalises only the real pairs;
         * if the shifted pairs are just as equal, the histogram is simply flat (e.g. noise at quality 100)
         * and a low chi2/df says nothing about embedding.
         */
        PairTest control;

        PairTest(String name) { this.name = name; }

        PairTest withControl(PairTest c) {
            control = c;
            return this;
        }

        /** True when the histogram is steep enough that equal pairs would stand out (control chi2/df >= 5). */
        boolean informative() {
            return control == null || (control.pairs >= 3 && control.perDf >= CONTROL_MIN_PER_DF);
        }

        void add(long a, long b) {
            if (a + b < MIN_PAIR_COUNT) return;
            chi2 += (double) (a - b) * (a - b) / (a + b);
            pairs++;
        }

        PairTest done() {
            perDf = pairs == 0 ? 0 : chi2 / pairs;
            p = ChiSquare.pValue(chi2, pairs);
            return this;
        }
    }

    /** Coefficient histogram of one component (or of the whole frame). */
    static final class Stats {
        final long[] histogram = new long[2 * BIN + 1];
        long total;
        long zeros;
        long acNonZero;
        long dcNonZero;
        long even;
        long odd;
        long ones;
        long sumAbs;
        long tail;
        /** Pairs {|v|=2i, 2i+1}. */
        PairTest magnitudePairs;
        /** Pairs (2i, 2i+1) of the stored two's complement value, excluding (0, 1). */
        PairTest twosComplementPairs;

        void add(int value, boolean dc) {
            total++;
            if (value == 0) { zeros++; return; }
            if (dc) {
                // DC coefficients carry the local brightness and are not used by LSB replacement, so they
                // stay out of the value histogram and of the pair test (only their count is reported).
                dcNonZero++;
                return;
            }
            acNonZero++;
            int abs = Math.abs(value);
            if (abs <= BIN) histogram[value + BIN]++; else tail++;
            // coefficients of +-1 are skipped by LSB replacement (they would shrink to zero), so they must
            // not enter the parity balance either - they are always odd and would mask the real signal.
            if (abs >= 2) {
                if ((value & 1) == 0) even++; else odd++;
            }
            if (abs == 1) ones++;
            sumAbs += abs;
        }

        /** Number of coefficients with value exactly {@code value} (0 when outside the histogram window). */
        long count(int value) {
            return value < -BIN || value > BIN ? 0 : histogram[value + BIN];
        }

        /** Number of coefficients with magnitude exactly {@code magnitude} (both signs). */
        long magnitude(int magnitude) {
            if (magnitude < 0 || magnitude > BIN) return magnitude > BIN ? tail : 0;
            if (magnitude == 0) return zeros;
            return histogram[BIN + magnitude] + histogram[BIN - magnitude];
        }

        void finish() {
            PairTest m = new PairTest("magnitude"), mc = new PairTest("control");
            for (int i = 1; 2 * i + 1 <= BIN; i++) m.add(magnitude(2 * i), magnitude(2 * i + 1));
            for (int i = 1; 2 * i + 2 <= BIN; i++) mc.add(magnitude(2 * i + 1), magnitude(2 * i + 2));
            magnitudePairs = m.done().withControl(mc.done());
            PairTest t = new PairTest("two's complement"), tc = new PairTest("control");
            for (int e = -BIN; e + 1 <= BIN - 1; e += 2) {
                if (e == 0) continue;                 // 0 and +1 are never used by JSteg
                t.add(count(e), count(e + 1));
                // shifted pairs (e+1, e+2) avoiding 0 and +1: (-3,-2), (-5,-4) ... (3,4), (5,6) ...
                if (e + 2 <= BIN && e + 1 != -1 && e + 1 != 1 && e + 2 != 0) tc.add(count(e + 1), count(e + 2));
            }
            twosComplementPairs = t.done().withControl(tc.done());
        }

        /**
         * The pairing convention that looks most equalised (lowest chi2/df), preferring conventions whose
         * control pairs are informative (see {@link PairTest#informative()}).
         */
        PairTest best() {
            PairTest a = magnitudePairs, b = twosComplementPairs;
            if (a.pairs == 0) return b;
            if (b.pairs == 0) return a;
            if (a.informative() != b.informative()) return a.informative() ? a : b;
            return b.perDf < a.perDf ? b : a;
        }

        /** Standard score of the even/odd split: 0 means perfectly balanced parities. */
        double parityZ() {
            long n = even + odd;
            if (n == 0) return 0;
            return (even - odd) / Math.sqrt(n);
        }
    }
}
