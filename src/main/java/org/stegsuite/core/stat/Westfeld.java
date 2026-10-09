package org.stegsuite.core.stat;

/**
 * Westfeld/Pfitzmann pairs-of-values chi-square test in its original formulation: for every pair (2i, 2i+1)
 * the observed count of the even value is compared with the pair mean, chi2 = sum (n_2i - m_i)^2 / m_i with
 * m_i = (n_2i + n_2i+1) / 2, over k pairs with df = k - 1. p close to 1 means the pairs are equalised, as
 * LSB replacement with random bits leaves them; p close to 0 means the histogram keeps its natural shape.
 */
public final class Westfeld {
    private Westfeld() {}

    /** Result of one test: chi2, number of pairs used, p-value (NaN when fewer than 2 pairs qualified). */
    public static final class Result {
        public final double chi2;
        public final int pairs;
        public final double p;

        Result(double chi2, int pairs, double p) {
            this.chi2 = chi2;
            this.pairs = pairs;
            this.p = p;
        }
    }

    /** Accumulates pairs, ignoring those with fewer than {@code minCount} samples in total. */
    public static final class Accumulator {
        private final int minCount;
        private double chi2;
        private int pairs;

        public Accumulator(int minCount) { this.minCount = minCount; }

        public void pair(long even, long odd) {
            long n = even + odd;
            if (n < minCount || n == 0) return;
            double m = n / 2.0;
            chi2 += (even - m) * (even - m) / m;
            pairs++;
        }

        public Result result() {
            double p = pairs < 2 ? Double.NaN : ChiSquare.pValue(chi2, pairs - 1);
            if (pairs >= 2 && chi2 == 0) p = 1.0;
            return new Result(chi2, pairs, p);
        }
    }

    /** Test over a 0..255 sample histogram with pairs (2i, 2i+1). */
    public static Result ofHistogram256(long[] hist, int minCount) {
        Accumulator a = new Accumulator(minCount);
        for (int v = 0; v + 1 < hist.length; v += 2) a.pair(hist[v], hist[v + 1]);
        return a.result();
    }
}
