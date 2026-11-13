package org.lumen.installer.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The signed release files, checked on the projector with the certificates of the model's catalog entry:
 *  1. the release's SHA256SUMS + .sig: RSA PKCS#1 v1.5 SHA-256 by the model's OTA key (made by
 *     `openssl dgst -sha256 -sign ota.pk8` at release time; the computer installer checks the same pair): its line for
 *     the build's image must give the catalog's sha256;
 *  2. the image's sha256 against both (Engine.verify).
 * Anything else is refused: another key, another file name, a malformed line.
 */
public final class Release {
    private Release() {}

    public static final class Bad extends Exception {
        public final Msg msg;
        public final String arg;
        public Bad(Msg msg, String arg) { super(msg + (arg == null ? "" : ": " + arg)); this.msg = msg; this.arg = arg; }
    }

    public static List<X509Certificate> certs(List<byte[]> pems) throws IOException {
        List<X509Certificate> out = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (byte[] p : pems) {
                try (InputStream in = new ByteArrayInputStream(p)) {
                    out.add((X509Certificate) cf.generateCertificate(in));
                }
            }
        } catch (java.security.cert.CertificateException e) {
            throw new IOException(e);
        }
        if (out.isEmpty()) throw new IOException("no certificate");
        return out;
    }

    /** RSA PKCS#1 v1.5 SHA-256 over the exact bytes, by any of the certificates (ota, then ota_next). */
    public static boolean verify(byte[] data, byte[] sig, List<X509Certificate> certs) {
        if (data == null || sig == null) return false;
        for (X509Certificate c : certs) {
            try {
                Signature s = Signature.getInstance("SHA256withRSA");
                s.initVerify(c.getPublicKey());
                s.update(data);
                if (s.verify(sig)) return true;
            } catch (Exception ignored) {
                // next certificate
            }
        }
        return false;
    }

    /** x.y.z, optionally with a beta or rc mark (1.0.10-beta1, 1.0.10-rc2), the Lumen OS release names */
    static final String VERSION = "[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}(?:-(?:beta|rc)[0-9]{1,2})?";

    static boolean versionOk(String v) { return v != null && v.matches(VERSION); }

    /** Every file a signed SHA256SUMS lists (name -> sha256), for a USB stick folder; a malformed line refuses it. */
    public static Map<String, String> listed(byte[] sums, byte[] sig, List<X509Certificate> certs) throws Bad {
        if (!verify(sums, sig, certs)) throw new Bad(Msg.SUMS_BAD_SIG, null);
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Bytes.utf8(sums).split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] f = line.split("\\s+", 2);
            if (f.length != 2 || !f[0].matches("[0-9a-f]{64}")) throw new Bad(Msg.SUMS_NO_IMAGE, line);
            String n = f[1].startsWith("*") ? f[1].substring(1) : f[1];
            if (out.put(n, f[0]) != null) throw new Bad(Msg.SUMS_NO_IMAGE, n + " (listed twice)");
        }
        return out;
    }

    /** The sha256 that the signed SHA256SUMS gives for exactly this file name. */
    public static String imageSha(byte[] sums, byte[] sig, List<X509Certificate> certs, String name) throws Bad {
        if (!verify(sums, sig, certs)) throw new Bad(Msg.SUMS_BAD_SIG, null);
        String found = null;
        for (String line : Bytes.utf8(sums).split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] f = line.split("\\s+", 2);
            if (f.length != 2) continue;
            String n = f[1].startsWith("*") ? f[1].substring(1) : f[1];
            if (!n.equals(name)) continue;
            if (found != null) throw new Bad(Msg.SUMS_NO_IMAGE, name + " (listed twice)");
            if (!f[0].matches("[0-9a-f]{64}")) throw new Bad(Msg.SUMS_NO_IMAGE, name + " (bad sha256)");
            found = f[0];
        }
        if (found == null) throw new Bad(Msg.SUMS_NO_IMAGE, name);
        return found;
    }
}
