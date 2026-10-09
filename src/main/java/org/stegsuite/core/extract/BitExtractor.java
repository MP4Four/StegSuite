package org.stegsuite.core.extract;

import org.stegsuite.core.image.ImageUtil;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/** Reads selected bits out of image samples and packs them into bytes. */
public final class BitExtractor {
    private BitExtractor() {}

    public static byte[] extract(BufferedImage img, ExtractConfig cfg, int maxBytes) {
        return extract(ImageUtil.pixels(img), img.getWidth(), img.getHeight(), cfg, maxBytes);
    }

    public static byte[] extract(int[] px, int w, int h, ExtractConfig cfg, int maxBytes) {
        char[] order = cfg.order().toCharArray();
        int nc = order.length;
        int[] shifts = new int[nc];
        int[][] bitLists = new int[nc][];
        for (int i = 0; i < nc; i++) {
            shifts[i] = ExtractConfig.shiftOf(order[i]);
            int m = cfg.masks()[ExtractConfig.channelIndex(order[i])] & 0xff;
            int[] bl = new int[Integer.bitCount(m)];
            int k = 0;
            if (cfg.msbFirstInSample()) { for (int b = 7; b >= 0; b--) if ((m >> b & 1) != 0) bl[k++] = b; }
            else { for (int b = 0; b <= 7; b++) if ((m >> b & 1) != 0) bl[k++] = b; }
            bitLists[i] = bl;
        }
        long totalBits = (long) w * h * cfg.bitsPerPixel();
        int outLen = (int) Math.min(maxBytes, totalBits / 8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, outLen));
        int cur = 0, nbits = 0;
        int outer = cfg.columnOrder() ? w : h, inner = cfg.columnOrder() ? h : w;
        boolean lsbPack = cfg.packLsbFirst();
        for (int o = 0; o < outer; o++) {
            for (int in = 0; in < inner; in++) {
                int p = cfg.columnOrder() ? px[in * w + o] : px[o * w + in];
                for (int c = 0; c < nc; c++) {
                    int v = (p >>> shifts[c]) & 0xff;
                    for (int b : bitLists[c]) {
                        int bit = (v >> b) & 1;
                        if (lsbPack) cur |= bit << nbits; else cur = (cur << 1) | bit;
                        if (++nbits == 8) {
                            out.write(cur);
                            if (out.size() >= outLen) return out.toByteArray();
                            cur = 0; nbits = 0;
                        }
                    }
                }
            }
        }
        return out.toByteArray();
    }
}
