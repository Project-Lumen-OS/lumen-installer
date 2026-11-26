// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.contrib;

/** Lowercase hex and base64url without padding, the two encodings of the wire. */
final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    static String of(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = DIGITS[(b[i] >> 4) & 15];
            out[2 * i + 1] = DIGITS[b[i] & 15];
        }
        return new String(out);
    }

    static String b64url(byte[] b) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
