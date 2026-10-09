package org.stegsuite.plugin.dct;

import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.ZigZag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * LSB extraction from quantized DCT coefficients: the classic JSteg layout and the variants that show up in
 * CTF files (DC included, zeros included, raster instead of zig-zag coefficient order, luma only) plus a
 * password keyed permutation.
 *
 * <p>JSteg hides one bit per non-zero AC coefficient, replacing its least significant bit; the coefficients
 * are visited component by component, block by block in raster order and inside a block in zig-zag order,
 * skipping the DC coefficient and all zero coefficients. The original JSteg also skips +1 (coefficients 0
 * and 1 are left alone); a common variant skips both +-1. Bits are packed most significant first.
 *
 * <p>{@link Mode#KEYED} is a custom StegSuite order; it is not the F5 or OutGuess permutation and cannot
 * read files produced by those tools.
 */
public final class DctExtraction {
    private DctExtraction() {}

    /** Hard cap on the extracted stream so a huge image cannot exhaust memory. */
    public static final int MAX_BYTES = 256 * 1024;

    /** Cap on the number of coefficients the keyed permutation materialises. */
    public static final int MAX_KEYED_POSITIONS = 4 * 1024 * 1024;

    /** One extraction layout. */
    public enum Mode {
        JSTEG_AC("JSteg: LSB of non-zero AC coefficients, zig-zag order", false, false, false, false, false),
        JSTEG_ORIGINAL("JSteg original: AC coefficients except 0 and +1, zig-zag order", false, false, false, false, false, true),
        JSTEG_HEADER("JSteg original with length header (exact payload)", false, false, false, false, false, true),
        JSTEG_AC_LUMA("JSteg (first component only)", false, false, false, true, false),
        JSTEG_AC_SKIP_ONES("JSteg variant: AC coefficients except 0 and +-1", false, false, true, false, false),
        ALL_NON_ZERO("LSB of all non-zero coefficients (DC included)", true, false, false, false, false),
        ALL("LSB of every coefficient (zeros and DC included)", true, true, false, false, false),
        JSTEG_AC_NATURAL("LSB of non-zero AC coefficients, raster (non zig-zag) order", false, false, false, false, true),
        KEYED("custom order (not compatible with F5/OutGuess)",
                false, false, false, false, false);

        final String label;
        final boolean includeDc;
        final boolean includeZeros;
        final boolean skipOnes;
        final boolean lumaOnly;
        final boolean naturalOrder;
        /** Skip +1 but not -1 (original JSteg reads the LSB of the stored two's complement value). */
        final boolean skipPlusOne;

        Mode(String label, boolean includeDc, boolean includeZeros, boolean skipOnes, boolean lumaOnly, boolean naturalOrder) {
            this(label, includeDc, includeZeros, skipOnes, lumaOnly, naturalOrder, false);
        }

        Mode(String label, boolean includeDc, boolean includeZeros, boolean skipOnes, boolean lumaOnly, boolean naturalOrder,
             boolean skipPlusOne) {
            this.skipPlusOne = skipPlusOne;
            this.label = label;
            this.includeDc = includeDc;
            this.includeZeros = includeZeros;
            this.skipOnes = skipOnes;
            this.lumaOnly = lumaOnly;
            this.naturalOrder = naturalOrder;
        }

        public String label() { return label; }
    }

    /** Modes tried without a password, cheapest and most common first. */
    public static final Mode[] SEQUENTIAL_MODES = {
            Mode.JSTEG_HEADER, Mode.JSTEG_AC, Mode.JSTEG_ORIGINAL, Mode.JSTEG_AC_LUMA, Mode.JSTEG_AC_SKIP_ONES, Mode.ALL_NON_ZERO, Mode.ALL, Mode.JSTEG_AC_NATURAL
    };

    /** Number of coefficients a mode can read a bit from. */
    public static long usablePositions(DctImage img, Mode mode) {
        long n = 0;
        for (int ci = 0; ci < img.components().size(); ci++) {
            if (mode.lumaOnly && ci > 0) break;
            DctImage.Component c = img.components().get(ci);
            for (int b = 0; b < c.blockCount(); b++)
                for (int k = 0; k < 64; k++) {
                    int natural = mode.naturalOrder ? k : ZigZag.NATURAL[k];
                    if (natural == 0 && !mode.includeDc) continue;
                    int v = c.coefficient(b, natural);
                    if (v == 0 && !mode.includeZeros) continue;
                    if (mode.skipOnes && (v == 1 || v == -1)) continue;
                    if (mode.skipPlusOne && v == 1) continue;
                    n++;
                }
        }
        return n;
    }

    /**
     * Extracts an LSB stream.
     *
     * @param password key for {@link Mode#KEYED}; ignored by the other modes
     * @return the packed bytes (at most {@link #MAX_BYTES}), or null if a keyed mode is not applicable
     */
    public static byte[] extract(DctImage img, Mode mode, String password) {
        if (mode == Mode.KEYED) return extractKeyed(img, password);
        if (mode == Mode.JSTEG_HEADER) {
            JstegHeader.Header h = jstegHeader(img);
            return h == null ? new byte[0] : h.payload;
        }
        return extractSequential(img, mode);
    }

    /** The original JSteg length header at the start of the JSteg stream, or null if it is not plausible. */
    public static JstegHeader.Header jstegHeader(DctImage img) {
        return JstegHeader.parse(extractSequential(img, Mode.JSTEG_ORIGINAL), usablePositions(img, Mode.JSTEG_ORIGINAL));
    }

    private static byte[] extractSequential(DctImage img, Mode mode) {
        BitSink sink = new BitSink();
        for (int ci = 0; ci < img.components().size() && !sink.full(); ci++) {
            if (mode.lumaOnly && ci > 0) break;
            DctImage.Component c = img.components().get(ci);
            for (int b = 0; b < c.blockCount() && !sink.full(); b++)
                for (int k = 0; k < 64 && !sink.full(); k++) {
                    int natural = mode.naturalOrder ? k : ZigZag.NATURAL[k];
                    if (natural == 0 && !mode.includeDc) continue;
                    int v = c.coefficient(b, natural);
                    if (v == 0 && !mode.includeZeros) continue;
                    if (mode.skipOnes && (v == 1 || v == -1)) continue;
                    if (mode.skipPlusOne && v == 1) continue;
                    sink.add(v & 1);
                }
        }
        return sink.toByteArray();
    }

    /**
     * Keyed mode: the JSteg coefficient positions are permuted with a Fisher-Yates shuffle driven by a
     * SHA-1 seeded xorshift generator. This is a custom StegSuite order, NOT compatible with F5 or OutGuess;
     * reads are only meaningful for files embedded with the same password and scheme.
     */
    private static byte[] extractKeyed(DctImage img, String password) {
        long usable = usablePositions(img, Mode.JSTEG_AC);
        if (usable == 0) return new byte[0];
        if (usable > MAX_KEYED_POSITIONS) return null;   // do not shuffle a truncated position list
        long[] positions = new long[(int) usable];
        int n = 0;
        for (int ci = 0; ci < img.components().size(); ci++) {
            DctImage.Component c = img.components().get(ci);
            for (int b = 0; b < c.blockCount(); b++)
                for (int k = 1; k < 64; k++) {
                    int natural = ZigZag.NATURAL[k];
                    if (c.coefficient(b, natural) == 0) continue;
                    positions[n++] = ((long) ci << 40) | ((long) b << 6) | k;
                }
        }
        long state = seed(password);
        for (int i = n - 1; i > 0; i--) {
            state = nextRandom(state);
            int j = (int) ((state >>> 1) % (i + 1));
            long t = positions[i];
            positions[i] = positions[j];
            positions[j] = t;
        }
        BitSink sink = new BitSink();
        for (int i = 0; i < n && !sink.full(); i++) {
            long p = positions[i];
            int ci = (int) (p >>> 40), b = (int) ((p >>> 6) & 0xffffffffL), k = (int) (p & 63);
            sink.add(img.components().get(ci).zigZagCoefficient(b, k) & 1);
        }
        return sink.toByteArray();
    }

    /** Deterministic key material for the keyed mode (SHA-1 of the password). */
    private static long seed(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(("StegSuite-DCT:" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            long s = 0;
            for (int i = 0; i < 8; i++) s = (s << 8) | (h[i] & 0xffL);
            return s == 0 ? 0x9E3779B97F4A7C15L : s;
        } catch (Exception e) {
            return 0x9E3779B97F4A7C15L;
        }
    }

    private static long nextRandom(long state) {
        long s = state;
        s ^= s >>> 12;
        s ^= s << 25;
        s ^= s >>> 27;
        return s * 0x2545F4914F6CDD1DL;
    }

    /** Packs bits most significant first and stops at {@link #MAX_BYTES}. */
    private static final class BitSink {
        private final byte[] out = new byte[MAX_BYTES];
        private int bitIndex;
        private int current;

        boolean full() { return bitIndex >= MAX_BYTES * 8; }

        void add(int bit) {
            current = (current << 1) | (bit & 1);
            bitIndex++;
            if ((bitIndex & 7) == 0) {
                out[(bitIndex >> 3) - 1] = (byte) current;
                current = 0;
            }
        }

        byte[] toByteArray() {
            int whole = bitIndex >> 3, rest = bitIndex & 7;
            byte[] result = Arrays.copyOf(out, whole + (rest == 0 ? 0 : 1));
            if (rest != 0) result[whole] = (byte) (current << (8 - rest));
            return result;
        }
    }
}
