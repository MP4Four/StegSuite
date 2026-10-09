package org.stegsuite;

import org.junit.Test;
import org.stegsuite.core.extract.*;
import org.stegsuite.core.image.ImageUtil;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class ScanDeepTest {
    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    private static BufferedImage roundTrip(BufferedImage img) throws Exception { return ImageUtil.decode(TestImages.png(img)); }

    @Test public void deepComboSetCoversHighMasksAndMixed() {
        List<ExtractConfig> quick = AutoScanner.combos(false, AutoScanner.Mode.QUICK);
        List<ExtractConfig> deep = AutoScanner.combos(false, AutoScanner.Mode.DEEP);
        assertTrue(deep.size() > quick.size());
        for (String l : new String[]{"b5,rgb,lsb,xy", "b6,rgb,lsb,xy", "b7,rgb,lsb,xy", "b8,rgb,msb,xy", "b1,r,lsb,xy",
                "b1r+b2g,rg,lsb,xy", "b2r+b1g+b1b,rgb,lsb,yx"})
            assertTrue(l, deep.stream().anyMatch(c -> c.label().equals(l)));
        assertTrue(quick.stream().noneMatch(c -> c.label().startsWith("b6")));
        assertEquals("number of distinct configs", deep.size(), deep.stream().distinct().count());
    }

    @Test public void b6HiddenFlagFoundOnlyByDeep() throws Exception {
        ExtractConfig cfg = ExtractConfig.uniform("RGB", 0x3f, false, false, false);
        BufferedImage img = roundTrip(TestImages.withPayload(TestImages.noise(48, 48, 21), ascii("x:flag{six_bits_deep}"), cfg));
        List<ScanHit> deep = AutoScanner.scan(img, AutoScanner.Mode.DEEP, null, () -> false);
        ScanHit top = deep.get(0);
        assertEquals(ScanHit.Type.FLAG, top.type());
        assertEquals("b6,rgb,lsb,xy", top.config().label());
        assertEquals(2, top.offset());
        assertTrue(top.detail().contains("flag{six_bits_deep}"));
        List<ScanHit> quick = AutoScanner.scan(img, AutoScanner.Mode.QUICK, null, () -> false);
        assertTrue(quick.stream().noneMatch(h -> h.detail().contains("six_bits_deep")));
    }

    @Test public void mixedMaskHiddenTextFound() throws Exception {
        ExtractConfig cfg = new ExtractConfig("RGB", new int[]{0x01, 0x03, 0, 0}, false, false, false);
        assertEquals("b1r+b2g,rg,lsb,xy", cfg.label());
        String msg = "Mixed mask message: flag{b1r_b2g_mix} and some trailing words.";
        BufferedImage img = roundTrip(TestImages.withPayload(TestImages.noise(40, 40, 22), ascii(msg), cfg));
        List<ScanHit> hits = AutoScanner.scan(img, AutoScanner.Mode.DEEP, null, () -> false);
        assertEquals("b1r+b2g,rg,lsb,xy", hits.get(0).config().label());
        assertEquals(ScanHit.Type.FLAG, hits.get(0).type());
        assertEquals(msg, new String(hits.get(0).data(), 0, msg.length(), StandardCharsets.US_ASCII));
    }

    @Test public void signatureAndTextAtNonZeroOffset() throws Exception {
        Random r = new Random(5);
        byte[] junk = new byte[37];
        r.nextBytes(junk);
        junk[0] = 0; // make sure no accidental magic at offset 0
        byte[] png = TestImages.png(TestImages.noise(2, 2, 3)); // a real PNG header (+IHDR) hidden after junk
        byte[] payload = new byte[junk.length + 40];
        System.arraycopy(junk, 0, payload, 0, junk.length);
        System.arraycopy(png, 0, payload, junk.length, 40);
        BufferedImage img = TestImages.withLsbPayload(TestImages.noise(64, 64, 23), payload, false);
        List<ScanHit> hits = AutoScanner.scan(img, AutoScanner.Mode.QUICK, null, () -> false);
        ScanHit f = hits.stream().filter(h -> h.type() == ScanHit.Type.FILE && h.config().label().equals("b1,rgb,lsb,xy"))
                .findFirst().get();
        assertEquals(37, f.offset());
        assertTrue(f.detail(), f.detail().startsWith("PNG image") && f.detail().endsWith("@0x25"));

        byte[] pre = new byte[100];
        r.nextBytes(pre);
        for (int i = 0; i < pre.length; i++) pre[i] = (byte) (pre[i] | 0x80); // non-printable filler
        String text = "this hidden sentence starts at byte one hundred";
        byte[] p2 = new byte[pre.length + text.length() + 1];
        System.arraycopy(pre, 0, p2, 0, pre.length);
        System.arraycopy(ascii(text), 0, p2, pre.length, text.length());
        p2[p2.length - 1] = (byte) 0x90;
        BufferedImage img2 = TestImages.withLsbPayload(TestImages.noise(64, 64, 24), p2, true);
        ScanHit t = AutoScanner.scan(img2, AutoScanner.Mode.QUICK, null, () -> false).stream()
                .filter(h -> h.type() == ScanHit.Type.TEXT && h.config().label().equals("b1,rgb,lsb,yx")).findFirst().get();
        assertEquals(100, t.offset());
        assertTrue(t.detail(), t.detail().contains(text));
    }

    @Test public void cleanNoiseDeepScanHasNoFlagsOrEmbeddedFiles() {
        List<ScanHit> hits = AutoScanner.scan(TestImages.noise(96, 96, 25), AutoScanner.Mode.DEEP, null, () -> false);
        assertTrue(hits.stream().noneMatch(h -> h.type() == ScanHit.Type.FLAG));
        assertTrue(hits.toString(), hits.stream().noneMatch(h -> h.type() == ScanHit.Type.FILE && h.offset() > 0));
    }

    @Test public void cancelStopsScan() {
        int[] calls = {0};
        List<ScanHit> hits = AutoScanner.scan(TestImages.noise(64, 64, 26), AutoScanner.Mode.DEEP,
                (i, n) -> calls[0]++, () -> true);
        assertTrue(hits.isEmpty());
        assertEquals(0, calls[0]);
    }
}
