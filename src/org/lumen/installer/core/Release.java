package org.lumen.installer.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The signed release chain, all checked on the projector, with the certificates and the channel that the model's
 * entry of the signed list of projectors gives (Index.Entry):
 *  1. the channel manifest (update-*.json) + its .sig: RSA PKCS#1 v1.5 SHA-256 by the model's OTA key (the same file
 *     and key the Lumen updater trusts); it names the version, the model family and the stock versions it supports;
 *  2. the release's SHA256SUMS + .sig, same key (made by `openssl dgst -sha256 -sign ota.pk8` at release time; the
 *     computer installer checks the same pair): it must list exactly the channel's image name of that version;
 *  3. the image's sha256 against that line (Engine.verify).
 * Anything else is refused: another key, another device, another file name, a malformed version.
 */
public final class Release {
    private Release() {}

    public static final class Bad extends Exception {
        public final Msg msg;
        public final String arg;
        public Bad(Msg msg, String arg) { super(msg + (arg == null ? "" : ": " + arg)); this.msg = msg; this.arg = arg; }
    }

    public static final class Manifest {
        public String device, channel, version, buildId;
        public long versionCode;
        public final List<String> vendorOk = new ArrayList<>();
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

    /** The one image of this model a signed SHA256SUMS lists, for a USB stick folder (no manifest names the version). */
    public static final class Listed {
        public String name, version, sha;
    }

    /** The signed SHA256SUMS must list exactly one image of this channel (its image name with any version). */
    public static Listed onlyImage(byte[] sums, byte[] sig, List<X509Certificate> certs, Index.Channel ch) throws Bad {
        if (!verify(sums, sig, certs)) throw new Bad(Msg.SUMS_BAD_SIG, null);
        Pattern p = ch.imagePattern();
        Listed found = null;
        for (String line : Bytes.utf8(sums).split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] f = line.split("\\s+", 2);
            if (f.length != 2) continue;
            String n = f[1].startsWith("*") ? f[1].substring(1) : f[1];
            Matcher x = p.matcher(n);
            if (!x.matches()) continue;
            if (found != null) throw new Bad(Msg.SUMS_NO_IMAGE, n + " (more than one image of this projector)");
            if (!f[0].matches("[0-9a-f]{64}")) throw new Bad(Msg.SUMS_NO_IMAGE, n + " (bad sha256)");
            found = new Listed();
            found.name = n;
            found.version = x.group(1);
            found.sha = f[0];
        }
        if (found == null) throw new Bad(Msg.SUMS_NO_IMAGE, ch.imageName("VERSION"));
        return found;
    }

    public static Manifest manifest(byte[] json, byte[] sig, List<X509Certificate> certs, Index.Channel m) throws Bad {
        if (!verify(json, sig, certs)) throw new Bad(Msg.MANIFEST_BAD_SIG, null);
        Manifest r = new Manifest();
        try {
            Map<String, Object> o = Json.obj(Json.parse(Bytes.utf8(json)), "manifest");
            if (!"1".equals(Json.num(o, "schema"))) throw new Bad(Msg.MANIFEST_BAD, "schema");
            r.device = Json.str(o, "device");
            r.channel = Json.str(o, "channel");
            r.version = Json.str(o, "version");
            r.versionCode = Long.parseLong(Json.num(o, "version_code"));
            r.buildId = o.get("build_id") instanceof String ? (String) o.get("build_id") : "";
            Map<String, Object> req = Json.obj(o.get("requires"), "requires");
            for (Object v : Json.arr(req.get("vendor_incremental"), "vendor_incremental")) {
                if (!(v instanceof String)) throw new Bad(Msg.MANIFEST_BAD, "vendor_incremental");
                r.vendorOk.add((String) v);
            }
        } catch (IllegalArgumentException e) {
            throw new Bad(Msg.MANIFEST_BAD, e.getMessage());
        }
        if (!m.device.equals(r.device)) throw new Bad(Msg.MANIFEST_WRONG_DEVICE, r.device);
        if (!m.channel.equals(r.channel)) throw new Bad(Msg.MANIFEST_WRONG_DEVICE, r.device + "/" + r.channel);
        if (!versionOk(r.version)) throw new Bad(Msg.MANIFEST_BAD, "version " + r.version);
        if (r.vendorOk.isEmpty()) throw new Bad(Msg.MANIFEST_BAD, "no stock versions");
        return r;
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
