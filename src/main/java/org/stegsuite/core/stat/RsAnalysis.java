package org.stegsuite.core.stat;

/**
 * RS steganalysis (Fridrich, Goljan, Du, "Reliable detection of LSB steganography in color and grayscale
 * images", 2001) for one 8-bit channel.
 *
 * <p>The samples are split into groups of 4 neighbours (horizontal and vertical, non-overlapping). The
 * smoothness f(G) = sum |x(i+1) - x(i)| of a group is compared before and after flipping with the mask
 * M = [0 1 1 0] using F1 (0<->1, 2<->3, ...) and with -M using F-1 (-1<->0, 1<->2, ...). A group is
 * regular (R) when flipping increases f and singular (S) when it decreases f. For a cover R_M ~ R_-M and
 * S_M ~ S_-M; LSB replacement pushes R_M and S_M together while R_-M and S_-M drift apart. Evaluating the
 * counts on the image and on the image with all LSBs flipped gives a quadratic in the embedding rate.
 *
 * <p>The estimate p is the embedding rate as a fraction of all samples (p = 1 means every LSB carries a
 * random message bit, i.e. about half of the LSBs were flipped).
 */
public final class RsAnalysis {
    private RsAnalysis() {}

    private static final int[] MASK = {0, 1, 1, 0};

    /** Relative group counts and the estimate. */
    public static final class Result {
        /** R_M, S_M, R_-M, S_-M of the image (fractions of all groups). */
        public final double rm, sm, rmNeg, smNeg;
        /** The same counts for the image with all LSBs flipped. */
        public final double rmFlip, smFlip, rmNegFlip, smNegFlip;
        public final long groups;
        /** Estimated embedding rate 0..1 (clamped; NaN when the quadratic has no real root). */
        public final double rate;
        /** Unclamped estimate (slightly negative values are normal for covers). */
        public final double rawRate;

        Result(double[] c, double[] f, long groups, double rawRate) {
            rm = c[0]; sm = c[1]; rmNeg = c[2]; smNeg = c[3];
            rmFlip = f[0]; smFlip = f[1]; rmNegFlip = f[2]; smNegFlip = f[3];
            this.groups = groups;
            this.rawRate = rawRate;
            this.rate = Double.isNaN(rawRate) ? Double.NaN : Math.max(0, Math.min(1, rawRate));
        }
    }

    /**
     * Runs RS on a channel.
     *
     * @param samples row-major 0..255 samples, width * height entries
     */
    public static Result analyze(int[] samples, int width, int height, Progress progress) {
        long[] normal = new long[4], flipped = new long[4];
        long groups = count(samples, width, height, false, normal, progress);
        count(samples, width, height, true, flipped, progress);
        if (groups == 0) return new Result(new double[4], new double[4], 0, Double.NaN);
        double[] c = new double[4], f = new double[4];
        for (int i = 0; i < 4; i++) { c[i] = normal[i] / (double) groups; f[i] = flipped[i] / (double) groups; }
        return new Result(c, f, groups, estimate(c, f, groups));
    }

    /**
     * Solves the RS quadratic for counts c (image) and f (all LSBs flipped). The quadratic coefficient
     * 2(d1 + d0) is often within its sampling noise (and at rates near 100% d0 and d1 are both ~0); then the
     * linear solution is used, which is far more stable than a root of a noise-dominated parabola.
     */
    static double estimate(double[] c, double[] f, long groups) {
        double d0 = c[0] - c[1], d1 = f[0] - f[1], dm0 = c[2] - c[3], dm1 = f[2] - f[3];
        double a = 2 * (d1 + d0);
        double b = dm0 - dm1 - d1 - 3 * d0;
        double cc = d0 - dm0;
        // standard error of a: d0 and d1 are differences of group fractions
        double sigmaA = 2 * Math.sqrt(Math.max(1e-12, (c[0] + c[1] + f[0] + f[1]) / Math.max(1, groups)));
        double x;
        if (Math.abs(a) < 2 * sigmaA && Math.abs(b) > 1e-12) {
            x = -cc / b;
        } else if (Math.abs(a) < 1e-12) {
            if (Math.abs(b) < 1e-12) return Double.NaN;
            x = -cc / b;
        } else {
            double disc = b * b - 4 * a * cc;
            // at rates near 100% the parabola only touches zero; a slightly negative discriminant is noise
            if (disc < 0) disc = 0;
            double s = Math.sqrt(disc);
            double x1 = (-b + s) / (2 * a), x2 = (-b - s) / (2 * a);
            x = Math.abs(x1) < Math.abs(x2) ? x1 : x2;
        }
        if (Math.abs(x - 0.5) < 1e-12) return Double.NaN;
        return x / (x - 0.5);
    }

    /** Counts R_M, S_M, R_-M, S_-M over horizontal and vertical groups; returns the number of groups. */
    private static long count(int[] s, int w, int h, boolean flipAll, long[] out, Progress progress) {
        int[] g = new int[4];
        long groups = 0;
        for (int y = 0; y < h; y++) {
            if ((y & 63) == 0) Progress.check(progress);
            for (int x = 0; x + 4 <= w; x += 4) {
                for (int i = 0; i < 4; i++) g[i] = s[y * w + x + i];
                classify(g, flipAll, out);
                groups++;
            }
        }
        for (int x = 0; x < w; x++) {
            if ((x & 63) == 0) Progress.check(progress);
            for (int y = 0; y + 4 <= h; y += 4) {
                for (int i = 0; i < 4; i++) g[i] = s[(y + i) * w + x];
                classify(g, flipAll, out);
                groups++;
            }
        }
        return groups;
    }

    private static void classify(int[] g, boolean flipAll, long[] out) {
        int a0 = g[0], a1 = g[1], a2 = g[2], a3 = g[3];
        if (flipAll) { a0 ^= 1; a1 ^= 1; a2 ^= 1; a3 ^= 1; }
        int f0 = Math.abs(a1 - a0) + Math.abs(a2 - a1) + Math.abs(a3 - a2);
        // positive mask: F1 on positions with MASK = 1
        int p0 = MASK[0] == 1 ? a0 ^ 1 : a0, p1 = MASK[1] == 1 ? a1 ^ 1 : a1;
        int p2 = MASK[2] == 1 ? a2 ^ 1 : a2, p3 = MASK[3] == 1 ? a3 ^ 1 : a3;
        int fp = Math.abs(p1 - p0) + Math.abs(p2 - p1) + Math.abs(p3 - p2);
        if (fp > f0) out[0]++; else if (fp < f0) out[1]++;
        // negative mask: F-1(x) = F1(x + 1) - 1
        int n0 = MASK[0] == 1 ? fNeg(a0) : a0, n1 = MASK[1] == 1 ? fNeg(a1) : a1;
        int n2 = MASK[2] == 1 ? fNeg(a2) : a2, n3 = MASK[3] == 1 ? fNeg(a3) : a3;
        int fn = Math.abs(n1 - n0) + Math.abs(n2 - n1) + Math.abs(n3 - n2);
        if (fn > f0) out[2]++; else if (fn < f0) out[3]++;
    }

    private static int fNeg(int x) {
        return ((x + 1) ^ 1) - 1;
    }
}
