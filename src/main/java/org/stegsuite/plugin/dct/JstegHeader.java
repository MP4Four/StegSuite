package org.stegsuite.plugin.dct;

/**
 * Length header of the original JSteg (Derek Upham, jpeg-jsteg-v4): the LSB stream starts with a 5-bit
 * field A (most significant bit first) giving the size in bits of field B, then B (MSB first) = payload
 * length in bytes, then the payload bits. JSteg writes B with its leading one bit, optionally preceded by
 * a single zero, so a header is plausible when bitLength(B) is A or A - 1, B &gt; 0 and the payload fits in
 * the coefficients that carry bits. There is no magic number, so random streams pass this check fairly
 * often; a plausible header is a hint, a recognised payload behind it is the confirmation.
 */
public final class JstegHeader {
    private JstegHeader() {}

    public static final class Header {
        /** Size of the length field in bits (A). */
        public final int lengthBits;
        /** Payload length in bytes (B). */
        public final long length;
        /** Offset of the first payload bit in the stream. */
        public final int payloadBitOffset;
        /** Exact payload (truncated to the extracted stream if it is longer than what was read). */
        public final byte[] payload;
        public final boolean truncated;

        Header(int lengthBits, long length, int payloadBitOffset, byte[] payload, boolean truncated) {
            this.lengthBits = lengthBits;
            this.length = length;
            this.payloadBitOffset = payloadBitOffset;
            this.payload = payload;
            this.truncated = truncated;
        }

        @Override public String toString() {
            return String.format("A=%d, length %d bytes, payload starts at bit %d%s", lengthBits, length,
                    payloadBitOffset, truncated ? " (truncated to the extracted stream)" : "");
        }
    }

    /**
     * Parses the header of a packed MSB-first bit stream.
     *
     * @param stream        packed bits (most significant bit of each byte first)
     * @param carrierBits   number of bits the image can carry in this order (bounds the payload length)
     * @return the header with the sliced payload, or null if it is not plausible
     */
    public static Header parse(byte[] stream, long carrierBits) {
        long available = (long) stream.length * 8;
        if (available < 5) return null;
        int a = (int) bits(stream, 0, 5);
        if (a == 0 || 5 + a > available) return null;
        long b = bits(stream, 5, a);
        if (b <= 0) return null;
        int bitLength = 64 - Long.numberOfLeadingZeros(b);
        if (bitLength != a && bitLength != a - 1) return null;
        long payloadStart = 5 + a;
        if (payloadStart + b * 8 > carrierBits) return null;
        long readable = Math.max(0, (available - payloadStart) / 8);
        int n = (int) Math.min(b, readable);
        byte[] payload = new byte[n];
        for (int i = 0; i < n; i++) payload[i] = (byte) bits(stream, payloadStart + 8L * i, 8);
        return new Header(a, b, (int) payloadStart, payload, n < b);
    }

    /** Reads {@code count} (&lt;= 63) bits MSB first starting at bit {@code offset}. */
    static long bits(byte[] s, long offset, int count) {
        long v = 0;
        for (int i = 0; i < count; i++) {
            long bit = offset + i;
            v = (v << 1) | ((s[(int) (bit >>> 3)] >> (7 - (bit & 7))) & 1);
        }
        return v;
    }
}
