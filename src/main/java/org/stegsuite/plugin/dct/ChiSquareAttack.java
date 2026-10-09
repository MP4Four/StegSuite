package org.stegsuite.plugin.dct;

import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.ZigZag;
import org.stegsuite.core.stat.Progress;
import org.stegsuite.core.stat.Westfeld;

/**
 * Westfeld's chi-square attack curve: the pairs-of-values test is evaluated on the first x% of the
 * non-zero AC coefficients in JSteg read order (component by component, blocks in raster order, zig-zag
 * inside a block) for x = 1/STEPS .. 100%. While the sample lies inside a sequentially embedded payload the
 * pairs are equalised and p stays close to 1; once clean coefficients dominate, p falls to 0. The point
 * where p drops estimates the payload length.
 *
 * <p>Two pairing conventions are evaluated: two's complement pairs (2i, 2i+1) of the stored value (original
 * JSteg, values 0 and +1 excluded) and magnitude pairs {|2i|, |2i+1|} (variants that keep the sign).
 */
public final class ChiSquareAttack {
    private ChiSquareAttack() {}

    /** Number of evaluation points of the curve. */
    public static final int STEPS = 200;
    /** Histogram window for signed values. */
    static final int BIN = 64;
    /** p above this counts as "equalised" when locating the payload end. */
    public static final double HIGH_P = 0.5;
    /** p below this marks the drop. */
    public static final double LOW_P = 0.01;
    /** Minimum samples in a pair; smaller pairs are left out of the sum. */
    public static final int MIN_PAIR = 10;
    /** Minimum number of qualifying pairs for a curve point. */
    public static final int MIN_PAIRS = 2;
    /**
     * A high p only counts as "equalised by embedding" where the shifted control pairs of the same prefix are
     * clearly unequal (control p below this): otherwise the prefix is merely flat or too small.
     */
    public static final double CONTROL_P = 0.05;

    public enum Pairing { TWOS_COMPLEMENT, MAGNITUDE }

    /** One curve with its payload estimate. */
    public static final class Curve {
        public final Pairing pairing;
        /** Fraction of the coefficients (x axis), STEPS entries, increasing up to 1. */
        public final double[] fraction;
        /** p-value at each point; NaN where fewer than {@link #MIN_PAIRS} pairs qualified. */
        public final double[] p;
        /** p of the shifted (2i+1, 2i+2) control pairs on the same prefix; NaN where too few pairs. */
        public final double[] control;
        /** Estimated payload end as a fraction of the coefficients (0 = none, 1 = whole image). */
        public final double estimate;
        /** Usable carrier positions (bits) inside the estimated prefix. */
        public final long estimatedBits;
        /** p of the shifted (2i+1, 2i+2) control pairs over all coefficients; high = histogram flat, test uninformative. */
        public final double controlP;

        Curve(Pairing pairing, double[] fraction, double[] p, double[] control, double estimate, long estimatedBits,
              double controlP) {
            this.pairing = pairing;
            this.fraction = fraction;
            this.p = p;
            this.control = control;
            this.estimate = estimate;
            this.estimatedBits = estimatedBits;
            this.controlP = controlP;
        }

        public boolean informative() { return Double.isNaN(controlP) || controlP < LOW_P; }
    }

    /** Both curves plus the total number of coefficients in read order. */
    public static final class Result {
        public final Curve twosComplement;
        public final Curve magnitude;
        public final int coefficients;

        Result(Curve twosComplement, Curve magnitude, int coefficients) {
            this.twosComplement = twosComplement;
            this.magnitude = magnitude;
            this.coefficients = coefficients;
        }

        /** The informative curve with the longer payload estimate (the stronger evidence). */
        public Curve best() {
            Curve a = twosComplement, b = magnitude;
            if (a.informative() != b.informative()) return a.informative() ? a : b;
            return b.estimate > a.estimate ? b : a;
        }

