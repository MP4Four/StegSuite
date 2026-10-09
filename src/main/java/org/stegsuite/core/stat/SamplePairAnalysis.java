package org.stegsuite.core.stat;

/**
 * Sample Pair Analysis (Dumitrescu, Wu, Wang, "Detection of LSB steganography via sample pair analysis",
 * 2003) for one 8-bit channel, over horizontally and vertically adjacent sample pairs (u, v).
 *
 * <p>X = pairs with (v even and u &lt; v) or (v odd and u &gt; v), Y = pairs with (v even and u &gt; v) or
 * (v odd and u &lt; v), and gamma = number of pairs with floor(u/2) = floor(v/2). For a natural cover
 * |X| ~ |Y|; LSB replacement at rate p changes them so that
 * (gamma / 2) p^2 + (2|X| - |P|) p + |Y| - |X| = 0, whose smaller root estimates p.
 */
public final class SamplePairAnalysis {
    private SamplePairAnalysis() {}

    public static final class Result {
        public final long pairs, x, y, gamma;
        /** Estimated embedding rate 0..1 (clamped; NaN when undefined). */
        public final double rate;
        public final double rawRate;

        Result(long pairs, long x, long y, long gamma, double rawRate) {
            this.pairs = pairs;
            this.x = x;
            this.y = y;
            this.gamma = gamma;
            this.rawRate = rawRate;
            this.rate = Double.isNaN(rawRate) ? Double.NaN : Math.max(0, Math.min(1, rawRate));
        }
    }

    public static Result analyze(int[] s, int w, int h, Progress progress) {
        long[] c = new long[4];   // pairs, x, y, gamma
        for (int y = 0; y < h; y++) {
            if ((y & 63) == 0) Progress.check(progress);
            int row = y * w;
            for (int x = 0; x + 1 < w; x++) add(s[row + x], s[row + x + 1], c);
            if (y + 1 < h) for (int x = 0; x < w; x++) add(s[row + x], s[row + w + x], c);
        }
        return new Result(c[0], c[1], c[2], c[3], estimate(c[0], c[1], c[2], c[3]));
    }

    private static void add(int u, int v, long[] c) {
        c[0]++;
        if (((v & 1) == 0 && u < v) || ((v & 1) == 1 && u > v)) c[1]++;
        if (((v & 1) == 0 && u > v) || ((v & 1) == 1 && u < v)) c[2]++;
        if ((u >> 1) == (v >> 1)) c[3]++;
    }

    static double estimate(long pairs, long x, long y, long gamma) {
        double a = gamma / 2.0, b = 2.0 * x - pairs, c = (double) y - x;
        if (gamma == 0) return Math.abs(b) < 1e-12 ? Double.NaN : -c / b;
        double disc = b * b - 4 * a * c;
        // at rates near 100% the parabola only touches zero; a slightly negative discriminant is noise
        if (disc < 0) disc = 0;
        double s = Math.sqrt(disc);
        return Math.min((-b + s) / (2 * a), (-b - s) / (2 * a));
    }
}
