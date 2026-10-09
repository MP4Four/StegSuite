package org.stegsuite.core.jpeg;

import org.junit.Test;
import org.stegsuite.JpegDctTestAccess;
import org.stegsuite.JpegRobustnessTest;
import org.stegsuite.core.format.FormatAnalyzer;
import org.stegsuite.core.format.FormatReport;
import org.stegsuite.core.stat.SyntheticCovers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The shared marker walker against the two parsers it replaced (the decoder's resynchronising loop and the
 * format report's marker list), reproduced here (without their text output) as reference implementations.
 */
public class JpegSegmentsTest {

    /** Old JpegAnalyzer loop: returns "offset:marker:len" for every printed segment and the trailing offset. */
    static List<String> oldAnalyzer(byte[] d, long[] trailing) {
        List<String> out = new ArrayList<>();
        int p = 2;
        trailing[0] = -1;
        while (p + 4 <= d.length) {
            if ((d[p] & 0xff) != 0xff) break;
            int m = d[p + 1] & 0xff;
            if (m == 0xff) { p++; continue; }
            if (m == 0xd9) { out.add(p + ":d9:-1"); p += 2; break; }
            if (m == 0x01 || (m >= 0xd0 && m <= 0xd7)) { p += 2; continue; }
            int len = ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
            int s = p + 4, segLen = len - 2;
            if (s + segLen > d.length) break;
            out.add(p + ":" + Integer.toHexString(m) + ":" + len);
            p = s + segLen;
            if (m == 0xda) {
                while (p + 1 < d.length) {
                    if ((d[p] & 0xff) == 0xff) {
                        int n = d[p + 1] & 0xff;
                        if (n != 0 && !(n >= 0xd0 && n <= 0xd7)) break;
                    }
                    p++;
                }
            }
        }
        if (p < d.length && p > 2) trailing[0] = p;
        return out;
    }

    /** Old JpegDctDecoder loop (segments it dispatched, with their data range; SOS ends at the entropy end). */
    static List<String> oldDecoder(byte[] d) {
        List<String> out = new ArrayList<>();
        int pos = 2;
        while (pos + 1 < d.length) {
            if ((d[pos] & 0xff) != 0xff) { pos++; continue; }
            int marker = d[pos + 1] & 0xff;
            if (marker == 0xff) { pos++; continue; }
            pos += 2;
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
            if (marker == 0xd9) break;
            if (pos + 2 > d.length) break;
            int len = ((d[pos] & 0xff) << 8) | (d[pos + 1] & 0xff);
            int segStart = pos + 2;
            int declared = pos + len;
            if (len < 2 || declared > d.length) declared = d.length;
            int segEnd = Math.max(segStart, declared);
            if (marker == 0xda) {
                int e = JpegSegments.entropyEnd(d, segEnd);
                out.add(Integer.toHexString(marker) + ":" + segStart + "-" + segEnd + ":" + e);
                pos = e;
                continue;
            }
            out.add(Integer.toHexString(marker) + ":" + segStart + "-" + segEnd);
            pos = segEnd;
        }
        return out;
    }

    static List<String> newAnalyzerView(JpegSegments s, int length) {
        List<String> out = new ArrayList<>();
        for (JpegSegments.Segment g : s) {
            if (g.marker == 0xd8 || g.marker == 0x01 || (g.marker >= 0xd0 && g.marker <= 0xd7)) continue;
            if (g.truncated) break;
            if (g.marker == 0xd9 && g.offset + 4 > length) break;   // old loop guard (p + 4 <= length) never saw a final EOI
            out.add(g.offset + ":" + Integer.toHexString(g.marker) + ":" + g.length);
        }
        return out;
    }

    static List<String> newDecoderView(JpegSegments s) {
        List<String> out = new ArrayList<>();
        for (JpegSegments.Segment g : s) {
            if (g.standalone()) continue;
            String base = Integer.toHexString(g.marker) + ":" + g.dataStart + "-" + g.dataEnd;
            out.add(g.marker == 0xda ? base + ":" + g.entropyEnd : base);
        }
        return out;
    }

    private static List<byte[]> samples() throws Exception {
        List<byte[]> files = new ArrayList<>();
        byte[] base = JpegRobustnessTest.jpegAtQuality(SyntheticCovers.smoothCover(120, 90, 2, 4.0), 0.8f);
        files.add(base);
        files.add(JpegDctTestAccess.progressive(SyntheticCovers.smoothCover(96, 64, 3)));
        files.add(JpegDctTestAccess.colorWithRestarts(80, 48, 2, 3));
        // COM + APP segments inserted after SOI, fill bytes before a marker, trailing data after EOI
        byte[] com = {(byte) 0xff, (byte) 0xfe, 0, 9, 'h', 'e', 'l', 'l', 'o', '!', '!'};
        byte[] app = {(byte) 0xff, (byte) 0xe5, 0, 6, 'a', 'b', 'c', 'd'};
        byte[] withSegs = new byte[base.length + com.length + app.length + 2];
        System.arraycopy(base, 0, withSegs, 0, 2);
        System.arraycopy(com, 0, withSegs, 2, com.length);
        withSegs[2 + com.length] = (byte) 0xff;                       // fill bytes
        withSegs[3 + com.length] = (byte) 0xff;
        withSegs[4 + com.length] = (byte) 0xff;
        System.arraycopy(app, 1, withSegs, 5 + com.length, app.length - 1);
        System.arraycopy(base, 2, withSegs, 4 + com.length + app.length, base.length - 2);
        files.add(withSegs);
        byte[] trailing = Arrays.copyOf(base, base.length + 40);
        trailing[base.length] = 'P';
        trailing[base.length + 1] = 'K';
        files.add(trailing);
        return files;
    }

    @Test public void segmentsMatchTheOldFormatReportParser() throws Exception {
        for (byte[] d : samples()) {
            long[] oldTrailing = new long[1];
            List<String> old = oldAnalyzer(d, oldTrailing);
            JpegSegments s = JpegSegments.parse(d);
            assertEquals(old, newAnalyzerView(s, d.length));
            long expected = oldTrailing[0];
            // old bug: a final EOI (last two bytes) stopped the p + 4 <= length loop and was reported as 2 bytes of trailing data
            if (expected == d.length - 2 && (d[d.length - 2] & 0xff) == 0xff && (d[d.length - 1] & 0xff) == 0xd9) expected = -1;
            oldTrailing[0] = expected;
            assertEquals(expected, (long) s.trailingOffset());
            FormatReport r = FormatAnalyzer.analyze(d);
            assertEquals(oldTrailing[0] >= 0, r.hasTrailing());
            if (r.hasTrailing()) assertEquals(oldTrailing[0], r.trailingOffset);
        }
    }

    @Test public void segmentsMatchTheOldDecoderLoop() throws Exception {
        List<byte[]> files = samples();
        byte[] base = files.get(0);
        files.add(Arrays.copyOf(base, base.length / 2));             // truncated inside the scan
        byte[] garbage = base.clone();
        garbage[20] ^= 0x5a;                                           // corrupt a header byte
        files.add(garbage);
        for (byte[] d : files) assertEquals(oldDecoder(d), newDecoderView(JpegSegments.parse(d)));
    }

    @Test public void formatReportShowsTheJpegQuality() throws Exception {
        byte[] jpg = JpegRobustnessTest.jpegAtQuality(SyntheticCovers.smoothCover(64, 64, 1), 0.75f);
        FormatReport r = FormatAnalyzer.analyze(jpg);
        assertEquals(75, r.jpegQuality);
        assertTrue(r.jpegQualityExact);
        assertTrue(r.text.toString(), r.text.toString().contains("Estimated JPEG quality: 75"));
        int[][] q = JpegSegments.parse(jpg).quantTables();
        assertNotNull(q[0]);
        assertEquals(StandardTables.scaleQuant(StandardTables.QUANT_LUMINANCE, 75)[0], q[0][0]);
        // a custom table gets an estimate
        int[] custom = StandardTables.scaleQuant(StandardTables.QUANT_LUMINANCE, 60).clone();
        custom[63] += 3;
        JpegQuality.Estimate e = JpegQuality.of(custom);
        assertFalse(e.exact);
        assertTrue(String.valueOf(e.quality), Math.abs(e.quality - 60) <= 3);
    }
}
