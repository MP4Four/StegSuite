package org.stegsuite.plugin.dct;

import org.junit.Test;
import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.JpegDctDecoder;
import org.stegsuite.core.stat.Progress;

import java.util.Random;

import static org.junit.Assert.*;

/** Chi-square attack curve, calibration, JSteg length header and the DCT heatmap. */
public class DetectionDctTest {

    // ------------------------------------------------------------------ test-only fixtures

    /** Non-zero AC-like coefficients with a Laplacian (geometrically decaying) magnitude distribution. */
    static int[] laplacianCoefficients(int n, double meanMagnitude, long seed) {
        Random r = new Random(seed);
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            int m = 1 + (int) Math.floor(-Math.log(1 - r.nextDouble()) * meanMagnitude);
            v[i] = r.nextBoolean() ? m : -m;
        }
        return v;
    }

    /**
     * Sequential LSB replacement with random bits over the first {@code fraction} of the positions.
     * twosComplement: original JSteg (stored value LSB, 0 and +1 skipped); else magnitude LSB, +-1 skipped.
     */
    static int[] embedSequential(int[] cover, double fraction, boolean twosComplement, long seed) {
        Random r = new Random(seed);
        int[] v = cover.clone();
        int end = (int) Math.round(cover.length * fraction);
        for (int i = 0; i < end; i++) {
            int x = v[i], bit = r.nextInt(2);
            if (twosComplement) {
                if (x == 0 || x == 1) continue;
                v[i] = (x & ~1) | bit;
            } else {
                if (x == 0 || x == 1 || x == -1) continue;
                int m = (Math.abs(x) & ~1) | bit;
                v[i] = x > 0 ? m : -m;
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ chi-square attack

    @Test public void curveStaysHighUpToThePayloadBoundary() {
        for (boolean tc : new boolean[]{true, false}) {
            for (double payload : new double[]{0.2, 0.5}) {
                int[] stego = embedSequential(laplacianCoefficients(60000, 2.5, 11), payload, tc, 5);
                ChiSquareAttack.Result r = ChiSquareAttack.analyze(stego, Progress.NONE);
                ChiSquareAttack.Curve c = tc ? r.twosComplement : r.magnitude;
                String where = (tc ? "two's complement" : "magnitude") + " payload " + payload;
                double min = 1;
                for (int i = 0; i < c.p.length; i++)
                    if (c.fraction[i] <= 0.95 * payload && !Double.isNaN(c.p[i])) min = Math.min(min, c.p[i]);
                assertTrue(where + ": min p inside the payload " + min, min > 0.1);
                for (int i = 0; i < c.p.length; i++)
                    if (c.fraction[i] >= 1.5 * payload + 0.05) assertTrue(where + ": p after the payload " + c.p[i], c.p[i] < 0.01);
                assertEquals(where + " estimate", payload, c.estimate, 0.03);
                assertSame(where, c, r.best());
                assertTrue(r.summary(), r.summary().startsWith("sequential payload likely"));
            }
        }
    }

    @Test public void cleanCoefficientsGiveNoPayloadEstimate() {
        ChiSquareAttack.Result r = ChiSquareAttack.analyze(laplacianCoefficients(60000, 2.5, 12), Progress.NONE);
        assertEquals(0.0, r.twosComplement.estimate, 0);
        assertEquals(0.0, r.magnitude.estimate, 0);
        assertTrue(r.summary(), r.summary().startsWith("no sequential payload"));
    }

    @Test public void fullyEmbeddedSequenceIsReportedAsWholeImage() {
        int[] stego = embedSequential(laplacianCoefficients(60000, 2.5, 13), 1.0, true, 6);
        ChiSquareAttack.Result r = ChiSquareAttack.analyze(stego, Progress.NONE);
        assertEquals(1.0, r.twosComplement.estimate, 0);
        assertTrue(r.summary(), r.summary().contains("whole image"));
    }

    @Test public void curveOnARealJpegFindsTheEmbeddedPrefix() throws Exception {
        byte[] cover = org.stegsuite.JpegRobustnessTest.jpegAtQuality(
                org.stegsuite.core.stat.SyntheticCovers.smoothCover(512, 512, 3, 8.0), 0.75f);
        // JSteg magnitude embedding of 400 random bytes at the start of the luma stream (test fixture);
        // the cover has ~6200 carriers (|v| >= 2), so the payload covers about half of the read order
        byte[] payload = new byte[400];
        new Random(4).nextBytes(payload);
        byte[] stego = org.stegsuite.JpegDctTestAccess.embedJstegInLuma(cover, payload);
        DctImage img = JpegDctDecoder.decode(stego);
        ChiSquareAttack.Result r = ChiSquareAttack.analyze(img, Progress.NONE);
        ChiSquareAttack.Curve c = r.best();
        long bytes = c.estimatedBits / 8;
        assertTrue("estimated " + bytes + " bytes, " + r.summary(), bytes > 340 && bytes < 480);
        DctAnalysis.Analysis a = DctAnalysis.analyze(stego, Progress.NONE);
        assertTrue(a.report, a.report.contains("== Chi-square attack (Westfeld) =="));
        assertTrue(a.report, a.report.contains("Chi-square attack: sequential payload likely"));
    }

    @Test public void analysisCanBeCancelled() throws Exception {
        byte[] jpg = org.stegsuite.TestImages.jpeg(org.stegsuite.TestImages.noise(64, 48, 1));
        try {
            DctAnalysis.analyze(jpg, new Progress() {
                @Override public void update(double f) {}
                @Override public boolean cancelled() { return true; }
            });
            fail("expected cancellation");
        } catch (Progress.Cancelled expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------ calibration

    @Test public void calibrationDoesNotFlagCleanJpegs() throws Exception {
        for (float q : new float[]{0.5f, 0.75f, 0.9f})
            for (long seed = 1; seed <= 3; seed++) {
                byte[] jpg = org.stegsuite.JpegRobustnessTest.jpegAtQuality(
                        org.stegsuite.core.stat.SyntheticCovers.smoothCover(384, 320, seed), q);
                Calibration.Result r = Calibration.analyze(JpegDctDecoder.decode(jpg));
                assertFalse("q " + q + " seed " + seed + " beta " + r.beta, r.flagged());
                assertTrue("q " + q + " seed " + seed + " beta " + r.beta, Math.abs(r.beta) < Calibration.BETA_THRESHOLD);
                String report = DctAnalysis.report(jpg);
                assertTrue(report, report.contains("no sign of F5-style shrinkage"));
            }
    }

    @Test public void calibrationEstimatesSimulatedShrinkage() throws Exception {
        byte[] cover = org.stegsuite.JpegRobustnessTest.jpegAtQuality(
                org.stegsuite.core.stat.SyntheticCovers.smoothCover(512, 512, 7), 0.75f);
        // calibration of a modified image over-estimates beta by roughly 20-30% (the recompressed reference is
        // built from the modified pixels); detection only needs the estimate to clear the threshold
        double[] tolerance = {0.06, 0.1, 0.2};
        double[] betas = {0.1, 0.25, 0.5};
        for (int i = 0; i < betas.length; i++) {
            double beta = betas[i];
            byte[] mod = org.stegsuite.JpegDctTestAccess.shrinkLuma(cover, beta, 9);
            Calibration.Result r = Calibration.analyze(JpegDctDecoder.decode(mod));
            assertEquals("simulated beta " + beta, beta, r.beta, tolerance[i]);
            assertTrue(r.flagged());
        }
    }

    // ------------------------------------------------------------------ JSteg header

    @Test public void jstegLengthHeaderSlicesTheExactPayload() throws Exception {
        byte[] payload = new byte[333];
        new Random(21).nextBytes(payload);                     // binary, no flag/text/file signature
        byte[] cover = org.stegsuite.JpegRobustnessTest.jpegAtQuality(
                org.stegsuite.core.stat.SyntheticCovers.smoothCover(256, 256, 8), 0.9f);
        byte[] stego = org.stegsuite.JpegDctTestAccess.embedOriginalJstegWithHeader(cover, payload);
        DctImage img = JpegDctDecoder.decode(stego);
        JstegHeader.Header h = DctExtraction.jstegHeader(img);
        assertNotNull("header expected", h);
        assertEquals(333, h.length);
        assertEquals(9, h.lengthBits);
        assertArrayEquals(payload, h.payload);
        assertArrayEquals(payload, DctExtraction.extract(img, DctExtraction.Mode.JSTEG_HEADER, ""));
        assertArrayEquals(payload, new JpegDctPlugin().extract(stego, ""));
        // a flag behind the header is found at offset 0 of the sliced stream
        byte[] flagged = org.stegsuite.JpegDctTestAccess.embedOriginalJstegWithHeader(cover,
                "flag{jsteg_header_v4}".getBytes("ISO-8859-1"));
        assertArrayEquals("flag{jsteg_header_v4}".getBytes("ISO-8859-1"), new JpegDctPlugin().extract(flagged, ""));
        String report = DctAnalysis.report(flagged);
        assertTrue(report, report.contains("Payload: FLAG flag{jsteg_header_v4} @0x0 via JSteg original with length header"));
    }

    @Test public void implausibleHeadersAreRejected() {
        // A = 0
        assertNull(JstegHeader.parse(new byte[]{0x00, 0x00, 0x00}, 1000));
        // A = 4, B = 0001 (bitLength 1, needs 3 or 4)
        assertNull(JstegHeader.parse(new byte[]{(byte) 0b00100000, (byte) 0b10000000, 0}, 1000));
        // A = 4, B = 1100 = 12 bytes -> needs 9 + 96 bits, only 50 available
        assertNull(JstegHeader.parse(new byte[]{(byte) 0b00100110, 0, 0, 0}, 50));
        JstegHeader.Header ok = JstegHeader.parse(new byte[]{(byte) 0b00100110, 0x41, 0, 0}, 1000);
        assertNotNull(ok);
        assertEquals(12, ok.length);
        assertTrue(ok.truncated);
    }

    // ------------------------------------------------------------------ heatmap

    @Test public void dctHeatmapHasTheBlockGridShape() throws Exception {
        byte[] jpg = org.stegsuite.TestImages.jpeg(org.stegsuite.core.stat.SyntheticCovers.smoothCover(100, 70, 2));
        DctImage img = JpegDctDecoder.decode(jpg);
        DctImage.Component y = img.components().get(0);
        assertEquals(13, y.blocksPerLine());
        assertEquals(9, y.blocksPerColumn());
        for (int tile : new int[]{1, 2, 4, 5}) {
            double[][] m = DctHeatmap.compute(img, 0, tile, Progress.NONE);
            assertEquals((9 + tile - 1) / tile, m.length);
            assertEquals((13 + tile - 1) / tile, m[0].length);
            for (double[] row : m) for (double v : row) assertTrue(Double.isNaN(v) || (v >= 0 && v <= 1));
            int[] cell = DctHeatmap.cellSize(img, 0, tile);
            assertEquals(8 * tile, cell[0]);
        }
    }

    @Test public void dctHeatmapIsHotWhereTheCoefficientsWereEmbedded() throws Exception {
        byte[] cover = org.stegsuite.JpegRobustnessTest.jpegAtQuality(
                org.stegsuite.core.stat.SyntheticCovers.smoothCover(512, 512, 5), 0.75f);
        byte[] stego = org.stegsuite.JpegDctTestAccess.fullJstegInTopHalf(cover, 3);
        double[][] m = DctHeatmap.compute(JpegDctDecoder.decode(stego), 0, 8, Progress.NONE);
        double top = org.stegsuite.core.stat.LsbHeatmap.mean(m, 0, m.length / 2, 0, m[0].length);
        double bottom = org.stegsuite.core.stat.LsbHeatmap.mean(m, m.length / 2, m.length, 0, m[0].length);
        assertTrue("top " + top + " bottom " + bottom, top > bottom + 0.3);
    }
}
