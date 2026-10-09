package org.stegsuite.core.stat;

import java.awt.image.BufferedImage;
import java.util.Random;

/** Test-only fixtures: smooth natural-looking covers and LSB replacement at a known rate. */
public final class SyntheticCovers {
    private SyntheticCovers() {}

    /** Smooth gradients and low-frequency waves plus mild Gaussian sensor-like noise, 8-bit RGB. */
    public static BufferedImage smoothCover(int w, int h, long seed) {
        return smoothCover(w, h, seed, 2.0);
    }

    /** Like {@link #smoothCover(int, int, long)} with a chosen noise level (texture). */
    public static BufferedImage smoothCover(int w, int h, long seed, double noiseSigma) {
        Random r = new Random(seed);
        double[] fx = new double[3], fy = new double[3], ph = new double[3];
        for (int i = 0; i < 3; i++) { fx[i] = 0.01 + r.nextDouble() * 0.05; fy[i] = 0.01 + r.nextDouble() * 0.05; ph[i] = r.nextDouble() * 6; }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    double v = 128 + 50 * Math.sin(fx[c] * x + ph[c]) * Math.cos(fy[c] * y + c)
                            + 30.0 * (x + y) / (w + h) - 15 + noiseSigma * r.nextGaussian();
                    int iv = (int) Math.round(Math.max(0, Math.min(255, v)));
                    rgb = (rgb << 8) | iv;
                }
                img.setRGB(x, y, rgb);
            }
        return img;
    }

    /**
     * LSB replacement with random bits on a fraction {@code rate} of the samples (each R, G, B sample is
     * selected independently with probability {@code rate}); about rate / 2 of all LSBs actually flip.
     */
    public static BufferedImage withRandomLsb(BufferedImage src, double rate, long seed) {
        Random r = new Random(seed);
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y) & 0xffffff;
                for (int shift = 0; shift <= 16; shift += 8)
                    if (r.nextDouble() < rate) {
                        int bit = r.nextInt(2);
                        p = (p & ~(1 << shift)) | (bit << shift);
                    }
                out.setRGB(x, y, p);
            }
        return out;
    }
}
