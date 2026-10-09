package org.stegsuite.core.extract;

import org.stegsuite.core.image.ImageUtil;
import org.stegsuite.core.util.FileMagic;
import org.stegsuite.core.util.TextHeuristics;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * zsteg-style brute force over extraction parameter combinations.
 * Every extracted stream is searched (at any offset) for flag{...} patterns, known file signatures
 * and natural-looking text. QUICK covers the common zsteg set; DEEP adds b5..b8, every single bit plane,
 * all RGB permutations and a bounded set of mixed per-channel masks (e.g. b1r+b2g).
 */
public final class AutoScanner {
    private AutoScanner() {}

    public enum Mode {
        QUICK(256 * 1024), DEEP(512 * 1024);
        public final int maxBytesPerCombo;
        Mode(int m) { maxBytesPerCombo = m; }
    }

    public static int minTextLen = 8;          // printable run starting at offset 0
    public static int minTextLenAnywhere = 20; // printable run at a non-zero offset
    public static int minSigBytesAnywhere = 6; // magic + plausible header bytes for a signature at offset > 0
    public static int maxHitsPerKind = 3;

    private static final int[] QUICK_MASKS = {0x01, 0x02, 0x04, 0x08, 0x80, 0x03, 0x07, 0x0f};
    private static final int[] DEEP_MASKS = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80,
            0x03, 0x07, 0x0f, 0x1f, 0x3f, 0x7f, 0xff};
    /** Per-channel widths used for the bounded mixed-mask set (0 = channel unused, b1, b2). */
    private static final int[] MIX_WIDTHS = {0x00, 0x01, 0x03};

    /** Quick combination set (backwards compatible name). */
    public static List<ExtractConfig> combos(boolean hasAlpha) { return combos(hasAlpha, Mode.QUICK); }

    public static List<ExtractConfig> combos(boolean hasAlpha, Mode mode) {
        List<String> orders = new ArrayList<>(java.util.Arrays.asList("R", "G", "B", "RGB", "BGR"));
        if (hasAlpha) orders.addAll(java.util.Arrays.asList("A", "RGBA", "ABGR"));
        if (mode == Mode.DEEP) {
            orders.addAll(java.util.Arrays.asList("RBG", "GRB", "GBR", "BRG"));
            if (hasAlpha) orders.addAll(java.util.Arrays.asList("ARGB", "BGRA"));
        }
        int[] masks = mode == Mode.DEEP ? DEEP_MASKS : QUICK_MASKS;
        Set<ExtractConfig> l = new LinkedHashSet<>();
        for (String o : orders)
            for (int m : masks)
                for (boolean col : new boolean[]{false, true})
                    for (boolean packLsb : new boolean[]{false, true}) {
                        l.add(ExtractConfig.uniform(o, m, false, col, packLsb));
                        if (Integer.bitCount(m) > 1) l.add(ExtractConfig.uniform(o, m, true, col, packLsb));
                    }
        if (mode == Mode.DEEP) l.addAll(mixedCombos(hasAlpha));
        return new ArrayList<>(l);
    }

    /** Mixed per-channel masks like zsteg's b1r+b2g: R,G,B each in {unused, b1, b2}, at least two channels, not all equal. */
    public static List<ExtractConfig> mixedCombos(boolean hasAlpha) {
        List<ExtractConfig> l = new ArrayList<>();
        List<String> orders = new ArrayList<>(java.util.Arrays.asList("RGB", "BGR"));
        if (hasAlpha) orders.add("RGBA");
        for (String o : orders)
            for (int r : MIX_WIDTHS) for (int g : MIX_WIDTHS) for (int b : MIX_WIDTHS) {
                int a = o.contains("A") ? 0x01 : 0;
                int[] m = {r, g, b, a};
                int used = (r != 0 ? 1 : 0) + (g != 0 ? 1 : 0) + (b != 0 ? 1 : 0);
                if (used < 2) continue;
                if (o.contains("A")) { if (r == 0 || g == 0 || b == 0) continue; }
                for (boolean col : new boolean[]{false, true})
                    for (boolean msb : new boolean[]{false, true}) {
                        ExtractConfig c = new ExtractConfig(o, m.clone(), msb, col, false);
                        if (!c.mixed()) continue; // uniform masks are covered by the regular set (or are b1,rg-type subsets)
                        l.add(c);
                    }
            }
        return l;
    }

    public static boolean hasAlpha(int[] px) {
        for (int p : px) if ((p >>> 24) != 0xff) return true;
        return false;
    }

    public static List<ScanHit> scan(BufferedImage img, BiConsumer<Integer, Integer> progress) {
        return scan(img, Mode.QUICK, progress, () -> Thread.currentThread().isInterrupted());
    }

    /**
     * Runs the scan in parallel. {@code cancelled} is polled before every combination; results so far are discarded
     * by the caller on cancel. Results are sorted by score (stable, so equal scores keep combination order).
     */
    public static List<ScanHit> scan(BufferedImage img, Mode mode, BiConsumer<Integer, Integer> progress, BooleanSupplier cancelled) {
        int[] px = ImageUtil.pixels(img);
        int w = img.getWidth(), h = img.getHeight();
        List<ExtractConfig> cs = combos(hasAlpha(px), mode);
        @SuppressWarnings("unchecked")
        List<ScanHit>[] per = new List[cs.size()];
        AtomicInteger done = new AtomicInteger();
        IntStream.range(0, cs.size()).parallel().forEach(i -> {
            if (cancelled != null && cancelled.getAsBoolean()) return;
            ExtractConfig c = cs.get(i);
            per[i] = analyze(c, BitExtractor.extract(px, w, h, c, mode.maxBytesPerCombo));
            if (progress != null) progress.accept(done.incrementAndGet(), cs.size());
        });
        List<ScanHit> hits = new ArrayList<>();
        for (List<ScanHit> l : per) if (l != null) hits.addAll(l);
        hits.sort(Comparator.comparingInt(ScanHit::score).reversed());
        return hits;
    }

    /** Penalty for exotic parameters so that, for equal findings, the simplest (most likely intended) config ranks first. */
    static int complexity(ExtractConfig c) {
        int maxBits = 0;
        for (int m : c.masks()) maxBits = Math.max(maxBits, Integer.bitCount(m & 0xff));
        int p = (maxBits - 1) * 3;
        if (c.mixed()) p += 10;
        if (c.packLsbFirst()) p += 5;
        if (c.columnOrder()) p += 2;
        if (c.msbFirstInSample()) p += 1;
        if (!java.util.Arrays.asList("R", "G", "B", "A", "RGB", "BGR", "RGBA", "ABGR").contains(c.order())) p += 4;
        return p;
    }

    public static List<ScanHit> analyze(ExtractConfig c, byte[] d) {
        List<ScanHit> hits = new ArrayList<>();
        int pen = complexity(c);

        List<TextHeuristics.Match> flags = TextHeuristics.findFlagsAt(d);
        if (!flags.isEmpty()) {
            int off = flags.get(0).offset();
            StringBuilder s = new StringBuilder();
            for (TextHeuristics.Match m : flags) s.append(s.length() == 0 ? "" : " ").append(m.text());
            hits.add(new ScanHit(c, ScanHit.Type.FLAG, s + " @0x" + Integer.toHexString(off),
                    1000 + (off == 0 ? 50 : 0) - Math.min(40, off / 4096) - pen, d, off));
        }

        // file signatures: offset 0 (>=3 byte magic, not contradicted) or anywhere (>= minSigBytesAnywhere confirmed bytes)
        FileMagic.Sig head = FileMagic.identifyStrong(d);
        int nFiles = 0;
        if (head != null && FileMagic.confirmBytes(d, 0, head) >= 0) {
            int conf = head.magic().length + FileMagic.confirmBytes(d, 0, head);
            hits.add(new ScanHit(c, ScanHit.Type.FILE, head.name() + " (." + head.ext() + ") @0x0",
                    500 + Math.min(100, conf * 10) - pen, d, 0));
            nFiles++;
        }
        for (FileMagic.Hit fh : FileMagic.findAnywhere(d, minSigBytesAnywhere, maxHitsPerKind + 1)) {
            if (fh.offset() == 0 || nFiles >= maxHitsPerKind) continue;
            int conf = fh.sig().magic().length + FileMagic.confirmBytes(d, fh.offset(), fh.sig());
            hits.add(new ScanHit(c, ScanHit.Type.FILE,
                    fh.sig().name() + " (." + fh.sig().ext() + ") @0x" + Integer.toHexString(fh.offset()),
                    300 + Math.min(100, conf * 10) - Math.min(50, fh.offset() / 4096) - pen, d, fh.offset()));
            nFiles++;
        }

        if (flags.isEmpty()) {
            int lead = TextHeuristics.leadingPrintable(d);
            if (lead >= minTextLen && TextHeuristics.looksLikeText(d, 0, Math.min(lead, 4096))) {
                hits.add(new ScanHit(c, ScanHit.Type.TEXT, "text[" + lead + "] @0x0: \"" + TextHeuristics.preview(d, 0, 80) + "\"",
                        100 + Math.min(lead, 300) - pen, d, 0));
            } else {
                int[] run = TextHeuristics.bestTextRun(d, minTextLenAnywhere);
                if (run != null)
                    hits.add(new ScanHit(c, ScanHit.Type.TEXT, "text[" + run[1] + "] @0x" + Integer.toHexString(run[0]) + ": \""
                            + TextHeuristics.preview(d, run[0], 80) + "\"", 60 + Math.min(run[1], 300) / 2 - pen, d, run[0]));
            }
        }
        return hits;
    }
}
