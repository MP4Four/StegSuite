package org.stegsuite.core.format;

import java.nio.charset.StandardCharsets;

/** GIF block walker: frames, comments, application extensions and trailing data after the 0x3B trailer. */
public final class GifAnalyzer {
    private GifAnalyzer() {}

    public static void analyze(byte[] d, FormatReport r) {
        r.format = "GIF";
        int w = (d[6] & 0xff) | (d[7] & 0xff) << 8, h = (d[8] & 0xff) | (d[9] & 0xff) << 8, flags = d[10] & 0xff;
        r.line("== GIF ==");
        r.line(new String(d, 0, 6, StandardCharsets.ISO_8859_1) + "  logical screen " + w + "x" + h
                + ((flags & 0x80) != 0 ? ", global colour table " + (2 << (flags & 7)) + " entries" : ""));
        int p = 13 + ((flags & 0x80) != 0 ? 3 * (2 << (flags & 7)) : 0);
        int frames = 0;
        boolean trailer = false;
        while (p < d.length) {
            int b = d[p] & 0xff;
            if (b == 0x3b) { trailer = true; p++; break; }
            if (b == 0x2c) {
                int fl = d[p + 9] & 0xff;
                frames++;
                r.line(String.format("0x%08x image #%d at (%d,%d) %dx%d", p, frames,
                        (d[p + 1] & 0xff) | (d[p + 2] & 0xff) << 8, (d[p + 3] & 0xff) | (d[p + 4] & 0xff) << 8,
                        (d[p + 5] & 0xff) | (d[p + 6] & 0xff) << 8, (d[p + 7] & 0xff) | (d[p + 8] & 0xff) << 8));
                p += 10 + ((fl & 0x80) != 0 ? 3 * (2 << (fl & 7)) : 0);
                p++; // LZW min code size
                p = skipSub(d, p, null);
            } else if (b == 0x21) {
                int label = d[p + 1] & 0xff;
                StringBuilder sb = new StringBuilder();
                int start = p;
                p = skipSub(d, p + 2, sb);
                if (label == 0xfe) r.line(String.format("0x%08x COMMENT: %s", start, sb));
                else if (label == 0xff) r.line(String.format("0x%08x application ext: %s", start, sb.length() > 60 ? sb.substring(0, 60) : sb));
                else if (label == 0x01) r.line(String.format("0x%08x plain text ext: %s", start, sb));
            } else { r.warn(String.format("Unknown block 0x%02x at 0x%x", b, p)); break; }
        }
        r.line("Frames: " + frames);
        if (!trailer) r.warn("No GIF trailer (0x3B) found");
        if (trailer && p < d.length) { r.trailingOffset = p; r.trailingLength = d.length - p; }
    }

    private static int skipSub(byte[] d, int p, StringBuilder text) {
        while (p < d.length) {
            int n = d[p] & 0xff;
            p++;
            if (n == 0) break;
            if (text != null && text.length() < 500)
                text.append(new String(d, p, Math.min(n, d.length - p), StandardCharsets.ISO_8859_1));
            p += n;
        }
        return p;
    }
}
