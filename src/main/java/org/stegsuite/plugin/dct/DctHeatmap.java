package org.stegsuite.plugin.dct;

import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.stat.Progress;
import org.stegsuite.core.stat.Westfeld;

/**
 * Block-grid LSB map for the DCT domain: the 8x8 blocks of a component are grouped into tiles of
 * {@code tile x tile} blocks and the Westfeld pairs-of-values test is run on the non-zero AC coefficients of
 * every tile (two's complement and magnitude pairs; the higher p is kept). A single 8x8 block rarely has
 * enough coefficients, so tiles of 2-8 blocks give the most readable maps.
 */
public final class DctHeatmap {
    private DctHeatmap() {}

    /** Minimum samples in a pair for small tiles. */
    public static final int MIN_PAIR = 4;

    /** p-values [rows][cols], rows = ceil(blocksPerColumn / tile), cols = ceil(blocksPerLine / tile). */
    public static double[][] compute(DctImage img, int componentIndex, int tile, Progress progress) {
        if (tile < 1) throw new IllegalArgumentException("tile must be >= 1");
        DctImage.Component c = img.components().get(componentIndex);
        int bpl = c.blocksPerLine(), bpc = c.blocksPerColumn();
        int rows = (bpc + tile - 1) / tile, cols = (bpl + tile - 1) / tile;
        double[][] out = new double[rows][cols];
        int bin = ChiSquareAttack.BIN;
        long[] hist = new long[2 * bin + 1];
        for (int r = 0; r < rows; r++) {
            Progress.check(progress);
            for (int col = 0; col < cols; col++) {
                java.util.Arrays.fill(hist, 0);
                for (int by = r * tile; by < Math.min(bpc, (r + 1) * tile); by++)
                    for (int bx = col * tile; bx < Math.min(bpl, (col + 1) * tile); bx++) {
                        int b = by * bpl + bx;
                        for (int k = 1; k < 64; k++) {
                            int v = c.zigZagCoefficient(b, k);
                            if (v != 0 && v >= -bin && v <= bin) hist[v + bin]++;
                        }
                    }
                double best = Double.NaN;
                for (ChiSquareAttack.Pairing pairing : ChiSquareAttack.Pairing.values()) {
                    double p = test(hist, pairing);
                    if (!Double.isNaN(p) && (Double.isNaN(best) || p > best)) best = p;
                }
                out[r][col] = best;
            }
            if (progress != null) progress.update((r + 1) / (double) rows);
        }
        return out;
    }

    private static double test(long[] hist, ChiSquareAttack.Pairing pairing) {
        int bin = ChiSquareAttack.BIN;
        Westfeld.Accumulator acc = new Westfeld.Accumulator(MIN_PAIR);
        if (pairing == ChiSquareAttack.Pairing.MAGNITUDE) {
            for (int a = 2; a + 1 <= bin; a += 2) acc.pair(hist[bin + a] + hist[bin - a], hist[bin + a + 1] + hist[bin - a - 1]);
        } else {
            for (int e = -bin; e + 1 <= bin; e += 2) if (e != 0) acc.pair(hist[bin + e], hist[bin + e + 1]);
        }
        return acc.result().p;
    }

    /** Size in image pixels of one heatmap cell for this component and tile size. */
    public static int[] cellSize(DctImage img, int componentIndex, int tile) {
        DctImage.Component c = img.components().get(componentIndex);
        return new int[]{8 * tile * img.maxH() / c.h(), 8 * tile * img.maxV() / c.v()};
    }
}
