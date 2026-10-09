package org.stegsuite.core.image;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stegsolve-style image transforms: colour inversion, individual bit planes of each channel,
 * full channels, random colour maps, gray bits, grayscale and neighbour XOR.
 */
public final class BitPlaneRenderer {

    public enum Kind { NORMAL, INVERT, PLANE, FULL, RANDOM_MAP, GRAY_BITS, GRAYSCALE, XOR_NEIGHBOUR }

    /** A single view: kind + channel shift (24=A,16=R,8=G,0=B) + bit / seed. */
    public static final class View {
        private final String name;
        private final Kind kind;
        private final int shift;
        private final int bit;
        public View(String name, Kind kind, int shift, int bit) {
            this.name = name;
            this.kind = kind;
            this.shift = shift;
            this.bit = bit;
        }
        public String name() { return name; }
        public Kind kind() { return kind; }
        public int shift() { return shift; }
        public int bit() { return bit; }
        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof View)) return false;
            View o = (View) obj;
            return java.util.Objects.equals(name, o.name) && java.util.Objects.equals(kind, o.kind) && shift == o.shift && bit == o.bit;
        }
        @Override public int hashCode() { return java.util.Objects.hash(name, kind, shift, bit); }
        @Override public String toString() { return "View[" + "name=" + name + ", " + "kind=" + kind + ", " + "shift=" + shift + ", " + "bit=" + bit + "]"; }
    }

    private static final List<View> VIEWS = new ArrayList<>();

    static {
        VIEWS.add(new View("Normal image", Kind.NORMAL, 0, 0));
        VIEWS.add(new View("Colour inversion (XOR 0xFFFFFF)", Kind.INVERT, 0, 0));
        String[] names = {"Red", "Green", "Blue", "Alpha"};
        int[] shifts = {16, 8, 0, 24};
        for (int c = 0; c < 4; c++)
            for (int b = 7; b >= 0; b--)
                VIEWS.add(new View(names[c] + " plane " + b, Kind.PLANE, shifts[c], b));
        for (int c = 0; c < 4; c++) VIEWS.add(new View("Full " + names[c].toLowerCase(), Kind.FULL, shifts[c], 0));
        for (int s = 1; s <= 3; s++) VIEWS.add(new View("Random colour map " + s, Kind.RANDOM_MAP, 0, s));
        VIEWS.add(new View("Gray bits (R==G==B)", Kind.GRAY_BITS, 0, 0));
        VIEWS.add(new View("Grayscale", Kind.GRAYSCALE, 0, 0));
        VIEWS.add(new View("XOR with right neighbour", Kind.XOR_NEIGHBOUR, 0, 0));
    }

    public static List<View> views() { return VIEWS; }

    public static int indexOf(String name) {
        for (int i = 0; i < VIEWS.size(); i++) if (VIEWS.get(i).name().equals(name)) return i;
        return -1;
    }

    public static BufferedImage render(BufferedImage src, int viewIndex, boolean invertResult) {
        return render(src, VIEWS.get(viewIndex), invertResult);
    }

    public static BufferedImage render(BufferedImage src, View v, boolean invertResult) {
        int w = src.getWidth(), h = src.getHeight();
        int[] in = ImageUtil.pixels(src);
        int[] out = new int[in.length];
        int[][] lut = null;
        if (v.kind() == Kind.RANDOM_MAP) {
            Random r = new Random(v.bit() * 7919L);
            lut = new int[3][256];
            for (int c = 0; c < 3; c++) for (int i = 0; i < 256; i++) lut[c][i] = r.nextInt(256);
        }
        for (int i = 0; i < in.length; i++) {
            int p = in[i];
            int a = p >>> 24, r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            int o;
            switch (v.kind()) {
                case NORMAL: o = p; break;
                case INVERT: o = p ^ 0x00ffffff; break;
                case PLANE: o = (((p >>> v.shift()) >> v.bit()) & 1) != 0 ? 0xffffffff : 0xff000000; break;
                case FULL: { int c = (p >>> v.shift()) & 0xff; o = 0xff000000 | (c << 16) | (c << 8) | c; } break;
                case RANDOM_MAP: o = 0xff000000 | (lut[0][r] << 16) | (lut[1][g] << 8) | lut[2][b]; break;
                case GRAY_BITS: o = (r == g && g == b) ? 0xffffffff : 0xff000000; break;
                case GRAYSCALE: { int y = (r * 299 + g * 587 + b * 114) / 1000; o = (a << 24) | (y << 16) | (y << 8) | y; } break;
                case XOR_NEIGHBOUR: {
                    int x = i % w;
                    int q = x + 1 < w ? in[i + 1] : 0;
                    o = 0xff000000 | ((p ^ q) & 0xffffff);
                } break;
                default: o = p; break;
            }
            if (invertResult) o ^= 0x00ffffff;
            out[i] = o;
        }
        return ImageUtil.fromPixels(out, w, h);
    }
}
