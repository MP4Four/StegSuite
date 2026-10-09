package org.stegsuite;

import org.junit.Test;
import org.stegsuite.core.image.BitPlaneRenderer;
import org.stegsuite.core.image.ImageCombiner;

import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

public class BitPlaneTest {
    private static BufferedImage sample() {
        BufferedImage img = new BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xff010000); // red bit 0
        img.setRGB(1, 0, 0xff800000); // red bit 7
        img.setRGB(2, 0, 0xff000001); // blue bit 0
        img.setRGB(3, 0, 0xff7f7f7f); // gray
        return img;
    }

    @Test public void redPlanes() {
        BufferedImage p0 = BitPlaneRenderer.render(sample(), BitPlaneRenderer.indexOf("Red plane 0"), false);
        assertEquals(0xffffffff, p0.getRGB(0, 0));
        assertEquals(0xff000000, p0.getRGB(1, 0));
        assertEquals(0xff000000, p0.getRGB(2, 0));
        assertEquals(0xffffffff, p0.getRGB(3, 0));
        BufferedImage p7 = BitPlaneRenderer.render(sample(), BitPlaneRenderer.indexOf("Red plane 7"), false);
        assertEquals(0xff000000, p7.getRGB(0, 0));
        assertEquals(0xffffffff, p7.getRGB(1, 0));
    }

    @Test public void bluePlaneAndInverse() {
        BufferedImage b0 = BitPlaneRenderer.render(sample(), BitPlaneRenderer.indexOf("Blue plane 0"), true);
        assertEquals(0xff000000, b0.getRGB(2, 0)); // inverted: set bit -> black
        assertEquals(0xffffffff, b0.getRGB(0, 0));
        BufferedImage inv = BitPlaneRenderer.render(sample(), BitPlaneRenderer.indexOf("Colour inversion (XOR 0xFFFFFF)"), false);
        assertEquals(0xfffeffff, inv.getRGB(0, 0));
    }

    @Test public void viewsCoverAllPlanes() {
        for (String c : new String[]{"Red", "Green", "Blue", "Alpha"})
            for (int b = 0; b < 8; b++) assertTrue(BitPlaneRenderer.indexOf(c + " plane " + b) >= 0);
        BufferedImage g = BitPlaneRenderer.render(sample(), BitPlaneRenderer.indexOf("Gray bits (R==G==B)"), false);
        assertEquals(0xffffffff, g.getRGB(3, 0));
        assertEquals(0xff000000, g.getRGB(0, 0));
    }

    @Test public void combinerXorSelfIsBlack() {
        BufferedImage x = ImageCombiner.combine(TestImages.noise(8, 8, 5), TestImages.noise(8, 8, 5), ImageCombiner.Op.XOR);
        for (int y = 0; y < 8; y++) for (int i = 0; i < 8; i++) assertEquals(0xff000000, x.getRGB(i, y));
        BufferedImage il = ImageCombiner.combine(sample(), TestImages.noise(4, 1, 6), ImageCombiner.Op.INTERLACE_COLS);
        assertEquals(sample().getRGB(0, 0), il.getRGB(0, 0));
    }
}
