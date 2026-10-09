package org.stegsuite.core.jpeg;

import java.awt.image.BufferedImage;

/**
 * Inverse DCT and sample reconstruction for {@link DctImage}: dequantize every block, transform it back
 * to the spatial domain, upsample the chroma components and convert to RGB.
 *
 * <p>Two transforms are available: the separable floating point IDCT of T.81 A.3.3 ({@link #idct}, the
 * reference) and the AAN (Arai-Agui-Nakajima) factorisation with the scale factors folded into the
 * dequantization table ({@link #idctFast}, libjpeg's "float" method), which needs 5 multiplications per
 * 1-D transform instead of 64 and is used for reconstruction. Blocks without AC coefficients are filled
 * with their DC value directly. The 1x2 / 2x2 upsampling is the same triangular "fancy" filter libjpeg
 * uses, and the colour conversion uses libjpeg's fixed-point tables, so the result can be compared sample
 * by sample with the JDK's own JPEG decoder (the DCT report does that as a sanity check).
 */
public final class InverseDct {
    private InverseDct() {}

    /** Scaled cosine basis: KCOS[row * 8 + u] = C(u) * cos((2*row+1) * u * pi / 16). */
    private static final double[] KCOS = new double[64];

    static {
        for (int row = 0; row < 8; row++)
            for (int u = 0; u < 8; u++)
                KCOS[row * 8 + u] = (u == 0 ? 1.0 / Math.sqrt(2.0) : 1.0) * Math.cos((2 * row + 1) * u * Math.PI / 16.0);
    }

