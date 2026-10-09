package org.stegsuite.core.format;

import org.stegsuite.core.jpeg.JpegQuality;
import org.stegsuite.core.jpeg.JpegSegments;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** JPEG marker walker: segments, comments, EXIF (IFD0 / Exif IFD / GPS), dimensions and trailing data. */
public final class JpegAnalyzer {
    private JpegAnalyzer() {}

    private static final Map<Integer, String> TAGS = new HashMap<>();
    static {
        Object[] t = {0x010e, "ImageDescription", 0x010f, "Make", 0x0110, "Model", 0x0112, "Orientation",
                0x011a, "XResolution", 0x011b, "YResolution", 0x0131, "Software", 0x0132, "DateTime",
                0x013b, "Artist", 0x8298, "Copyright", 0x8769, "ExifIFDPointer", 0x8825, "GPSInfoPointer",
                0x9003, "DateTimeOriginal", 0x9004, "DateTimeDigitized", 0x9286, "UserComment",
                0x9c9b, "XPTitle", 0x9c9c, "XPComment", 0x9c9d, "XPAuthor", 0x9c9e, "XPKeywords", 0x9c9f, "XPSubject",
                0xa002, "PixelXDimension", 0xa003, "PixelYDimension", 0xa420, "ImageUniqueID",
                0x0001, "GPSLatitudeRef", 0x0002, "GPSLatitude", 0x0003, "GPSLongitudeRef", 0x0004, "GPSLongitude"};
        for (int i = 0; i < t.length; i += 2) TAGS.put((Integer) t[i], (String) t[i + 1]);
    }

    static String markerName(int m) {
        if (m == 0xc0) return "SOF0 (baseline)";
        if (m == 0xc2) return "SOF2 (progressive)";
        return JpegSegments.markerName(m);
    }

    public static void analyze(byte[] d, FormatReport r) {
        r.format = "JPEG";
        r.line("== JPEG markers ==");
        if (!JpegSegments.isJpeg(d)) { r.warn("no SOI marker"); return; }
        JpegSegments segs = JpegSegments.parse(d);
        for (JpegSegments.Segment seg : segs) {
            int m = seg.marker;
            if (m == 0xd8) { r.line(String.format("0x%08x SOI", seg.offset)); continue; }
            if (m == 0xd9) { r.line(String.format("0x%08x EOI", seg.offset)); continue; }
            if (seg.standalone() && !seg.truncated) continue;           // RSTn / TEM
            if (seg.truncated) { r.warn("Segment " + markerName(m) + " at 0x" + Integer.toHexString(seg.offset) + " runs past end of file"); break; }
            int s = seg.dataStart, segLen = seg.dataLength();
            r.line(String.format("0x%08x %-18s len=%d", seg.offset, markerName(m), seg.length));
            if (m == 0xfe) r.line("    COMMENT: " + new String(d, s, Math.min(segLen, 500), StandardCharsets.ISO_8859_1));
            else if (m == 0xe0 && segLen >= 7 && new String(d, s, 4, StandardCharsets.ISO_8859_1).equals("JFIF"))
                r.line(String.format("    JFIF v%d.%02d", d[s + 5], d[s + 6]));
            else if (m == 0xe1 && segLen > 6 && new String(d, s, 4, StandardCharsets.ISO_8859_1).equals("Exif")) exif(d, s + 6, segLen - 6, r);
            else if (m == 0xe1 && new String(d, s, Math.min(segLen, 29), StandardCharsets.ISO_8859_1).startsWith("http://ns.adobe.com/xap"))
                r.line("    XMP: " + new String(d, s, Math.min(segLen, 400), StandardCharsets.UTF_8).replaceAll("\\s+", " "));
            else if ((m >= 0xc0 && m <= 0xc3) && segLen >= 6)
                r.line(String.format("    %dx%d, %d components, precision %d", ((d[s + 3] & 0xff) << 8) | (d[s + 4] & 0xff),
                        ((d[s + 1] & 0xff) << 8) | (d[s + 2] & 0xff), d[s + 5], d[s]));
            else if (m >= 0xe2 && m <= 0xef) r.line("    id: " + printable(d, s, Math.min(segLen, 40)));
        }
        for (int g : segs.garbageOffsets())
            r.warn(String.format("Expected marker at 0x%x, found 0x%02x (skipped to the next marker)", g, d[g] & 0xff));
        r.line("Scans (SOS): " + segs.scanCount());
        if (segs.eoiOffset() < 0) r.warn("No EOI marker - the file is truncated or the scan data is damaged");
        quality(segs.quantTables(), r);
        int t = segs.trailingOffset();
        if (t > 2) { r.trailingOffset = t; r.trailingLength = d.length - t; }
    }

