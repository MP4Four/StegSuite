package org.stegsuite;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;

/** Test-only fixtures: builds images with known LSB content so the scanner can be verified. */
public final class TestImages {
    private TestImages() {}

    public static BufferedImage noise(int w, int h, long seed) {
        Random r = new Random(seed);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, r.nextInt(0x1000000));
        return img;
    }

    /** Writes payload bits (MSB of each byte first) into bit 0 of R,G,B, walking rows or columns. */
    public static BufferedImage withLsbPayload(BufferedImage img, byte[] payload, boolean columnOrder) {
        int w = img.getWidth(), h = img.getHeight();
        int bit = 0, total = payload.length * 8;
        int outer = columnOrder ? w : h, inner = columnOrder ? h : w;
        for (int o = 0; o < outer && bit < total; o++)
            for (int i = 0; i < inner && bit < total; i++) {
                int x = columnOrder ? o : i, y = columnOrder ? i : o;
                int p = img.getRGB(x, y);
                for (int shift = 16; shift >= 0 && bit < total; shift -= 8) {
                    int b = (payload[bit / 8] >> (7 - bit % 8)) & 1;
                    p = (p & ~(1 << shift)) | (b << shift);
                    bit++;
                }
                img.setRGB(x, y, p);
            }
        return img;
    }

    public static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        return o.toByteArray();
    }

    public static byte[] jpeg(BufferedImage img) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", o);
        return o.toByteArray();
    }

    /**
     * Test fixture: sets payload bits (MSB of each byte first) into the bit positions selected by {@code cfg}
     * (row order, low-to-high bits within a sample), i.e. exactly the layout BitExtractor reads back.
     */
    public static BufferedImage withPayload(BufferedImage img, byte[] payload, org.stegsuite.core.extract.ExtractConfig cfg) {
        int w = img.getWidth(), h = img.getHeight(), bit = 0, total = payload.length * 8;
        for (int y = 0; y < h && bit < total; y++)
            for (int x = 0; x < w && bit < total; x++) {
                int p = img.getRGB(x, y);
                for (char c : cfg.order().toCharArray()) {
                    int shift = org.stegsuite.core.extract.ExtractConfig.shiftOf(c);
                    int m = cfg.masks()[org.stegsuite.core.extract.ExtractConfig.channelIndex(c)];
                    for (int b = 0; b < 8 && bit < total; b++) {
                        if ((m >> b & 1) == 0) continue;
                        int v = (payload[bit / 8] >> (7 - bit % 8)) & 1;
                        p = (p & ~(1 << (shift + b))) | (v << (shift + b));
                        bit++;
                    }
                }
                img.setRGB(x, y, p);
            }
        return img;
    }
}
