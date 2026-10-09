package org.stegsuite;

import org.junit.Test;
import org.stegsuite.core.format.FormatAnalyzer;
import org.stegsuite.core.format.FormatReport;
import org.stegsuite.core.format.PngAnalyzer;
import org.stegsuite.core.format.PngSizeRepair;
import org.stegsuite.core.image.ImageUtil;
import org.stegsuite.core.stat.Progress;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.Assert.*;

/** PNG width/height repair: 2D CRC search, manual rewrite, IDAT-size inference. */
public class PngSizeRepairTest {

    private static byte[] png(int w, int h, int type, long seed) throws Exception {
        BufferedImage img = new BufferedImage(w, h, type);
        java.util.Random r = new java.util.Random(seed);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, r.nextInt() | 0xff000000);
        return TestImages.png(img);
    }

    /** Changes IHDR width/height but keeps the original CRC (the usual CTF trick). */
    private static byte[] deform(byte[] png, int w, int h) {
        byte[] o = png.clone();
        ByteBuffer.wrap(o).putInt(16, w).putInt(20, h);
        return o;
    }

    private static long ihdrCrc(byte[] d) {
        CRC32 c = new CRC32();
        c.update(d, 12, 17);
        return c.getValue();
    }

    @Test public void twoDimensionalSearchFindsBothChangedWidthAndHeight() throws Exception {
        byte[] orig = png(1234, 457, BufferedImage.TYPE_INT_RGB, 1);
        byte[] bad = deform(orig, 600, 300);
        PngSizeRepair.Ihdr ih = PngSizeRepair.ihdr(bad);
        assertFalse(ih.crcOk());
        long t0 = System.nanoTime();
        PngSizeRepair.SearchResult r = PngSizeRepair.search(bad, ih.storedCrc, 0, 20000, 0, 20000, 100, 4, Progress.NONE);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue("20000x20000 search took " + ms + " ms", ms < 20000);
        assertEquals(20001L * 20001L, r.combinations);
        boolean found = false;
        for (int[] m : r.matches) {
            // every reported match really has the stored CRC
            assertEquals(ih.storedCrc, ihdrCrc(PngSizeRepair.rewrite(bad, m[0], m[1])));
            if (m[0] == 1234 && m[1] == 457) found = true;
        }
        assertTrue("original size not among " + r.matches.size() + " matches", found);
        assertArrayEquals(new int[]{1234, 457}, PngSizeRepair.best(r.matches, 600, 300));
        // the format report uses the same search and offers the fix
        FormatReport fr = FormatAnalyzer.analyze(bad);
        assertArrayEquals(new int[]{1234, 457}, fr.suggestedSize);
        assertArrayEquals(orig, PngAnalyzer.patchIhdr(bad, 1234, 457));
    }

    @Test public void searchIsCancelableAndMultithreaded() throws Exception {
        byte[] bad = deform(png(40, 30, BufferedImage.TYPE_INT_RGB, 2), 41, 31);
        try {
            PngSizeRepair.search(bad, PngSizeRepair.ihdr(bad).storedCrc, 0, 1_000_000, 0, 20000, 10, 3, new Progress() {
                @Override public void update(double f) {}
                @Override public boolean cancelled() { return true; }
            });
            fail("expected cancellation");
        } catch (Progress.Cancelled expected) {
            // ok
        }
        // results do not depend on the thread count
        long crc = PngSizeRepair.ihdr(bad).storedCrc;
        List<int[]> a = PngSizeRepair.search(bad, crc, 1, 5000, 1, 5000, 100, 1, null).matches;
        List<int[]> b = PngSizeRepair.search(bad, crc, 1, 5000, 1, 5000, 100, 8, null).matches;
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertArrayEquals(a.get(i), b.get(i));
    }

    @Test public void manualRewriteProducesAValidCrc() throws Exception {
        byte[] orig = png(64, 48, BufferedImage.TYPE_INT_ARGB, 3);
        byte[] fixed = PngSizeRepair.rewrite(orig, 64, 20);
        PngSizeRepair.Ihdr ih = PngSizeRepair.ihdr(fixed);
        assertEquals(64, ih.width);
        assertEquals(20, ih.height);
        assertTrue(ih.crcOk());
        BufferedImage img = ImageUtil.decode(fixed);
        assertNotNull(img);
        assertEquals(20, img.getHeight());
        // rewriting back to the real size restores the original bytes exactly
        assertArrayEquals(orig, PngSizeRepair.rewrite(fixed, 64, 48));
    }

    @Test public void idatSizeInferenceRecoversHeightWhenTheCrcIsAlsoWrong() throws Exception {
        for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_BYTE_GRAY}) {
            byte[] orig = png(300, 517, type, 4);
            // height shrunk AND the CRC recomputed, so the CRC search cannot help
            byte[] bad = PngSizeRepair.rewrite(orig, 300, 200);
            assertTrue(PngSizeRepair.ihdr(bad).crcOk());
            PngSizeRepair.Inference inf = PngSizeRepair.inferFromIdat(bad, 65535, 500, Progress.NONE);
            assertEquals(517, inf.heightForStoredWidth);
            assertFalse(inf.candidates.isEmpty());
            PngSizeRepair.Candidate first = inf.candidates.get(0);
            assertEquals(300, first.width);
            assertEquals(517, first.height);
            assertTrue(first.filtersValid);
            for (PngSizeRepair.Candidate c : inf.candidates)
                assertEquals(inf.inflatedSize, c.height * PngSizeRepair.ihdr(bad).rowBytes(c.width));
            FormatReport fr = FormatAnalyzer.analyze(bad);
            assertTrue(fr.text.toString(), fr.text.toString().contains("IDAT size does not match IHDR"));
        }
    }

    @Test public void idatInferenceListsCandidateWidthsWhenWidthIsWrongToo() throws Exception {
        byte[] orig = png(120, 90, BufferedImage.TYPE_INT_RGB, 5);
        byte[] bad = PngSizeRepair.rewrite(orig, 77, 33);          // both changed, CRC recomputed
        PngSizeRepair.Inference inf = PngSizeRepair.inferFromIdat(bad, 65535, 500, null);
        boolean found = false;
        for (PngSizeRepair.Candidate c : inf.candidates) if (c.width == 120 && c.height == 90) found = true;
        assertTrue("120x90 expected among " + inf.candidates, found);
    }
}
