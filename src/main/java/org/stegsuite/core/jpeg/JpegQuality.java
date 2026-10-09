package org.stegsuite.core.jpeg;

import java.util.Arrays;

/** IJG quality of JPEG quantization tables: exact Annex K matches, otherwise an estimate. */
public final class JpegQuality {
    private JpegQuality() {}

    /** Quality of one table (natural order): exact Annex K luminance/chrominance match at q, else estimated. */
    public static final class Estimate {
        /** 1..100, or 0 when no estimate is possible. */
        public final int quality;
        /** True when the table is exactly an Annex K table scaled to {@link #quality}. */
        public final boolean exact;
        /** "luminance", "chrominance" or null for custom tables. */
        public final String annexKTable;

        Estimate(int quality, boolean exact, String annexKTable) {
            this.quality = quality;
            this.exact = exact;
            this.annexKTable = annexKTable;
        }

        @Override public String toString() {
            if (exact) return "Annex K " + annexKTable + " table, IJG quality " + quality;
            return "custom table, IJG quality ~" + quality;
        }
    }

    public static Estimate of(int[] table) {
        for (int q = 1; q <= 100; q++) {
            if (StandardTables.matchesQuality(table, StandardTables.QUANT_LUMINANCE, q)) return new Estimate(q, true, "luminance");
            if (StandardTables.matchesQuality(table, StandardTables.QUANT_CHROMINANCE, q)) return new Estimate(q, true, "chrominance");
        }
        return new Estimate(estimate(table), false, null);
    }

    /** Median of the quality implied by each coefficient relative to the Annex K luminance table. */
    public static int estimate(int[] table) {
        double[] implied = new double[64];
        int n = 0;
        for (int i = 0; i < 64; i++) {
            int base = StandardTables.QUANT_LUMINANCE[i];
            if (base <= 0) continue;
            double scale = table[i] * 100.0 / base;
            if (scale <= 0) continue;
            // inverse of the IJG scaling: scale = 5000/q below quality 50, scale = 200 - 2q above
            implied[n++] = scale > 100 ? (200 - scale) / 2 : 5000.0 / scale;
        }
        if (n == 0) return 0;
        Arrays.sort(implied, 0, n);
        double median = implied[n / 2];
        return (int) Math.max(1, Math.min(100, Math.round(median)));
    }

    /**
     * Overall quality of a file from its tables (index = table id, null = absent): the luminance table (id 0, or
     * the first present one) decides; -1 when the file has no quantization table.
     */
    public static Estimate ofFile(int[][] tables) {
        for (int[] t : tables) if (t != null) return of(t);
        return null;
    }
}