    /** Quality of each quantization table and of the file (luminance table). */
    private static void quality(int[][] tables, FormatReport r) {
        r.line("");
        r.line("== Quantization tables / quality ==");
        boolean any = false;
        for (int id = 0; id < tables.length; id++) {
            if (tables[id] == null) continue;
            any = true;
            r.line(String.format("  table %d: %s", id, JpegQuality.of(tables[id])));
        }
        if (!any) { r.line("  none (abbreviated stream?)"); return; }
        JpegQuality.Estimate e = JpegQuality.ofFile(tables);
        r.jpegQuality = e.quality;
        r.jpegQualityExact = e.exact;
        r.line(String.format("Estimated JPEG quality: %s%d (IJG scale)%s", e.exact ? "" : "~", e.quality,
                e.exact ? " - standard IJG/libjpeg tables" : " - custom tables (camera/editor), estimate from the luminance table"));
    }

    private static String printable(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < d.length; i++) { int c = d[i] & 0xff; sb.append(c >= 32 && c < 127 ? (char) c : '.'); }
        return sb.toString();
    }

    private static void exif(byte[] d, int tiff, int len, FormatReport r) {
        r.line("    EXIF:");
        ByteBuffer b = ByteBuffer.wrap(d, tiff, len).slice();
        b.order(d[tiff] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        try {
            int ifd0 = b.getInt(4);
            int[] next = ifd(b, ifd0, "IFD0", r);
            if (next[0] > 0) ifd(b, next[0], "ExifIFD", r);
            if (next[1] > 0) ifd(b, next[1], "GPS", r);
        } catch (RuntimeException e) { r.warn("EXIF parse error: " + e.getMessage()); }
    }

    /** Returns {exifPointer, gpsPointer}. */
    private static int[] ifd(ByteBuffer b, int off, String name, FormatReport r) {
        int[] ptrs = new int[2];
        int n = b.getShort(off) & 0xffff;
        for (int i = 0; i < n && i < 200; i++) {
            int e = off + 2 + i * 12;
            int tag = b.getShort(e) & 0xffff, type = b.getShort(e + 2) & 0xffff, count = b.getInt(e + 4);
            int[] sizes = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};
            int sz = type < sizes.length ? sizes[type] * count : 0;
            int vo = sz <= 4 ? e + 8 : b.getInt(e + 8);
            String val;
            if (tag == 0x8769) { ptrs[0] = b.getInt(e + 8); val = String.valueOf(ptrs[0]); }
            else if (tag == 0x8825) { ptrs[1] = b.getInt(e + 8); val = String.valueOf(ptrs[1]); }
            else if (type == 2 || type == 7 || (type == 1 && tag >= 0x9c9b)) {
                byte[] s = new byte[Math.min(Math.max(count, 0), 500)];
                for (int k = 0; k < s.length && vo + k < b.limit(); k++) s[k] = b.get(vo + k);
                val = tag >= 0x9c9b && tag <= 0x9c9f ? new String(s, StandardCharsets.UTF_16LE) : printable(s, 0, s.length);
            } else if (type == 3) val = String.valueOf(b.getShort(vo) & 0xffff);
            else if (type == 4) val = String.valueOf(b.getInt(vo) & 0xffffffffL);
            else if ((type == 5 || type == 10) && vo + 8 <= b.limit()) val = b.getInt(vo) + "/" + b.getInt(vo + 4) + (count > 1 ? " ..." : "");
            else val = "(type " + type + ", count " + count + ")";
            r.line(String.format("      [%s] %s (0x%04x) = %s", name, TAGS.getOrDefault(tag, "tag"), tag, val.trim()));
        }
        return ptrs;
    }
}
