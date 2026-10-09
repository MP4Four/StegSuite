package org.stegsuite;

import org.junit.Test;
import org.stegsuite.core.format.FormatAnalyzer;
import org.stegsuite.core.format.FormatReport;
import org.stegsuite.core.format.PngAnalyzer;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.*;

public class FormatTest {
    @Test public void validPngAllCrcOk() throws Exception {
        byte[] png = TestImages.png(TestImages.noise(20, 10, 7));
        FormatReport r = FormatAnalyzer.analyze(png);
        assertEquals("PNG", r.format);
        assertFalse(r.chunks.isEmpty());
        assertTrue(r.chunks.stream().allMatch(PngAnalyzer.ChunkInfo::crcOk));
        assertNull(r.suggestedSize);
        assertFalse(r.hasTrailing());
    }

    @Test public void tamperedHeightDetectedAndFixed() throws Exception {
        byte[] png = TestImages.png(TestImages.noise(20, 37, 8));
        byte[] bad = png.clone();
        ByteBuffer.wrap(bad).putInt(20, 12); // shrink height, keep original CRC (classic CTF trick)
        FormatReport r = FormatAnalyzer.analyze(bad);
        assertFalse(r.chunks.get(0).crcOk());
        assertArrayEquals(new int[]{20, 37}, r.suggestedSize);
        assertArrayEquals(png, PngAnalyzer.patchIhdr(bad, 20, 37));
    }

    @Test public void trailingDataAfterIend() throws Exception {
        byte[] png = TestImages.png(TestImages.noise(8, 8, 9));
        byte[] extra = "PK\u0003\u0004 secret zip".getBytes("ISO-8859-1");
        byte[] all = Arrays.copyOf(png, png.length + extra.length);
        System.arraycopy(extra, 0, all, png.length, extra.length);
        FormatReport r = FormatAnalyzer.analyze(all);
        assertTrue(r.hasTrailing());
        assertEquals(png.length, r.trailingOffset);
        assertEquals(extra.length, r.trailingLength);
        assertTrue(r.text.toString().contains("ZIP archive"));
    }

    @Test public void jpegMarkersAndTrailing() throws Exception {
        byte[] jpg = TestImages.jpeg(TestImages.noise(16, 16, 10));
        byte[] all = Arrays.copyOf(jpg, jpg.length + 5);
        FormatReport r = FormatAnalyzer.analyze(all);
        assertEquals("JPEG", r.format);
        assertTrue(r.text.toString().contains("SOS"));
        assertTrue(r.text.toString().contains("16x16"));
        assertEquals(jpg.length, r.trailingOffset);
    }

    @Test public void bmpHeader() throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(TestImages.noise(5, 3, 11), "bmp", o);
        byte[] bmp = o.toByteArray();
        FormatReport r = FormatAnalyzer.analyze(bmp);
        assertEquals("BMP", r.format);
        assertTrue(r.text.toString().contains("width=5 height=3"));
        assertFalse(r.hasTrailing());
    }
}
