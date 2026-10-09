package org.stegsuite.core.format;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

/** PNG chunk walker with CRC verification and IHDR width/height brute force. */
public final class PngAnalyzer {
    private PngAnalyzer() {}

    public static final class ChunkInfo {
        private final long offset;
        private final String type;
        private final int length;
        private final long storedCrc;
        private final long computedCrc;
        public ChunkInfo(long offset, String type, int length, long storedCrc, long computedCrc) {
            this.offset = offset;
            this.type = type;
            this.length = length;
            this.storedCrc = storedCrc;
            this.computedCrc = computedCrc;
        }
        public long offset() { return offset; }
        public String type() { return type; }
        public int length() { return length; }
        public long storedCrc() { return storedCrc; }
        public long computedCrc() { return computedCrc; }
        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ChunkInfo)) return false;
            ChunkInfo o = (ChunkInfo) obj;
            return offset == o.offset && java.util.Objects.equals(type, o.type) && length == o.length && storedCrc == o.storedCrc && computedCrc == o.computedCrc;
        }
        @Override public int hashCode() { return java.util.Objects.hash(offset, type, length, storedCrc, computedCrc); }
        @Override public String toString() { return "ChunkInfo[" + "offset=" + offset + ", " + "type=" + type + ", " + "length=" + length + ", " + "storedCrc=" + storedCrc + ", " + "computedCrc=" + computedCrc + "]"; }
        public boolean crcOk() { return storedCrc == computedCrc; }
    }

    public static long crc(byte[] d, int off, int len) {
        CRC32 c = new CRC32();
        c.update(d, off, len);
        return c.getValue();
    }

    public static void analyze(byte[] d, FormatReport r) {
        r.format = "PNG";
        r.line("== PNG chunks ==");
        r.line(String.format("%-10s %-6s %10s  %-10s %-10s %s", "offset", "type", "length", "storedCRC", "calcCRC", "status"));
        int p = 8;
        boolean iend = false;
        long idatTotal = 0;
        while (p + 8 <= d.length) {
            long len = ByteBuffer.wrap(d, p, 4).getInt() & 0xffffffffL;
            String type = new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
            if (p + 12L + len > d.length) {
                r.warn(String.format("Chunk %s at 0x%x claims length %d past end of file", type, p, len));
                break;
            }
            int l = (int) len;
            long stored = ByteBuffer.wrap(d, p + 8 + l, 4).getInt() & 0xffffffffL;
            long calc = crc(d, p + 4, l + 4);
            ChunkInfo ci = new ChunkInfo(p, type, l, stored, calc);
            r.chunks.add(ci);
            r.line(String.format("0x%08x %-6s %10d  %08x   %08x   %s", p, type, l, stored, calc, ci.crcOk() ? "OK" : "CRC MISMATCH"));
            describe(d, p + 8, l, type, r);
            if (type.equals("IDAT")) idatTotal += l;
            if (!ci.crcOk()) {
                r.warn("CRC mismatch in " + type + " chunk at 0x" + Long.toHexString(p));
                if (type.equals("IHDR") && l == 13) {
                    int[] fix = bruteForceIhdr(d, p, stored);
                    r.suggestedSize = fix;
                    int w = ByteBuffer.wrap(d, p + 8, 4).getInt(), h = ByteBuffer.wrap(d, p + 12, 4).getInt();
                    if (fix != null)
                        r.line(String.format("    -> CRC matches width=%d height=%d (stored %dx%d). Use 'Save fixed PNG'.", fix[0], fix[1], w, h));
                    else r.line("    -> no width/height up to 20000x20000 (or one side up to 65535) matches the stored CRC;"
                            + " the CRC may be altered too - see 'PNG size repair...' (IDAT size inference)");
                }
            }
            p += 12 + l;
            if (type.equals("IEND")) { iend = true; break; }
        }
        r.line("Total IDAT payload: " + idatTotal + " bytes");
        idatSizeCheck(d, r);
        if (!iend) r.warn("No IEND chunk found");
        if (iend && p < d.length) { r.trailingOffset = p; r.trailingLength = d.length - p; }
    }

    /** Compares the decompressed IDAT size with the size implied by IHDR (non-interlaced images). */
    private static void idatSizeCheck(byte[] d, FormatReport r) {
        if (!PngSizeRepair.isPng(d)) return;
        try {
            PngSizeRepair.Ihdr ih = PngSizeRepair.ihdr(d);
            if (ih.interlace != 0 || ih.bitsPerPixel() == 0 || ih.width <= 0 || ih.height <= 0) return;
            long expected = ih.rowBytes(ih.width) * (long) ih.height;
            long actual = PngSizeRepair.inflatedSize(d, expected * 4 + (1 << 20));
            if (actual < 0) return;
            r.line(String.format("Decompressed IDAT: %d bytes, IHDR %dx%d implies %d", actual, ih.width, ih.height, expected));
            if (actual != expected)
                r.warn("IDAT size does not match IHDR width/height - the dimensions were probably changed"
                        + (ih.crcOk() ? " together with the CRC" : "") + "; use 'PNG size repair...' (IDAT size inference)");
        } catch (RuntimeException ignored) {
            // malformed IHDR: reported elsewhere
        }
    }

    private static void describe(byte[] d, int off, int len, String type, FormatReport r) {
        ByteBuffer b = ByteBuffer.wrap(d, off, len);
        switch (type) {
            case "IHDR": {
                if (len < 13) return;
                r.line(String.format("    width=%d height=%d bitDepth=%d colorType=%d compression=%d filter=%d interlace=%d",
                        b.getInt(off), b.getInt(off + 4), d[off + 8], d[off + 9], d[off + 10], d[off + 11], d[off + 12]));
            } break;
            case "tEXt": r.line("    " + text(d, off, len, false)); break;
            case "zTXt": r.line("    " + text(d, off, len, true)); break;
            case "iTXt": r.line("    " + new String(d, off, Math.min(len, 300), StandardCharsets.UTF_8).replace('\0', '|')); break;
            case "acTL": { if (len >= 8) r.line(String.format("    APNG: frames=%d plays=%d", b.getInt(off), b.getInt(off + 4))); } break;
            case "pHYs": { if (len >= 9) r.line(String.format("    %d x %d px/unit, unit=%d", b.getInt(off), b.getInt(off + 4), d[off + 8])); } break;
            default: {
                boolean known = "PLTE IDAT IEND tRNS gAMA cHRM sRGB iCCP bKGD sBIT hIST sPLT tIME fcTL fdAT eXIf".contains(type);
                if (!known) r.warn("Non-standard chunk type '" + type + "' (possible hidden data)");
            } break;
        }
    }

    private static String text(byte[] d, int off, int len, boolean compressed) {
        int z = off;
        while (z < off + len && d[z] != 0) z++;
        String key = new String(d, off, z - off, StandardCharsets.ISO_8859_1);
        String val;
        if (!compressed) val = new String(d, Math.min(z + 1, off + len), Math.max(0, off + len - z - 1), StandardCharsets.ISO_8859_1);
        else {
            try {
                Inflater inf = new Inflater();
                inf.setInput(d, z + 2, off + len - z - 2);
                byte[] buf = new byte[65536];
                int n = inf.inflate(buf);
                inf.end();
                val = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
            } catch (Exception e) { val = "<inflate error " + e.getMessage() + ">"; }
        }
        if (val.length() > 300) val = val.substring(0, 300) + "...";
        return key + " = " + val;
    }

    /**
     * Finds a width/height whose IHDR CRC equals the stored CRC: the full 2D range 1..20000 x 1..20000 (as in
     * AabyssZG's Deformed-Image-Restorer) plus single-dimension changes up to 65535, using the fast affine CRC
     * search of {@link PngSizeRepair}. Prefers matches that keep one dimension. Returns null if nothing matches.
     */
    public static int[] bruteForceIhdr(byte[] d, int chunkOff, long storedCrc) {
        if (chunkOff != 8 || !PngSizeRepair.isPng(d)) return null;
        try {
            PngSizeRepair.Ihdr ih = PngSizeRepair.ihdr(d);
            List<int[]> all = new ArrayList<>();
            int threads = Runtime.getRuntime().availableProcessors();
            all.addAll(PngSizeRepair.search(d, storedCrc, 1, 20000, 1, 20000, 64, threads, null).matches);
            all.addAll(PngSizeRepair.search(d, storedCrc, ih.width, ih.width, 1, 65535, 64, 1, null).matches);
            all.addAll(PngSizeRepair.search(d, storedCrc, 1, 65535, ih.height, ih.height, 64, threads, null).matches);
            return PngSizeRepair.best(all, ih.width, ih.height);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Returns a copy of the PNG with IHDR width/height replaced (CRC left as stored, which now matches). */
    public static byte[] patchIhdr(byte[] d, int w, int h) {
        byte[] o = d.clone();
        ByteBuffer.wrap(o).putInt(16, w).putInt(20, h);
        long crc = crc(o, 12, 17);
        ByteBuffer.wrap(o).putInt(29, (int) crc);
        return o;
    }
}
