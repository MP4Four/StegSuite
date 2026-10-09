package org.stegsuite;

import org.stegsuite.core.jpeg.StandardTables;
import org.stegsuite.core.jpeg.ZigZag;

import java.io.ByteArrayOutputStream;

/**
 * Test-only minimal baseline JPEG writer. It encodes given quantized coefficients (natural order) with the
 * Annex K Huffman tables, which is what the DCT tests need: a JPEG whose coefficients are known exactly,
 * so embedding LSBs into them can be checked against the decoder.
 */
final class JpegTestWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int bitBuf;
    private int bitCount;

    private final int[] dcLumaCode = new int[256], dcLumaLen = new int[256];
    private final int[] acLumaCode = new int[256], acLumaLen = new int[256];
    private final int[] dcChromaCode = new int[256], dcChromaLen = new int[256];
    private final int[] acChromaCode = new int[256], acChromaLen = new int[256];

    /** One frame component of a crafted JPEG. */
    static final class Comp {
        final int h, v, quantId;
        final int[] quant;
        final int blocksPerLine, blocksPerColumn;
        final int[] coefficients;

        Comp(int h, int v, int quantId, int[] quant, int width, int height, int maxH, int maxV) {
            this.h = h;
            this.v = v;
            this.quantId = quantId;
            this.quant = quant;
            int compWidth = (width * h + maxH - 1) / maxH;
            int compHeight = (height * v + maxV - 1) / maxV;
            this.blocksPerLine = (compWidth + 7) / 8;
            this.blocksPerColumn = (compHeight + 7) / 8;
            this.coefficients = new int[blocksPerLine * blocksPerColumn * 64];
        }
    }

    static int maxH(Comp[] comps) {
        int m = 1;
        for (Comp c : comps) m = Math.max(m, c.h);
        return m;
    }

    static int maxV(Comp[] comps) {
        int m = 1;
        for (Comp c : comps) m = Math.max(m, c.v);
        return m;
    }

    private JpegTestWriter() {
        buildCodes(StandardTables.DC_LUMA_BITS, StandardTables.DC_LUMA_VALUES, dcLumaCode, dcLumaLen);
        buildCodes(StandardTables.AC_LUMA_BITS, StandardTables.AC_LUMA_VALUES, acLumaCode, acLumaLen);
        buildCodes(StandardTables.DC_CHROMA_BITS, StandardTables.DC_CHROMA_VALUES, dcChromaCode, dcChromaLen);
        buildCodes(StandardTables.AC_CHROMA_BITS, StandardTables.AC_CHROMA_VALUES, acChromaCode, acChromaLen);
    }

    private static void buildCodes(int[] bits, int[] values, int[] code, int[] length) {
        int c = 0, k = 0;
        for (int len = 1; len <= 16; len++) {
            for (int i = 0; i < bits[len - 1]; i++) {
                code[values[k]] = c;
                length[values[k]] = len;
                c++;
                k++;
            }
            c <<= 1;
        }
    }

    /**
     * Writes a single-component (grayscale) baseline JPEG.
     *
     * @param coefficients quantized coefficients, {@code block * 64 + naturalIndex}, row-major blocks
     * @param quant        quantization table in natural order
     * @param restartInterval MCU interval for RSTn markers, 0 for none
     */
    static byte[] grayscale(int width, int height, int blocksPerLine, int blocksPerColumn, int[] coefficients,
                            int[] quant, int restartInterval) {
        JpegTestWriter w = new JpegTestWriter();
        Comp[] comps = {fixedComp(1, 1, 0, quant, blocksPerLine, blocksPerColumn, coefficients)};
        w.writeHeaders(comps, width, height, restartInterval);
        w.writeEntropyInterleaved(comps, width, height, 1, 1, restartInterval);
        w.marker(0xd9);
        return w.out.toByteArray();
    }

    /** Writes a three-component (4:x:y) baseline JPEG with the given components, ids 1, 2 and 3. */
    static byte[] color(Comp[] comps, int width, int height, int restartInterval) {
        if (comps.length != 3) throw new IllegalArgumentException("expected 3 components");
        JpegTestWriter w = new JpegTestWriter();
        w.writeHeaders(comps, width, height, restartInterval);
        w.writeEntropyInterleaved(comps, width, height, maxH(comps), maxV(comps), restartInterval);
        w.marker(0xd9);
        return w.out.toByteArray();
    }

    private static Comp fixedComp(int h, int v, int quantId, int[] quant, int blocksPerLine, int blocksPerColumn,
                                  int[] coefficients) {
        Comp c = new Comp(h, v, quantId, quant, blocksPerLine * 8, blocksPerColumn * 8, h, v);
        if (c.blocksPerLine != blocksPerLine || c.blocksPerColumn != blocksPerColumn)
            throw new IllegalArgumentException("coefficient grid does not match the component geometry");
        System.arraycopy(coefficients, 0, c.coefficients, 0, coefficients.length);
        return c;
    }

    private void writeHeaders(Comp[] comps, int width, int height, int restartInterval) {
        int mh = maxH(comps), mv = maxV(comps);
        marker(0xd8);                                    // SOI
        segment(0xe0, new int[]{'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0}); // APP0 JFIF 1.01
        for (int i = 0; i < comps.length; i++) {
            if (hasQuantTable(comps, i)) {
                int[] dqt = new int[65];
                dqt[0] = comps[i].quantId;               // 8-bit values, table id
                for (int k = 0; k < 64; k++) dqt[1 + k] = comps[i].quant[ZigZag.NATURAL[k]];
                segment(0xdb, dqt);
            }
        }
        int[] sof = new int[6 + 3 * comps.length];
        sof[0] = 8;
        sof[1] = height >> 8;
        sof[2] = height & 0xff;
        sof[3] = width >> 8;
        sof[4] = width & 0xff;
        sof[5] = comps.length;
        for (int i = 0; i < comps.length; i++) {
            sof[6 + 3 * i] = i + 1;
            sof[7 + 3 * i] = (comps[i].h << 4) | comps[i].v;
            sof[8 + 3 * i] = comps[i].quantId;
        }
        segment(0xc0, sof);
        segment(0xc4, huffmanSegment(0, 0, StandardTables.DC_LUMA_BITS, StandardTables.DC_LUMA_VALUES));
        segment(0xc4, huffmanSegment(1, 0, StandardTables.AC_LUMA_BITS, StandardTables.AC_LUMA_VALUES));
        if (comps.length > 1) {
            segment(0xc4, huffmanSegment(0, 1, StandardTables.DC_CHROMA_BITS, StandardTables.DC_CHROMA_VALUES));
            segment(0xc4, huffmanSegment(1, 1, StandardTables.AC_CHROMA_BITS, StandardTables.AC_CHROMA_VALUES));
        }
        if (restartInterval > 0) segment(0xdd, new int[]{restartInterval >> 8, restartInterval & 0xff});
        int[] sos = new int[1 + 2 * comps.length + 3];
        sos[0] = comps.length;
        for (int i = 0; i < comps.length; i++) {
            sos[1 + 2 * i] = i + 1;
            sos[2 + 2 * i] = i == 0 ? 0x00 : 0x11;       // component 0 uses table set 0, chroma table set 1
        }
        sos[sos.length - 3] = 0;
        sos[sos.length - 2] = 63;
        sos[sos.length - 1] = 0;
        segment(0xda, sos);
    }

    /** True when this component's quantization table is the first one with that id (written once). */
    private static boolean hasQuantTable(Comp[] comps, int index) {
        for (int i = 0; i < index; i++) if (comps[i].quantId == comps[index].quantId) return false;
        return comps[index].quant != null;
    }

    private static int[] huffmanSegment(int tableClass, int id, int[] bits, int[] values) {
        int[] seg = new int[1 + 16 + values.length];
        seg[0] = (tableClass << 4) | id;
        System.arraycopy(bits, 0, seg, 1, 16);
        System.arraycopy(values, 0, seg, 17, values.length);
        return seg;
    }

    /** Encodes the scan in MCU order; data units outside a component plane are coded as zero blocks. */
    private void writeEntropyInterleaved(Comp[] comps, int width, int height, int maxH, int maxV, int restartInterval) {
        int[] pred = new int[comps.length];
        int[] zeroBlock = new int[64];
        int mcuW = (width + 8 * maxH - 1) / (8 * maxH);
        int mcuH = (height + 8 * maxV - 1) / (8 * maxV);
        int inInterval = 0, rst = 0;
        for (int my = 0; my < mcuH; my++) {
            for (int mx = 0; mx < mcuW; mx++) {
                if (restartInterval > 0 && inInterval == restartInterval) {
                    flushByte();
                    marker(0xd0 + (rst++ & 7));
                    pred = new int[comps.length];
                    inInterval = 0;
                }
                for (int ci = 0; ci < comps.length; ci++) {
                    Comp c = comps[ci];
                    for (int v = 0; v < c.v; v++) {
                        for (int h = 0; h < c.h; h++) {
                            int bx = mx * c.h + h, by = my * c.v + v;
                            if (bx < c.blocksPerLine && by < c.blocksPerColumn)
                                writeBlock(c.coefficients, (by * c.blocksPerLine + bx) * 64, ci, pred);
                            else
                                writeBlock(zeroBlock, 0, ci, pred);   // padding data unit
                        }
                    }
                }
                inInterval++;
            }
        }
        flushByte();
    }

    /** Writes one data unit: DC difference, AC run/size symbols and amplitude bits. */
    private void writeBlock(int[] coefficients, int base, int ci, int[] pred) {
        boolean luma = ci == 0;
        int[] dcCode = luma ? dcLumaCode : dcChromaCode, dcLen = luma ? dcLumaLen : dcChromaLen;
        int[] acCode = luma ? acLumaCode : acChromaCode, acLen = luma ? acLumaLen : acChromaLen;
        int dc = coefficients[base];
        int diff = dc - pred[ci];
        int size = category(diff);
        if (dcLen[size] == 0) throw new IllegalArgumentException("DC difference " + diff + " does not fit the test table");
        writeBits(dcCode[size], dcLen[size]);
        if (size > 0) writeBits(diff >= 0 ? diff : diff + (1 << size) - 1, size);
        pred[ci] = dc;
        int run = 0;
        for (int k = 1; k < 64; k++) {
            int v = coefficients[base + ZigZag.NATURAL[k]];
            if (v == 0) { run++; continue; }
            while (run > 15) { writeBits(acCode[0xf0], acLen[0xf0]); run -= 16; }
            int sz = category(v);
            int symbol = (run << 4) | sz;
            if (acLen[symbol] == 0) throw new IllegalArgumentException("AC symbol " + symbol + " missing from the test table");
            writeBits(acCode[symbol], acLen[symbol]);
            writeBits(v >= 0 ? v : v + (1 << sz) - 1, sz);
            run = 0;
        }
        if (run > 0) writeBits(acCode[0x00], acLen[0x00]);   // EOB
    }

    private static int category(int v) {
        int a = Math.abs(v), n = 0;
        while (a != 0) { a >>= 1; n++; }
        return n;
    }

    private void writeBits(int value, int length) {
        if (length == 0) throw new IllegalArgumentException("symbol missing from the test Huffman table");
        for (int i = length - 1; i >= 0; i--) {
            bitBuf = (bitBuf << 1) | ((value >> i) & 1);
            if (++bitCount == 8) flushByte();
        }
    }

    /** Pads with 1 bits and appends the byte, applying 0xFF00 byte stuffing. */
    private void flushByte() {
        if (bitCount == 0) return;
        int b = bitBuf << (8 - bitCount);
        b |= (1 << (8 - bitCount)) - 1;
        bitBuf = 0;
        bitCount = 0;
        out.write(b);
        if (b == 0xff) out.write(0x00);
    }

    private void segment(int marker, int[] payload) {
        marker(marker);
        int len = payload.length + 2;
        out.write(len >> 8);
        out.write(len & 0xff);
        for (int b : payload) out.write(b);
    }

    private void marker(int m) {
        out.write(0xff);
        out.write(m);
    }
}
