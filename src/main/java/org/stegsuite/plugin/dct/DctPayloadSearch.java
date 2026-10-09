package org.stegsuite.plugin.dct;

import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.util.FileMagic;
import org.stegsuite.core.util.TextHeuristics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scores extracted DCT LSB streams the same way the bit-plane auto scan does: CTF flags beat a file header
 * at offset 0, which beats an embedded file header, which beats natural looking text. A finding is what
 * turns a statistical hint into an actual payload.
 */
final class DctPayloadSearch {
    private DctPayloadSearch() {}

    static final int MIN_TEXT_AT_ZERO = 8;
    static final int MIN_TEXT_ANYWHERE = 20;
    static final int MIN_SIGNATURE_BYTES = 6;
    private static final int MAX_HITS_PER_KIND = 3;

    /** One finding inside an extracted stream. */
    static final class Hit {
        final DctExtraction.Mode mode;
        final String kind;
        final String detail;
        final int offset;
        final int score;
        final byte[] stream;

        Hit(DctExtraction.Mode mode, String kind, String detail, int offset, int score, byte[] stream) {
            this.mode = mode;
            this.kind = kind;
            this.detail = detail;
            this.offset = offset;
            this.score = score;
            this.stream = stream;
        }
    }

    /** All findings of one extraction mode, best score first. */
    static List<Hit> search(DctExtraction.Mode mode, byte[] stream) {
        List<Hit> hits = new ArrayList<>();
        if (stream.length == 0) return hits;
        List<TextHeuristics.Match> flags = TextHeuristics.findFlagsAt(stream);
        if (!flags.isEmpty()) {
            int offset = flags.get(0).offset();
            StringBuilder text = new StringBuilder();
            for (TextHeuristics.Match m : flags) text.append(text.length() == 0 ? "" : " ").append(m.text());
            hits.add(new Hit(mode, "FLAG", text + " @0x" + Integer.toHexString(offset), offset,
                    1000 + (offset == 0 ? 50 : 0) - Math.min(40, offset / 4096), stream));
        }
        FileMagic.Sig head = FileMagic.identifyStrong(stream);
        int files = 0;
        if (head != null && FileMagic.confirmBytes(stream, 0, head) >= 0) {
            int confirmed = head.magic().length + FileMagic.confirmBytes(stream, 0, head);
            hits.add(new Hit(mode, "FILE", head.name() + " (." + head.ext() + ") @0x0", 0,
                    500 + Math.min(100, confirmed * 10), stream));
            files++;
        }
        for (FileMagic.Hit fh : FileMagic.findAnywhere(stream, MIN_SIGNATURE_BYTES, MAX_HITS_PER_KIND + 1)) {
            if (fh.offset() == 0 || files >= MAX_HITS_PER_KIND) continue;
            int confirmed = fh.sig().magic().length + FileMagic.confirmBytes(stream, fh.offset(), fh.sig());
            hits.add(new Hit(mode, "FILE", fh.sig().name() + " (." + fh.sig().ext() + ") @0x" + Integer.toHexString(fh.offset()),
                    fh.offset(), 300 + Math.min(100, confirmed * 10) - Math.min(50, fh.offset() / 4096), stream));
            files++;
        }
        if (flags.isEmpty()) {
            int leading = TextHeuristics.leadingPrintable(stream);
            if (leading >= MIN_TEXT_AT_ZERO && TextHeuristics.looksLikeText(stream, 0, Math.min(leading, 4096))) {
                hits.add(new Hit(mode, "TEXT", "text[" + leading + "] @0x0: \"" + TextHeuristics.preview(stream, 0, 60) + "\"",
                        0, 100 + Math.min(leading, 300), stream));
            } else {
                int[] run = TextHeuristics.bestTextRun(stream, MIN_TEXT_ANYWHERE);
                if (run != null)
                    hits.add(new Hit(mode, "TEXT", "text[" + run[1] + "] @0x" + Integer.toHexString(run[0]) + ": \""
                            + TextHeuristics.preview(stream, run[0], 60) + "\"", run[0], 60 + Math.min(run[1], 300) / 2, stream));
            }
        }
        hits.sort((a, b) -> b.score - a.score);
        return hits;
    }

    /** Highest scoring finding over all applicable modes, or null when no stream looks like a payload. */
    static Hit bestHit(DctImage img, String password) {
        Hit best = null;
        for (DctExtraction.Mode mode : DctExtraction.SEQUENTIAL_MODES) {
            for (Hit h : search(mode, DctExtraction.extract(img, mode, password)))
                if (best == null || h.score > best.score) best = h;
            // (JSTEG_HEADER comes first, so on equal scores the exactly sliced payload wins)
        }
        if (password != null && !password.isEmpty()) {
            byte[] keyed = DctExtraction.extract(img, DctExtraction.Mode.KEYED, password);
            if (keyed != null)
                for (Hit h : search(DctExtraction.Mode.KEYED, keyed))
                    if (best == null || h.score > best.score) best = h;
        }
        return best;
    }

    /**
     * What the Extract button shows: the best payload (from its first byte) when one was recognised,
     * otherwise the raw JSteg LSB stream so the bytes can still be inspected by hand.
     */
    static byte[] bestPayload(DctImage img, String password) {
        Hit best = bestHit(img, password);
        if (best != null && best.offset > 0) return Arrays.copyOfRange(best.stream, best.offset, best.stream.length);
        if (best != null) return best.stream;
        JstegHeader.Header header = DctExtraction.jstegHeader(img);
        if (header != null && header.payload.length > 0) return header.payload;
        byte[] raw = DctExtraction.extract(img, DctExtraction.Mode.JSTEG_AC, password);
        return raw.length == 0 ? null : raw;
    }
}
