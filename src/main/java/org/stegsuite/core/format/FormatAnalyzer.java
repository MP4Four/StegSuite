package org.stegsuite.core.format;

import org.stegsuite.core.util.FileMagic;
import org.stegsuite.core.util.HexDump;
import org.stegsuite.core.util.TextHeuristics;

import java.util.Arrays;
import java.util.List;

/** Dispatches to the format-specific analyzer and adds generic checks (embedded files, flags, trailing data). */
public final class FormatAnalyzer {
    private FormatAnalyzer() {}

    public static FormatReport analyze(byte[] d) {
        FormatReport r = new FormatReport();
        FileMagic.Sig sig = FileMagic.identify(d);
        r.line("File size: " + d.length + " bytes");
        r.line("Detected type: " + (sig == null ? "unknown" : sig.name()));
        r.line("");
        try {
            if (sig != null && sig.ext().equals("png")) PngAnalyzer.analyze(d, r);
            else if (sig != null && sig.ext().equals("jpg")) JpegAnalyzer.analyze(d, r);
            else if (sig != null && sig.ext().equals("gif")) GifAnalyzer.analyze(d, r);
            else if (sig != null && sig.ext().equals("bmp")) BmpAnalyzer.analyze(d, r);
            else if (d.length > 3 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8) JpegAnalyzer.analyze(d, r);
        } catch (RuntimeException e) {
            r.warn("Parser error (file may be truncated/corrupt): " + e);
        }
        if (r.hasTrailing()) {
            r.line("");
            r.warn(String.format("%d bytes of trailing data after end marker at offset 0x%x", r.trailingLength, r.trailingOffset));
            byte[] t = Arrays.copyOfRange(d, (int) r.trailingOffset, (int) Math.min(d.length, r.trailingOffset + 256));
            FileMagic.Sig ts = FileMagic.identify(t);
            if (ts != null) r.line("    trailing data looks like: " + ts.name());
            r.line(HexDump.dump(t, 0, Math.min(t.length, 128), r.trailingOffset));
        }
        List<FileMagic.Hit> emb = FileMagic.scanEmbedded(d, 50);
        r.line("");
        r.line("== Embedded signatures (binwalk-style) ==");
        if (emb.isEmpty()) r.line("none");
        for (FileMagic.Hit h : emb) r.line(String.format("  0x%08x  %s", h.offset(), h.sig().name()));
        List<String> flags = TextHeuristics.findFlags(d);
        r.line("");
        r.line("== Flag-like strings in raw file ==");
        r.line(flags.isEmpty() ? "none" : String.join("\n", flags));
        return r;
    }
}
