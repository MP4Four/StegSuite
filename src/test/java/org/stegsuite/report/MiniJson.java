package org.stegsuite.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict little JSON parser for the tests (RFC 8259 grammar; throws on anything malformed or trailing). */
final class MiniJson {
    private final String s;
    private int p;

    private MiniJson(String s) { this.s = s; }

    static Object parse(String text) {
        MiniJson j = new MiniJson(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.p != text.length()) throw j.err("trailing characters");
        return v;
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException(m + " at " + p + ": " + s.substring(Math.max(0, p - 20), Math.min(s.length(), p + 20)));
    }

    private void ws() { while (p < s.length() && " \t\r\n".indexOf(s.charAt(p)) >= 0) p++; }

    private char peek() { if (p >= s.length()) throw err("unexpected end"); return s.charAt(p); }

    private void expect(char c) { if (peek() != c) throw err("expected '" + c + "'"); p++; }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': word("true"); return Boolean.TRUE;
            case 'f': word("false"); return Boolean.FALSE;
            case 'n': word("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected character");
        }
    }

    private void word(String w) {
        if (!s.startsWith(w, p)) throw err("expected " + w);
        p += w.length();
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek() == '}') { p++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            if (m.containsKey(k)) throw err("duplicate key " + k);
            m.put(k, value());
            ws();
            if (peek() == ',') { p++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> l = new ArrayList<>();
        ws();
        if (peek() == ']') { p++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek() == ',') { p++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek();
            p++;
            if (c == '"') return sb.toString();
            if (c < 0x20) throw err("raw control character in string");
            if (c != '\\') { sb.append(c); continue; }
            char e = peek();
            p++;
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (p + 4 > s.length()) throw err("short \\u escape");
                    sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                    p += 4;
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Number number() {
        int start = p;
        if (peek() == '-') p++;
        if (peek() == '0') p++;
        else if (peek() >= '1' && peek() <= '9') while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        else throw err("bad number");
        boolean real = false;
        if (p < s.length() && s.charAt(p) == '.') {
            real = true;
            p++;
            if (!Character.isDigit(peek())) throw err("bad fraction");
            while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        }
        if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
            real = true;
            p++;
            if (peek() == '+' || peek() == '-') p++;
            if (!Character.isDigit(peek())) throw err("bad exponent");
            while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        }
        String t = s.substring(start, p);
        return real ? (Number) Double.valueOf(t) : (Number) Long.valueOf(t);
    }
}
