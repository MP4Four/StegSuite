package org.stegsuite.core.image;

import java.awt.image.BufferedImage;

/**
 * Stegsolve-style stereogram solver: compares every pixel with the pixel {@code offset} columns to its right
 * (wrapping around), which reveals the hidden shape of an autostereogram at the right offset.
 */
public final class StereogramSolver {
    private StereogramSolver() {}

    public enum Mode {
        XOR("XOR (Stegsolve)"), DIFF("Absolute difference");
        private final String label;
        Mode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public static BufferedImage render(BufferedImage img, int offset, Mode mode) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = ImageUtil.pixels(img), out = new int[px.length];
        int off = ((offset % w) + w) % w;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int a = px[row + x], b = px[row + (x + off) % w];
                int v;
                if (mode == Mode.XOR) v = (a ^ b) & 0xffffff;
                else v = Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff)) << 16
                        | Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff)) << 8
                        | Math.abs((a & 0xff) - (b & 0xff));
                out[row + x] = 0xff000000 | v;
            }
        }
        return ImageUtil.fromPixels(out, w, h);
    }
}
