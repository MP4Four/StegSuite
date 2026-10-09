package org.stegsuite;

import org.junit.Test;
import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.JpegDctDecoder;
import org.stegsuite.core.jpeg.ZigZag;
import org.stegsuite.plugin.dct.DctExtraction;
import org.stegsuite.plugin.dct.JpegDctPlugin;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Malformed / hostile JPEG input and the false-positive behaviour of the DCT histogram test. */
public class JpegRobustnessTest {

    /** Offset of the first marker {@code 0xFF m} after SOI (segments are walked by their lengths). */
    private static int findMarker(byte[] d, int m) {
        int p = 2;
        while (p + 3 < d.length) {
            if ((d[p] & 0xff) != 0xff) return -1;
            int marker = d[p + 1] & 0xff;
            if (marker == m) return p;
            if (marker == 0xda) return -1;
            p += 2 + (((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff));
        }
        return -1;
    }

    private static void expectIOException(byte[] jpeg, String messagePart) {
        try {
            JpegDctDecoder.decode(jpeg);
            fail("expected IOException containing \"" + messagePart + "\"");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }

    @Test public void hugeDimensionsAreRejectedWithoutAllocating() throws Exception {
        byte[] jpg = TestImages.jpeg(TestImages.noise(32, 32, 1));
        int sof = findMarker(jpg, 0xc0);
        assertTrue(sof > 0);
        byte[] crafted = jpg.clone();
        for (int i = 5; i <= 8; i++) crafted[sof + i] = (byte) 0xff;   // height = width = 65535
        expectIOException(crafted, "too large");
        try {
            new JpegDctPlugin().analyze(crafted);
            fail("analyze must fail with IOException");
        } catch (IOException expected) {
            // ok - not an OutOfMemoryError / NegativeArraySizeException
        }
        // just below the cap still decodes (header only - the scan data is simply too short)
        byte[] large = jpg.clone();
        large[sof + 5] = (byte) (2000 >> 8); large[sof + 6] = (byte) (2000 & 0xff);
        large[sof + 7] = (byte) (2000 >> 8); large[sof + 8] = (byte) (2000 & 0xff);
        DctImage img = JpegDctDecoder.decode(large);
        assertEquals(2000, img.width());
    }

    @Test public void missingHuffmanTablesThrowIOException() throws Exception {
        byte[] jpg = TestImages.jpeg(TestImages.noise(32, 32, 2));
        int sos = findSos(jpg);
        int ns = jpg[sos + 4] & 0xff;
        assertTrue(ns >= 1);
        // point the first scan component at undefined AC table 3
        byte[] noAc = jpg.clone();
        noAc[sos + 6] = (byte) ((noAc[sos + 6] & 0xf0) | 3);
        expectIOException(noAc, "undefined AC Huffman table 3");
        byte[] noDc = jpg.clone();
        noDc[sos + 6] = (byte) ((noDc[sos + 6] & 0x0f) | 0x30);
        expectIOException(noDc, "undefined DC Huffman table 3");
        // drop every DHT segment
        expectIOException(withoutSegments(jpg, 0xc4), "undefined");
    }

    @Test public void truncatedFilesFailCleanly() throws Exception {
        byte[] jpg = TestImages.jpeg(TestImages.noise(48, 40, 3));
        for (int len = 0; len < jpg.length; len += Math.max(1, jpg.length / 97)) {
            byte[] cut = Arrays.copyOf(jpg, len);
            try {
                DctImage img = JpegDctDecoder.decode(cut);
                assertNotNull(img);
                new JpegDctPlugin().analyze(cut);
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test public void cleanHighQualityNoiseJpegIsNotFlagged() throws Exception {
        for (long seed = 40; seed < 43; seed++) {
            byte[] jpg = jpegAtQuality(TestImages.noise(256, 256, seed), 1.0f);
            String report = new JpegDctPlugin().analyze(jpg);
            String verdict = report.substring(report.indexOf("Histogram indicators: "));
            verdict = verdict.substring(0, verdict.indexOf('\n'));
            assertFalse(verdict, verdict.contains("sign of JSteg") && !verdict.contains("no sign"));
            assertFalse(verdict, verdict.contains("consistent with"));
            assertTrue(verdict, verdict.contains("no sign of JSteg-style") || verdict.contains("inconclusive"));
            assertTrue(report, report.contains("two's complement pairs"));
            assertTrue(report, report.contains("F5 and OutGuess are designed to resist it"));
        }
    }

    @Test public void originalJstegOrderSkipsOnlyZeroAndPlusOne() throws Exception {
        byte[] payload = "flag{orig_jsteg_twos_complement}".getBytes("ISO-8859-1");
        byte[] stego = embedOriginalJsteg(TestImages.jpeg(TestImages.noise(64, 48, 7)), payload);
        DctImage img = JpegDctDecoder.decode(stego);
        byte[] got = DctExtraction.extract(img, DctExtraction.Mode.JSTEG_ORIGINAL, "");
        assertArrayEquals(payload, Arrays.copyOf(got, payload.length));
        byte[] variant = DctExtraction.extract(img, DctExtraction.Mode.JSTEG_AC_SKIP_ONES, "");
        assertFalse(Arrays.equals(payload, Arrays.copyOf(variant, payload.length)));
        assertTrue(Arrays.asList(DctExtraction.SEQUENTIAL_MODES).contains(DctExtraction.Mode.JSTEG_ORIGINAL));
        assertEquals("custom order (not compatible with F5/OutGuess)", DctExtraction.Mode.KEYED.label());
        String report = new JpegDctPlugin().analyze(stego);
        assertTrue(report, report.contains("flag{orig_jsteg_twos_complement}"));
    }

    // ------------------------------------------------------------------ helpers

    private static int findSos(byte[] d) {
        int p = 2;
        while (p + 3 < d.length) {
            int marker = d[p + 1] & 0xff;
            if (marker == 0xda) return p;
            p += 2 + (((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff));
        }
        throw new AssertionError("no SOS");
    }

    private static byte[] withoutSegments(byte[] d, int m) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(d, 0, 2);
        int p = 2;
        while (p + 3 < d.length) {
            int marker = d[p + 1] & 0xff;
            if (marker == 0xda) break;
            int len = 2 + (((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff));
            if (marker != m) o.write(d, p, len);
            p += len;
        }
        o.write(d, p, d.length - p);
        return o.toByteArray();
    }

    public static byte[] jpegAtQuality(BufferedImage img, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    /** Original JSteg: LSB of the stored (two's complement) value, skipping only 0 and +1; luma only. */
    private static byte[] embedOriginalJsteg(byte[] jpeg, byte[] payload) throws Exception {
        DctImage img = JpegDctDecoder.decode(jpeg);
        DctImage.Component y = img.components().get(0);
        int blocks = y.blockCount();
        int[] c = new int[blocks * 64];
        for (int b = 0; b < blocks; b++) for (int i = 0; i < 64; i++) c[b * 64 + i] = y.coefficient(b, i);
        int bit = 0, total = payload.length * 8;
        for (int b = 0; b < blocks && bit < total; b++)
            for (int k = 1; k < 64 && bit < total; k++) {
                int n = ZigZag.NATURAL[k], v = c[b * 64 + n];
                if (v == 0 || v == 1) continue;
                c[b * 64 + n] = (v & ~1) | ((payload[bit / 8] >> (7 - bit % 8)) & 1);
                bit++;
            }
        assertEquals("cover too small", total, bit);
        return JpegTestWriter.grayscale(y.blocksPerLine() * 8, y.blocksPerColumn() * 8, y.blocksPerLine(),
                y.blocksPerColumn(), c, img.quantTableOf(y).values(), 0);
    }
}
