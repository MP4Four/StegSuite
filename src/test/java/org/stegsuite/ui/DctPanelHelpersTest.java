package org.stegsuite.ui;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

/** Non-GUI helpers of the JPEG DCT tab. */
public class DctPanelHelpersTest {

    @Test public void unwrapsExecutionException() {
        IOException io = new IOException("scan uses undefined AC Huffman table 3");
        Throwable root = DctPanel.rootCause(new ExecutionException(io));
        assertSame(io, root);
        assertEquals("scan uses undefined AC Huffman table 3", DctPanel.describe(root));
        assertEquals("NullPointerException", DctPanel.describe(new NullPointerException()));
        assertTrue(DctPanel.describe(new OutOfMemoryError()).startsWith("Out of memory"));
    }

    @Test public void recognisesJpegSignature() {
        assertTrue(DctPanel.isJpeg(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}));
        assertFalse(DctPanel.isJpeg(new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
        assertFalse(DctPanel.isJpeg(null));
        assertFalse(DctPanel.isJpeg(new byte[]{(byte) 0xff, (byte) 0xd8}));
    }
}
