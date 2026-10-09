package org.stegsuite;

import org.junit.Test;
import org.stegsuite.core.extract.*;
import org.stegsuite.core.image.ImageUtil;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class AutoScannerTest {
    private static final String TEXT = "Hello analyst! flag{lsb_scanner_works_42} end of message.";

    @Test public void scannerFindsLsbTextInPng() throws Exception {
        BufferedImage img = TestImages.withLsbPayload(TestImages.noise(64, 64, 1), TEXT.getBytes(StandardCharsets.US_ASCII), false);
        BufferedImage reloaded = ImageUtil.decode(TestImages.png(img)); // round trip through a real PNG file
        assertNotNull(reloaded);
        List<ScanHit> hits = AutoScanner.scan(reloaded, null);
        ScanHit top = hits.get(0);
        assertEquals(ScanHit.Type.FLAG, top.type());
        assertEquals("b1,rgb,lsb,xy", top.config().label());
        assertTrue(top.detail().contains("flag{lsb_scanner_works_42}"));
        String head = new String(top.data(), 0, TEXT.length(), StandardCharsets.US_ASCII);
        assertEquals(TEXT, head);
    }

    @Test public void manualExtractMatchesPayload() {
        BufferedImage img = TestImages.withLsbPayload(TestImages.noise(32, 32, 2), "plain text payload".getBytes(), false);
        byte[] d = BitExtractor.extract(img, ExtractConfig.uniform("RGB", 1, false, false, false), 18);
        assertEquals("plain text payload", new String(d, StandardCharsets.US_ASCII));
        assertEquals(32 * 32 * 3 / 8, BitExtractor.extract(img, ExtractConfig.uniform("RGB", 1, false, false, false), 1 << 20).length);
    }

    @Test public void scannerFindsFileMagicInColumnOrder() {
        byte[] zip = {'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0, 1, 2, 3, 4, 5, 6};
        BufferedImage img = TestImages.withLsbPayload(TestImages.noise(40, 40, 3), zip, true);
        List<ScanHit> hits = AutoScanner.scan(img, null);
        assertTrue(hits.stream().anyMatch(h -> h.type() == ScanHit.Type.FILE
                && h.config().label().equals("b1,rgb,lsb,yx") && h.detail().startsWith("ZIP")));
    }

    @Test public void cleanNoiseHasNoFlagHits() {
        List<ScanHit> hits = AutoScanner.scan(TestImages.noise(48, 48, 4), null);
        assertTrue(hits.stream().noneMatch(h -> h.type() == ScanHit.Type.FLAG));
    }

    @Test public void labels() {
        assertEquals("b2,bgr,msb,yx,packLSB", ExtractConfig.uniform("BGR", 3, true, true, true).label());
        assertEquals(6, ExtractConfig.uniform("BGR", 3, true, true, true).bitsPerPixel());
    }
}
