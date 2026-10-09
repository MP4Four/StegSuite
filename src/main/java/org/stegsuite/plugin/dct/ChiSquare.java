package org.stegsuite.plugin.dct;

/** Package-local shortcut to {@link org.stegsuite.core.stat.ChiSquare}. */
final class ChiSquare {
    private ChiSquare() {}

    /** Upper tail probability P(X > chi2) of a chi-square distribution with df degrees of freedom. */
    static double pValue(double chi2, int df) {
        return org.stegsuite.core.stat.ChiSquare.pValue(chi2, df);
    }

    static double logGamma(double x) {
        return org.stegsuite.core.stat.ChiSquare.logGamma(x);
    }
}
