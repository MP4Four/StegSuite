package org.stegsuite.core.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Heuristics for spotting human readable content in extracted bytes. */
public final class TextHeuristics {
    private TextHeuristics() {}

    /** CTF-style flag patterns, e.g. flag{...}, ctf{...}, key{...}. */
    public static final Pattern FLAG = Pattern.compile("(?i)(flag|ctf|key|[a-z]{2,8}ctf)\\{[\\x20-\\x7c\\x7e]{1,120}\\}");

    public static boolean isPrintable(int c) {
        return (c >= 0x20 && c < 0x7f) || c == '\n' || c == '\r' || c == '\t';
    }

    /** Length of the printable ASCII run starting at offset 0. */
    public static int leadingPrintable(byte[] d) {
        int n = 0;
        while (n < d.length && isPrintable(d[n] & 0xff)) n++;
        return n;
    }

    /** Longest printable run anywhere; returns {offset, length}. */
    public static int[] longestPrintable(byte[] d) {
        int best = 0, bestOff = 0, cur = 0;
        for (int i = 0; i < d.length; i++) {
            if (isPrintable(d[i] & 0xff)) {
                cur++;
                if (cur > best) { best = cur; bestOff = i - cur + 1; }
            } else cur = 0;
        }
        return new int[]{bestOff, best};
    }

    public static List<String> findFlags(byte[] d) {
        List<String> out = new ArrayList<>();
        Matcher m = FLAG.matcher(new String(d, StandardCharsets.ISO_8859_1));
        while (m.find() && out.size() < 20) out.add(m.group());
        return out;
    }

    public static String preview(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < Math.min(d.length, off + len); i++) {
            int c = d[i] & 0xff;
            if (c >= 0x20 && c < 0x7f) sb.append((char) c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append('.');
        }
        return sb.toString();
    }

    public static final class Match {
        private final int offset;
        private final String text;
        public Match(int offset, String text) {
            this.offset = offset;
            this.text = text;
        }
        public int offset() { return offset; }
        public String text() { return text; }
        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Match)) return false;
            Match o = (Match) obj;
            return offset == o.offset && java.util.Objects.equals(text, o.text);
        }
        @Override public int hashCode() { return java.util.Objects.hash(offset, text); }
        @Override public String toString() { return "Match[" + "offset=" + offset + ", " + "text=" + text + "]"; }
    }

    /** Flag patterns with their byte offsets. */
    public static List<Match> findFlagsAt(byte[] d) {
        List<Match> out = new ArrayList<>();
        Matcher m = FLAG.matcher(new String(d, StandardCharsets.ISO_8859_1));
        while (m.find() && out.size() < 20) out.add(new Match(m.start(), m.group()));
        return out;
    }

    /**
     * Best "natural text" printable run anywhere in the data: {offset, length}, or null.
     * Runs must be at least minLen long, contain enough distinct characters and be mostly letters/digits/spaces,
     * which filters out the repetitive runs produced by flat image areas.
     */
    public static int[] bestTextRun(byte[] d, int minLen) {
        int bestOff = -1, bestLen = 0, i = 0;
        while (i < d.length) {
            if (!isPrintable(d[i] & 0xff)) { i++; continue; }
            int s = i;
            while (i < d.length && isPrintable(d[i] & 0xff)) i++;
            int len = i - s;
            if (len >= minLen && len > bestLen && looksLikeTextStrict(d, s, len)) { bestOff = s; bestLen = len; }
        }
        return bestOff < 0 ? null : new int[]{bestOff, bestLen};
    }

    public static boolean looksLikeText(byte[] d, int off, int len) {
        boolean[] seen = new boolean[128];
        int distinct = 0, good = 0;
        for (int k = off; k < off + len; k++) {
            int c = d[k] & 0x7f;
            if (!seen[c]) { seen[c] = true; distinct++; }
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '\n' || c == '_' || c == '{' || c == '}' || c == '.' || c == ',') good++;
        }
        if (distinct < Math.min(6, Math.max(3, len / 3)) || good * 10 < len * 7) return false;
        // reject "smooth" runs (gradients / flat areas read as raw bytes): mostly +-1 steps between neighbours
        int smooth = 0;
        for (int k = off + 1; k < off + len; k++) if (Math.abs((d[k] & 0xff) - (d[k - 1] & 0xff)) <= 1) smooth++;
        return smooth * 10 < (len - 1) * 3;
    }

    private static final String COMMON = "etaoinshrdlu ETAOINSHRDLU";

    /**
     * Stricter check for runs found at a non-zero offset: English-like (common letters / spaces)
     * or a long pure base64 / hex token.
     */
    public static boolean looksLikeTextStrict(byte[] d, int off, int len) {
        if (!looksLikeText(d, off, len)) return false;
        int common = 0, b64 = 0, hex = 0;
        for (int k = off; k < off + len; k++) {
            char c = (char) (d[k] & 0x7f);
            if (COMMON.indexOf(c) >= 0) common++;
            if (Character.isLetterOrDigit(c) || c == '+' || c == '/' || c == '=') b64++;
            if (Character.digit(c, 16) >= 0) hex++;
        }
        int spaces = 0;
        java.util.Set<Integer> bigrams = new java.util.HashSet<>();
        for (int k = off; k < off + len; k++) {
            if (d[k] == ' ') spaces++;
            if (k > off) bigrams.add((d[k - 1] & 0xff) << 8 | (d[k] & 0xff));
        }
        boolean wordy = spaces * 14 >= len && bigrams.size() * 10 >= (len - 1) * 6;
        if (wordy && common * 100 >= len * 45) return true;
        return len >= 32 && (b64 == len || hex == len) && bigrams.size() * 10 >= (len - 1) * 6;
    }
}
