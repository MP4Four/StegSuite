package org.stegsuite.core.extract;

/**
 * One auto-scan finding.
 *
 * @param offset byte offset of the finding inside the extracted stream
 */
public final class ScanHit {
    private final ExtractConfig config;
    private final Type type;
    private final String detail;
    private final int score;
    private final byte[] data;
    private final int offset;
    public ScanHit(ExtractConfig config, Type type, String detail, int score, byte[] data, int offset) {
        this.config = config;
        this.type = type;
        this.detail = detail;
        this.score = score;
        this.data = data;
        this.offset = offset;
    }
    public ExtractConfig config() { return config; }
    public Type type() { return type; }
    public String detail() { return detail; }
    public int score() { return score; }
    public byte[] data() { return data; }
    public int offset() { return offset; }
    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ScanHit)) return false;
        ScanHit o = (ScanHit) obj;
        return java.util.Objects.equals(config, o.config) && java.util.Objects.equals(type, o.type) && java.util.Objects.equals(detail, o.detail) && score == o.score && java.util.Objects.equals(data, o.data) && offset == o.offset;
    }
    @Override public int hashCode() { return java.util.Objects.hash(config, type, detail, score, data, offset); }
    @Override public String toString() { return "ScanHit[" + "config=" + config + ", " + "type=" + type + ", " + "detail=" + detail + ", " + "score=" + score + ", " + "data=" + data + ", " + "offset=" + offset + "]"; }
    public enum Type { FLAG, FILE, TEXT }
}
