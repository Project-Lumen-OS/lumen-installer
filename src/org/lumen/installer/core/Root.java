package org.lumen.installer.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract between the app and its root script (assets/lumen_installer.sh), the same on the projector and in the
 * host tests. The app writes job.conf next to the script and starts it as root through XGIMI's stock debugserver
 * service; the script appends to status.txt:
 *   LUMENINST_START nonce
 *   KV key=value                 facts (probe) and results
 *   LOG text                     shown in the app's log
 *   LUMENINST_DONE nonce         or   LUMENINST_FAIL nonce code [detail]
 * The nonce is new for every job, so a status file left from an earlier job never counts.
 */
public final class Root {
    private Root() {}

    public static final String[] ACTIONS = { "probe", "install", "enable", "remove", "reboot" };
    public static final String[] KEYS = { "IMG", "TAIL", "IMG_SIZE", "TAIL_SIZE", "TOTAL", "USERDATA", "IMG_SHA",
                                          "TAIL_SHA", "NEED_KIB", "MODEL" };

    public static final class Job {
        public final String action, nonce;
        public final Map<String, String> params = new LinkedHashMap<>();

        public Job(String action, String nonce) {
            boolean ok = false;
            for (String a : ACTIONS) ok |= a.equals(action);
            if (!ok) throw new IllegalArgumentException("action not allowed: " + action);
            if (!nonce.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("bad nonce");
            this.action = action;
            this.nonce = nonce;
        }

        public Job put(String k, String v) {
            boolean ok = false;
            for (String a : KEYS) ok |= a.equals(k);
            if (!ok) throw new IllegalArgumentException("key not allowed: " + k);
            if (v == null || !v.matches("[A-Za-z0-9._/-]{1,240}")) throw new IllegalArgumentException("bad value for " + k);
            params.put(k, v);
            return this;
        }

        public String conf() {
            StringBuilder b = new StringBuilder();
            b.append("ACTION=").append(action).append('\n').append("NONCE=").append(nonce).append('\n');
            for (Map.Entry<String, String> e : params.entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            return b.toString();
        }
    }

    public static final class Result {
        public boolean started, done, failed;
        public String failCode = "", failDetail = "";
        public final Map<String, String> kv = new LinkedHashMap<>();
        public final List<String> log = new ArrayList<>();

        public String kv(String k) { String v = kv.get(k); return v == null ? "" : v; }

        public long kvLong(String k, long dflt) {
            try { return Long.parseLong(kv(k)); } catch (NumberFormatException e) { return dflt; }
        }
    }

    /** Parse a status file text for one job. Lines before this job's START are ignored. */
    public static Result parse(String status, String nonce) {
        Result r = new Result();
        for (String line : status.split("\n")) {
            if (line.equals("LUMENINST_START " + nonce)) { r.started = true; continue; }
            if (!r.started) continue;
            if (line.startsWith("KV ")) {
                int eq = line.indexOf('=');
                if (eq > 3) r.kv.put(line.substring(3, eq), line.substring(eq + 1));
            } else if (line.startsWith("LOG ")) {
                r.log.add(line.substring(4));
            } else if (line.equals("LUMENINST_DONE " + nonce)) {
                r.done = true;
                break;
            } else if (line.startsWith("LUMENINST_FAIL " + nonce)) {
                r.failed = true;
                String rest = line.substring(("LUMENINST_FAIL " + nonce).length()).trim();
                int sp = rest.indexOf(' ');
                r.failCode = sp < 0 ? rest : rest.substring(0, sp);
                r.failDetail = sp < 0 ? "" : rest.substring(sp + 1);
                break;
            }
        }
        return r;
    }

    public static String newNonce() {
        byte[] b = new byte[8];
        new java.security.SecureRandom().nextBytes(b);
        return Bytes.hex(b);
    }
}
