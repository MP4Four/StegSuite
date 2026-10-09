package org.stegsuite.core.image;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32;

/** Splits animated GIF and APNG files into frames (raw frame placed on the logical canvas). */
public final class FrameExtractor {
    private FrameExtractor() {}

    public static final class Frame {
        private final BufferedImage image;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String info;
        public Frame(BufferedImage image, int x, int y, int width, int height, String info) {
            this.image = image;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.info = info;
        }
        public BufferedImage image() { return image; }
        public int x() { return x; }
        public int y() { return y; }
        public int width() { return width; }
        public int height() { return height; }
        public String info() { return info; }
        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Frame)) return false;
            Frame o = (Frame) obj;
            return java.util.Objects.equals(image, o.image) && x == o.x && y == o.y && width == o.width && height == o.height && java.util.Objects.equals(info, o.info);
        }
        @Override public int hashCode() { return java.util.Objects.hash(image, x, y, width, height, info); }
        @Override public String toString() { return "Frame[" + "image=" + image + ", " + "x=" + x + ", " + "y=" + y + ", " + "width=" + width + ", " + "height=" + height + ", " + "info=" + info + "]"; }
    }

    public static boolean isGif(byte[] d) { return d.length > 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F'; }
    public static boolean isPng(byte[] d) { return d.length > 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'; }

    public static List<Frame> extract(byte[] data, boolean composite) throws IOException {
        if (isGif(data)) return gif(data, composite);
        if (isPng(data)) return apng(data, composite);
        BufferedImage img = ImageUtil.decode(data);
        List<Frame> l = new ArrayList<>();
        if (img != null) l.add(new Frame(img, 0, 0, img.getWidth(), img.getHeight(), "single image"));
        return l;
    }

    // ---------------- GIF ----------------
    private static List<Frame> gif(byte[] data, boolean composite) throws IOException {
        List<Frame> frames = new ArrayList<>();
        int cw = (data[6] & 0xff) | (data[7] & 0xff) << 8, ch = (data[8] & 0xff) | (data[9] & 0xff) << 8;
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("gif");
        if (!it.hasNext()) return frames;
        ImageReader r = it.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            r.setInput(in, false);
            BufferedImage canvas = null;
            for (int i = 0; ; i++) {
                BufferedImage f;
                try { f = r.read(i); } catch (IndexOutOfBoundsException e) { break; }
                catch (IOException e) { if (i == 0) throw e; break; }
                int x = 0, y = 0, delay = -1;
                String disposal = "none";
                IIOMetadata md = r.getImageMetadata(i);
                Node root = md.getAsTree("javax_imageio_gif_image_1.0");
                for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                    NamedNodeMap at = n.getAttributes();
                    if ("ImageDescriptor".equals(n.getNodeName())) {
                        x = Integer.parseInt(at.getNamedItem("imageLeftPosition").getNodeValue());
                        y = Integer.parseInt(at.getNamedItem("imageTopPosition").getNodeValue());
                    } else if ("GraphicControlExtension".equals(n.getNodeName())) {
                        delay = Integer.parseInt(at.getNamedItem("delayTime").getNodeValue());
                        disposal = at.getNamedItem("disposalMethod").getNodeValue();
                    }
                }
                int w = Math.max(cw, x + f.getWidth()), h = Math.max(ch, y + f.getHeight());
                BufferedImage out;
                if (composite) {
                    if (canvas == null) canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    else if (canvas.getWidth() < w || canvas.getHeight() < h) canvas = grow(canvas, w, h);
                    BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                    Graphics2D g = canvas.createGraphics(); g.drawImage(f, x, y, null); g.dispose();
                    out = copy(canvas);
                    // dispose after display: background -> clear frame rect (transparent, as browsers do); previous -> restore
                    if ("restoreToBackgroundColor".equals(disposal)) clear(canvas, x, y, f.getWidth(), f.getHeight());
                    else if (before != null) canvas = before;
                } else {
                    out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = out.createGraphics(); g.drawImage(f, x, y, null); g.dispose();
                }
                frames.add(new Frame(out, x, y, f.getWidth(), f.getHeight(),
                        "delay=" + (delay < 0 ? "?" : delay * 10 + "ms") + " dispose=" + gifDisposalName(disposal)));
            }
        } finally { r.dispose(); }
        return frames;
    }

    static String gifDisposalName(String d) {
        switch (d) {
            case "restoreToBackgroundColor": return "background";
            case "restoreToPrevious": return "previous";
            case "doNotDispose": return "keep";
            default: return "none";
        }
    }

    private static void clear(BufferedImage img, int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(img.getWidth(), x + w), y1 = Math.min(img.getHeight(), y + h);
        if (x1 <= x0 || y1 <= y0) return;
        img.setRGB(x0, y0, x1 - x0, y1 - y0, new int[(x1 - x0) * (y1 - y0)], 0, x1 - x0);
    }

    private static BufferedImage grow(BufferedImage s, int w, int h) {
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics(); g.drawImage(s, 0, 0, null); g.dispose();
        return o;
    }

    /** Exact pixel copy (no compositing, keeps partially transparent pixels bit-identical). */
    private static BufferedImage copy(BufferedImage s) {
        int w = s.getWidth(), h = s.getHeight();
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        o.setRGB(0, 0, w, h, s.getRGB(0, 0, w, h, null, 0, w), 0, w);
        return o;
    }

    // ---------------- APNG ----------------
    private static final class Chunk {
        private final String type;
        private final byte[] data;
        public Chunk(String type, byte[] data) {
            this.type = type;
            this.data = data;
        }
        public String type() { return type; }
        public byte[] data() { return data; }
        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Chunk)) return false;
            Chunk o = (Chunk) obj;
            return java.util.Objects.equals(type, o.type) && java.util.Objects.equals(data, o.data);
        }
        @Override public int hashCode() { return java.util.Objects.hash(type, data); }
        @Override public String toString() { return "Chunk[" + "type=" + type + ", " + "data=" + data + "]"; }
    }

    private static List<Chunk> chunks(byte[] d) {
        List<Chunk> l = new ArrayList<>();
        int p = 8;
        while (p + 12 <= d.length) {
            int len = ByteBuffer.wrap(d, p, 4).getInt();
            if (len < 0 || p + 12L + len > d.length) break;
            String t = new String(d, p + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            byte[] c = new byte[len];
            System.arraycopy(d, p + 8, c, 0, len);
            l.add(new Chunk(t, c));
            p += 12 + len;
            if (t.equals("IEND")) break;
        }
        return l;
    }

    private static List<Frame> apng(byte[] data, boolean composite) throws IOException {
        List<Chunk> cs = chunks(data);
        boolean animated = cs.stream().anyMatch(c -> c.type().equals("acTL"));
        List<Frame> frames = new ArrayList<>();
        if (!animated) {
            BufferedImage img = ImageUtil.decode(data);
            if (img != null) frames.add(new Frame(img, 0, 0, img.getWidth(), img.getHeight(), "static PNG (no acTL)"));
            return frames;
        }
        byte[] ihdr = null;
        List<Chunk> shared = new ArrayList<>();
        int cw = 0, ch = 0;
        byte[] curFctl = null;
        ByteArrayOutputStream curData = null;
        List<Object[]> raw = new ArrayList<>();
        for (Chunk c : cs) {
            switch (c.type()) {
                case "IHDR": { ihdr = c.data(); cw = ByteBuffer.wrap(ihdr).getInt(0); ch = ByteBuffer.wrap(ihdr).getInt(4); } break;
                case "PLTE": case "tRNS": case "gAMA": case "sRGB": case "cHRM": case "iCCP": case "sBIT": shared.add(c); break;
                case "fcTL": {
                    if (curFctl != null && curData != null && curData.size() > 0) raw.add(new Object[]{curFctl, curData.toByteArray()});
                    curFctl = c.data(); curData = new ByteArrayOutputStream();
                } break;
                case "IDAT": { if (curData != null) curData.write(c.data()); } break;
                case "fdAT": { if (curData != null) curData.write(c.data(), 4, c.data().length - 4); } break;
                default: break;
            }
        }
        if (curFctl != null && curData != null && curData.size() > 0) raw.add(new Object[]{curFctl, curData.toByteArray()});
        if (ihdr == null) return frames;
        BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        int idx = 0;
        for (Object[] fr : raw) {
            ByteBuffer f = ByteBuffer.wrap((byte[]) fr[0]);
            int w = f.getInt(4), h = f.getInt(8), x = f.getInt(12), y = f.getInt(16);
            int dn = f.getShort(20) & 0xffff, dd = f.getShort(22) & 0xffff;
            int disposeOp = ((byte[]) fr[0]).length > 24 ? f.get(24) & 0xff : 0;
            int blendOp = ((byte[]) fr[0]).length > 25 ? f.get(25) & 0xff : 0;
            if (idx == 0 && disposeOp == 2) disposeOp = 1; // spec: first frame PREVIOUS is treated as BACKGROUND
            byte[] hdr = ihdr.clone();
            ByteBuffer.wrap(hdr).putInt(0, w).putInt(4, h);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            writeChunk(png, "IHDR", hdr);
            for (Chunk s : shared) writeChunk(png, s.type(), s.data());
            writeChunk(png, "IDAT", (byte[]) fr[1]);
            writeChunk(png, "IEND", new byte[0]);
            BufferedImage img = ImageUtil.decode(png.toByteArray());
            idx++;
            if (img == null) continue;
            BufferedImage out;
            if (composite) {
                BufferedImage before = disposeOp == 2 ? copy(canvas) : null;
                if (blendOp == 0) { // APNG_BLEND_OP_SOURCE: replace the region, alpha included
                    clear(canvas, x, y, w, h);
                    int cwid = Math.min(img.getWidth(), canvas.getWidth() - x), chei = Math.min(img.getHeight(), canvas.getHeight() - y);
                    if (cwid > 0 && chei > 0 && x >= 0 && y >= 0)
                        canvas.setRGB(x, y, cwid, chei, img.getRGB(0, 0, cwid, chei, null, 0, cwid), 0, cwid);
                } else { // APNG_BLEND_OP_OVER: alpha-composite over the canvas
                    Graphics2D g = canvas.createGraphics(); g.drawImage(img, x, y, null); g.dispose();
                }
                out = copy(canvas);
                if (disposeOp == 1) clear(canvas, x, y, w, h);          // APNG_DISPOSE_OP_BACKGROUND
                else if (before != null) canvas = before;               // APNG_DISPOSE_OP_PREVIOUS
            } else {
                out = new BufferedImage(Math.max(cw, x + w), Math.max(ch, y + h), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = out.createGraphics(); g.drawImage(img, x, y, null); g.dispose();
            }
            frames.add(new Frame(out, x, y, w, h, "delay=" + dn + "/" + (dd == 0 ? 100 : dd) + "s"
                    + " dispose=" + new String[]{"none", "background", "previous"}[Math.min(2, disposeOp)]
                    + " blend=" + (blendOp == 0 ? "source" : "over")));
        }
        return frames;
    }

    static void writeChunk(ByteArrayOutputStream o, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        o.write(ByteBuffer.allocate(4).putInt(data.length).array());
        o.write(t);
        o.write(data);
        CRC32 crc = new CRC32(); crc.update(t); crc.update(data);
        o.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
