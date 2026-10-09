package org.stegsuite.core.stat;

/**
 * Chi-square tail probability for the Westfeld/Pfitzmann coefficient-pair test.
 * The regularized upper incomplete gamma function Q(a, x) is evaluated with the standard series and
 * continued fraction expansions, so p = Q(df / 2, chi2 / 2) is accurate for the df range used here.
 */
public final class ChiSquare {
    private ChiSquare() {}

    private static final int MAX_ITERATIONS = 300;
    private static final double EPSILON = 1e-13;
    private static final double TINY = 1e-300;

    /** Upper tail probability P(X > chi2) of a chi-square distribution with df degrees of freedom. */
    public static double pValue(double chi2, int df) {
        if (df <= 0 || chi2 <= 0) return 1.0;
        if (Double.isNaN(chi2) || Double.isInfinite(chi2)) return 0.0;
        return gammaQ(df / 2.0, chi2 / 2.0);
    }

    private static double gammaQ(double a, double x) {
        return x < a + 1.0 ? 1.0 - gammaSeries(a, x) : gammaContinuedFraction(a, x);
    }

    private static double gammaSeries(double a, double x) {
        double sum = 1.0 / a, term = sum, ap = a;
        for (int n = 1; n <= MAX_ITERATIONS; n++) {
            ap += 1.0;
            term *= x / ap;
            sum += term;
            if (Math.abs(term) < Math.abs(sum) * EPSILON) break;
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    private static double gammaContinuedFraction(double a, double x) {
        double b = x + 1.0 - a, c = 1.0 / TINY, d = 1.0 / b, h = d;
        for (int i = 1; i <= MAX_ITERATIONS; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < TINY) d = TINY;
            c = b + an / c;
            if (Math.abs(c) < TINY) c = TINY;
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < EPSILON) break;
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    /** Lanczos approximation of log(Gamma(x)) for x > 0. */
    public static double logGamma(double x) {
        double[] coefficients = {
                76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5
        };
        double y = x, tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double sum = 1.000000000190015;
        for (int j = 0; j < 6; j++) sum += coefficients[j] / ++y;
        return -tmp + Math.log(2.5066282746310005 * sum / x);
    }
}
