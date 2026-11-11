package org.lumen.installer.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The AVB hashtree footer of the trial system image, made on the projector in Java, exactly as
 * {@code avbtool 1.3 add_hashtree_footer --partition_size 0 --partition_name system --hash_algorithm sha256
 * --do_not_generate_fec --algorithm SHA256_RSA4096} would append it (host test: byte-equal with avbtool for the same
 * key and salt, and `avbtool verify_image` accepts image + tail).
 *
 * Why: first-stage init verifies the trial system against its OWN footer (avb_keys=/avb, LoadAndVerifyVbmeta). Stock
 * XGIMI ramdisks have no /avb keys and boot orange, so fs_avb accepts an unknown key ("Found unknown public key", AVB
 * permissive). Without a footer init falls back to the stock vbmeta_system digest and dm-verity rejects every block.
 * A self-made RSA-4096 key works on an unlocked projector. The key is generated on the projector for each run and
 * never stored.
 *
 * Nothing is written into the downloaded image: the tail (hash tree, vbmeta, footer block) goes into its own file
 * and the root side streams `cat image tail` into gsi_tool, whose --gsi-size is image + tail.
 *
 * Layout after the image (all 4096-aligned): hash tree (top level first, level 0 last) | vbmeta blob, zero padded |
 * one 4096 block whose last 64 bytes are the footer "AVBf".
 */
public final class AvbTail {
    private AvbTail() {}

    public static final int BS = 4096, FOOTER = 64;
    static final String RELEASE = "avbtool 1.3.0";
    static final int ALG_SHA256_RSA4096 = 2, SIG_BYTES = 512, KEY_BITS = 4096;

    public static final class Result {
        public long imageSize, treeSize, vbmetaOffset, vbmetaSize, tailSize, total;
        public byte[] rootDigest, salt;
        public String tailSha256;
    }

