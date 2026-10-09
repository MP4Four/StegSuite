package org.stegsuite.core.jpeg;

import java.util.Arrays;

/**
 * One JPEG Huffman table (ITU-T T.81 B.2.4): the 16 code-length counts plus the symbol list,
 * pre-processed into the canonical decode arrays (min code / symbol pointer per length).
 */
public final class HuffmanTable {
    private final int[] bits;                 // code counts for lengths 1..16
    private final int[] values;               // symbols, in canonical code order
    private final int[] minCode = new int[17]; // first canonical code of each length
    private final int[] valPtr = new int[17];  // index of the first symbol of each length
    /** Bits of the fast lookup table: codes of up to this length are decoded with one table access. */
    public static final int LOOKUP_BITS = 9;
    /**
     * Fast decode table indexed by the next {@link #LOOKUP_BITS} bits of the stream: (code length << 8) | symbol
     * for codes of at most LOOKUP_BITS bits, 0 when the code is longer (slow canonical path).
     */
    private final int[] lookup = new int[1 << LOOKUP_BITS];

    public HuffmanTable(int[] bits, int[] values) {
        if (bits.length < 16) throw new IllegalArgumentException("Huffman BITS must have 16 entries");
        this.bits = Arrays.copyOf(bits, 16);
        this.values = values.clone();
        int code = 0, k = 0;
        for (int len = 1; len <= 16; len++) {
            int n = this.bits[len - 1];
            minCode[len] = code;
            valPtr[len] = k;
            code += n;
            k += n;
            if (code > (1 << len)) throw new IllegalArgumentException("oversubscribed Huffman table at length " + len);
            code <<= 1;
        }
        if (k != this.values.length) throw new IllegalArgumentException("Huffman table has " + k + " codes but " + this.values.length + " symbols");
        for (int len = 1; len <= LOOKUP_BITS; len++) {
            for (int i = 0; i < this.bits[len - 1]; i++) {
                int c = minCode[len] + i;
                int sym = this.values[valPtr[len] + i];
                int shift = LOOKUP_BITS - len;
                for (int fill = 0; fill < (1 << shift); fill++) lookup[(c << shift) | fill] = (len << 8) | sym;
            }
        }
    }

    /** Fast table entry for the next LOOKUP_BITS bits: (length << 8) | symbol, or 0 for longer codes. */
    public int lookup(int nextBits) { return lookup[nextBits]; }

    /** Symbol at the given canonical index (no array copy; used by the entropy decoder). */
    public int symbol(int index) { return values[index]; }

    /** First canonical code of the given length (1..16). */
    public int minCode(int length) { return minCode[length]; }

    /** Symbol index of the first code of the given length (1..16). */
    public int valuePointer(int length) { return valPtr[length]; }

    /** Number of codes of the given length (1..16). */
    public int count(int length) { return bits[length - 1]; }

    /** True if this table is exactly the given Annex K table. */
    public boolean matches(int[] otherBits, int[] otherValues) {
        return Arrays.equals(bits, otherBits) && Arrays.equals(values, otherValues);
    }

    /** Short description of the table shape, e.g. "12 symbols, 2-9 bits". */
    public String describe() {
        int min = 0, max = 0;
        for (int len = 1; len <= 16; len++) if (bits[len - 1] > 0) { if (min == 0) min = len; max = len; }
        return values.length + " symbols, " + (min == 0 ? "empty" : min + "-" + max + " bits");
    }
}
