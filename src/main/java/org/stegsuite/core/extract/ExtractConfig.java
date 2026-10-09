package org.stegsuite.core.extract;

/**
 * Unified bit-extraction parameters (superset of Stegsolve "Data Extract" and zsteg's b/channel/order options).
 *
 * @param order         channel visiting order, letters from R,G,B,A (e.g. "RGB", "BGR", "RGBA")
 * @param masks         bit masks per channel indexed R=0,G=1,B=2,A=3 (bit i selects bit plane i)
 * @param msbFirstInSample visit selected bits of a sample from high to low (zsteg "msb") instead of low to high
 * @param columnOrder   walk pixels column by column (zsteg "yx") instead of row by row ("xy")
 * @param packLsbFirst  first extracted bit becomes bit 0 of the output byte (default: bit 7, like zsteg/Stegsolve)
 */
public final class ExtractConfig {
    private final String order;
    private final int[] masks;
    private final boolean msbFirstInSample;
    private final boolean columnOrder;
    private final boolean packLsbFirst;
    public ExtractConfig(String order, int[] masks, boolean msbFirstInSample, boolean columnOrder, boolean packLsbFirst) {
        this.order = order;
        this.masks = masks;
        this.msbFirstInSample = msbFirstInSample;
        this.columnOrder = columnOrder;
        this.packLsbFirst = packLsbFirst;
    }
    public String order() { return order; }
    public int[] masks() { return masks; }
    public boolean msbFirstInSample() { return msbFirstInSample; }
    public boolean columnOrder() { return columnOrder; }
    public boolean packLsbFirst() { return packLsbFirst; }

    public static ExtractConfig uniform(String order, int mask, boolean msbFirstInSample, boolean columnOrder, boolean packLsbFirst) {
        int[] m = new int[4];
        for (char c : order.toCharArray()) m[channelIndex(c)] = mask;
        return new ExtractConfig(order, m, msbFirstInSample, columnOrder, packLsbFirst);
    }

    public static int channelIndex(char c) {
        switch (Character.toUpperCase(c)) {
            case 'R': return 0;
            case 'G': return 1;
            case 'B': return 2;
            case 'A': return 3;
            default: throw new IllegalArgumentException("bad channel " + c);
        }
    }

    public static int shiftOf(char c) {
        switch (Character.toUpperCase(c)) {
            case 'R': return 16;
            case 'G': return 8;
            case 'B': return 0;
            case 'A': return 24;
            default: throw new IllegalArgumentException("bad channel " + c);
        }
    }

    /** Bits extracted per pixel. */
    public int bitsPerPixel() {
        int n = 0;
        for (char c : order.toCharArray()) n += Integer.bitCount(masks[channelIndex(c)] & 0xff);
        return n;
    }

    /** zsteg-like label, e.g. "b1,rgb,lsb,xy". */
    public String label() {
        StringBuilder ch = new StringBuilder();
        int common = -1; boolean same = true;
        for (char c : order.toCharArray()) {
            int m = masks[channelIndex(c)] & 0xff;
            if (m == 0) continue;
            ch.append(Character.toLowerCase(c));
            if (common == -1) common = m; else if (common != m) same = false;
        }
        String bits;
        if (same && common > 0 && (common & (common + 1)) == 0) bits = "b" + Integer.bitCount(common);
        else if (same && common > 0) bits = String.format("m%02x", common);
        else if (allLowMasks()) {
            // zsteg-style mixed per-channel widths, e.g. "b1r+b2g"
            StringBuilder s = new StringBuilder();
            for (char c : order.toCharArray()) {
                int m = masks[channelIndex(c)] & 0xff;
                if (m == 0) continue;
                if (s.length() > 0) s.append('+');
                s.append('b').append(Integer.bitCount(m)).append(Character.toLowerCase(c));
            }
            bits = s.toString();
        } else {
            StringBuilder s = new StringBuilder("m");
            for (char c : order.toCharArray()) s.append(String.format("%02x", masks[channelIndex(c)] & 0xff));
            bits = s.toString();
        }
        return bits + "," + ch + "," + (msbFirstInSample ? "msb" : "lsb") + "," + (columnOrder ? "yx" : "xy")
                + (packLsbFirst ? ",packLSB" : "");
    }

    /** True when every used channel mask is of the form 0b0..01..1 (bits 0..n-1). */
    private boolean allLowMasks() {
        for (char c : order.toCharArray()) {
            int m = masks[channelIndex(c)] & 0xff;
            if (m != 0 && (m & (m + 1)) != 0) return false;
        }
        return true;
    }

    /** True if the channels use different masks. */
    public boolean mixed() {
        int common = -1;
        for (char c : order.toCharArray()) {
            int m = masks[channelIndex(c)] & 0xff;
            if (m == 0) continue;
            if (common == -1) common = m; else if (common != m) return true;
        }
        return false;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ExtractConfig)) return false;
        ExtractConfig e = (ExtractConfig) o;
        return label().equals(e.label()) && order.equals(e.order());
    }

    @Override public int hashCode() { return (order + "|" + label()).hashCode(); }

    @Override public String toString() { return label(); }
}
