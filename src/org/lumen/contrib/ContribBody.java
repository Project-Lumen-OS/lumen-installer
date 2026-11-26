// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.contrib;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** The exact text a unit signs for one contribution event (context line, then one key=value per line in a fixed order).
 *  The serial never leaves the unit: only SHA-256 of "LUMEN-SERIAL-V1|" + serial goes into the body. */
public final class ContribBody {
    public static final String CONTEXT = "LUMEN-CONTRIB-V1";
    static final Pattern MODEL = Pattern.compile("[A-Z][0-9]{4}");
    static final Pattern VERSION = Pattern.compile("[0-9A-Za-z._-]{1,32}");
    static final Pattern REF = Pattern.compile("[0-9A-Za-z._-]{0,40}");
    static final Pattern NONCE = Pattern.compile("[0-9a-f]{16,32}");

    private ContribBody() {}

    public static String serialHash(String serial) {
        if (serial == null || serial.trim().isEmpty()) throw new IllegalArgumentException("no serial");
        return Hex.of(sha256(("LUMEN-SERIAL-V1|" + serial.trim()).getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] build(Kind kind, String model, String version, String serialHash, String ref, long time, String nonce) {
        require(MODEL, model, "model");
        require(VERSION, version, "version");
        require(REF, ref, "ref");
        require(NONCE, nonce, "nonce");
        if (serialHash == null || !serialHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("serial hash");
        if (time < 0) throw new IllegalArgumentException("time");
        String s = CONTEXT + "\nkind=" + kind.wire + "\nmodel=" + model + "\nfw=" + version + "\nsh=" + serialHash
                + "\nref=" + ref + "\nt=" + time + "\nn=" + nonce + "\n";
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void require(Pattern p, String v, String what) {
        if (v == null || !p.matcher(v).matches()) throw new IllegalArgumentException(what);
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
