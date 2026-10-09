package org.stegsuite;

import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.JpegDctDecoder;
import org.stegsuite.core.jpeg.ZigZag;

import java.util.Random;

/**
 * Test-only fixtures that modify luma DCT coefficients of a JPEG and write them back as a single component
 * baseline file (via {@link JpegTestWriter}). Used to validate the detectors; not part of the application.
 */
public final class JpegDctTestAccess {
    private JpegDctTestAccess() {}

    public static byte[] embedJstegInLuma(byte[] jpeg, byte[] payload) throws Exception {
        return JpegDctTest.embedJstegInLuma(jpeg, payload, false, 0);
    }

    private interface Modifier { void apply(int[] c, int blocks, int blocksPerLine); }

    private static byte[] modifyLuma(byte[] jpeg, Modifier m) throws Exception {
        DctImage img = JpegDctDecoder.decode(jpeg);
        DctImage.Component y = img.components().get(0);
        int blocks = y.blockCount();
        int[] c = new int[blocks * 64];
        for (int b = 0; b < blocks; b++) for (int i = 0; i < 64; i++) c[b * 64 + i] = y.coefficient(b, i);
        m.apply(c, blocks, y.blocksPerLine());
        return JpegTestWriter.grayscale(y.blocksPerLine() * 8, y.blocksPerColumn() * 8, y.blocksPerLine(),
                y.blocksPerColumn(), c, img.quantTableOf(y).values(), 0);
    }

    /** F5-like shrinkage: each non-zero AC coefficient loses one unit of magnitude with probability beta. */
    public static byte[] shrinkLuma(byte[] jpeg, double beta, long seed) throws Exception {
        Random r = new Random(seed);
        return modifyLuma(jpeg, (c, blocks, bpl) -> {
            for (int b = 0; b < blocks; b++)
                for (int k = 1; k < 64; k++) {
                    int i = b * 64 + ZigZag.NATURAL[k], v = c[i];
                    if (v != 0 && r.nextDouble() < beta) c[i] = v > 0 ? v - 1 : v + 1;
                }
        });
    }

    /** Original JSteg layout: 5-bit A, A-bit length, payload; LSB of the stored value, 0 and +1 skipped. */
    public static byte[] embedOriginalJstegWithHeader(byte[] jpeg, byte[] payload) throws Exception {
        int a = 32 - Integer.numberOfLeadingZeros(payload.length);
        int total = 5 + a + payload.length * 8;
        int[] bits = new int[total];
        for (int i = 0; i < 5; i++) bits[i] = (a >> (4 - i)) & 1;
        for (int i = 0; i < a; i++) bits[5 + i] = (payload.length >> (a - 1 - i)) & 1;
        for (int i = 0; i < payload.length * 8; i++) bits[5 + a + i] = (payload[i / 8] >> (7 - i % 8)) & 1;
        return modifyLuma(jpeg, (c, blocks, bpl) -> {
            int n = 0;
            for (int b = 0; b < blocks && n < total; b++)
                for (int k = 1; k < 64 && n < total; k++) {
                    int i = b * 64 + ZigZag.NATURAL[k], v = c[i];
                    if (v == 0 || v == 1) continue;
                    c[i] = (v & ~1) | bits[n++];
                }
            if (n < total) throw new IllegalStateException("cover too small");
        });
    }

    /** Random JSteg (two's complement) bits in every usable coefficient of the top half of the blocks. */
    public static byte[] fullJstegInTopHalf(byte[] jpeg, long seed) throws Exception {
        Random r = new Random(seed);
        return modifyLuma(jpeg, (c, blocks, bpl) -> {
            for (int b = 0; b < blocks / 2; b++)
                for (int k = 1; k < 64; k++) {
                    int i = b * 64 + ZigZag.NATURAL[k], v = c[i];
                    if (v == 0 || v == 1) continue;
                    c[i] = (v & ~1) | r.nextInt(2);
                }
        });
    }

    /** Crafted 4:2:0 baseline JPEG with random coefficients and RSTn markers every {@code restart} MCUs. */
    public static byte[] colorWithRestarts(int width, int height, int restart, long seed) {
        int[] yq = org.stegsuite.core.jpeg.StandardTables.scaleQuant(org.stegsuite.core.jpeg.StandardTables.QUANT_LUMINANCE, 80);
        int[] cq = org.stegsuite.core.jpeg.StandardTables.scaleQuant(org.stegsuite.core.jpeg.StandardTables.QUANT_CHROMINANCE, 80);
        JpegTestWriter.Comp y = new JpegTestWriter.Comp(2, 2, 0, yq, width, height, 2, 2);
        JpegTestWriter.Comp cb = new JpegTestWriter.Comp(1, 1, 1, cq, width, height, 2, 2);
        JpegTestWriter.Comp cr = new JpegTestWriter.Comp(1, 1, 1, cq, width, height, 2, 2);
        Random r = new Random(seed);
        for (JpegTestWriter.Comp c : new JpegTestWriter.Comp[]{y, cb, cr})
            for (int i = 0; i < c.coefficients.length; i++) {
                int k = i % 64;
                if (k == 0) c.coefficients[i] = r.nextInt(81) - 40;
                else if (r.nextInt(k + 2) < 2) c.coefficients[i] = (int) Math.round(r.nextGaussian() * 30.0 / (1 + k / 6));
            }
        return JpegTestWriter.color(new JpegTestWriter.Comp[]{y, cb, cr}, width, height, restart);
    }

    /** Progressive JPEG written by the JDK encoder. */
    public static byte[] progressive(java.awt.image.BufferedImage img) throws Exception {
        return JpegDctTest.progressiveJpeg(img);
    }
}
