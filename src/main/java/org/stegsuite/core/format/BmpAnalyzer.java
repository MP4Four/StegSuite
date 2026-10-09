package org.stegsuite.core.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** BMP header decoder with file-size and pixel-array consistency checks. */
public final class BmpAnalyzer {
    private BmpAnalyzer() {}

    public static void analyze(byte[] d, FormatReport r) {
        r.format = "BMP";
        if (d.length < 54) { r.warn("File too short for BMP headers"); return; }
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int fileSize = b.getInt(2), reserved = b.getInt(6), offBits = b.getInt(10), hdr = b.getInt(14);
        int w = b.getInt(18), h = b.getInt(22);
        int planes = b.getShort(26) & 0xffff, bpp = b.getShort(28) & 0xffff, comp = b.getInt(30), imgSize = b.getInt(34);
        int xppm = b.getInt(38), yppm = b.getInt(42), clrUsed = b.getInt(46), clrImp = b.getInt(50);
        r.line("== BMP header ==");
        r.line("bfSize (declared file size): " + fileSize + (fileSize != d.length ? "   [actual " + d.length + "]" : ""));
        r.line("bfReserved: 0x" + Integer.toHexString(reserved));
        r.line("bfOffBits (pixel data offset): " + offBits);
        r.line("biSize (DIB header): " + hdr);
        r.line("width=" + w + " height=" + h + (h < 0 ? " (top-down)" : " (bottom-up)"));
        r.line("planes=" + planes + " bitCount=" + bpp + " compression=" + comp + " sizeImage=" + imgSize);
        r.line("XPelsPerMeter=" + xppm + " YPelsPerMeter=" + yppm + " clrUsed=" + clrUsed + " clrImportant=" + clrImp);
        if (reserved != 0) r.warn("Reserved field non-zero (data may be hidden there)");
        if (fileSize != d.length) r.warn("Declared size differs from actual file size");
        long stride = ((long) w * bpp + 31) / 32 * 4;
        long expected = stride * Math.abs((long) h);
        r.line("Expected pixel array size: " + expected + " bytes (row stride " + stride + ")");
        if (comp == 0 && expected > 0) {
            long end = offBits + expected;
            if (end < d.length) { r.trailingOffset = end; r.trailingLength = d.length - end; }
            else if (end > d.length) {
                r.warn("Pixel data truncated: file is " + (end - d.length) + " bytes short");
                if (stride > 0) r.line("    height that fits the data: " + (d.length - offBits) / stride);
            }
        }
    }
}
