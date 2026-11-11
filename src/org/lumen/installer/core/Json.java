package org.lumen.installer.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader (objects, arrays, strings, numbers, true/false/null) for the signed channel manifest.
 * org.json is not usable in the host tests (android.jar has stubs only), and the manifest is tiny. Numbers are kept
 * as their text (the caller parses what it needs). Anything malformed throws.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value(0);
        j.ws();
        if (j.i != j.s.length()) throw new IllegalArgumentException("trailing data at " + j.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o, String what) {
        if (!(o instanceof Map)) throw new IllegalArgumentException(what + " is not an object");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o, String what) {
        if (!(o instanceof List)) throw new IllegalArgumentException(what + " is not an array");
        return (List<Object>) o;
    }

    public static String str(Map<String, Object> m, String k) {
        Object o = m.get(k);
        if (!(o instanceof String)) throw new IllegalArgumentException(k + " is not a string");
        return (String) o;
    }

    public static String num(Map<String, Object> m, String k) {
        Object o = m.get(k);
        if (!(o instanceof Number)) throw new IllegalArgumentException(k + " is not a number");
        return o.toString();
    }

    /** A number kept as text. */
    public static final class Number {
        final String text;
        Number(String t) { text = t; }
        @Override public String toString() { return text; }
    }

    private Object value(int depth) {
        if (depth > 32) throw new IllegalArgumentException("too deep");
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (c == 't') { lit("true"); return Boolean.TRUE; }
        if (c == 'f') { lit("false"); return Boolean.FALSE; }
        if (c == 'n') { lit("null"); return null; }
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw new IllegalArgumentException("unexpected '" + c + "' at " + i);
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw new IllegalArgumentException("key expected at " + i);
            String k = string();
            ws();
            expect(':');
            ws();
            if (m.containsKey(k)) throw new IllegalArgumentException("duplicate key " + k);
            m.put(k, value(depth + 1));
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("',' or '}' expected at " + (i - 1));
        }
    }

    private List<Object> array(int depth) {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            char c = next();
            if (c == ']') return a;
            if (c != ',') throw new IllegalArgumentException("',' or ']' expected at " + (i - 1));
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return b.toString();
            if (c < 0x20) throw new IllegalArgumentException("control character in string at " + (i - 1));
            if (c != '\\') { b.append(c); continue; }
            char e = next();
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new IllegalArgumentException("bad \\u escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw new IllegalArgumentException("bad escape \\" + e);
            }
        }
    }

    private Number number() {
        int st = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(st, i);
        if (!t.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("bad number " + t);
        return new Number(t);
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw new IllegalArgumentException("bad literal at " + i);
        i += w.length();
    }

    private void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }

    private char peek() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("'" + c + "' expected at " + (i - 1));
    }
}
