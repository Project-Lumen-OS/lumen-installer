package org.lumen.installer.core;

import java.util.HashMap;
import java.util.Map;

/** Parses the output of a plain `getprop` ("[key]: [value]" per line; a value may span lines). */
public final class Props {
    private Props() {}

    public static Map<String, String> parse(String out) {
        Map<String, String> m = new HashMap<>();
        String key = null;
        StringBuilder val = null;
        for (String line : out.split("\n", -1)) {
            if (key == null) {
                if (!line.startsWith("[")) continue;
                int e = line.indexOf("]: [");
                if (e < 0) continue;
                key = line.substring(1, e);
                val = new StringBuilder(line.substring(e + 4));
            } else {
                val.append('\n').append(line);
            }
            String v = val.toString();
            if (v.endsWith("]")) {
                m.put(key, v.substring(0, v.length() - 1));
                key = null;
            }
        }
        return m;
    }

    public static String get(Map<String, String> p, String k) {
        String v = p.get(k);
        return v == null ? "" : v.trim();
    }
}
