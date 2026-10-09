package org.stegsuite.core.util;

/** Classic offset / hex / ASCII dump formatting. */
public final class HexDump {
    private HexDump() {}

    public static String dump(byte[] data, int maxBytes) {
        return dump(data, 0, Math.min(data.length, maxBytes), 0);
    }

    public static String dump(byte[] data, int off, int len, long baseOffset) {
        StringBuilder sb = new StringBuilder(len * 4 + 16);
        int end = Math.min(data.length, off + len);
        for (int row = off; row < end; row += 16) {
            sb.append(String.format("%08x  ", baseOffset + row - off));
            for (int i = 0; i < 16; i++) {
                if (row + i < end) sb.append(String.format("%02x ", data[row + i] & 0xff));
                else sb.append("   ");
                if (i == 7) sb.append(' ');
            }
            sb.append(" |");
            for (int i = 0; i < 16 && row + i < end; i++) {
                int c = data[row + i] & 0xff;
                sb.append(c >= 0x20 && c < 0x7f ? (char) c : '.');
            }
            sb.append("|\n");
        }
        return sb.toString();
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < Math.min(b.length, off + len); i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString();
    }
}