        public String summary() {
            Curve c = best();
            String name = c.pairing == Pairing.TWOS_COMPLEMENT ? "two's complement" : "magnitude";
            if (coefficients == 0) return "no non-zero AC coefficients";
            if (!c.informative())
                return String.format("inconclusive - the coefficient histogram is flat (control p %.2f), so p stays high"
                        + " without any payload", c.controlP);
            if (c.estimate <= 0) return "no sequential payload: p drops at the first coefficients (" + name + " pairs)";
            if (c.estimate >= 1)
                return String.format("p stays high over all %d coefficients (%s pairs): the whole image may be embedded"
                        + " (capacity ~%d bytes)", coefficients, name, c.estimatedBits / 8);
            return String.format("sequential payload likely: p stays high up to %.1f%% of the coefficients (%s pairs),"
                    + " estimated length ~%d bytes (%d bits)", 100 * c.estimate, name, c.estimatedBits / 8, c.estimatedBits);
        }
    }

    /** Non-zero AC coefficients of all components in JSteg read order. */
    public static int[] readOrder(DctImage img) {
        long n = 0;
        for (DctImage.Component c : img.components()) n += (long) c.blockCount() * 63;
        int[] tmp = new int[(int) Math.min(n, Integer.MAX_VALUE - 8)];
        int k = 0;
        for (DctImage.Component c : img.components())
            for (int b = 0; b < c.blockCount() && k < tmp.length; b++)
                for (int z = 1; z < 64; z++) {
                    int v = c.zigZagCoefficient(b, z);
                    if (v != 0) tmp[k++] = v;
                }
        return java.util.Arrays.copyOf(tmp, k);
    }

    public static Result analyze(DctImage img, Progress progress) {
        return analyze(readOrder(img), progress);
    }

    /** Curves for a coefficient sequence (non-zero values in read order; zeros are skipped). */
    public static Result analyze(int[] values, Progress progress) {
        if (progress == null) progress = Progress.NONE;
        Curve tc = curve(values, Pairing.TWOS_COMPLEMENT, progress, 0);
        Curve mag = curve(values, Pairing.MAGNITUDE, progress, 0.5);
        int n = 0;
        for (int v : values) if (v != 0) n++;
        return new Result(tc, mag, n);
    }

    private static boolean carrier(int v, Pairing pairing) {
        return pairing == Pairing.TWOS_COMPLEMENT ? v != 0 && v != 1 : v != 0 && v != 1 && v != -1;
    }

