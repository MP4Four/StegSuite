package org.stegsuite.core.stat;

import org.junit.Test;

import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

/** RS / SPA accuracy on synthetic covers with known LSB replacement rates, and the spatial heatmap. */
public class SpatialDetectionTest {

    private static final double[] RATES = {0.0, 0.25, 0.5, 1.0};

    @Test public void rsAndSpaEstimateKnownRates() {
        for (long seed = 1; seed <= 2; seed++) {
            BufferedImage cover = SyntheticCovers.smoothCover(384, 384, seed);
            for (double rate : RATES) {
                SpatialDetection.Report r = SpatialDetection.analyze(SyntheticCovers.withRandomLsb(cover, rate, 100 + seed), false, null);
                assertEquals(3, r.channels.size());
                for (SpatialDetection.ChannelResult c : r.channels) {
                    String where = "seed " + seed + " rate " + rate + " channel " + c.channel;
                    if (rate < 1.0) {
                        assertEquals(where + " RS", rate, c.rs.rate, 0.06);
                        assertEquals(where + " SPA", rate, c.spa.rate, 0.06);
                    } else {
                        // at 100% the quadratics degenerate; the estimates saturate close to 1
                        assertTrue(where + " RS " + c.rs.rate, c.rs.rate > 0.9);
                        assertTrue(where + " SPA " + c.spa.rate, c.spa.rate > 0.9);
                    }
                }
                if (rate == 0) assertTrue(r.verdict(), r.verdict().startsWith("no evidence"));
                else assertTrue(r.verdict(), r.verdict().startsWith("LSB replacement likely"));
            }
        }
    }

    @Test public void grayscaleImagesAreAnalysedAsOneChannel() {
        BufferedImage rgb = SyntheticCovers.smoothCover(128, 128, 3);
        BufferedImage gray = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128; y++)
            for (int x = 0; x < 128; x++) {
                int v = (rgb.getRGB(x, y) >> 8) & 0xff;
                gray.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        SpatialDetection.Report r = SpatialDetection.analyze(gray, true, null);
        assertEquals(1, r.channels.size());
        assertEquals("gray", r.channels.get(0).channel);
        assertTrue(r.warnings.get(0).contains("lossy"));
    }

    @Test public void analysisCanBeCancelled() {
        try {
            SpatialDetection.analyze(SyntheticCovers.smoothCover(256, 256, 1), false, new Progress() {
                @Override public void update(double f) {}
                @Override public boolean cancelled() { return true; }
            });
            fail("expected cancellation");
        } catch (Progress.Cancelled expected) {
            // ok
        }
    }

    @Test public void heatmapHasTheExpectedShape() {
        BufferedImage img = SyntheticCovers.smoothCover(100, 70, 4);
        for (int block : new int[]{8, 16, 32, 64, 128}) {
            double[][] m = LsbHeatmap.compute(img, block, null);
            assertEquals((70 + block - 1) / block, m.length);
            assertEquals((100 + block - 1) / block, m[0].length);
            BufferedImage ov = LsbHeatmap.toOverlay(m, 0.5);
            assertEquals(m[0].length, ov.getWidth());
            assertEquals(m.length, ov.getHeight());
            assertEquals(BufferedImage.TYPE_INT_ARGB, ov.getType());
            for (double[] row : m) for (double v : row) assertTrue(Double.isNaN(v) || (v >= 0 && v <= 1));
        }
        // NaN cells are fully transparent, p = 1 is opaque red at the chosen opacity
        BufferedImage ov = LsbHeatmap.toOverlay(new double[][]{{Double.NaN, 1.0, 0.0}}, 1.0);
        assertEquals(0, ov.getRGB(0, 0) >>> 24);
        assertEquals(255, ov.getRGB(1, 0) >>> 24);
        assertEquals(255, (ov.getRGB(1, 0) >> 16) & 0xff);
        assertTrue((ov.getRGB(2, 0) >>> 24) < 40);
    }

    @Test public void heatmapIsHotWhereLsbsWereReplaced() {
        // low-noise content: in strongly noisy regions the LSBs of a cover are already random-looking
        BufferedImage cover = SyntheticCovers.smoothCover(256, 256, 5, 0.5);
        BufferedImage full = SyntheticCovers.withRandomLsb(cover, 1.0, 6);
        BufferedImage half = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++)
            for (int x = 0; x < 256; x++) half.setRGB(x, y, (x < 128 ? full : cover).getRGB(x, y));
        double[][] m = LsbHeatmap.compute(half, 32, null);
        double left = LsbHeatmap.mean(m, 0, m.length, 0, 4), right = LsbHeatmap.mean(m, 0, m.length, 4, 8);
        assertTrue("left " + left + " right " + right, left > right + 0.25);
    }
}
