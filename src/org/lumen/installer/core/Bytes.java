package org.lumen.installer.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small byte helpers. Plain Java 8 APIs only: the app runs on Android 10 to 14 (no readAllBytes, no String.repeat). */
public final class Bytes {
    private Bytes() {}

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd hex");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }

    public static String sha256Hex(byte[] b) { return hex(sha256().digest(b)); }

    public static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) throw new IOException("more than " + max + " bytes");
        }
        return bo.toByteArray();
    }

    public static byte[] readFile(File f, int max) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            return read(in, max);
        }
    }

    public static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    public static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    public static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public static void put32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24); b[off + 1] = (byte) (v >>> 16); b[off + 2] = (byte) (v >>> 8); b[off + 3] = (byte) v;
    }

    public static void put64(byte[] b, int off, long v) {
        put32(b, off, v >>> 32);
        put32(b, off + 4, v);
    }

    public static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16) | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    public static long u64(byte[] b, int off) { return (u32(b, off) << 32) | u32(b, off + 4); }

    public static int le16(byte[] b, int off) { return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8); }

    public static long le32(byte[] b, int off) { return (le16(b, off) & 0xFFFFL) | ((long) le16(b, off + 2) << 16); }

    public static long le64(byte[] b, int off) { return le32(b, off) | (le32(b, off + 4) << 32); }

    public static long roundUp(long v, long m) { return (v + m - 1) / m * m; }

    /** sha256 of a whole file, with progress and a stop check every MiB. */
    public static String sha256File(File f, Progress p, Stop stop) throws IOException {
        MessageDigest md = sha256();
        byte[] buf = new byte[1 << 20];
        long total = f.length(), done = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                done += n;
                if (p != null) p.at(done, total);
                if (stop != null && stop.stopped()) throw new Stopped();
            }
        }
        return hex(md.digest());
    }

    public interface Progress { void at(long done, long total); }

    public interface Stop { boolean stopped(); }

    /** The user pressed Pause / Cancel. */
    public static final class Stopped extends IOException {
        public Stopped() { super("stopped"); }
    }
}
