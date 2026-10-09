package org.stegsuite.plugin.dct;

import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.InverseDct;
import org.stegsuite.core.jpeg.JpegDctDecoder;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGQTable;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Calibration (Fridrich, Goljan, Hogea, "Steganalysis of JPEG images: breaking the F5 algorithm", 2002):
 * the luminance plane is decompressed, cropped by 4 pixels in both directions (which breaks the 8x8 grid
 * of any embedding) and recompressed with the same quantization table. The coefficient histogram of the
 * result estimates the cover histogram. F5 shrinks magnitudes (|v| -> |v| - 1), so with h the histogram of
 * a low-frequency mode in the image and c the calibrated one, the modification rate is the least squares
 * solution of h(0) = c(0) + beta c(1), h(1) = (1 - beta) c(1) + beta c(2):
 * beta = [c1 (h0 - c0) + (h1 - c1)(c2 - c1)] / [c1^2 + (c2 - c1)^2] per mode; the reported value is the
 * pooled least squares solution over the modes (0,1), (1,0) and (1,1) (sums of numerators and denominators),
 * which down-weights sparse modes. beta is the fraction of non-zero AC coefficients that were changed; clean images give
 * values close to 0.
 */
public final class Calibration {
    private Calibration() {}

    /** Natural (row-major) indices of the DCT modes used for the estimate: (0,1), (1,0), (1,1). */
    static final int[] MODES = {1, 8, 9};
    /** Modes whose calibrated h(1) is smaller than this are too sparse to use. */
    static final double MIN_C1 = 50;
    /** beta above this is reported as an F5-style modification. */
    public static final double BETA_THRESHOLD = 0.10;

    public static final class Result {
        /** beta per mode (NaN for sparse modes); {@link #beta} is the pooled estimate. */
        public final double[] betaPerMode;
        public final double beta;
        /** h(0), h(1), h(2) of the image and of the calibrated image (block-count normalised), per mode. */
        public final double[][] observed, calibrated;
        /** Coefficient statistics of the calibrated luminance plane (reference for the chi-square verdict). */
        final DctAnalysis.Stats calibratedStats;
        public final String note;

        Result(double[] betaPerMode, double beta, double[][] observed, double[][] calibrated,
               DctAnalysis.Stats calibratedStats, String note) {
            this.betaPerMode = betaPerMode;
            this.beta = beta;
            this.observed = observed;
            this.calibrated = calibrated;
            this.calibratedStats = calibratedStats;
            this.note = note;
        }

        public boolean flagged() { return !Double.isNaN(beta) && beta >= BETA_THRESHOLD; }
    }

    /**
     * Runs the calibration on the first component (luminance).
     *
     * @throws IOException when the luminance plane cannot be reconstructed or recompressed
     */
    public static Result analyze(DctImage img) throws IOException {
        DctImage.Component y = img.components().get(0);
        DctImage.QuantTable q = img.quantTableOf(y);
        if (q == null) throw new IOException("missing quantization table of the first component");
        int w = y.downsampledWidth(), h = y.downsampledHeight();
        if (w <= 4 + 16 || h <= 4 + 16) throw new IOException("image too small for calibration");
        int[] samples = InverseDct.componentSamples(img, 0);
        if (samples == null) throw new IOException("cannot reconstruct the luminance plane");
        BufferedImage cropped = new BufferedImage(w - 4, h - 4, BufferedImage.TYPE_BYTE_GRAY);
        byte[] dst = ((java.awt.image.DataBufferByte) cropped.getRaster().getDataBuffer()).getData();
        for (int yy = 4; yy < h; yy++)
            for (int xx = 4; xx < w; xx++) dst[(yy - 4) * (w - 4) + xx - 4] = (byte) samples[yy * w + xx];
        byte[] jpeg = recompress(cropped, q.values());
        DctImage cal = JpegDctDecoder.decode(jpeg);
        DctImage.Component cy = cal.components().get(0);

        double scale = y.blockCount() / (double) cy.blockCount();
        double[][] obs = new double[MODES.length][3], calib = new double[MODES.length][3];
        double[] betas = new double[MODES.length];
        double num = 0, den = 0;
        for (int m = 0; m < MODES.length; m++) {
            for (int b = 0; b < y.blockCount(); b++) {
                int a = Math.abs(y.coefficient(b, MODES[m]));
                if (a <= 2) obs[m][a]++;
            }
            for (int b = 0; b < cy.blockCount(); b++) {
                int a = Math.abs(cy.coefficient(b, MODES[m]));
                if (a <= 2) calib[m][a] += scale;
            }
            betas[m] = beta(obs[m], calib[m]);
            if (!Double.isNaN(betas[m])) {
                double[] ho = obs[m], c = calib[m];
                num += c[1] * (ho[0] - c[0]) + (ho[1] - c[1]) * (c[2] - c[1]);
                den += c[1] * c[1] + (c[2] - c[1]) * (c[2] - c[1]);
            }
        }
        DctAnalysis.Stats stats = new DctAnalysis.Stats();
        for (int b = 0; b < cy.blockCount(); b++)
            for (int k = 1; k < 64; k++) stats.add(cy.zigZagCoefficient(b, k), false);
        stats.finish();
        String note = q.precision() != 0 ? "16-bit quantization table: recompression approximated" : null;
        return new Result(betas, den <= 0 ? Double.NaN : num / den, obs, calib, stats, note);
    }

    /** Least squares beta for one mode; NaN when the calibrated histogram is degenerate. */
    static double beta(double[] h, double[] c) {
        double den = c[1] * c[1] + (c[2] - c[1]) * (c[2] - c[1]);
        if (den <= 0 || c[1] < MIN_C1) return Double.NaN;
        return (c[1] * (h[0] - c[0]) + (h[1] - c[1]) * (c[2] - c[1])) / den;
    }

    /** Baseline grayscale JPEG with the given quantization table (natural order) and the Annex K Huffman tables. */
    static byte[] recompress(BufferedImage gray, int[] quantNatural) throws IOException {
        int[] table = new int[64];
        for (int i = 0; i < 64; i++) table[i] = Math.max(1, Math.min(255, quantNatural[i]));
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        if (!it.hasNext()) throw new IOException("no JPEG writer available");
        ImageWriter writer = it.next();
        try {
            // put the table into the default metadata (writing with tables in the param alone produces an
            // abbreviated stream without DHT segments on some JDKs)
            javax.imageio.metadata.IIOMetadata meta = writer.getDefaultImageMetadata(
                    javax.imageio.ImageTypeSpecifier.createFromRenderedImage(gray), null);
            String format = "javax_imageio_jpeg_image_1.0";
            org.w3c.dom.Node tree = meta.getAsTree(format);
            org.w3c.dom.NodeList tables = ((org.w3c.dom.Element) tree).getElementsByTagName("dqtable");
            if (tables.getLength() == 0) throw new IOException("JPEG writer metadata has no quantization table");
            for (int i = 0; i < tables.getLength(); i++)
                ((javax.imageio.metadata.IIOMetadataNode) tables.item(i)).setUserObject(new JPEGQTable(table));
            meta.setFromTree(format, tree);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(gray, null, meta), null);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
