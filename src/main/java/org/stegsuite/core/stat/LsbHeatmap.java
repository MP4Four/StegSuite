package org.stegsuite.core.stat;

import java.awt.image.BufferedImage;

/**
 * Block-wise LSB detection map for spatial images: for every block of {@code block x block} pixels the
 * Westfeld pairs-of-values test is run on the histogram of all R, G and B samples of the block. A high p
 * (pairs (2i, 2i+1) equalised) is what random LSB replacement produces; natural content usually gives a
 * low p. Blocks with too few populated pairs get NaN. Noisy covers can also produce high p - the map is a
 * localisation aid, not a detector on its own.
 */
public final class LsbHeatmap {
    private LsbHeatmap() {}

    /** Minimum samples in a value pair for it to enter the block test. */
    public static final int MIN_PAIR = 4;

    /** p-values, [rows][cols] with rows = ceil(h / block), cols = ceil(w / block). */
    public static double[][] compute(BufferedImage img, int block, Progress progress) {
        if (block < 2) throw new IllegalArgumentException("block size must be >= 2");
        int w = img.getWidth(), h = img.getHeight();
        int rows = (h + block - 1) / block, cols = (w + block - 1) / block;
        double[][] out = new double[rows][cols];
        int[] row = new int[w * block];
        long[] hist = new long[256];
        for (int r = 0; r < rows; r++) {
            Progress.check(progress);
            int y0 = r * block, bh = Math.min(block, h - y0);
            img.getRGB(0, y0, w, bh, row, 0, w);
            for (int c = 0; c < cols; c++) {
                java.util.Arrays.fill(hist, 0);
                int x0 = c * block, bw = Math.min(block, w - x0);
                for (int y = 0; y < bh; y++)
                    for (int x = 0; x < bw; x++) {
                        int p = row[y * w + x0 + x];
                        hist[(p >> 16) & 0xff]++;
                        hist[(p >> 8) & 0xff]++;
                        hist[p & 0xff]++;
                    }
                out[r][c] = Westfeld.ofHistogram256(hist, MIN_PAIR).p;
            }
            if (progress != null) progress.update((r + 1) / (double) rows);
        }
        return out;
    }

    /**
     * Renders p-values as a cols x rows ARGB image: red with alpha = opacity * p (high p = suspicious),
     * fully transparent for NaN. Scale it by the block size to overlay it on the image.
     */
    public static BufferedImage toOverlay(double[][] p, double opacity) {
        int rows = p.length, cols = rows == 0 ? 0 : p[0].length;
        BufferedImage out = new BufferedImage(Math.max(1, cols), Math.max(1, rows), BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                double v = p[r][c];
                if (Double.isNaN(v)) continue;
                int a = (int) Math.round(255 * Math.max(0, Math.min(1, opacity)) * Math.max(0.08, v));
                // low p: faint blue, high p: red
                int red = (int) Math.round(255 * v), blue = (int) Math.round(255 * (1 - v));
                out.setRGB(c, r, (a << 24) | (red << 16) | (40 << 8) | blue);
            }
        return out;
    }

    /** Mean of the non-NaN entries (NaN when there are none). */
    public static double mean(double[][] p, int r0, int r1, int c0, int c1) {
        double s = 0;
        int n = 0;
        for (int r = r0; r < r1; r++)
            for (int c = c0; c < c1; c++)
                if (!Double.isNaN(p[r][c])) { s += p[r][c]; n++; }
        return n == 0 ? Double.NaN : s / n;
    }
}
