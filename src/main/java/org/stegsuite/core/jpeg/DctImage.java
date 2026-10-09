package org.stegsuite.core.jpeg;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of decoding the DCT (Huffman) layers of a JPEG file: frame layout, quantization and Huffman
 * tables and the quantized coefficients of every component, stored in natural (row-major) block order.
 *
 * <p>Coefficients are the values as they appear in the entropy-coded stream, i.e. still quantized.
 * Dequantization, the inverse DCT and upsampling are only done on demand by {@link #toBufferedImage()}.
 */
public final class DctImage {

    /** Largest frame (width * height) the decoder accepts; protects against crafted headers. */
    public static final long MAX_PIXELS = 100_000_000L;
    /** Largest number of 8x8 blocks over all components (four full-resolution components at MAX_PIXELS). */
    public static final long MAX_TOTAL_BLOCKS = 4 * ((MAX_PIXELS + 63) / 64) + 4 * 65536L;

    /**
     * Number of blocks of a component plane computed in long arithmetic.
     *
     * @throws IllegalArgumentException if it is negative or exceeds {@link #MAX_TOTAL_BLOCKS}
     */
    static long checkedBlockCount(int blocksPerLine, int blocksPerColumn) {
        long n = (long) blocksPerLine * blocksPerColumn;
        if (blocksPerLine < 0 || blocksPerColumn < 0 || n > MAX_TOTAL_BLOCKS)
            throw new IllegalArgumentException(String.format("bad block count %d x %d (limit %,d blocks)",
                    blocksPerLine, blocksPerColumn, MAX_TOTAL_BLOCKS));
        return n;
    }

    /** One quantization table (values in natural row-major order). */
    public static final class QuantTable {
        private final int id;
        private final int precision;
        private final int[] values;

        QuantTable(int id, int precision, int[] values) {
            this.id = id;
            this.precision = precision;
            this.values = values;
        }

        public int id() { return id; }
        /** 0 = 8-bit values, 1 = 16-bit values. */
        public int precision() { return precision; }
        /** Natural (row-major) order, 64 values. */
        public int[] values() { return values; }
        public int at(int naturalIndex) { return values[naturalIndex]; }
    }

    /** One frame component: sampling factors, quantization table and its coefficient blocks. */
    public static final class Component {
        private final int id;
        private final int h;
        private final int v;
        private final int quantId;
        private final int blocksPerLine;
        private final int blocksPerColumn;
        private final int downsampledWidth;
        private final int downsampledHeight;
        private final short[] coeffs;

        Component(int id, int h, int v, int quantId, int blocksPerLine, int blocksPerColumn,
                  int downsampledWidth, int downsampledHeight) {
            this.id = id;
            this.h = h;
            this.v = v;
            this.quantId = quantId;
            this.blocksPerLine = blocksPerLine;
            this.blocksPerColumn = blocksPerColumn;
            this.downsampledWidth = downsampledWidth;
            this.downsampledHeight = downsampledHeight;
            // one spare block (index blockCount) absorbs data units that fall outside the plane,
            // e.g. the padding blocks of the widest MCU row/column, which must still be decoded.
            long size = (checkedBlockCount(blocksPerLine, blocksPerColumn) + 1) * 64L;
            this.coeffs = new short[(int) size];
        }

        public int id() { return id; }
        public int h() { return h; }
        public int v() { return v; }
        public int quantId() { return quantId; }
        public int blocksPerLine() { return blocksPerLine; }
        public int blocksPerColumn() { return blocksPerColumn; }
        public int blockCount() { return blocksPerLine * blocksPerColumn; }

        /** Index of the spare block that holds data units outside the component plane. */
        public int scratchBlock() { return blocksPerLine * blocksPerColumn; }
        /** Width of the component plane in samples (blocks * 8, including padding). */
        public int sampleWidth() { return blocksPerLine * 8; }
        /** Height of the component plane in samples (blocks * 8, including padding). */
        public int sampleHeight() { return blocksPerColumn * 8; }
        /** Width of the component plane as used by the frame (before upsampling). */
        public int downsampledWidth() { return downsampledWidth; }
        public int downsampledHeight() { return downsampledHeight; }

        /** Backing array (block * 64 + natural index), for the inverse DCT; not a copy. */
        short[] raw() { return coeffs; }

        /** Quantized coefficient at natural index 0..63 of a block; block = y * blocksPerLine + x. */
        public int coefficient(int block, int naturalIndex) {
            return coeffs[(block << 6) | (naturalIndex & 63)];
        }

        void setCoefficient(int block, int naturalIndex, int value) {
            coeffs[(block << 6) | (naturalIndex & 63)] = (short) value;
        }

        void addCoefficient(int block, int naturalIndex, int delta) {
            int i = (block << 6) | (naturalIndex & 63);
            coeffs[i] = (short) (coeffs[i] + delta);
        }

        /** Quantized coefficient at zig-zag scan position 0..63 (0 = DC) of a block. */
        public int zigZagCoefficient(int block, int k) {
            return coeffs[(block << 6) | ZigZag.NATURAL[k]];
        }

        /** Number of non-zero AC coefficients of this component. */
        public int nonZeroAcCount() {
            int n = 0;
            for (int b = 0; b < blocksPerLine * blocksPerColumn; b++) {
                int base = b << 6;
                for (int k = 1; k < 64; k++) if (coeffs[base + ZigZag.NATURAL[k]] != 0) n++;
            }
            return n;
        }
    }

    /** One Huffman table as found in the file (plus its Annex K match, if any). */
    public static final class HuffmanInfo {
        private final boolean dc;
        private final int id;
        private final HuffmanTable table;
        private final String standardName;

        HuffmanInfo(boolean dc, int id, HuffmanTable table, String standardName) {
            this.dc = dc;
            this.id = id;
            this.table = table;
            this.standardName = standardName;
        }

        public boolean isDc() { return dc; }
        public int id() { return id; }
        public HuffmanTable table() { return table; }
        /** Annex K table name if the bits/symbols match one exactly, else null. */
        public String standardName() { return standardName; }
    }

    private final int width;
    private final int height;
    private final int precision;
    private final int maxH;
    private final int maxV;
    private final boolean progressive;
    private final String frameType;
    private final int restartInterval;
    private final int scanCount;
    private final List<Component> components;
    private final List<QuantTable> quantTables;
    private final List<HuffmanInfo> huffmanTables;
    private final List<String> comments;
    private final String jfifVersion;
    private final int adobeTransform;
    private final List<String> warnings = new ArrayList<>();

    DctImage(int width, int height, int precision, int maxH, int maxV, boolean progressive, String frameType,
             int restartInterval, int scanCount, List<Component> components, List<QuantTable> quantTables,
             List<HuffmanInfo> huffmanTables, List<String> comments, String jfifVersion, int adobeTransform) {
        this.width = width;
        this.height = height;
        this.precision = precision;
        this.maxH = maxH;
        this.maxV = maxV;
        this.progressive = progressive;
        this.frameType = frameType;
        this.restartInterval = restartInterval;
        this.scanCount = scanCount;
        this.components = components;
        this.quantTables = quantTables;
        this.huffmanTables = huffmanTables;
        this.comments = comments;
        this.jfifVersion = jfifVersion;
        this.adobeTransform = adobeTransform;
    }

    public int width() { return width; }
    public int height() { return height; }
    /** Sample precision in bits (8 for all files this decoder reconstructs). */
    public int precision() { return precision; }
    public int maxH() { return maxH; }
    public int maxV() { return maxV; }
    public boolean progressive() { return progressive; }
    /** "baseline", "extended sequential" or "progressive". */
    public String frameType() { return frameType; }
    /** Restart interval in MCUs, 0 if none. */
    public int restartInterval() { return restartInterval; }
    /** Number of SOS scans decoded. */
    public int scanCount() { return scanCount; }
    public List<Component> components() { return components; }
    public List<QuantTable> quantTables() { return quantTables; }
    public List<HuffmanInfo> huffmanTables() { return huffmanTables; }
    public List<String> comments() { return comments; }
    /** "1.01"/"1.02" when a JFIF APP0 marker was present, else null. */
    public String jfifVersion() { return jfifVersion; }
    /** Adobe APP14 colour transform (-1 when no APP14 marker was present). */
    public int adobeTransform() { return adobeTransform; }

    /** Non-fatal problems found while decoding (truncated scans, unsupported sub-features, ...). */
    public List<String> warnings() { return warnings; }

    /** Quantization table of a component, or null if the file references a missing table. */
    public QuantTable quantTableOf(Component c) {
        for (QuantTable q : quantTables) if (q.id() == c.quantId()) return q;
        return null;
    }

    /**
     * Reconstructs the image (dequantize, inverse DCT, upsample, colour convert).
     * Returns null for component layouts this decoder cannot render (e.g. CMYK / YCCK).
     */
    public BufferedImage toBufferedImage() {
        return InverseDct.toRgb(this);
    }
}
