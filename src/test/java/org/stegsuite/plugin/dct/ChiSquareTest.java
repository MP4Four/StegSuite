package org.stegsuite.plugin.dct;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Unit tests for the chi-square tail probability used by the coefficient pair test. */
public class ChiSquareTest {

    @Test public void matchesTableValues() {
        assertEquals(0.050, ChiSquare.pValue(3.8415, 1), 0.001);   // chi2 0.95 quantile, df 1
        assertEquals(0.050, ChiSquare.pValue(5.9915, 2), 0.001);
        assertEquals(0.050, ChiSquare.pValue(18.307, 10), 0.001);
        assertEquals(0.010, ChiSquare.pValue(23.209, 10), 0.001);
        assertEquals(0.440, ChiSquare.pValue(10, 10), 0.02);       // mean of the distribution
    }

    @Test public void handlesDegenerateInput() {
        assertEquals(1.0, ChiSquare.pValue(0, 5), 1e-9);
        assertEquals(1.0, ChiSquare.pValue(5, 0), 1e-9);
        assertEquals(1.0, ChiSquare.pValue(-1, 3), 1e-9);
        assertEquals(0.0, ChiSquare.pValue(Double.POSITIVE_INFINITY, 3), 1e-9);
    }

    @Test public void logGammaMatchesFactorials() {
        assertEquals(0.0, ChiSquare.logGamma(1), 1e-9);            // 0! = 1
        assertEquals(0.0, ChiSquare.logGamma(2), 1e-9);            // 1! = 1
        assertEquals(Math.log(120), ChiSquare.logGamma(6), 1e-9);  // 5! = 120
    }
}