    /**
     * Inverse DCT of one dequantized block (natural row-major order) into 64 samples (row-major),
     * level shifted by 128.
     */
    public static void idct(int[] dequantized, int[] out) {
        double[] tmp = new double[64];
        // vertical pass: tmp[row][v] = sum over u of C(u) * F(u,v) * cos((2*row+1)*u*pi/16)
        for (int v = 0; v < 8; v++) {
            for (int row = 0; row < 8; row++) {
                double sum = 0;
                for (int u = 0; u < 8; u++) sum += dequantized[u * 8 + v] * KCOS[row * 8 + u];
                tmp[row * 8 + v] = sum;
            }
        }
        // horizontal pass: sample(row,col) = 0.25 * sum over v of C(v) * tmp[row][v] * cos((2*col+1)*v*pi/16)
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                double sum = 0;
                for (int v = 0; v < 8; v++) sum += tmp[row * 8 + v] * KCOS[col * 8 + v];
                int s = (int) Math.round(sum * 0.25) + 128;
                out[row * 8 + col] = s < 0 ? 0 : s > 255 ? 255 : s;
            }
        }
    }

    /** AAN scale factors: cos(k*pi/16) * sqrt(2) for k = 1..7, 1 for k = 0. */
    private static final double[] AAN = {1.0, 1.387039845, 1.306562965, 1.175875602, 1.0, 0.785694958, 0.541196100, 0.275899379};

    /** Dequantization table with the AAN scale factors folded in (natural order), input of {@link #idctFast}. */
    public static float[] aanTable(int[] quantNatural) {
        float[] t = new float[64];
        for (int i = 0; i < 64; i++) t[i] = (float) (quantNatural[i] * AAN[i >> 3] * AAN[i & 7]);
        return t;
    }

    /**
     * AAN float inverse DCT of one block of quantized coefficients ({@code coeffs[base .. base+63]}, natural
     * order) with an {@link #aanTable}; writes 64 level-shifted, clamped samples to {@code out} at
     * {@code outOff} with row stride {@code stride}. {@code ws} is a 64-entry scratch array.
     */
    public static void idctFast(short[] coeffs, int base, float[] qt, float[] ws, byte[] out, int outOff, int stride) {
        // pass 1: columns
        for (int c = 0; c < 8; c++) {
            int i0 = base + c;
            if (coeffs[i0 + 8] == 0 && coeffs[i0 + 16] == 0 && coeffs[i0 + 24] == 0 && coeffs[i0 + 32] == 0
                    && coeffs[i0 + 40] == 0 && coeffs[i0 + 48] == 0 && coeffs[i0 + 56] == 0) {
                float dc = coeffs[i0] * qt[c];
                for (int r = 0; r < 8; r++) ws[r * 8 + c] = dc;
                continue;
            }
            float tmp0 = coeffs[i0] * qt[c], tmp1 = coeffs[i0 + 16] * qt[16 + c];
            float tmp2 = coeffs[i0 + 32] * qt[32 + c], tmp3 = coeffs[i0 + 48] * qt[48 + c];
            float tmp10 = tmp0 + tmp2, tmp11 = tmp0 - tmp2;
            float tmp13 = tmp1 + tmp3, tmp12 = (tmp1 - tmp3) * 1.414213562f - tmp13;
            tmp0 = tmp10 + tmp13; tmp3 = tmp10 - tmp13; tmp1 = tmp11 + tmp12; tmp2 = tmp11 - tmp12;
            float tmp4 = coeffs[i0 + 8] * qt[8 + c], tmp5 = coeffs[i0 + 24] * qt[24 + c];
            float tmp6 = coeffs[i0 + 40] * qt[40 + c], tmp7 = coeffs[i0 + 56] * qt[56 + c];
            float z13 = tmp6 + tmp5, z10 = tmp6 - tmp5, z11 = tmp4 + tmp7, z12 = tmp4 - tmp7;
            tmp7 = z11 + z13;
            tmp11 = (z11 - z13) * 1.414213562f;
            float z5 = (z10 + z12) * 1.847759065f;
            tmp10 = 1.082392200f * z12 - z5;
            tmp12 = -2.613125930f * z10 + z5;
            tmp6 = tmp12 - tmp7; tmp5 = tmp11 - tmp6; tmp4 = tmp10 + tmp5;
            ws[c] = tmp0 + tmp7; ws[56 + c] = tmp0 - tmp7;
            ws[8 + c] = tmp1 + tmp6; ws[48 + c] = tmp1 - tmp6;
            ws[16 + c] = tmp2 + tmp5; ws[40 + c] = tmp2 - tmp5;
            ws[32 + c] = tmp3 + tmp4; ws[24 + c] = tmp3 - tmp4;
        }
        // pass 2: rows, descale by 8, level shift, clamp
        for (int r = 0; r < 8; r++) {
            int w = r * 8;
            float tmp10 = ws[w] + ws[w + 4], tmp11 = ws[w] - ws[w + 4];
            float tmp13 = ws[w + 2] + ws[w + 6], tmp12 = (ws[w + 2] - ws[w + 6]) * 1.414213562f - tmp13;
            float tmp0 = tmp10 + tmp13, tmp3 = tmp10 - tmp13, tmp1 = tmp11 + tmp12, tmp2 = tmp11 - tmp12;
            float z13 = ws[w + 5] + ws[w + 3], z10 = ws[w + 5] - ws[w + 3];
            float z11 = ws[w + 1] + ws[w + 7], z12 = ws[w + 1] - ws[w + 7];
            float tmp7 = z11 + z13;
            tmp11 = (z11 - z13) * 1.414213562f;
            float z5 = (z10 + z12) * 1.847759065f;
            tmp10 = 1.082392200f * z12 - z5;
            tmp12 = -2.613125930f * z10 + z5;
            float tmp6 = tmp12 - tmp7, tmp5 = tmp11 - tmp6, tmp4 = tmp10 + tmp5;
            int o = outOff + r * stride;
            out[o] = clampShift(tmp0 + tmp7);
            out[o + 7] = clampShift(tmp0 - tmp7);
            out[o + 1] = clampShift(tmp1 + tmp6);
            out[o + 6] = clampShift(tmp1 - tmp6);
            out[o + 2] = clampShift(tmp2 + tmp5);
            out[o + 5] = clampShift(tmp2 - tmp5);
            out[o + 4] = clampShift(tmp3 + tmp4);
            out[o + 3] = clampShift(tmp3 - tmp4);
        }
    }

    private static byte clampShift(float v) {
        int s = Math.round(v * 0.125f) + 128;
        return (byte) (s < 0 ? 0 : s > 255 ? 255 : s);
    }

    /** Fills one 8x8 block of a plane with its DC sample (blocks without AC coefficients). */
    private static boolean dcOnly(short[] coeffs, int base) {
        for (int i = 1; i < 64; i++) if (coeffs[base + i] != 0) return false;
        return true;
    }

    /** Reconstructs one component plane (blocks * 8 samples) with the fast IDCT. */
    private static byte[] plane(DctImage.Component c, DctImage.QuantTable q) {
        byte[] plane = new byte[c.sampleWidth() * c.sampleHeight()];
        float[] qt = aanTable(q.values());
        float[] ws = new float[64];
        short[] raw = c.raw();
        int stride = c.sampleWidth();
        for (int b = 0; b < c.blockCount(); b++) {
            int base = b << 6;
            int off = (b / c.blocksPerLine()) * 8 * stride + (b % c.blocksPerLine()) * 8;
            if (dcOnly(raw, base)) {
                byte v = clampShift(raw[base] * qt[0]);
                for (int y = 0; y < 8; y++) java.util.Arrays.fill(plane, off + y * stride, off + y * stride + 8, v);
            } else {
                idctFast(raw, base, qt, ws, plane, off, stride);
            }
        }
        return plane;
    }

    /**
     * Reconstructs the component planes at their own resolution (blocks * 8 samples, level shifted by 128).
     * Returns null if a component references a missing quantization table.
     */
    private static byte[][] planes(DctImage img) {
        byte[][] planes = new byte[img.components().size()][];
        for (int ci = 0; ci < img.components().size(); ci++) {
            DctImage.Component c = img.components().get(ci);
            DctImage.QuantTable q = img.quantTableOf(c);
            if (q == null) return null;
            planes[ci] = plane(c, q);
        }
        return planes;
    }

    /**
     * Samples (0..255) of one component at its own resolution, cropped to the size used by the frame
     * ({@code downsampledWidth x downsampledHeight}, row-major), or null for a missing quantization table.
     */
    public static int[] componentSamples(DctImage img, int componentIndex) {
        DctImage.Component c = img.components().get(componentIndex);
        DctImage.QuantTable q = img.quantTableOf(c);
        if (q == null) return null;
        int w = c.downsampledWidth(), h = c.downsampledHeight();
        byte[] plane = plane(c, q);
        int stride = c.sampleWidth();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int o = y * w, s = y * stride;
            for (int x = 0; x < w; x++) out[o + x] = plane[s + x] & 0xff;
        }
        return out;
    }

    /**
     * Converts the decoded coefficients into an RGB image, or null when the component layout cannot be
     * rendered (CMYK / YCCK, missing quantization table, unusual precision).
     */
    public static BufferedImage toRgb(DctImage img) {
        int n = img.components().size();
        if (img.precision() != 8 || (n != 1 && n != 3)) return null;
        byte[][] planes = planes(img);
        if (planes == null) return null;
        int w = img.width(), h = img.height();
        // upsampled, full resolution planes
        byte[][] full = new byte[n][];
        int[] fullWidth = new int[n];
        for (int ci = 0; ci < n; ci++) {
            DctImage.Component c = img.components().get(ci);
            full[ci] = upsample(planes[ci], c, img.maxH(), img.maxV());
            fullWidth[ci] = c.downsampledWidth() * ratio(img.maxH(), c.h());
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        // write straight into the raster: setRGB would convert every pixel through the colour model
        int[] px = ((java.awt.image.DataBufferInt) out.getRaster().getDataBuffer()).getData();
        // Adobe APP14 transform 0 means the three components are already R, G and B.
        boolean directRgb = n == 3 && img.adobeTransform() == 0;
        int[] rows = new int[n];
        for (int ci = 0; ci < n; ci++) rows[ci] = full[ci].length / fullWidth[ci];
        for (int y = 0; y < h; y++) {
            int o = y * w;
            int b0 = Math.min(y, rows[0] - 1) * fullWidth[0], x0 = Math.min(w, fullWidth[0]);
            byte[] p0 = full[0];
            if (n == 1) {
                for (int x = 0; x < w; x++) {
                    int v = p0[b0 + Math.min(x, x0 - 1)] & 0xff;
                    px[o + x] = (v << 16) | (v << 8) | v;
                }
                continue;
            }
            int b1 = Math.min(y, rows[1] - 1) * fullWidth[1], x1 = Math.min(w, fullWidth[1]);
            int b2 = Math.min(y, rows[2] - 1) * fullWidth[2], x2 = Math.min(w, fullWidth[2]);
            byte[] p1 = full[1], p2 = full[2];
            for (int x = 0; x < w; x++) {
                int yy = p0[b0 + Math.min(x, x0 - 1)] & 0xff;
                int c1 = p1[b1 + Math.min(x, x1 - 1)] & 0xff;
                int c2 = p2[b2 + Math.min(x, x2 - 1)] & 0xff;
                if (directRgb) {
                    px[o + x] = (yy << 16) | (c1 << 8) | c2;
                } else {
                    int r = yy + CR_R[c2], g = yy + ((CB_G[c1] + CR_G[c2]) >> 16), b = yy + CB_B[c1];
                    px[o + x] = (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
                }
            }
        }
        return out;
    }

    /** libjpeg jdcolor.c fixed-point YCbCr -> RGB tables (16 fractional bits). */
    private static final int[] CR_R = new int[256], CB_B = new int[256], CR_G = new int[256], CB_G = new int[256];

    static {
        final int one = 1 << 16, half = 1 << 15;
        for (int i = 0; i < 256; i++) {
            int x = i - 128;
            CR_R[i] = (int) ((Math.round(1.40200 * one) * x + half) >> 16);
            CB_B[i] = (int) ((Math.round(1.77200 * one) * x + half) >> 16);
            CR_G[i] = (int) (-Math.round(0.71414 * one) * x);
            CB_G[i] = (int) (-Math.round(0.34414 * one) * x + half);
        }
    }

    private static int clamp8(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    private static int at(byte[] plane, int planeWidth, int x, int y) {
        int px = Math.min(x, planeWidth - 1), py = Math.min(y, plane.length / planeWidth - 1);
        if (px < 0) px = 0;
        if (py < 0) py = 0;
        return plane[py * planeWidth + px] & 0xff;
    }

    /**
     * Expands a component plane to full resolution. 2x1 and 2x2 ratios use the triangular filter of
     * libjpeg's h2v1/h2v2 "fancy" upsampling (edge samples are duplicated); every other ratio replicates
     * samples, which is what libjpeg does for the generic case too.
     */
    private static byte[] upsample(byte[] plane, DctImage.Component c, int maxH, int maxV) {
        int inW = c.downsampledWidth(), inH = c.downsampledHeight();
        int ratioH = ratio(maxH, c.h()), ratioV = ratio(maxV, c.v());
        int outW = inW * ratioH, outH = inH * ratioV;
        byte[] out = new byte[outW * outH];
        if (ratioH == 1 && ratioV == 1) {
            for (int y = 0; y < outH; y++) System.arraycopy(plane, y * c.sampleWidth(), out, y * outW, outW);
            return out;
        }
        if (ratioH == 2 && ratioV == 1) {
            int[] row = new int[outW];
            for (int y = 0; y < inH; y++) {
                h2Fancy(plane, c, y, row);
                for (int x = 0; x < outW; x++) out[y * outW + x] = (byte) row[x];
            }
            return out;
        }
        if (ratioH == 2 && ratioV == 2) {
            int[] row = new int[outW];
            for (int y = 0; y < inH; y++) {
                int above = y == 0 ? 0 : y - 1, below = y + 1 >= inH ? y : y + 1;
                h2v2Fancy(plane, c, y, above, row);
                for (int x = 0; x < outW; x++) out[(2 * y) * outW + x] = (byte) row[x];
                h2v2Fancy(plane, c, y, below, row);
                for (int x = 0; x < outW; x++) out[(2 * y + 1) * outW + x] = (byte) row[x];
            }
            return out;
        }
        for (int y = 0; y < outH; y++)
            for (int x = 0; x < outW; x++) out[y * outW + x] = (byte) sample(plane, c, x / ratioH, y / ratioV);
        return out;
    }

    /** Sampling ratio of a component against the frame's maximum, or 1 when the factors do not divide. */
    private static int ratio(int maxSampling, int sampling) {
        return sampling > 0 && maxSampling % sampling == 0 ? maxSampling / sampling : 1;
    }

    private static int sample(byte[] plane, DctImage.Component c, int x, int y) {
        int sx = x < c.downsampledWidth() ? x : c.downsampledWidth() - 1;
        int sy = y < c.downsampledHeight() ? y : c.downsampledHeight() - 1;
        return plane[sy * c.sampleWidth() + sx] & 0xff;
    }

    /** libjpeg h2v1_fancy_upsample: doubles the width of one row. */
    private static void h2Fancy(byte[] plane, DctImage.Component c, int y, int[] out) {
        int n = c.downsampledWidth();
        if (n == 1) {
            out[0] = sample(plane, c, 0, y);
            out[1] = out[0];
            if (out.length > 2) out[2] = out[0];
            return;
        }
        int first = sample(plane, c, 0, y);
        out[0] = first;
        out[1] = (first * 3 + sample(plane, c, 1, y) + 2) >> 2;
        for (int i = 1; i < n - 1; i++) {
            int v = sample(plane, c, i, y);
            out[2 * i] = (v * 3 + sample(plane, c, i - 1, y) + 1) >> 2;
            out[2 * i + 1] = (v * 3 + sample(plane, c, i + 1, y) + 2) >> 2;
        }
        int last = sample(plane, c, n - 1, y);
        out[2 * (n - 1)] = (last * 3 + sample(plane, c, n - 2, y) + 1) >> 2;
        out[2 * (n - 1) + 1] = last;
    }

    /**
     * libjpeg h2v2_fancy_upsample: doubles width and height. {@code y} is the input row the output pair
     * belongs to and {@code other} the neighbouring row the vertical 3:1 weighting is taken from.
     */
    private static void h2v2Fancy(byte[] plane, DctImage.Component c, int y, int other, int[] out) {
        int n = c.downsampledWidth();
        int ry = y * c.sampleWidth(), ro = other * c.sampleWidth();
        if (n == 1) {
            int v = ((plane[ry] & 0xff) * 3 + (plane[ro] & 0xff) + 2) >> 2;
            out[0] = v;
            out[1] = v;
            return;
        }
        int thisCol = 3 * (plane[ry] & 0xff) + (plane[ro] & 0xff);
        int nextCol = 3 * (plane[ry + 1] & 0xff) + (plane[ro + 1] & 0xff);
        out[0] = (thisCol * 4 + 8) >> 4;
        out[1] = (thisCol * 3 + nextCol + 7) >> 4;
        int lastCol = thisCol;
        thisCol = nextCol;
        for (int i = 1; i < n - 1; i++) {
            nextCol = 3 * (plane[ry + i + 1] & 0xff) + (plane[ro + i + 1] & 0xff);
            out[2 * i] = (thisCol * 3 + lastCol + 8) >> 4;
            out[2 * i + 1] = (thisCol * 3 + nextCol + 7) >> 4;
            lastCol = thisCol;
            thisCol = nextCol;
        }
        out[2 * (n - 1)] = (thisCol * 3 + lastCol + 8) >> 4;
        out[2 * (n - 1) + 1] = (thisCol * 4 + 7) >> 4;
    }

    private static int clamp(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }
}
