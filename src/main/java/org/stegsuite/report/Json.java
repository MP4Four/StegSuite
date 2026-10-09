package org.stegsuite.report;

import java.util.Collection;
import java.util.Map;

/** Minimal JSON serialiser (no dependency): Map, Collection, arrays of primitives, String, Number, Boolean, null. */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.append('\n').toString();
    }

    static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { string(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d).append(".0");
            else sb.append(d);
            return;
        }
        if (v instanceof Number) { sb.append(v); return; }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                string(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                if (++i < m.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
            return;
        }
        if (v instanceof Collection) {
            Collection<?> c = (Collection<?>) v;
            if (c.isEmpty()) { sb.append("[]"); return; }
            boolean simple = true;
            for (Object o : c) if (o instanceof Map || o instanceof Collection) { simple = false; break; }
            sb.append('[');
            int i = 0;
            for (Object o : c) {
                if (!simple) { sb.append('\n'); pad(sb, indent + 1); }
                write(sb, o, indent + 1);
                if (++i < c.size()) sb.append(simple ? ", " : ",");
            }
            if (!simple) { sb.append('\n'); pad(sb, indent); }
            sb.append(']');
            return;
        }
        if (v instanceof int[]) { java.util.List<Object> l = new java.util.ArrayList<>(); for (int x : (int[]) v) l.add(x); write(sb, l, indent); return; }
        if (v instanceof long[]) { java.util.List<Object> l = new java.util.ArrayList<>(); for (long x : (long[]) v) l.add(x); write(sb, l, indent); return; }
        if (v instanceof double[]) { java.util.List<Object> l = new java.util.ArrayList<>(); for (double x : (double[]) v) l.add(x); write(sb, l, indent); return; }
        string(sb, String.valueOf(v));
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || (c >= 0xd800 && c <= 0xdfff && !validSurrogate(s, i)) || c == 0x2028 || c == 0x2029)
                        sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static boolean validSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }
}
