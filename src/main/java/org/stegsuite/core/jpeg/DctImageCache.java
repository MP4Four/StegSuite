package org.stegsuite.core.jpeg;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * Decoded {@link DctImage} (and its RGB reconstruction) of the most recently used JPEG files, shared by the
 * DCT tab's Analyze, Extract, chi-square chart and block heatmap and the report export, so a file is entropy
 * decoded once. Entries are keyed by the identity of the file's byte array (the open file keeps one array),
 * the cache holds at most {@link #CAPACITY} files, and concurrent requests for the same file wait for one
 * decode instead of decoding twice. The cached objects must be treated as read-only.
 */
public final class DctImageCache {
    private DctImageCache() {}

    public static final int CAPACITY = 2;

    private static final class Entry {
        final byte[] key;
        DctImage image;
        IOException error;
        BufferedImage preview;
        boolean previewDone;

        Entry(byte[] key) { this.key = key; }
    }

    private static final LinkedList<Entry> entries = new LinkedList<>();
    private static int decodes;

    private static Entry entry(byte[] data) {
        synchronized (entries) {
            for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (e.key == data) {
                    it.remove();
                    entries.addFirst(e);
                    return e;
                }
            }
            Entry e = new Entry(data);
            entries.addFirst(e);
            while (entries.size() > CAPACITY) entries.removeLast();
            return e;
        }
    }

    /** Decoded coefficients of the file (decoded on first use). */
    public static DctImage get(byte[] data) throws IOException {
        Entry e = entry(data);
        synchronized (e) {
            if (e.image == null && e.error == null) {
                try {
                    e.image = JpegDctDecoder.decode(data);
                    synchronized (entries) { decodes++; }
                } catch (IOException ex) {
                    e.error = ex;
                }
            }
            if (e.error != null) throw new IOException(e.error.getMessage(), e.error);
            return e.image;
        }
    }

    /** RGB reconstruction of the cached coefficients, or null for layouts that cannot be rendered. */
    public static BufferedImage preview(byte[] data) throws IOException {
        DctImage img = get(data);
        Entry e = entry(data);
        synchronized (e) {
            if (!e.previewDone) {
                e.preview = img.toBufferedImage();
                e.previewDone = true;
            }
            return e.preview;
        }
    }

    /**
     * RGB reconstruction of {@code img}: the cached preview when {@code img} is the cached decode of
     * {@code data}, otherwise a fresh reconstruction (not cached).
     */
    public static BufferedImage previewOf(byte[] data, DctImage img) {
        Entry found = null;
        synchronized (entries) {
            for (Entry e : entries) if (e.key == data) { found = e; break; }
        }
        if (found != null) {
            synchronized (found) {          // never hold the list lock here (get() locks entry, then list)
                if (found.image == img) {
                    if (!found.previewDone) {
                        found.preview = img.toBufferedImage();
                        found.previewDone = true;
                    }
                    return found.preview;
                }
            }
        }
        return img.toBufferedImage();
    }

    /** Number of entropy decodes performed through the cache since start (for tests and diagnostics). */
    public static int decodeCount() {
        synchronized (entries) { return decodes; }
    }

    public static void clear() {
        synchronized (entries) { entries.clear(); }
    }
}
