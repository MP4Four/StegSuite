package org.stegsuite.core.image;

import org.junit.Test;
import org.stegsuite.TestImages;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ApngTest {
    private static byte[] chunkData(byte[] png, String type) {
        int p = 8;
        while (p < png.length) {
            int len = ByteBuffer.wrap(png, p, 4).getInt();
            if (new String(png, p + 4, 4).equals(type)) return Arrays.copyOfRange(png, p + 8, p + 8 + len);
            p += 12 + len;
        }
        return null;
    }

    private static byte[] fctl(int seq, int w, int h) {
        return ByteBuffer.allocate(26).putInt(seq).putInt(w).putInt(h).putInt(0).putInt(0)
                .putShort((short) 1).putShort((short) 10).put((byte) 0).put((byte) 0).array();
    }

    @Test public void apngFramesSplit() throws Exception {
        java.awt.image.BufferedImage a = TestImages.noise(6, 4, 1);
        java.awt.image.BufferedImage b = TestImages.noise(6, 4, 2);
        byte[] pa = TestImages.png(a), pb = TestImages.png(b);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Arrays.copyOf(pa, 8));
        FrameExtractor.writeChunk(o, "IHDR", chunkData(pa, "IHDR"));
        FrameExtractor.writeChunk(o, "acTL", ByteBuffer.allocate(8).putInt(2).putInt(0).array());
        FrameExtractor.writeChunk(o, "fcTL", fctl(0, 6, 4));
        FrameExtractor.writeChunk(o, "IDAT", chunkData(pa, "IDAT"));
        FrameExtractor.writeChunk(o, "fcTL", fctl(1, 6, 4));
        byte[] idb = chunkData(pb, "IDAT");
        FrameExtractor.writeChunk(o, "fdAT", ByteBuffer.allocate(4 + idb.length).putInt(2).put(idb).array());
        FrameExtractor.writeChunk(o, "IEND", new byte[0]);
        List<FrameExtractor.Frame> frames = FrameExtractor.extract(o.toByteArray(), false);
        assertEquals(2, frames.size());
        assertEquals(a.getRGB(3, 2), frames.get(0).image().getRGB(3, 2));
        assertEquals(b.getRGB(3, 2), frames.get(1).image().getRGB(3, 2));
    }
}
