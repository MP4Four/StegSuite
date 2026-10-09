package org.stegsuite.plugin.dct;

import org.junit.Test;

import static org.junit.Assert.*;

/** The two pairing conventions of the chi-square test and the minimum pair count. */
public class PairTestTest {

    private static void addMany(DctAnalysis.Stats s, int value, int count) {
        for (int i = 0; i < count; i++) s.add(value, false);
    }

    @Test public void twosComplementEqualisationIsSeenOnlyByThatPairing() {
        DctAnalysis.Stats s = new DctAnalysis.Stats();
        // two's complement pairs (2,3), (4,5) ... and (-2,-1), (-4,-3) ... are equal; on the negative side the
        // pair sizes alternate, so the magnitude pairs {|2|,|3|}, {|4|,|5|} ... are clearly unequal
        int[] pos = {400, 300, 220, 160, 120, 90, 70, 50, 40, 30, 24, 18, 14, 12, 10};
        for (int i = 0; i < pos.length; i++) {
            int e = 2 * (i + 1);
            addMany(s, e, pos[i]); addMany(s, e + 1, pos[i]);
            int neg = i % 2 == 0 ? 1000 / (i + 1) : 60 / (i + 1) + 5;
            addMany(s, -e, neg); addMany(s, -e + 1, neg);
        }
        s.finish();
        assertTrue(s.twosComplementPairs.pairs >= 8);
        assertEquals(0.0, s.twosComplementPairs.perDf, 1e-9);
        assertTrue("magnitude pairing must not look equalised: " + s.magnitudePairs.perDf, s.magnitudePairs.perDf > 5);
        assertSame(s.twosComplementPairs, s.best());
    }

    @Test public void magnitudeEqualisationIsSeenByMagnitudePairing() {
        DctAnalysis.Stats s = new DctAnalysis.Stats();
        int[] mags = {500, 380, 260, 180, 130, 100, 70, 50, 40, 30};
        for (int i = 0; i < mags.length; i++) {
            int a = 2 * (i + 1);
            addMany(s, a, mags[i]); addMany(s, -a, mags[i]);
            addMany(s, a + 1, mags[i]); addMany(s, -(a + 1), mags[i]);
        }
        addMany(s, -1, 2000); addMany(s, 1, 2000);
        s.finish();
        assertEquals(0.0, s.magnitudePairs.perDf, 1e-9);
        assertTrue(s.twosComplementPairs.perDf > 5);
        assertSame(s.magnitudePairs, s.best());
    }

    @Test public void sparsePairsAreIgnored() {
        DctAnalysis.Stats s = new DctAnalysis.Stats();
        addMany(s, 2, 500); addMany(s, 3, 500);         // one well populated equal pair
        addMany(s, 20, 9);                               // a+b = 9 < 10: ignored
        addMany(s, 24, 5); addMany(s, 25, 4);            // a+b = 9: ignored
        s.finish();
        assertEquals(1, s.magnitudePairs.pairs);
        assertEquals(0.0, s.magnitudePairs.chi2, 1e-9);
        assertEquals(DctAnalysis.MIN_PAIR_COUNT, 10);
    }

    @Test public void flatHistogramIsNotInformative() {
        DctAnalysis.Stats s = new DctAnalysis.Stats();
        for (int v = 2; v <= 32; v++) { addMany(s, v, 200); addMany(s, -v, 200); }
        s.finish();
        assertEquals(0.0, s.magnitudePairs.perDf, 1e-9);
        assertFalse(s.magnitudePairs.informative());
        assertFalse(s.twosComplementPairs.informative());
    }

    @Test public void onesRatioNoteIsInterpreted() {
        assertTrue(DctAnalysis.onesRatioNote(3000, 1000).contains("usual range;"));
        assertTrue(DctAnalysis.onesRatioNote(9000, 1000).contains("F5"));
        assertTrue(DctAnalysis.onesRatioNote(500, 1000).contains("atypical"));
        assertTrue(DctAnalysis.onesRatioNote(10, 5).contains("too few"));
    }
}
