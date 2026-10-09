package org.stegsuite.core.jpeg;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Shared JPEG marker walker used by the coefficient decoder ({@link JpegDctDecoder}) and the file-format
 * report ({@code core.format.JpegAnalyzer}), so both see exactly the same segments.
 *
 * <p>Iterates the markers after SOI: fill bytes (0xFF 0xFF) are skipped, stand-alone markers (SOI, TEM,
 * RST0-7) have no length, every other marker carries a big-endian length. For SOS the iterator also skips
 * the entropy-coded data that follows the scan header (byte-stuffed 0xFF00, fill bytes and restart markers
 * belong to the scan) and reports where it ends. Iteration stops after EOI, at a truncated segment, or at
 * the end of the data. Bytes that are not a marker where one is expected are skipped (resynchronisation) and
 * recorded in {@link #garbageOffsets()}.
 */
public final class JpegSegments implements Iterable<JpegSegments.Segment> {

    /** One marker segment. Offsets are absolute positions in the file. */
    public static final class Segment {
        /** Marker code without the 0xFF prefix, e.g. 0xDB for DQT. */
        public final int marker;
        /** Offset of the 0xFF byte of the marker. */
        public final int offset;
        /** Value of the length field (includes the two length bytes), or -1 for stand-alone markers. */
        public final int length;
        /** First byte after the length field. */
        public final int dataStart;
        /** End of the segment data (exclusive), clipped to the file when {@link #truncated}. */
        public final int dataEnd;
        /** True when the length field points past the end of the file (or is smaller than 2). */
        public final boolean truncated;
        /** SOS only: offset of the marker that terminates the entropy-coded data, else -1. */
        public final int entropyEnd;

        Segment(int marker, int offset, int length, int dataStart, int dataEnd, boolean truncated, int entropyEnd) {
            this.marker = marker;
            this.offset = offset;
            this.length = length;
            this.dataStart = dataStart;
            this.dataEnd = dataEnd;
            this.truncated = truncated;
            this.entropyEnd = entropyEnd;
        }

        public boolean standalone() { return length < 0; }
        public int dataLength() { return dataEnd - dataStart; }
        public String name() { return JpegSegments.markerName(marker); }

        @Override public String toString() {
            return String.format("0x%08x %s len=%d%s", offset, name(), length, truncated ? " (truncated)" : "");
        }
    }

    private final byte[] d;
    private final List<Segment> segments = new ArrayList<>();
    private final List<Integer> garbage = new ArrayList<>();
    private int eoiOffset = -1;
    private int end;

    private JpegSegments(byte[] d) {
        this.d = d;
        walk();
    }

    /** True when the data starts with an SOI marker. */
    public static boolean isJpeg(byte[] d) {
        return d != null && d.length >= 2 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8;
    }

    /** Parses the marker structure; the data must start with SOI (see {@link #isJpeg}). */
    public static JpegSegments parse(byte[] d) {
        if (!isJpeg(d)) throw new IllegalArgumentException("not a JPEG file (no SOI marker)");
        return new JpegSegments(d);
    }

    private void walk() {
        int pos = 2;
        while (pos + 1 < d.length) {
            if ((d[pos] & 0xff) != 0xff) {                         // resynchronise
                if (garbage.isEmpty() || garbage.get(garbage.size() - 1) != pos - 1) garbage.add(pos);
                pos++;
                continue;
            }
            int marker = d[pos + 1] & 0xff;
            if (marker == 0xff) { pos++; continue; }                 // fill byte
            if (marker == 0x00) { pos += 2; continue; }               // stray stuffed byte outside a scan
            int offset = pos;
            pos += 2;
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                segments.add(new Segment(marker, offset, -1, pos, pos, false, -1));
                continue;
            }
            if (marker == 0xd9) {
                segments.add(new Segment(marker, offset, -1, pos, pos, false, -1));
                eoiOffset = offset;
                end = pos;
                return;
            }
            if (pos + 2 > d.length) {
                segments.add(new Segment(marker, offset, -1, d.length, d.length, true, -1));
                end = d.length;
                return;
            }
            int len = ((d[pos] & 0xff) << 8) | (d[pos + 1] & 0xff);
            int dataStart = pos + 2;
            int declared = pos + len;
            boolean truncated = len < 2 || declared > d.length;
            int dataEnd = truncated ? d.length : declared;
            if (dataEnd < dataStart) dataEnd = dataStart;
            int entropyEnd = -1;
            if (marker == 0xda && !truncated) entropyEnd = entropyEnd(d, dataEnd);
            segments.add(new Segment(marker, offset, len, dataStart, dataEnd, truncated, entropyEnd));
            if (truncated) { end = d.length; return; }
            pos = entropyEnd >= 0 ? entropyEnd : dataEnd;
        }
        end = Math.min(pos, d.length);
    }

    /**
     * Offset of the marker that terminates entropy-coded data starting at {@code from}: the first 0xFF that is
     * neither a stuffed zero byte, a fill byte nor a restart marker; the data length if there is none.
     */
    public static int entropyEnd(byte[] d, int from) {
        int p = from;
        while (p + 1 < d.length) {
            if ((d[p] & 0xff) != 0xff) { p++; continue; }
            int m = d[p + 1] & 0xff;
            if (m == 0x00 || m == 0xff) { p += 2; continue; }
            if (m >= 0xd0 && m <= 0xd7) { p += 2; continue; }
            return p;
        }
        return d.length;
    }

    public List<Segment> segments() { return segments; }

    @Override public Iterator<Segment> iterator() {
        final Iterator<Segment> it = segments.iterator();
        return new Iterator<Segment>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public Segment next() {
                if (!it.hasNext()) throw new NoSuchElementException();
                return it.next();
            }
        };
    }

    /** Offset of the EOI marker, or -1 when the file has none. */
    public int eoiOffset() { return eoiOffset; }

    /** Offset right after the last parsed byte (after EOI when present). */
    public int end() { return end; }

    /** Offset of the first byte after EOI when bytes follow it, else -1. */
    public int trailingOffset() { return eoiOffset >= 0 && end < d.length ? end : -1; }

    /** Offsets where bytes other than a marker were skipped (start of each skipped run). */
    public List<Integer> garbageOffsets() { return garbage; }

    /** Number of SOS segments. */
    public int scanCount() {
        int n = 0;
        for (Segment s : segments) if (s.marker == 0xda) n++;
        return n;
    }

    /** All quantization tables found in DQT segments (later definitions replace earlier ones), index = table id. */
    public int[][] quantTables() {
        int[][] q = new int[4][];
        for (Segment s : segments) {
            if (s.marker != 0xdb) continue;
            int p = s.dataStart;
            while (p < s.dataEnd) {
                int pq = (d[p] >> 4) & 15, tq = d[p] & 15;
                p++;
                int need = pq == 0 ? 64 : 128;
                if (tq > 3 || p + need > s.dataEnd) break;
                int[] values = new int[64];
                for (int k = 0; k < 64; k++) {
                    values[ZigZag.NATURAL[k]] = pq == 0 ? d[p + k] & 0xff : ((d[p + 2 * k] & 0xff) << 8) | (d[p + 2 * k + 1] & 0xff);
                }
                p += need;
                q[tq] = values;
            }
        }
        return q;
    }

    public static String markerName(int m) {
        if (m >= 0xe0 && m <= 0xef) return "APP" + (m - 0xe0);
        if (m >= 0xd0 && m <= 0xd7) return "RST" + (m - 0xd0);
        switch (m) {
            case 0x01: return "TEM";
            case 0xd8: return "SOI";
            case 0xd9: return "EOI";
            case 0xda: return "SOS";
            case 0xdb: return "DQT";
            case 0xc4: return "DHT";
            case 0xc0: return "SOF0";
            case 0xc1: return "SOF1";
            case 0xc2: return "SOF2";
            case 0xc3: return "SOF3";
            case 0xc5: return "SOF5";
            case 0xc6: return "SOF6";
            case 0xc7: return "SOF7";
            case 0xc9: return "SOF9";
            case 0xca: return "SOF10";
            case 0xcb: return "SOF11";
            case 0xcd: return "SOF13";
            case 0xce: return "SOF14";
            case 0xcf: return "SOF15";
            case 0xcc: return "DAC";
            case 0xdd: return "DRI";
            case 0xfe: return "COM";
            default: return String.format("0x%02X", m);
        }
    }
}
