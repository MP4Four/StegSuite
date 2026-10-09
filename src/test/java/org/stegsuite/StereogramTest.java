package org.stegsuite;

import org.junit.Test;
import org.stegsuite.core.image.StereogramSolver;

import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

public class StereogramTest {
    /** 16 px wide image whose columns repeat every 4 px (like an autostereogram's base pattern). */
    private static BufferedImage periodic() {
        int[] cols = {0x123456, 0xabcdef, 0x00ff00, 0x777777};
        BufferedImage img = new BufferedImage(16, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 3; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, cols[x % 4] ^ y);
        return img;
    }

    private static boolean allBlack(BufferedImage i) {
        for (int y = 0; y < i.getHeight(); y++) for (int x = 0; x < i.getWidth(); x++) if (i.getRGB(x, y) != 0xff000000) return false;
        return true;
    }

    @Test public void offsetZeroAndPeriodAreBlack() {
        assertTrue(allBlack(StereogramSolver.render(periodic(), 0, StereogramSolver.Mode.XOR)));
        assertTrue(allBlack(StereogramSolver.render(periodic(), 4, StereogramSolver.Mode.XOR)));
        assertTrue(allBlack(StereogramSolver.render(periodic(), 8, StereogramSolver.Mode.DIFF)));
        assertTrue(allBlack(StereogramSolver.render(periodic(), 16, StereogramSolver.Mode.XOR))); // wraps to 0
        assertFalse(allBlack(StereogramSolver.render(periodic(), 3, StereogramSolver.Mode.XOR)));
    }

    @Test public void xorAndDiffValuesWithWrap() {
        BufferedImage n = TestImages.noise(10, 4, 31);
        BufferedImage x = StereogramSolver.render(n, 3, StereogramSolver.Mode.XOR);
        assertEquals(0xff000000 | ((n.getRGB(2, 1) ^ n.getRGB(5, 1)) & 0xffffff), x.getRGB(2, 1));
        assertEquals(0xff000000 | ((n.getRGB(8, 2) ^ n.getRGB(1, 2)) & 0xffffff), x.getRGB(8, 2)); // 8+3 wraps to 1
        BufferedImage d = StereogramSolver.render(n, 3, StereogramSolver.Mode.DIFF);
        int a = n.getRGB(0, 0), b = n.getRGB(3, 0);
        int exp = 0xff000000 | Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff)) << 16
                | Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff)) << 8 | Math.abs((a & 0xff) - (b & 0xff));
        assertEquals(exp, d.getRGB(0, 0));
    }

    @Test public void hiddenShapeAppearsAtCorrectOffset() {
        // pattern repeats every 8 px; a 2x2 block is shifted in one repeat -> visible only at offset 8
        BufferedImage img = TestImages.noise(8, 8, 32);
        BufferedImage s = new BufferedImage(32, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 32; x++) s.setRGB(x, y, img.getRGB(x % 8, y));
        s.setRGB(20, 4, 0xffffff); s.setRGB(21, 4, 0xffffff);
        BufferedImage r = StereogramSolver.render(s, 8, StereogramSolver.Mode.XOR);
        int nonBlack = 0;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 32; x++) if (r.getRGB(x, y) != 0xff000000) nonBlack++;
        assertEquals(4, nonBlack); // (12,4),(13,4) see the changed pixels ahead, (20,4),(21,4) differ from (28,4),(29,4)
        assertNotEquals(0xff000000, r.getRGB(12, 4));
        assertNotEquals(0xff000000, r.getRGB(20, 4));
    }
}
