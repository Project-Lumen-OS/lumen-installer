package org.lumen.contrib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the flat JSON objects of the contribution base: string, integer and string-array values only. Anything
 *  else is refused, so a strange answer is never half understood. */
final class FlatJson {
    private final String s;
    private int i;

    private FlatJson(String s) {
        this.s = s;
    }

    static Map<String, Object> parse(String text) {
        FlatJson p = new FlatJson(text == null ? "" : text.trim());
        Map<String, Object> out = p.object();
        if (p.i != p.s.length()) throw new IllegalArgumentException("trailing data");
        return out;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        expect('{');
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            String k = string();
            expect(':');
            m.put(k, value());
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("object");
        }
    }

    private Object value() {
        char c = peek();
        if (c == '"') return string();
        if (c == '[') {
            i++;
            List<String> l = new ArrayList<>();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(string());
                char d = next();
                if (d == ']') return l;
                if (d != ',') throw new IllegalArgumentException("array");
            }
        }
        int start = i;
        if (c == '-') i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == start || (c == '-' && i == start + 1)) throw new IllegalArgumentException("value");
        return Long.parseLong(s.substring(start, i));
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new IllegalArgumentException("string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': case '\\': case '/': b.append(e); break;
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: throw new IllegalArgumentException("escape");
            }
        }
    }

    private char peek() {
        skip();
        if (i >= s.length()) throw new IllegalArgumentException("end");
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("expected " + c);
    }

    private void skip() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
}
