package org.stegsuite.core.util;

import java.util.ArrayList;
import java.util.List;

/** Known file signatures ("magic numbers"). */
public final class FileMagic {
    private FileMagic() {}

    public static final class Sig {
        private final String name;
        private final String ext;
        private final byte[] magic;
        private final int offset;
        public Sig(String name, String ext, byte[] magic, int offset) {
            this.name = name;
            this.ext = ext;
            this.magic = magic;
            this.offset = offset;
        }
        public String name() { return name; }
        public String ext() { return ext; }
        public byte[] magic() { return magic; }
        public int offset() { return offset; }
        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Sig)) return false;
            Sig o = (Sig) obj;
            return java.util.Objects.equals(name, o.name) && java.util.Objects.equals(ext, o.ext) && java.util.Objects.equals(magic, o.magic) && offset == o.offset;
        }
        @Override public int hashCode() { return java.util.Objects.hash(name, ext, magic, offset); }
        @Override public String toString() { return "Sig[" + "name=" + name + ", " + "ext=" + ext + ", " + "magic=" + magic + ", " + "offset=" + offset + "]"; }
        /** Strong signatures are long enough to be meaningful when found at random positions. */
        public boolean strong() { return magic.length >= 4; }
    }

    public static final class Hit {
        private final Sig sig;
        private final int offset;
        public Hit(Sig sig, int offset) {
            this.sig = sig;
            this.offset = offset;
        }
        public Sig sig() { return sig; }
        public int offset() { return offset; }
        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Hit)) return false;
            Hit o = (Hit) obj;
            return java.util.Objects.equals(sig, o.sig) && offset == o.offset;
        }
        @Override public int hashCode() { return java.util.Objects.hash(sig, offset); }
        @Override public String toString() { return "Hit[" + "sig=" + sig + ", " + "offset=" + offset + "]"; }
    }

    private static final List<Sig> SIGS = new ArrayList<>();

    private static void add(String name, String ext, String hex, int off) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        SIGS.add(new Sig(name, ext, b, off));
    }

    private static void addAscii(String name, String ext, String s, int off) {
        SIGS.add(new Sig(name, ext, s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), off));
    }

    static {
        add("PNG image", "png", "89504e470d0a1a0a", 0);
        add("JPEG image", "jpg", "ffd8ffe0", 0);
        add("JPEG image", "jpg", "ffd8ffe1", 0);
        add("JPEG image", "jpg", "ffd8ffdb", 0);
        add("JPEG image", "jpg", "ffd8ffee", 0);
        addAscii("GIF image", "gif", "GIF87a", 0);
        addAscii("GIF image", "gif", "GIF89a", 0);
        add("ZIP archive", "zip", "504b0304", 0);
        add("ZIP archive (empty)", "zip", "504b0506", 0);
        add("RAR archive", "rar", "526172211a07", 0);
        add("7-Zip archive", "7z", "377abcaf271c", 0);
        add("GZIP data", "gz", "1f8b08", 0);
        addAscii("BZIP2 data", "bz2", "BZh", 0);
        add("XZ data", "xz", "fd377a585a00", 0);
        addAscii("PDF document", "pdf", "%PDF-", 0);
        add("ELF executable", "elf", "7f454c46", 0);
        addAscii("SQLite database", "sqlite", "SQLite format 3", 0);
        addAscii("Photoshop PSD", "psd", "8BPS", 0);
        add("TIFF image (LE)", "tif", "49492a00", 0);
        add("TIFF image (BE)", "tif", "4d4d002a", 0);
        addAscii("RIFF (WAV/AVI/WEBP)", "riff", "RIFF", 0);
        addAscii("OGG media", "ogg", "OggS", 0);
        addAscii("FLAC audio", "flac", "fLaC", 0);
        addAscii("MP3 with ID3", "mp3", "ID3", 0);
        addAscii("MIDI", "mid", "MThd", 0);
        addAscii("Java class", "class", "\u00ca\u00fe\u00ba\u00be", 0);
        addAscii("PGP armored", "asc", "-----BEGIN PGP", 0);
        addAscii("PEM data", "pem", "-----BEGIN ", 0);
        addAscii("ISO-BMFF (MP4/HEIC)", "mp4", "ftyp", 4);
        addAscii("BMP image", "bmp", "BM", 0);
        addAscii("DOS/PE executable", "exe", "MZ", 0);
    }

    public static List<Sig> all() { return SIGS; }

    /** Identify the data by its header. */
    public static Sig identify(byte[] d) {
        for (Sig s : SIGS) if (matches(d, s.magic(), s.offset())) return s;
        return null;
    }

    /** Header match restricted to strong (>=3 byte) signatures; used by the auto scanner to limit noise. */
    public static Sig identifyStrong(byte[] d) {
        for (Sig s : SIGS) if (s.magic().length >= 3 && matches(d, s.magic(), s.offset())) return s;
        return null;
    }

    public static boolean matches(byte[] d, byte[] m, int off) {
        if (d.length < off + m.length) return false;
        for (int i = 0; i < m.length; i++) if (d[off + i] != m[i]) return false;
        return true;
    }

    /** binwalk-style search for strong signatures embedded anywhere after offset 0. */
    public static List<Hit> scanEmbedded(byte[] d, int maxHits) {
        List<Hit> hits = new ArrayList<>();
        for (int i = 1; i < d.length && hits.size() < maxHits; i++) {
            for (Sig s : SIGS) {
                if (!s.strong() || s.offset() != 0) continue;
                if (d[i] == s.magic()[0] && matches(d, s.magic(), i)) { hits.add(new Hit(s, i)); break; }
            }
        }
        return hits;
    }

    /**
     * Number of extra header bytes (beyond the magic) that look consistent with the format at {@code off},
     * or -1 if the bytes contradict the format. Used to keep random-position matches in noisy bit streams rare.
     */
    public static int confirmBytes(byte[] d, int off, Sig s) {
        int p = off + s.magic().length;
        String n = s.name();
        if (n.startsWith("ZIP archive") && n.contains("empty")) return -1;
        if (n.startsWith("ZIP archive")) { // version needed (<=63), general flags high byte small, method 0/8/12/14
            if (d.length < off + 10) return -1;
            int ver = d[off + 4] & 0xff, verHi = d[off + 5] & 0xff, meth = (d[off + 8] & 0xff) | (d[off + 9] & 0xff) << 8;
            return ver <= 63 && verHi == 0 && (meth == 0 || meth == 8 || meth == 12 || meth == 14) ? 3 : -1;
        }
        if (n.startsWith("JPEG")) {
            int m = d[off + 3] & 0xff;
            if (m == 0xe0) return matches(d, "JFIF".getBytes(), off + 6) ? 4 : -1;
            if (m == 0xe1) return matches(d, "Exif".getBytes(), off + 6) ? 4 : -1;
            return d.length > off + 5 && (d[off + 4] & 0xff) == 0 ? 1 : -1;
        }
        if (n.startsWith("ELF")) return d.length > off + 6 && (d[p] == 1 || d[p] == 2) && (d[p + 1] == 1 || d[p + 1] == 2) && d[p + 2] == 1 ? 3 : -1;
        if (n.startsWith("RIFF")) {
            for (String t : new String[]{"WAVE", "AVI ", "WEBP"}) if (matches(d, t.getBytes(), off + 8)) return 4;
            return -1;
        }
        if (n.startsWith("OGG")) return d.length > p && d[p] == 0 ? 1 : -1;
        if (n.startsWith("MIDI")) return matches(d, new byte[]{0, 0, 0, 6}, p) ? 4 : -1;
        if (n.startsWith("Photoshop")) return matches(d, new byte[]{0, 1}, p) ? 2 : -1;
        if (n.startsWith("TIFF")) return d.length > p + 3 ? 1 : -1;
        if (n.startsWith("GZIP")) return d.length > p && (d[p] & 0xe0) == 0 ? 3 : -1; // FLG reserved bits clear
        if (n.startsWith("BZIP2")) return d.length > p + 6 && d[p] >= '1' && d[p] <= '9'
                && matches(d, new byte[]{0x31, 0x41, 0x59, 0x26, 0x53, 0x59}, p + 1) ? 7 : -1;
        if (n.startsWith("MP3")) return d.length > p + 1 && d[p] >= 2 && d[p] <= 4 && d[p + 1] == 0 ? 2 : -1;
        return 0;
    }

    /**
     * Search for a file signature anywhere in {@code d} (offset 0 included). A hit needs at least
     * {@code minConfirmedBytes} matching bytes (magic + plausible header fields).
     */
    public static List<Hit> findAnywhere(byte[] d, int minConfirmedBytes, int maxHits) {
        List<Hit> hits = new ArrayList<>();
        List<Sig>[] byFirst = FIRST_BYTE_INDEX;
        for (int i = 0; i < d.length && hits.size() < maxHits; i++) {
            List<Sig> cand = byFirst[d[i] & 0xff];
            if (cand == null) continue;
            for (Sig s : cand) {
                int start = i - s.offset();
                if (start < 0 || !matches(d, s.magic(), i)) continue;
                int extra = confirmBytes(d, start, s);
                if (extra < 0 || s.magic().length + extra < minConfirmedBytes) continue;
                hits.add(new Hit(s, start));
                break;
            }
        }
        return hits;
    }

    @SuppressWarnings("unchecked")
    private static final List<Sig>[] FIRST_BYTE_INDEX = new List[256];
    static {
        for (Sig s : SIGS) {
            int b = s.magic()[0] & 0xff;
            if (FIRST_BYTE_INDEX[b] == null) FIRST_BYTE_INDEX[b] = new ArrayList<>();
            FIRST_BYTE_INDEX[b].add(s);
        }
        // prefer longer magics first (e.g. "-----BEGIN PGP" before "-----BEGIN ")
        for (List<Sig> l : FIRST_BYTE_INDEX) if (l != null) l.sort((a, b) -> b.magic().length - a.magic().length);
    }
}
