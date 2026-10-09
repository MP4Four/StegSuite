package org.stegsuite.core.image;

import java.awt.image.BufferedImage;

/** Stegsolve "Image Combiner": pixel-wise combination of two images (per RGB channel). */
public final class ImageCombiner {
    private ImageCombiner() {}

    public enum Op {
        XOR, OR, AND, ADD, SUB, SUB_ABS, LIGHTEST, DARKEST, INTERLACE_ROWS, INTERLACE_COLS;
        @Override public String toString() {
            switch (this) {
                case SUB: return "SUB (A-B mod 256)";
                case SUB_ABS: return "SUB |A-B|";
                case ADD: return "ADD (mod 256)";
                case INTERLACE_ROWS: return "Interlace rows (A,B,A,B...)";
                case INTERLACE_COLS: return "Interlace columns";
                default: return name();
            }
        }
    }

    public static BufferedImage combine(BufferedImage a, BufferedImage b, Op op) {
        int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int o;
                if (op == Op.INTERLACE_ROWS) o = (y % 2 == 0) ? p : q;
                else if (op == Op.INTERLACE_COLS) o = (x % 2 == 0) ? p : q;
                else {
                    o = 0xff000000;
                    for (int s = 0; s <= 16; s += 8) {
                        int c1 = (p >> s) & 0xff, c2 = (q >> s) & 0xff, c;
                        switch (op) {
                            case XOR: c = c1 ^ c2; break;
                            case OR: c = c1 | c2; break;
                            case AND: c = c1 & c2; break;
                            case ADD: c = (c1 + c2) & 0xff; break;
                            case SUB: c = (c1 - c2) & 0xff; break;
                            case SUB_ABS: c = Math.abs(c1 - c2); break;
                            case LIGHTEST: c = Math.max(c1, c2); break;
                            case DARKEST: c = Math.min(c1, c2); break;
                            default: c = 0; break;
                        }
                        o |= c << s;
                    }
                }
                out.setRGB(x, y, o);
            }
        }
        return out;
    }
}
