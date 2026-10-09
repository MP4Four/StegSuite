package org.stegsuite.core.jpeg;

/**
 * JPEG zig-zag (entropy coded) ordering of the 64 coefficients of an 8x8 block.
 * Coefficients are stored in natural (row-major) order so 2D frequency indexes can be used directly;
 * {@link #NATURAL} converts a zig-zag scan position into that index.
 */
public final class ZigZag {
    private ZigZag() {}

    /** Zig-zag scan position k (0..63) to natural row-major index. */
    public static final int[] NATURAL = {
             0,  1,  8, 16,  9,  2,  3, 10,
            17, 24, 32, 25, 18, 11,  4,  5,
            12, 19, 26, 33, 40, 48, 41, 34,
            27, 20, 13,  6,  7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36,
            29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46,
            53, 60, 61, 54, 47, 55, 62, 63
    };
}
