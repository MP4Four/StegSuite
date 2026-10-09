package org.stegsuite.core.jpeg;

import org.junit.Test;
import org.stegsuite.JpegRobustnessTest;
import org.stegsuite.core.stat.SyntheticCovers;
import org.stegsuite.plugin.dct.DctAnalysis;
import org.stegsuite.plugin.dct.JpegDctPlugin;

import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

/** One decode per file shared by Analyze, Extract and the preview. */
public class DctImageCacheTest {

    @Test public void analyseExtractAndPreviewShareOneDecode() throws Exception {
        DctImageCache.clear();
        byte[] jpg = JpegRobustnessTest.jpegAtQuality(SyntheticCovers.smoothCover(128, 128, 4), 0.85f);
        long before = DctImageCache.decodeCount();
        DctImage a = DctImageCache.get(jpg);
        DctAnalysis.analyze(jpg, null);
        new JpegDctPlugin().extract(jpg, "");
        BufferedImage p1 = DctImageCache.preview(jpg);
        BufferedImage p2 = DctImageCache.preview(jpg);
        assertSame(a, DctImageCache.get(jpg));
        assertSame(p1, p2);
        assertEquals(128, p1.getWidth());
        assertEquals(1, DctImageCache.decodeCount() - before);
        // equal content in a different array is a different key (identity), and the LRU keeps two files
        byte[] copy = jpg.clone();
        assertNotSame(a, DctImageCache.get(copy));
        byte[] other = JpegRobustnessTest.jpegAtQuality(SyntheticCovers.smoothCover(64, 64, 5), 0.85f);
        DctImageCache.get(other);
        assertEquals(3, DctImageCache.decodeCount() - before);
        DctImageCache.get(jpg);                                   // evicted by the two newer entries
        assertEquals(4, DctImageCache.decodeCount() - before);
        DctImageCache.clear();
    }
}
