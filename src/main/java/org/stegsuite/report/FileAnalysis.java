package org.stegsuite.report;

import org.stegsuite.core.extract.AutoScanner;
import org.stegsuite.core.extract.ScanHit;
import org.stegsuite.core.format.FormatAnalyzer;
import org.stegsuite.core.format.FormatReport;
import org.stegsuite.core.image.FrameExtractor;
import org.stegsuite.core.image.ImageUtil;
import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.DctImageCache;
import org.stegsuite.core.jpeg.JpegDctDecoder;
import org.stegsuite.core.stat.Progress;
import org.stegsuite.core.stat.SpatialDetection;
import org.stegsuite.core.util.FileMagic;
import org.stegsuite.core.util.TextHeuristics;
import org.stegsuite.plugin.dct.DctAnalysis;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs the automatic analyses of every tab on one file and turns their conclusions into a suspicion score:
 * file-format warnings (CRC errors, trailing data, embedded signatures, flag strings), the quick bit-plane
 * auto scan, the JPEG DCT analysis (pairs test, chi-square attack, calibration, extraction) and RS/SPA for
 * lossless images. Used by the batch scan and by the report export.
 *
 * <p>The score (0-100) is a ranking heuristic, not a probability: every indicator adds a fixed weight
 * ({@link Result#reasons} lists them) and the sum is capped at 100.
 */
public final class FileAnalysis {
    private FileAnalysis() {}

    /** File type classes used for filtering and dispatch. */
    public enum Kind { PNG, JPEG, BMP, GIF, OTHER }

    public static final class Options {
        /** Quick bit-plane auto scan on decodable images. */
        public boolean autoScan = true;
        /** JPEG DCT analysis. */
        public boolean dct = true;
        /** RS + SPA on lossless images (PNG, BMP, GIF). */
        public boolean spatial = true;
        /** Frame count of GIF / APNG files. */
        public boolean frames = true;
        /** Keep the decoded image (single-file reports need it for figures; batch scans drop it). */
        public boolean keepImage = false;
        /** Use the shared DCT cache (single-file reports of the open file) instead of a private decode. */
        public boolean useDctCache = false;
        public DctAnalysis.CrossCheck crossCheck = DctAnalysis.CrossCheck.NONE;
        /** Images above this many pixels skip the auto scan and RS/SPA (memory / time bound). */
        public long maxAnalysisPixels = 50_000_000L;
        /** Edge of the stored thumbnail (0 = none). */
        public int thumbnailSize = 160;
        /** Number of auto-scan hits kept. */
        public int maxHits = 5;
    }

    public static final class Result {
        public File file;
        public String name;
        public long size;
        public Kind kind = Kind.OTHER;
        public String type = "unknown";
        public int width = -1, height = -1;
        public FormatReport format;
        /** Format warnings (CRC errors, truncation, trailing data ...). */
        public final List<String> formatWarnings = new ArrayList<>();
        /** Embedded signatures of a different type than the container, e.g. "ZIP @0x1a2b". */
        public final List<String> embedded = new ArrayList<>();
        /** flag{...}-like strings in the raw bytes. */
        public final List<String> rawFlags = new ArrayList<>();
        public final List<ScanHit> scanHits = new ArrayList<>();
        public String scanNote;
        public DctAnalysis.Analysis dct;
        public String dctError;
        public SpatialDetection.Report spatial;
        public String spatialNote;
        public int frames = -1;
        public BufferedImage image;
        public BufferedImage thumbnail;
        public int score;
        /** Score contributions, e.g. "+30 trailing data (1234 bytes)". */
        public final List<String> reasons = new ArrayList<>();
        public String error;
        public long millis;

        /** Short verdict for tables. */
        public String level() {
            return score >= 60 ? "high" : score >= 30 ? "medium" : score > 0 ? "low" : "none";
        }

        /** Best auto-scan hit text or null. */
        public String bestHit() {
            return scanHits.isEmpty() ? null : scanHits.get(0).type() + " " + scanHits.get(0).detail() + " [" + scanHits.get(0).config().label() + "]";
        }

        /** Highest mean RS/SPA estimate over the channels, NaN when not computed. */
        public double spatialRate() {
            if (spatial == null) return Double.NaN;
            double best = Double.NaN;
            for (SpatialDetection.ChannelResult c : spatial.channels)
                if (!Double.isNaN(c.combined()) && (Double.isNaN(best) || c.combined() > best)) best = c.combined();
            return best;
        }

        /** One-line DCT conclusion for tables. */
        public String dctSummary() {
            if (dct == null) return dctError;
            List<String> parts = new ArrayList<>();
            if (dct.payload != null) parts.add("payload: " + dct.payload);
            if (dct.histogramFlagged()) parts.add("pairs equalised (JSteg-like)");
            else if (dct.histogramWeak()) parts.add("weak pairs sign");
            if (dct.attack.best().informative() && dct.attack.best().estimate > 0)
                parts.add(String.format("chi-square attack: ~%d bytes", dct.attack.best().estimatedBits / 8));
            if (dct.calibration != null && dct.calibration.flagged()) parts.add(String.format("F5 beta %.2f", dct.calibration.beta));
            if (dct.quality != null) parts.add("q" + (dct.quality.exact ? "" : "~") + dct.quality.quality);
            return parts.isEmpty() ? "clean" : String.join("; ", parts);
        }
    }

    public static Kind kindOf(byte[] d, String name) {
        FileMagic.Sig sig = FileMagic.identify(d);
        String ext = sig != null ? sig.ext() : "";
        if (ext.equals("png")) return Kind.PNG;
        if (ext.equals("jpg") || (d.length > 3 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8)) return Kind.JPEG;
        if (ext.equals("bmp")) return Kind.BMP;
        if (ext.equals("gif")) return Kind.GIF;
        return Kind.OTHER;
    }

    /**
     * Analyses one file.
     *
     * @throws Progress.Cancelled when the progress requests cancellation
     */
    public static Result analyze(File file, byte[] data, Options o, Progress progress) {
        if (progress == null) progress = Progress.NONE;
        long t0 = System.nanoTime();
        Result r = new Result();
        r.file = file;
        r.name = file == null ? "input" : file.getName();
        r.size = data.length;
        r.kind = kindOf(data, r.name);
        FileMagic.Sig sig = FileMagic.identify(data);
        r.type = sig == null ? (r.kind == Kind.JPEG ? "JPEG image" : "unknown") : sig.name();
        // --- file format
        r.format = FormatAnalyzer.analyze(data);
        r.formatWarnings.addAll(r.format.warnings);
        String own = sig == null ? "" : sig.ext();
        for (FileMagic.Hit h : FileMagic.scanEmbedded(data, 50)) {
            boolean afterEnd = r.format.hasTrailing() && h.offset() >= r.format.trailingOffset;
            if (!h.sig().ext().equals(own) || afterEnd)
                r.embedded.add(h.sig().name() + " @0x" + Integer.toHexString(h.offset()));
        }
        r.rawFlags.addAll(TextHeuristics.findFlags(data));
        progress.update(0.15);
        Progress.check(progress);
        // --- image
        BufferedImage img = r.kind == Kind.OTHER ? null : ImageUtil.decode(data);
        if (img != null) {
            r.width = img.getWidth();
            r.height = img.getHeight();
            if (o.thumbnailSize > 0) r.thumbnail = org.stegsuite.ui.Charts.thumbnail(img, o.thumbnailSize, o.thumbnailSize);
        }
        boolean tooLarge = img != null && (long) img.getWidth() * img.getHeight() > o.maxAnalysisPixels;
        if (o.frames && (r.kind == Kind.GIF || r.kind == Kind.PNG)) {
            try {
                if (r.kind == Kind.GIF || FrameExtractor.isPng(data) && hasActl(data))
                    r.frames = FrameExtractor.extract(data, false).size();
            } catch (Exception | OutOfMemoryError e) {
                r.frames = -1;
            }
        }
        // --- quick auto scan
        final Progress outer = progress;
        if (o.autoScan) {
            if (img == null) r.scanNote = r.kind == Kind.OTHER ? "not an image" : "image could not be decoded";
            else if (tooLarge) r.scanNote = "skipped: image larger than " + o.maxAnalysisPixels / 1_000_000 + " MP";
            else {
                List<ScanHit> hits = AutoScanner.scan(img, AutoScanner.Mode.QUICK,
                        (done, total) -> outer.update(0.15 + 0.35 * done / (double) total), outer::cancelled);
                Progress.check(progress);
                for (ScanHit h : hits) {
                    if (r.scanHits.size() >= o.maxHits) break;
                    r.scanHits.add(h);
                }
            }
        }
        progress.update(0.5);
        Progress.check(progress);
        // --- JPEG DCT
        if (o.dct && r.kind == Kind.JPEG) {
            try {
                DctImage di = o.useDctCache ? DctImageCache.get(data) : JpegDctDecoder.decode(data);
                r.dct = DctAnalysis.analyze(data, di, o.crossCheck, sub(progress, 0.5, 0.4));
            } catch (Progress.Cancelled c) {
                throw c;
            } catch (Exception | OutOfMemoryError e) {
                r.dctError = "DCT analysis failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        // --- RS / SPA on lossless images
        if (o.spatial && img != null && (r.kind == Kind.PNG || r.kind == Kind.BMP || r.kind == Kind.GIF)) {
            if (tooLarge) r.spatialNote = "skipped: image larger than " + o.maxAnalysisPixels / 1_000_000 + " MP";
            else r.spatial = SpatialDetection.analyze(img, false, sub(progress, 0.9, 0.1));
        }
        if (o.keepImage) r.image = img;
        score(r);
        progress.update(1.0);
        r.millis = (System.nanoTime() - t0) / 1_000_000;
        return r;
    }

    private static boolean hasActl(byte[] d) {
        for (int i = 8; i + 4 < Math.min(d.length, 1 << 20); i++)
            if (d[i] == 'a' && d[i + 1] == 'c' && d[i + 2] == 'T' && d[i + 3] == 'L') return true;
        return false;
    }

    /** Score weights (documented in the README). */
    static final int W_TRAILING = 30, W_EMBEDDED = 20, W_RAW_FLAG = 40, W_CRC = 25, W_IDAT = 20, W_CHUNK = 10,
            W_OTHER_WARN = 5, W_SCAN_FLAG = 50, W_SCAN_FILE = 35, W_SCAN_TEXT = 15, W_DCT_PAYLOAD = 40, W_DCT_FLAG = 60,
            W_DCT_PAIRS = 30, W_DCT_WEAK = 10, W_DCT_ATTACK = 30, W_F5 = 25, W_SPATIAL_MAX = 40;

    static void score(Result r) {
        List<String> reasons = r.reasons;
        reasons.clear();
        int s = 0;
        if (r.format.hasTrailing()) { s += W_TRAILING; reasons.add("+" + W_TRAILING + " trailing data (" + r.format.trailingLength + " bytes)"); }
        if (!r.embedded.isEmpty()) { s += W_EMBEDDED; reasons.add("+" + W_EMBEDDED + " embedded signature " + r.embedded.get(0)); }
        if (!r.rawFlags.isEmpty()) { s += W_RAW_FLAG; reasons.add("+" + W_RAW_FLAG + " flag string in raw bytes"); }
        boolean crc = false, idat = false, chunk = false, other = false;
        for (String w : r.formatWarnings) {
            if (w.startsWith("CRC mismatch")) crc = true;
            else if (w.startsWith("IDAT size does not match")) idat = true;
            else if (w.startsWith("Non-standard chunk")) chunk = true;
            else if (!w.contains("trailing data")) other = true;
        }
        if (crc) { s += W_CRC; reasons.add("+" + W_CRC + " CRC error"); }
        if (idat) { s += W_IDAT; reasons.add("+" + W_IDAT + " IDAT size does not match IHDR"); }
        if (chunk) { s += W_CHUNK; reasons.add("+" + W_CHUNK + " non-standard chunk"); }
        if (other) { s += W_OTHER_WARN; reasons.add("+" + W_OTHER_WARN + " other format warning"); }
        if (!r.scanHits.isEmpty()) {
            ScanHit h = r.scanHits.get(0);
            int w = h.type() == ScanHit.Type.FLAG ? W_SCAN_FLAG : h.type() == ScanHit.Type.FILE ? W_SCAN_FILE : W_SCAN_TEXT;
            s += w;
            reasons.add("+" + w + " auto scan " + h.type() + " in " + h.config().label());
        }
        if (r.dct != null) {
            if (r.dct.payload != null) {
                int w = r.dct.payload.startsWith("FLAG") ? W_DCT_FLAG
                        : r.dct.payloadScore >= 300 ? W_DCT_PAYLOAD : W_DCT_PAYLOAD / 2;
                s += w;
                reasons.add("+" + w + " DCT payload: " + r.dct.payload);
            }
            if (r.dct.histogramFlagged()) { s += W_DCT_PAIRS; reasons.add("+" + W_DCT_PAIRS + " DCT pairs equalised (JSteg-like)"); }
            else if (r.dct.histogramWeak()) { s += W_DCT_WEAK; reasons.add("+" + W_DCT_WEAK + " weak DCT pairs sign"); }
            if (r.dct.attack.best().informative() && r.dct.attack.best().estimate > 0) {
                s += W_DCT_ATTACK;
                reasons.add(String.format("+%d chi-square attack: sequential payload ~%d bytes", W_DCT_ATTACK, r.dct.attack.best().estimatedBits / 8));
            }
            if (r.dct.calibration != null && r.dct.calibration.flagged()) {
                s += W_F5;
                reasons.add(String.format("+%d calibration beta %.2f (F5-like)", W_F5, r.dct.calibration.beta));
            }
        }
        double rate = r.spatialRate();
        if (!Double.isNaN(rate) && rate >= SpatialDetection.DETECTION_THRESHOLD) {
            int w = (int) Math.min(W_SPATIAL_MAX, Math.round(20 + 40 * rate));
            s += w;
            reasons.add(String.format("+%d RS/SPA embedding rate ~%.0f%%", w, 100 * rate));
        }
        r.score = Math.min(100, s);
        if (reasons.isEmpty()) reasons.add("no indicator");
    }

    private static Progress sub(final Progress p, final double offset, final double span) {
        return new Progress() {
            @Override public void update(double f) { p.update(offset + span * Math.max(0, Math.min(1, f))); }
            @Override public boolean cancelled() { return p.cancelled(); }
        };
    }

    /** Immutable empty list helper. */
    static <T> List<T> none() { return Collections.emptyList(); }
}
