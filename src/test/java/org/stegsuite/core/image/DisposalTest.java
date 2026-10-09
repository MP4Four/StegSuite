package org.stegsuite.core.image;

import org.junit.Test;
import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Composite-mode correctness for GIF disposal methods and APNG dispose_op / blend_op. */
public class DisposalTest {
    static final int RED = 0xffff0000, GREEN = 0xff00ff00, BLUE = 0xff0000ff, CLEAR = 0x00000000;

    private static BufferedImage solid(int w, int h, int argb) {
        BufferedImage i = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) i.setRGB(x, y, argb);
        return i;
    }

    // ---------------- GIF ----------------
    private static final class GifFrame {
        private final BufferedImage img;
        private final int x;
        private final int y;
        private final String disposal;
        public GifFrame(BufferedImage img, int x, int y, String disposal) {
            this.img = img;
            this.x = x;
            this.y = y;
            this.disposal = disposal;
        }
        public BufferedImage img() { return img; }
        public int x() { return x; }
        public int y() { return y; }
        public String disposal() { return disposal; }
        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof GifFrame)) return false;
            GifFrame o = (GifFrame) obj;
            return java.util.Objects.equals(img, o.img) && x == o.x && y == o.y && java.util.Objects.equals(disposal, o.disposal);
        }
        @Override public int hashCode() { return java.util.Objects.hash(img, x, y, disposal); }
        @Override public String toString() { return "GifFrame[" + "img=" + img + ", " + "x=" + x + ", " + "y=" + y + ", " + "disposal=" + disposal + "]"; }
    }

    private static byte[] gif(int cw, int ch, GifFrame... frames) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bo)) {
            w.setOutput(out);
            w.prepareWriteSequence(null);
            for (GifFrame f : frames) {
                BufferedImage rgb = new BufferedImage(f.img().getWidth(), f.img().getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.getGraphics().drawImage(f.img(), 0, 0, null);
                IIOMetadata md = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(rgb), null);
                String fmt = "javax_imageio_gif_image_1.0";
                IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(fmt);
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", f.disposal());
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", "10");
                gce.setAttribute("transparentColorIndex", "0");
                IIOMetadataNode id = child(root, "ImageDescriptor");
                id.setAttribute("imageLeftPosition", String.valueOf(f.x()));
                id.setAttribute("imageTopPosition", String.valueOf(f.y()));
                md.setFromTree(fmt, root);
                w.writeToSequence(new IIOImage(rgb, null, md), null);
            }
            w.endWriteSequence();
        } finally { w.dispose(); }
        byte[] d = bo.toByteArray();
        // logical screen size = canvas size (the writer uses the first frame's size)
        d[6] = (byte) cw; d[7] = 0; d[8] = (byte) ch; d[9] = 0;
        return d;
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) if (n.getNodeName().equals(name)) return (IIOMetadataNode) n;
        IIOMetadataNode n = new IIOMetadataNode(name);
        root.appendChild(n);
        return n;
    }

    @Test public void gifRestoreToBackground() throws Exception {
        byte[] g = gif(4, 4,
                new GifFrame(solid(4, 4, RED), 0, 0, "none"),
                new GifFrame(solid(2, 2, GREEN), 0, 0, "restoreToBackgroundColor"),
                new GifFrame(solid(1, 1, BLUE), 3, 3, "none"));
        List<FrameExtractor.Frame> f = FrameExtractor.extract(g, true);
        assertEquals(3, f.size());
        assertEquals(GREEN, f.get(1).image().getRGB(0, 0));
        assertEquals(RED, f.get(1).image().getRGB(2, 2));
        assertEquals(CLEAR, f.get(2).image().getRGB(0, 0) & 0xff000000); // cleared after frame 1
        assertEquals(CLEAR, f.get(2).image().getRGB(1, 1) & 0xff000000);
        assertEquals(RED, f.get(2).image().getRGB(2, 2));
        assertEquals(BLUE, f.get(2).image().getRGB(3, 3));
        assertTrue(f.get(1).info().contains("dispose=background"));
    }

    @Test public void gifRestoreToPreviousAndNone() throws Exception {
        byte[] g = gif(4, 4,
                new GifFrame(solid(4, 4, RED), 0, 0, "doNotDispose"),
                new GifFrame(solid(2, 2, GREEN), 1, 1, "restoreToPrevious"),
                new GifFrame(solid(1, 1, BLUE), 0, 0, "none"));
        List<FrameExtractor.Frame> f = FrameExtractor.extract(g, true);
        assertEquals(GREEN, f.get(1).image().getRGB(2, 2));
        assertEquals(RED, f.get(2).image().getRGB(2, 2)); // restored
        assertEquals(RED, f.get(2).image().getRGB(1, 1));
        assertEquals(BLUE, f.get(2).image().getRGB(0, 0));
        assertEquals(RED, f.get(2).image().getRGB(3, 3));
        // raw mode is unaffected by disposal
        List<FrameExtractor.Frame> raw = FrameExtractor.extract(g, false);
        assertEquals(CLEAR, raw.get(2).image().getRGB(2, 2) & 0xff000000);
    }

    // ---------------- APNG ----------------
    private static byte[] chunkData(byte[] png, String type) {
        int p = 8;
        while (p < png.length) {
            int len = ByteBuffer.wrap(png, p, 4).getInt();
            if (new String(png, p + 4, 4).equals(type)) return Arrays.copyOfRange(png, p + 8, p + 8 + len);
            p += 12 + len;
        }
        return null;
    }

    private static byte[] png(BufferedImage i) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(i, "png", o);
        return o.toByteArray();
    }

    private static final class ApngFrame {
        private final BufferedImage img;
        private final int x;
        private final int y;
        private final int dispose;
        private final int blend;
        public ApngFrame(BufferedImage img, int x, int y, int dispose, int blend) {
            this.img = img;
            this.x = x;
            this.y = y;
            this.dispose = dispose;
            this.blend = blend;
        }
        public BufferedImage img() { return img; }
        public int x() { return x; }
        public int y() { return y; }
        public int dispose() { return dispose; }
        public int blend() { return blend; }
        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ApngFrame)) return false;
            ApngFrame o = (ApngFrame) obj;
            return java.util.Objects.equals(img, o.img) && x == o.x && y == o.y && dispose == o.dispose && blend == o.blend;
        }
        @Override public int hashCode() { return java.util.Objects.hash(img, x, y, dispose, blend); }
        @Override public String toString() { return "ApngFrame[" + "img=" + img + ", " + "x=" + x + ", " + "y=" + y + ", " + "dispose=" + dispose + ", " + "blend=" + blend + "]"; }
    }

    private static byte[] apng(int cw, int ch, ApngFrame... fs) throws Exception {
        byte[] first = png(fs[0].img());
        byte[] ihdr = chunkData(first, "IHDR");
        ByteBuffer.wrap(ihdr).putInt(0, cw).putInt(4, ch);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Arrays.copyOf(first, 8));
        FrameExtractor.writeChunk(o, "IHDR", ihdr);
        FrameExtractor.writeChunk(o, "acTL", ByteBuffer.allocate(8).putInt(fs.length).putInt(0).array());
        int seq = 0;
        for (int i = 0; i < fs.length; i++) {
            ApngFrame f = fs[i];
            FrameExtractor.writeChunk(o, "fcTL", ByteBuffer.allocate(26).putInt(seq++).putInt(f.img().getWidth()).putInt(f.img().getHeight())
                    .putInt(f.x()).putInt(f.y()).putShort((short) 1).putShort((short) 10).put((byte) f.dispose()).put((byte) f.blend()).array());
            byte[] idat = chunkData(png(f.img()), "IDAT");
            if (i == 0) FrameExtractor.writeChunk(o, "IDAT", idat);
            else FrameExtractor.writeChunk(o, "fdAT", ByteBuffer.allocate(4 + idat.length).putInt(seq++).put(idat).array());
        }
        FrameExtractor.writeChunk(o, "IEND", new byte[0]);
        return o.toByteArray();
    }

    /** 2x2 green with a fully transparent top-left pixel. */
    private static BufferedImage greenHole() {
        BufferedImage g = solid(2, 2, GREEN);
        g.setRGB(0, 0, CLEAR);
        return g;
    }

    @Test public void apngBlendSourceAndDisposeBackground() throws Exception {
        byte[] a = apng(4, 4,
                new ApngFrame(solid(4, 4, RED), 0, 0, 0, 0),
                new ApngFrame(greenHole(), 1, 1, 1, 0),      // dispose BACKGROUND, blend SOURCE
                new ApngFrame(solid(1, 1, BLUE), 0, 0, 0, 1)); // blend OVER
        List<FrameExtractor.Frame> f = FrameExtractor.extract(a, true);
        assertEquals(3, f.size());
        assertEquals(CLEAR, f.get(1).image().getRGB(1, 1) & 0xff000000); // SOURCE copies the transparent pixel
        assertEquals(GREEN, f.get(1).image().getRGB(2, 1));
        assertEquals(RED, f.get(1).image().getRGB(0, 0));
        assertEquals(CLEAR, f.get(2).image().getRGB(2, 2) & 0xff000000); // region cleared after frame 1
        assertEquals(BLUE, f.get(2).image().getRGB(0, 0));
        assertEquals(RED, f.get(2).image().getRGB(3, 3));
        assertTrue(f.get(1).info().contains("dispose=background blend=source"));
    }

    @Test public void apngBlendOverAndDisposePrevious() throws Exception {
        byte[] a = apng(4, 4,
                new ApngFrame(solid(4, 4, RED), 0, 0, 0, 0),
                new ApngFrame(greenHole(), 1, 1, 2, 1),      // dispose PREVIOUS, blend OVER
                new ApngFrame(solid(1, 1, BLUE), 3, 0, 0, 1));
        List<FrameExtractor.Frame> f = FrameExtractor.extract(a, true);
        assertEquals(RED, f.get(1).image().getRGB(1, 1));   // OVER keeps the canvas under the transparent pixel
        assertEquals(GREEN, f.get(1).image().getRGB(2, 2));
        assertEquals(RED, f.get(2).image().getRGB(2, 2));   // restored to previous
        assertEquals(BLUE, f.get(2).image().getRGB(3, 0));
    }

    @Test public void apngFirstFramePreviousActsAsBackground() throws Exception {
        byte[] a = apng(2, 2,
                new ApngFrame(solid(2, 2, RED), 0, 0, 2, 0),
                new ApngFrame(solid(1, 1, BLUE), 0, 0, 0, 1));
        List<FrameExtractor.Frame> f = FrameExtractor.extract(a, true);
        assertEquals(RED, f.get(0).image().getRGB(1, 1));
        assertEquals(CLEAR, f.get(1).image().getRGB(1, 1) & 0xff000000);
        assertEquals(BLUE, f.get(1).image().getRGB(0, 0));
    }
}
