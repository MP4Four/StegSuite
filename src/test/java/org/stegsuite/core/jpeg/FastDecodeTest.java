package org.stegsuite.core.jpeg;

import org.junit.Test;
import org.stegsuite.JpegDctTestAccess;
import org.stegsuite.JpegRobustnessTest;
import org.stegsuite.core.stat.SyntheticCovers;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** Fast paths of the decoder: AAN inverse DCT accuracy and the 9-bit Huffman lookup table. */
public class FastDecodeTest {

    @Test public void aanIdctMatchesTheReferenceTransform() {
        Random r = new Random(5);
        int[] quant = StandardTables.scaleQuant(StandardTables.QUANT_LUMINANCE, 75);
        float[] qt = InverseDct.aanTable(quant);
        float[] ws = new float[64];
        int[] deq = new int[64], ref = new int[64];
        byte[] fast = new byte[64];
        short[] coeffs = new short[64];
        long sum = 0, n = 0;
        int max = 0;
        for (int block = 0; block < 20000; block++) {
            Arrays.fill(coeffs, (short) 0);
            int nonZero = 1 + r.nextInt(block % 3 == 0 ? 63 : 12);
            coeffs[0] = (short) (r.nextInt(127) - 63);
            for (int i = 0; i < nonZero; i++) {
                int k = r.nextInt(64);
                coeffs[k] = (short) Math.round(r.nextGaussian() * (block % 5 == 0 ? 40 : 6) / (1 + k / 8.0));
            }
            for (int i = 0; i < 64; i++) deq[i] = coeffs[i] * quant[i];
            InverseDct.idct(deq, ref);
            InverseDct.idctFast(coeffs, 0, qt, ws, fast, 0, 8);
            for (int i = 0; i < 64; i++) {
                int d = Math.abs(ref[i] - (fast[i] & 0xff));
                sum += d;
                max = Math.max(max, d);
                n++;
            }
        }
        double mean = sum / (double) n;
        assertTrue("max |delta| " + max, max <= 1);
        assertTrue("mean |delta| " + mean, mean < 0.02);
    }

    @Test public void reconstructionStillAgreesWithTheJdkDecoder() throws Exception {
        BufferedImage cover = SyntheticCovers.smoothCover(200, 136, 4, 6.0);
        byte[] jpg = JpegRobustnessTest.jpegAtQuality(cover, 0.85f);
        BufferedImage mine = JpegDctDecoder.decode(jpg).toBufferedImage();
        BufferedImage ref = org.stegsuite.core.image.ImageUtil.decode(jpg);
        long sum = 0, n = 0;
        for (int y = 0; y < mine.getHeight(); y++)
            for (int x = 0; x < mine.getWidth(); x++)
                for (int s = 16; s >= 0; s -= 8) {
                    sum += Math.abs((mine.getRGB(x, y) >> s & 0xff) - (ref.getRGB(x, y) >> s & 0xff));
                    n++;
                }
        assertTrue("mean " + sum / (double) n, sum / (double) n < 1.0);
    }

    /** Decodes with and without the lookup table and compares everything the decoder produces. */
    private static void assertLookupTableMatchesBitwiseDecoder(String name, byte[] jpg) throws Exception {
        boolean old = JpegDctDecoder.useLookupTable;
        DctImage slow, fast;
        String slowError = null, fastError = null;
        try {
            JpegDctDecoder.useLookupTable = false;
            try { slow = JpegDctDecoder.decode(jpg); } catch (java.io.IOException e) { slow = null; slowError = e.getMessage(); }
            JpegDctDecoder.useLookupTable = true;
            try { fast = JpegDctDecoder.decode(jpg); } catch (java.io.IOException e) { fast = null; fastError = e.getMessage(); }
        } finally {
            JpegDctDecoder.useLookupTable = old;
        }
        assertEquals(name + ": error", slowError, fastError);
        if (slow == null) return;
        assertEquals(name + ": warnings", slow.warnings(), fast.warnings());
        assertEquals(name + ": scans", slow.scanCount(), fast.scanCount());
        assertEquals(slow.components().size(), fast.components().size());
        for (int c = 0; c < slow.components().size(); c++)
            assertArrayEquals(name + ": component " + c, slow.components().get(c).raw(), fast.components().get(c).raw());
    }

    @Test public void huffmanLookupTableDecodesExactlyLikeTheBitwiseDecoder() throws Exception {
        List<byte[]> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        BufferedImage cover = SyntheticCovers.smoothCover(181, 97, 9, 12.0);
        names.add("baseline q90 4:2:0"); files.add(JpegRobustnessTest.jpegAtQuality(cover, 0.9f));
        names.add("baseline q30"); files.add(JpegRobustnessTest.jpegAtQuality(cover, 0.3f));
        names.add("progressive"); files.add(JpegDctTestAccess.progressive(cover));
        names.add("restart interval 3"); files.add(JpegDctTestAccess.colorWithRestarts(76, 52, 3, 1));
        names.add("restart interval 1"); files.add(JpegDctTestAccess.colorWithRestarts(33, 17, 1, 2));
        byte[] full = JpegRobustnessTest.jpegAtQuality(cover, 0.95f);
        for (int cut : new int[]{full.length / 3, full.length / 2 + 7, full.length - 3}) {
            names.add("truncated at " + cut); files.add(Arrays.copyOf(full, cut));
        }
        // flipped bytes in the entropy data (invalid codes, early markers)
        Random r = new Random(3);
        for (int k = 0; k < 6; k++) {
            byte[] bad = full.clone();
            for (int i = 0; i < 4; i++) bad[full.length / 2 + r.nextInt(full.length / 3)] ^= (byte) (1 << r.nextInt(8));
            names.add("corrupted " + k); files.add(bad);
        }
        byte[] rst = JpegDctTestAccess.colorWithRestarts(64, 40, 2, 7);
        byte[] rstBad = rst.clone();
        rstBad[rst.length / 2] ^= 0x10;
        names.add("restart + corruption"); files.add(rstBad);
        for (int i = 0; i < files.size(); i++) assertLookupTableMatchesBitwiseDecoder(names.get(i), files.get(i));
    }

    @Test public void lookupTableEntriesMatchTheCanonicalCodes() {
        HuffmanTable t = new HuffmanTable(StandardTables.AC_LUMA_BITS, StandardTables.AC_LUMA_VALUES);
        // 0x01 (run 0, size 1) has the 2-bit code 00, 0xF0 (ZRL) the 11-bit code 11111111001 (longer than 9 bits)
        assertEquals((2 << 8) | 0x01, t.lookup(0b000000000));
        assertEquals((2 << 8) | 0x01, t.lookup(0b001111111));
        assertEquals(0, t.lookup(0b111111110));          // 11111111 0... is a prefix of a code longer than 9 bits
    }
}