    public static KeyPair newKey() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4));
        return g.generateKeyPair();
    }

    public static byte[] newSalt() {
        byte[] s = new byte[32];
        new java.security.SecureRandom().nextBytes(s);
        return s;
    }

    /** Level sizes of the tree, level 0 first (avbtool calc_hash_level_offsets). */
    static List<Long> levelSizes(long imageSize) {
        List<Long> sizes = new ArrayList<>();
        long size = imageSize;
        while (size > BS) {
            long blocks = (size + BS - 1) / BS;
            long level = Bytes.roundUp(blocks * 32, BS);
            sizes.add(level);
            size = level;
        }
        return sizes;
    }

    /** Build the tail for image into out. */
    public static Result build(File image, File out, byte[] salt, KeyPair key, Bytes.Progress p, Bytes.Stop stop)
            throws IOException, GeneralSecurityException {
        long n = image.length();
        if (n <= 0 || n % BS != 0) throw new IOException("image size " + n + " is not a multiple of " + BS);
        List<Long> sizes = levelSizes(n);
        long treeSize = 0;
        for (long s : sizes) treeSize += s;
        if (treeSize > Integer.MAX_VALUE / 2) throw new IOException("image too large for an in-memory hash tree");

        Result r = new Result();
        r.imageSize = n;
        r.salt = salt.clone();
        MessageDigest md = Bytes.sha256();
        byte[] tree = new byte[(int) treeSize];
        if (n == BS) {   // one block: the root is that block's hash, no tree (avbtool special case)
            byte[] blk = new byte[BS];
            try (RandomAccessFile f = new RandomAccessFile(image, "r")) { f.readFully(blk); }
            md.update(salt);
            md.update(blk);
            r.rootDigest = md.digest();
        } else {
            // levels are stored top level first: level i sits after the levels above it
            long[] offs = new long[sizes.size()];
            for (int i = 0; i < sizes.size(); i++) {
                long o = 0;
                for (int k = i + 1; k < sizes.size(); k++) o += sizes.get(k);
                offs[i] = o;
            }
            // level 0 from the image, 1 MiB reads
            int pos = (int) offs[0];
            byte[] buf = new byte[1 << 20];
            long done = 0;
            try (FileInputStream in = new FileInputStream(image)) {
                while (done < n) {
                    int want = (int) Math.min(buf.length, n - done), got = 0;
                    while (got < want) {
                        int k = in.read(buf, got, want - got);
                        if (k < 0) throw new IOException("image shorter than " + n);
                        got += k;
                    }
                    for (int o = 0; o < want; o += BS) {
                        md.update(salt);
                        md.update(buf, o, BS);
                        md.digest(tree, pos, 32);
                        pos += 32;
                    }
                    done += want;
                    if (p != null) p.at(done, n);
                    if (stop != null && stop.stopped()) throw new Bytes.Stopped();
                }
            } catch (java.security.DigestException e) {
                throw new IOException(e);
            }
            // upper levels from the level below (zero padding included, as avbtool hashes the padded level)
            for (int lv = 1; lv < sizes.size(); lv++) {
                int src = (int) offs[lv - 1], srcLen = (int) (long) sizes.get(lv - 1), dst = (int) offs[lv];
                try {
                    for (int o = 0; o < srcLen; o += BS) {
                        md.update(salt);
                        md.update(tree, src + o, BS);
                        md.digest(tree, dst, 32);
                        dst += 32;
                    }
                } catch (java.security.DigestException e) {
                    throw new IOException(e);
                }
            }
            int top = (int) offs[sizes.size() - 1];
            md.update(salt);
            md.update(tree, top, (int) (long) sizes.get(sizes.size() - 1));
            r.rootDigest = md.digest();
        }
        r.treeSize = treeSize;

        byte[] vbmeta = vbmeta(n, n, treeSize, salt, r.rootDigest, key);
        r.vbmetaOffset = n + treeSize;
        r.vbmetaSize = vbmeta.length;
        long vbPadded = Bytes.roundUp(vbmeta.length, BS);
        r.tailSize = treeSize + vbPadded + BS;
        r.total = n + r.tailSize;

        byte[] footerBlock = new byte[BS];
        System.arraycopy(footer(n, r.vbmetaOffset, r.vbmetaSize), 0, footerBlock, BS - FOOTER, FOOTER);
        MessageDigest td = Bytes.sha256();
        try (FileOutputStream os = new FileOutputStream(out)) {
            os.write(tree);
            td.update(tree);
            byte[] vb = Arrays.copyOf(vbmeta, (int) vbPadded);
            os.write(vb);
            td.update(vb);
            os.write(footerBlock);
            td.update(footerBlock);
            os.getFD().sync();
        }
        r.tailSha256 = Bytes.hex(td.digest());
        if (out.length() != r.tailSize) throw new IOException("tail size " + out.length() + " != " + r.tailSize);
        return r;
    }

    /** AvbHashtreeDescriptor (tag 1), avbtool encode(). */
    static byte[] hashtreeDescriptor(long imageSize, long treeOffset, long treeSize, byte[] salt, byte[] root) {
        byte[] name = "system".getBytes(StandardCharsets.US_ASCII);
        int nbf = 180 + name.length + salt.length + root.length - 16;
        int nbfPad = (int) Bytes.roundUp(nbf, 8);
        byte[] d = new byte[16 + nbfPad];
        Bytes.put64(d, 0, 1);              // tag
        Bytes.put64(d, 8, nbfPad);         // num_bytes_following
        Bytes.put32(d, 16, 1);             // dm-verity version
        Bytes.put64(d, 20, imageSize);
        Bytes.put64(d, 28, treeOffset);
        Bytes.put64(d, 36, treeSize);
        Bytes.put32(d, 44, BS);            // data block size
        Bytes.put32(d, 48, BS);            // hash block size
        // 52 fec_num_roots, 56 fec_offset, 64 fec_size: 0 (no FEC)
        byte[] alg = "sha256".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(alg, 0, d, 72, alg.length);   // 32-byte field
        Bytes.put32(d, 104, name.length);
        Bytes.put32(d, 108, salt.length);
        Bytes.put32(d, 112, root.length);
        // 116 flags 0, 120..179 reserved
        int p = 180;
        System.arraycopy(name, 0, d, p, name.length); p += name.length;
        System.arraycopy(salt, 0, d, p, salt.length); p += salt.length;
        System.arraycopy(root, 0, d, p, root.length);
        return d;
    }

    /** AvbRSAPublicKeyHeader + modulus + r^2 mod n, avbtool RSAPublicKey.encode(). */
    static byte[] encodeKey(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        if (!BigInteger.valueOf(65537).equals(k.getPublicExponent())) throw new IllegalArgumentException("exponent must be 65537");
        int bits = n.bitLength();
        if (bits != KEY_BITS) throw new IllegalArgumentException("key must be " + KEY_BITS + " bits");
        BigInteger b32 = BigInteger.ONE.shiftLeft(32);
        long n0inv = b32.subtract(n.modInverse(b32)).longValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(bits).pow(2).mod(n);
        byte[] out = new byte[8 + 2 * bits / 8];
        Bytes.put32(out, 0, bits);
        Bytes.put32(out, 4, n0inv);
        be(n, out, 8, bits / 8);
        be(rr, out, 8 + bits / 8, bits / 8);
        return out;
    }

    static void be(BigInteger v, byte[] out, int off, int len) {
        byte[] b = v.toByteArray();
        int src = Math.max(0, b.length - len), cnt = Math.min(b.length, len);
        System.arraycopy(b, src, out, off + len - cnt, cnt);
    }

    static RSAPublicKey decodeKey(byte[] enc) throws GeneralSecurityException {
        int bits = (int) Bytes.u32(enc, 0);
        BigInteger n = new BigInteger(1, Arrays.copyOfRange(enc, 8, 8 + bits / 8));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, BigInteger.valueOf(65537)));
    }

    /** The vbmeta blob (header, authentication block, auxiliary block), avbtool _generate_vbmeta_blob(). */
    static byte[] vbmeta(long imageSize, long treeOffset, long treeSize, byte[] salt, byte[] root, KeyPair key)
            throws GeneralSecurityException {
        byte[] desc = hashtreeDescriptor(imageSize, treeOffset, treeSize, salt, root);
        byte[] pub = encodeKey((RSAPublicKey) key.getPublic());
        int auxSize = (int) Bytes.roundUp(desc.length + pub.length, 64);
        int authSize = (int) Bytes.roundUp(32 + SIG_BYTES, 64);
        byte[] h = new byte[256];
        h[0] = 'A'; h[1] = 'V'; h[2] = 'B'; h[3] = '0';
        Bytes.put32(h, 4, 1);                    // required libavb major
        Bytes.put32(h, 8, 0);                    // minor
        Bytes.put64(h, 12, authSize);
        Bytes.put64(h, 20, auxSize);
        Bytes.put32(h, 28, ALG_SHA256_RSA4096);
        Bytes.put64(h, 32, 0);                   // hash offset
        Bytes.put64(h, 40, 32);                  // hash size
        Bytes.put64(h, 48, 32);                  // signature offset
        Bytes.put64(h, 56, SIG_BYTES);
        Bytes.put64(h, 64, desc.length);         // public key offset
        Bytes.put64(h, 72, pub.length);
        Bytes.put64(h, 80, desc.length + pub.length);   // public key metadata offset
        Bytes.put64(h, 88, 0);
        Bytes.put64(h, 96, 0);                   // descriptors offset
        Bytes.put64(h, 104, desc.length);
        Bytes.put64(h, 112, 0);                  // rollback index
        Bytes.put32(h, 120, 0);                  // flags
        Bytes.put32(h, 124, 0);                  // rollback index location
        byte[] rel = RELEASE.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(rel, 0, h, 128, rel.length);
        byte[] aux = new byte[auxSize];
        System.arraycopy(desc, 0, aux, 0, desc.length);
        System.arraycopy(pub, 0, aux, desc.length, pub.length);

        MessageDigest md = Bytes.sha256();
        md.update(h);
        md.update(aux);
        byte[] hash = md.digest();
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign((PrivateKey) key.getPrivate());
        s.update(h);
        s.update(aux);
        byte[] sig = s.sign();
        if (sig.length != SIG_BYTES) throw new GeneralSecurityException("signature length " + sig.length);
        byte[] auth = new byte[authSize];
        System.arraycopy(hash, 0, auth, 0, 32);
        System.arraycopy(sig, 0, auth, 32, SIG_BYTES);

        byte[] blob = new byte[256 + authSize + auxSize];
        System.arraycopy(h, 0, blob, 0, 256);
        System.arraycopy(auth, 0, blob, 256, authSize);
        System.arraycopy(aux, 0, blob, 256 + authSize, auxSize);
        return blob;
    }

    static byte[] footer(long originalSize, long vbmetaOffset, long vbmetaSize) {
        byte[] f = new byte[FOOTER];
        f[0] = 'A'; f[1] = 'V'; f[2] = 'B'; f[3] = 'f';
        Bytes.put32(f, 4, 1);
        Bytes.put32(f, 8, 0);
        Bytes.put64(f, 12, originalSize);
        Bytes.put64(f, 20, vbmetaOffset);
        Bytes.put64(f, 28, vbmetaSize);
        return f;
    }

    /**
     * Re-read a written tail like libavb would: footer at the end, vbmeta at its offset, the signature over header +
     * aux with the embedded key, the hashtree descriptor's sizes, the root over the top level of the stored tree.
     */
    public static void selfCheck(File tail, long imageSize) throws IOException {
        byte[] t = Bytes.readFile(tail, 64 << 20);
        if (t.length < BS || t.length % BS != 0) throw new IOException("tail size " + t.length);
        int fo = t.length - FOOTER;
        if (t[fo] != 'A' || t[fo + 1] != 'V' || t[fo + 2] != 'B' || t[fo + 3] != 'f') throw new IOException("no AVBf footer");
        long orig = Bytes.u64(t, fo + 12), vbOff = Bytes.u64(t, fo + 20), vbSize = Bytes.u64(t, fo + 28);
        if (orig != imageSize) throw new IOException("footer original size " + orig + " != " + imageSize);
        long rel = vbOff - imageSize;
        if (rel < 0 || rel + vbSize > t.length - BS) throw new IOException("vbmeta out of range");
        byte[] vb = Arrays.copyOfRange(t, (int) rel, (int) (rel + vbSize));
        if (vb[0] != 'A' || vb[1] != 'V' || vb[2] != 'B' || vb[3] != '0') throw new IOException("no AVB0 vbmeta");
        long auth = Bytes.u64(vb, 12), aux = Bytes.u64(vb, 20);
        if (Bytes.u32(vb, 28) != ALG_SHA256_RSA4096 || 256 + auth + aux != vbSize) throw new IOException("vbmeta header");
        int auxStart = (int) (256 + auth);
        long pkOff = Bytes.u64(vb, 64), pkLen = Bytes.u64(vb, 72), dLen = Bytes.u64(vb, 104);
        byte[] pk = Arrays.copyOfRange(vb, (int) (auxStart + pkOff), (int) (auxStart + pkOff + pkLen));
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(decodeKey(pk));
            s.update(vb, 0, 256);
            s.update(vb, auxStart, (int) aux);
            if (!s.verify(Arrays.copyOfRange(vb, 256 + 32, 256 + 32 + SIG_BYTES))) throw new IOException("vbmeta signature");
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
        int d = auxStart;
        if (Bytes.u64(vb, d) != 1 || dLen < 180) throw new IOException("no hashtree descriptor");
        long dImage = Bytes.u64(vb, d + 20), dTreeOff = Bytes.u64(vb, d + 28), dTree = Bytes.u64(vb, d + 36);
        if (dImage != imageSize || dTreeOff != imageSize || dTree != rel) throw new IOException("hashtree descriptor sizes");
        int saltLen = (int) Bytes.u32(vb, d + 108), rootLen = (int) Bytes.u32(vb, d + 112);
        byte[] salt = Arrays.copyOfRange(vb, d + 180 + 6, d + 180 + 6 + saltLen);
        byte[] root = Arrays.copyOfRange(vb, d + 180 + 6 + saltLen, d + 180 + 6 + saltLen + rootLen);
        if (dTree > 0) {
            List<Long> sizes = levelSizes(imageSize);
            long topSize = sizes.get(sizes.size() - 1);
            MessageDigest md = Bytes.sha256();
            md.update(salt);
            md.update(t, 0, (int) topSize);
            if (!Arrays.equals(md.digest(), root)) throw new IOException("root digest does not match the stored tree");
        }
    }
}
