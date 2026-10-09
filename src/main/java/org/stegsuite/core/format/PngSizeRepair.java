package org.stegsuite.core.format;

import org.stegsuite.core.stat.Progress;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PNG IHDR width/height repair (CTF "deformed image" challenges), merging the approach of AabyssZG's
 * Deformed-Image-Restorer (2D brute force of width and height 0..20000 against the stored IHDR CRC, and a
 * "reverse" mode that writes a chosen width/height) into StegSuite:
 *
 * <ul>
 *   <li>{@link #search}: all (width, height) pairs in configurable ranges whose IHDR CRC equals the stored
 *       CRC. CRC-32 is affine over GF(2) for messages of fixed length, so crc(w, h) = base ^ W(w) ^ H(h); the
 *       search tabulates H(h) once and looks up every width, which makes the full 20000 x 20000 search
 *       (4e8 combinations) take milliseconds instead of minutes. Widths are split across threads.</li>
 *   <li>{@link #rewrite}: writes a chosen width/height and recomputes the IHDR CRC.</li>
 *   <li>{@link #inferFromIdat}: when the CRC was altered too, the size of the decompressed IDAT stream
 *       (h * (1 + ceil(w * bitsPerPixel / 8)) for non-interlaced images) gives the height for the stored
 *       width and the candidate widths; each candidate is checked against the per-row filter bytes.</li>
 * </ul>
 */
public final class PngSizeRepair {
    private PngSizeRepair() {}

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    /** IHDR chunk: length at 8, type at 12, data at 16..28, CRC at 29. */
    static final int IHDR_DATA = 16, IHDR_CRC = 29;
    /** Largest height range of one search (the table takes 8 bytes per height). */
    public static final int MAX_HEIGHT_RANGE = 16 * 1024 * 1024;
    /** Largest decompressed IDAT stream kept in memory for the filter-byte check. */
    public static final int MAX_INFLATED = 256 * 1024 * 1024;

    /** Parsed IHDR. */
    public static final class Ihdr {
        public final int width, height, bitDepth, colorType, compression, filter, interlace;
        public final long storedCrc, computedCrc;

        Ihdr(byte[] d) {
            ByteBuffer b = ByteBuffer.wrap(d);
            width = b.getInt(IHDR_DATA);
            height = b.getInt(IHDR_DATA + 4);
            bitDepth = d[IHDR_DATA + 8] & 0xff;
            colorType = d[IHDR_DATA + 9] & 0xff;
            compression = d[IHDR_DATA + 10] & 0xff;
            filter = d[IHDR_DATA + 11] & 0xff;
            interlace = d[IHDR_DATA + 12] & 0xff;
            storedCrc = b.getInt(IHDR_CRC) & 0xffffffffL;
            computedCrc = PngAnalyzer.crc(d, 12, 17);
        }

        public boolean crcOk() { return storedCrc == computedCrc; }

        /** Samples per pixel for the colour type, or 0 if invalid. */
        public int channels() {
            switch (colorType) {
                case 0: return 1;
                case 2: return 3;
                case 3: return 1;
                case 4: return 2;
                case 6: return 4;
                default: return 0;
            }
        }

        public int bitsPerPixel() { return channels() * bitDepth; }

        /** Bytes of one filtered scanline including the filter byte. */
        public long rowBytes(long w) { return 1 + (w * bitsPerPixel() + 7) / 8; }

        @Override public String toString() {
            return String.format("%dx%d, bit depth %d, colour type %d, interlace %d, CRC stored %08x / computed %08x%s",
                    width, height, bitDepth, colorType, interlace, storedCrc, computedCrc, crcOk() ? " (OK)" : " (MISMATCH)");
        }
    }

    public static boolean isPng(byte[] d) {
        if (d == null || d.length < 33) return false;
        for (int i = 0; i < 8; i++) if (d[i] != SIGNATURE[i]) return false;
        return "IHDR".equals(new String(d, 12, 4, StandardCharsets.ISO_8859_1));
    }

    /** @throws IllegalArgumentException if the bytes do not start with a PNG signature and IHDR chunk */
    public static Ihdr ihdr(byte[] d) {
        if (!isPng(d)) throw new IllegalArgumentException("not a PNG with an IHDR chunk at offset 8");
        return new Ihdr(d);
    }

    // ------------------------------------------------------------------ CRC search

    /** Search result: matches in (width, height) order and whether the list was cut at maxResults. */
    public static final class SearchResult {
        public final List<int[]> matches;
        public final boolean truncated;
        public final long combinations;

        SearchResult(List<int[]> matches, boolean truncated, long combinations) {
            this.matches = matches;
            this.truncated = truncated;
            this.combinations = combinations;
        }
    }

    /**
     * Finds every (w, h) with minW &lt;= w &lt;= maxW and minH &lt;= h &lt;= maxH whose IHDR CRC equals
     * {@code targetCrc} (normally the stored CRC).
     */
    public static SearchResult search(byte[] png, long targetCrc, int minW, int maxW, int minH, int maxH,
                                      int maxResults, int threads, Progress progress) throws InterruptedException {
        if (progress == null) progress = Progress.NONE;
        if (minW < 0 || minH < 0 || maxW < minW || maxH < minH) throw new IllegalArgumentException("bad search range");
        if ((long) maxH - minH + 1 > MAX_HEIGHT_RANGE)
            throw new IllegalArgumentException("height range too large (max " + MAX_HEIGHT_RANGE + " values)");
        ihdr(png);
        byte[] msg = new byte[17];
        System.arraycopy(png, 12, msg, 0, 17);
        // affine decomposition: crc(w, h) = base ^ W(w) ^ H(h)
        ByteBuffer mb = ByteBuffer.wrap(msg);
        mb.putInt(4, 0).putInt(8, 0);
        final long base = crc(msg);
        final long[] wBit = new long[32], hBit = new long[32];
        for (int bit = 0; bit < 32; bit++) {
            mb.putInt(4, 1 << bit).putInt(8, 0);
            wBit[bit] = crc(msg) ^ base;
            mb.putInt(4, 0).putInt(8, 1 << bit);
            hBit[bit] = crc(msg) ^ base;
        }
        // sorted table of (H(h) << 32 | h): one long per height, binary searched per width
        int hCount = maxH - minH + 1;
        final long[] table = new long[hCount];
        for (int i = 0; i < hCount; i++) {
            if ((i & 0xffff) == 0) Progress.check(progress);
            int h = minH + i;
            table[i] = (combine(hBit, h) << 32) | (h & 0xffffffffL);
        }
        java.util.Arrays.sort(table);
        progress.update(0.1);
        final long wanted = targetCrc ^ base;
        final int nThreads = Math.max(1, Math.min(threads, 64));
        final long wCount = (long) maxW - minW + 1;
        final AtomicLong done = new AtomicLong();
        final AtomicInteger found = new AtomicInteger();
        final List<int[]> matches = Collections.synchronizedList(new ArrayList<int[]>());
        final Progress p = progress;
        ExecutorService pool = Executors.newFixedThreadPool(nThreads, r -> {
            Thread t = new Thread(r, "png-size-search");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            final long chunk = Math.max(1024, (wCount + nThreads * 8L - 1) / (nThreads * 8L));
            for (long start = minW; start <= maxW; start += chunk) {
                final long from = start, to = Math.min(maxW, start + chunk - 1);
                futures.add(pool.submit(() -> {
                    for (long w = from; w <= to; w++) {
                        if ((w & 0xfff) == 0) {
                            Progress.check(p);
                            if (found.get() >= maxResults) return;
                        }
                        long key = (wanted ^ combine(wBit, (int) w)) << 32;
                        int i = java.util.Arrays.binarySearch(table, key);
                        if (i < 0) i = -i - 1;
                        for (; i < table.length && (table[i] & 0xffffffff00000000L) == key; i++)
                            add(matches, found, (int) w, (int) table[i]);
                    }
                    long d = done.addAndGet(to - from + 1);
                    p.update(0.1 + 0.9 * d / (double) wCount);
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
                    throw new IllegalStateException(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        List<int[]> sorted = new ArrayList<>(matches);
        sorted.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        boolean truncated = sorted.size() > maxResults || found.get() >= maxResults;
        if (sorted.size() > maxResults) sorted = new ArrayList<>(sorted.subList(0, maxResults));
        return new SearchResult(sorted, truncated, wCount * hCount);
    }

    private static void add(List<int[]> matches, AtomicInteger found, int w, int h) {
        found.incrementAndGet();
        matches.add(new int[]{w, h});
    }

    private static long combine(long[] bits, int v) {
        long x = 0;
        for (int bit = 0; bit < 32; bit++) if ((v >>> bit & 1) != 0) x ^= bits[bit];
        return x;
    }

    private static long crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b, 0, b.length);
        return c.getValue();
    }

    /** The match most likely intended: one dimension unchanged first, then the smallest change in area. */
    public static int[] best(List<int[]> matches, int w0, int h0) {
        int[] best = null;
        long bestScore = Long.MAX_VALUE;
        for (int[] m : matches) {
            long score = (m[0] == w0 || m[1] == h0 ? 0 : 1L << 40) + Math.abs((long) m[0] * m[1] - (long) w0 * h0);
            if (m[0] == 0 || m[1] == 0) score += 1L << 50;
            if (score < bestScore) { bestScore = score; best = m; }
        }
        return best;
    }

    // ------------------------------------------------------------------ manual rewrite

    /** Copy of the PNG with IHDR width/height replaced and the IHDR CRC recomputed. */
    public static byte[] rewrite(byte[] png, int width, int height) {
        ihdr(png);
        byte[] o = png.clone();
        ByteBuffer.wrap(o).putInt(IHDR_DATA, width).putInt(IHDR_DATA + 4, height);
        ByteBuffer.wrap(o).putInt(IHDR_CRC, (int) PngAnalyzer.crc(o, 12, 17));
        return o;
    }

    // ------------------------------------------------------------------ IDAT size inference

    /** One size candidate derived from the decompressed IDAT size. */
    public static final class Candidate {
        public final int width, height;
        /** True when every row starts with a valid filter type (0..4); checked when the data fit in memory. */
        public final boolean filtersValid;
        public final String note;

        Candidate(int width, int height, boolean filtersValid, String note) {
            this.width = width;
            this.height = height;
            this.filtersValid = filtersValid;
            this.note = note;
        }

        @Override public String toString() { return width + "x" + height + (note.isEmpty() ? "" : " (" + note + ")"); }
    }

    public static final class Inference {
        public final long inflatedSize;
        public final boolean complete;
        public final Ihdr ihdr;
        /** Height for the stored width, or -1 if the size is not a multiple of that width's row length. */
        public final long heightForStoredWidth;
        public final List<Candidate> candidates;
        public final String message;

        Inference(long inflatedSize, boolean complete, Ihdr ihdr, long heightForStoredWidth, List<Candidate> candidates, String message) {
            this.inflatedSize = inflatedSize;
            this.complete = complete;
            this.ihdr = ihdr;
            this.heightForStoredWidth = heightForStoredWidth;
            this.candidates = candidates;
            this.message = message;
        }
    }

    /**
     * Infers the size from the decompressed IDAT stream: for each width 1..maxWidth whose row length divides
     * the stream size, h = size / rowBytes. Candidates whose filter bytes are invalid are dropped (when the
     * stream fits in memory); the stored width comes first, then the stored height, then the most square
     * shapes. Only non-interlaced images are supported (interlace = 0).
     */
    public static Inference inferFromIdat(byte[] png, int maxWidth, int maxCandidates, Progress progress) {
        if (progress == null) progress = Progress.NONE;
        Ihdr ih = ihdr(png);
        if (ih.interlace != 0) return new Inference(-1, false, ih, -1, new ArrayList<Candidate>(),
                "interlaced (Adam7) image: the IDAT size formula h*(1+rowbytes) does not apply");
        if (ih.bitsPerPixel() == 0 || (ih.bitDepth != 1 && ih.bitDepth != 2 && ih.bitDepth != 4 && ih.bitDepth != 8 && ih.bitDepth != 16))
            return new Inference(-1, false, ih, -1, new ArrayList<Candidate>(), "invalid colour type / bit depth in IHDR");
        byte[] z = concatIdat(png);
        long size;
        byte[] raw = null;
        boolean complete;
        Inflater inf = new Inflater();
        try {
            inf.setInput(z);
            ByteArrayOutputStream keep = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            long total = 0;
            boolean keepAll = true;
            while (!inf.finished()) {
                if ((total & 0xfffff) < buf.length) Progress.check(progress);
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                    continue;
                }
                total += n;
                if (keepAll) {
                    if (total > MAX_INFLATED) { keepAll = false; keep = null; }
                    else keep.write(buf, 0, n);
                }
            }
            complete = inf.finished();
            size = total;
            if (keepAll && keep != null) raw = keep.toByteArray();
        } catch (DataFormatException e) {
            return new Inference(-1, false, ih, -1, new ArrayList<Candidate>(), "IDAT stream is not valid zlib data: " + e.getMessage());
        } finally {
            inf.end();
        }
        progress.update(0.5);
        long hStored = size % ih.rowBytes(ih.width) == 0 && ih.width > 0 ? size / ih.rowBytes(ih.width) : -1;
        List<Candidate> list = new ArrayList<>();
        long upper = Math.min(maxWidth, (size * 8) / Math.max(1, ih.bitsPerPixel()));
        for (long w = 1; w <= upper; w++) {
            if ((w & 0xffff) == 0) Progress.check(progress);
            long rb = ih.rowBytes(w);
            if (rb > size || size % rb != 0) continue;
            long h = size / rb;
            if (h > Integer.MAX_VALUE) continue;
            boolean valid = raw == null || filtersValid(raw, rb, h);
            if (!valid) continue;
            StringBuilder note = new StringBuilder();
            if (w == ih.width) note.append("stored width");
            if (h == ih.height) note.append(note.length() > 0 ? ", " : "").append("stored height");
            if (raw == null) note.append(note.length() > 0 ? ", " : "").append("filters not checked");
            list.add(new Candidate((int) w, (int) h, raw != null, note.toString()));
        }
        progress.update(0.9);
        final int w0 = ih.width, h0 = ih.height;
        list.sort((a, b) -> {
            int ka = (a.width == w0 ? 0 : a.height == h0 ? 1 : 2), kb = (b.width == w0 ? 0 : b.height == h0 ? 1 : 2);
            if (ka != kb) return ka - kb;
            double ra = Math.abs(Math.log((double) a.width / a.height)), rbb = Math.abs(Math.log((double) b.width / b.height));
            return Double.compare(ra, rbb);
        });
        if (list.size() > maxCandidates) list = new ArrayList<>(list.subList(0, maxCandidates));
        String msg = String.format("decompressed IDAT: %d bytes%s; %d bits per pixel; row length for width w = 1 + ceil(w*%d/8)",
                size, complete ? "" : " (stream incomplete/truncated)", ih.bitsPerPixel(), ih.bitsPerPixel());
        return new Inference(size, complete, ih, hStored, list, msg);
    }

    /**
     * Size of the decompressed IDAT stream, counting at most {@code limit} bytes (returns limit + something
     * when larger), or -1 if the zlib data is invalid.
     */
    public static long inflatedSize(byte[] png, long limit) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(concatIdat(png));
            byte[] buf = new byte[1 << 16];
            long total = 0;
            while (!inf.finished() && total <= limit) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                total += n;
            }
            return total;
        } catch (DataFormatException e) {
            return -1;
        } finally {
            inf.end();
        }
    }

    /** True when the first byte of every row (filter type) is 0..4. */
    static boolean filtersValid(byte[] raw, long rowBytes, long rows) {
        for (long r = 0; r < rows; r++) {
            long off = r * rowBytes;
            if (off >= raw.length) return false;
            if ((raw[(int) off] & 0xff) > 4) return false;
        }
        return true;
    }

    /** Concatenated IDAT payloads (chunk lengths are trusted only as far as the file goes). */
    static byte[] concatIdat(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = 8;
        while (p + 8 <= d.length) {
            long len = ByteBuffer.wrap(d, p, 4).getInt() & 0xffffffffL;
            String type = new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
            long end = Math.min(d.length, p + 8L + len);
            if (type.equals("IDAT")) out.write(d, p + 8, (int) (end - p - 8));
            if (type.equals("IEND") || p + 12L + len > d.length) break;
            p += 12 + (int) len;
        }
        return out.toByteArray();
    }
}