    static Curve curve(int[] values, Pairing pairing, Progress progress, double progressOffset) {
        int n = values.length;
        double[] fraction = new double[STEPS], p = new double[STEPS], control = new double[STEPS];
        double[] chi = new double[STEPS];
        int[] pairCount = new int[STEPS];
        long[] carriersAt = new long[STEPS];
        long[] hist = new long[2 * BIN + 1];
        int pos = 0;
        long carriers = 0;
        for (int s = 0; s < STEPS; s++) {
            if ((s & 15) == 0) {
                Progress.check(progress);
                progress.update(progressOffset + 0.5 * s / STEPS);
            }
            int end = (int) Math.ceil((long) n * (s + 1) / (double) STEPS);
            for (; pos < end; pos++) {
                int v = values[pos];
                if (v >= -BIN && v <= BIN) hist[v + BIN]++;
                if (carrier(v, pairing)) carriers++;
            }
            fraction[s] = n == 0 ? (s + 1) / (double) STEPS : end / (double) n;
            Westfeld.Result main = test(hist, pairing, false), shifted = test(hist, pairing, true);
            p[s] = main.pairs < MIN_PAIRS ? Double.NaN : main.p;
            chi[s] = main.chi2;
            pairCount[s] = main.pairs;
            control[s] = shifted.pairs < MIN_PAIRS ? Double.NaN : shifted.p;
            carriersAt[s] = carriers;
        }
        double controlP = test(hist, pairing, true).p;
        Westfeld.Result full = test(hist, pairing, false);
        // payload end: last point with p >= HIGH_P before the first point with p < LOW_P. A point only counts
        // when (a) the shifted control pairs of the same prefix are clearly unequal (the prefix is not just
        // flat) and (b) the test has power there: a prefix with the shape of the whole image would already
        // give p < LOW_P at that size (otherwise a short prefix of a mildly unequal cover looks equalised too).
        int drop = STEPS;
        for (int s = 0; s < STEPS; s++) if (!Double.isNaN(p[s]) && p[s] < LOW_P) { drop = s; break; }
        int last = -1;
        for (int s = 0; s < drop; s++) {
            boolean equalised = !Double.isNaN(p[s]) && p[s] >= HIGH_P;
            boolean shaped = !Double.isNaN(control[s]) && control[s] < CONTROL_P;
            boolean power = full.pairs >= 2 && org.stegsuite.core.stat.ChiSquare.pValue(full.chi2 * fraction[s], full.pairs - 1) < LOW_P;
            if (equalised && shaped && power) last = s;
        }
        double estimate;
        long bits;
        boolean wholeImage = drop == STEPS && !Double.isNaN(p[STEPS - 1]) && p[STEPS - 1] >= HIGH_P
                && !Double.isNaN(controlP) && controlP < LOW_P;
        if (wholeImage) { estimate = 1.0; bits = carriers; }
        else if (last < 0) { estimate = 0; bits = 0; }
        else {
            estimate = fraction[last];
            bits = carriersAt[last];
            double refined = refine(fraction, chi, pairCount, drop);
            // the cumulative curve overshoots (p only falls once enough clean coefficients are mixed in);
            // accept the extrapolated boundary when it lies a little before the coarse one
            if (!Double.isNaN(refined) && refined < estimate && refined > 0.5 * estimate) {
                int idx = Math.max(0, Math.min(STEPS - 1, (int) Math.round(refined * STEPS) - 1));
                long refinedBits = carriersAt[idx];
                estimate = refined;
                bits = refinedBits;
            }
        }
        return new Curve(pairing, fraction, p, control, estimate, bits, controlP);
    }

    /**
     * Extrapolates the payload end from the points after the drop. With an equalised prefix of length L
     * (fractions of the sequence) and a cover with stationary pair imbalance, the cumulative statistic is
     * chi(f) = (f - L)^2 / f * const above the noise floor (about pairs/2), so sqrt(chi(f) * f) is linear in
     * f and crosses zero at L. Returns NaN when the fit is not usable.
     */
    static double refine(double[] f, double[] chi, int[] pairs, int from) {
        int n = 0;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int s = from; s < f.length; s++) {
            if (pairs[s] < MIN_PAIRS) continue;
            double y = Math.sqrt(Math.max(0, chi[s] - pairs[s] / 2.0) * f[s]);
            sx += f[s]; sy += y; sxx += f[s] * f[s]; sxy += f[s] * y;
            n++;
        }
        if (n < 5) return Double.NaN;
        double var = sxx - sx * sx / n;
        if (var <= 0) return Double.NaN;
        double k = (sxy - sx * sy / n) / var;
        double b = (sy - k * sx) / n;
        if (k <= 0) return Double.NaN;
        return -b / k;
    }

    /** Westfeld test on a signed histogram; {@code shifted} uses the (2i+1, 2i+2) control pairs. */
    static Westfeld.Result test(long[] hist, Pairing pairing, boolean shifted) {
        Westfeld.Accumulator acc = new Westfeld.Accumulator(MIN_PAIR);
        if (pairing == Pairing.MAGNITUDE) {
            for (int a = shifted ? 3 : 2; a + 1 <= BIN; a += 2)
                acc.pair(hist[BIN + a] + hist[BIN - a], hist[BIN + a + 1] + hist[BIN - a - 1]);
        } else {
            for (int e = -BIN + (shifted ? 1 : 0); e + 1 <= BIN; e += 2) {
                int lo = e, hi = e + 1;
                if (!shifted && lo == 0) continue;                       // (0, 1) is never used
                if (shifted && (lo == -1 || lo == 1 || hi == 0 || hi == 1 || lo == 0)) continue;
                acc.pair(hist[BIN + lo], hist[BIN + hi]);
            }
        }
        return acc.result();
    }
}
