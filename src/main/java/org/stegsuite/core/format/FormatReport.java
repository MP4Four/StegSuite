package org.stegsuite.core.format;

import java.util.ArrayList;
import java.util.List;

/** Result of a file-format analysis. */
public final class FormatReport {
    public String format = "unknown";
    public final StringBuilder text = new StringBuilder();
    public final List<String> warnings = new ArrayList<>();
    /** Offset of the first byte after the format's end marker, or -1 if none / no trailing data. */
    public long trailingOffset = -1;
    public long trailingLength = 0;
    /** PNG: chunk table and IHDR repair suggestion. */
    public final List<PngAnalyzer.ChunkInfo> chunks = new ArrayList<>();
    /** JPEG: estimated IJG quality of the luminance table (-1 if not a JPEG / no table), exact Annex K match. */
    public int jpegQuality = -1;
    public boolean jpegQualityExact;
    public int[] suggestedSize; // {width,height} matching IHDR CRC, if IHDR CRC was bad

    public FormatReport line(String s) { text.append(s).append('\n'); return this; }
    public FormatReport warn(String s) { warnings.add(s); text.append("[!] ").append(s).append('\n'); return this; }

    public boolean hasTrailing() { return trailingOffset >= 0 && trailingLength > 0; }
}
