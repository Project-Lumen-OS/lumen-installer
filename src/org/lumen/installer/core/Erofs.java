package org.lumen.installer.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Read-only path lookup in an erofs image (the Lumen system images are erofs), enough to tell whether a release can
 * install itself from the trial: it carries the installer app and the init file of Lumen OS's own installer (the two
 * paths in HANDOVER below). Only directories are read
 * (layouts FLAT_PLAIN and FLAT_INLINE, as mkfs.erofs writes them); file contents are never decompressed.
 * Host test: lookups agree with dump.erofs on a mkfs.erofs image and on the real 1.0.9 image.
 */
public final class Erofs implements AutoCloseable {
    static final long MAGIC = 0xE0F5E1E2L;
    private final RandomAccessFile f;
    private final int blkBits, blk;
    private final long metaBlk, rootNid;

    public Erofs(File img) throws IOException {
        f = new RandomAccessFile(img, "r");
        byte[] sb = read(1024, 128);
        if (Bytes.le32(sb, 0) != MAGIC) { f.close(); throw new IOException("not erofs"); }
        blkBits = sb[12] & 0xFF;
        if (blkBits < 9 || blkBits > 16) { f.close(); throw new IOException("erofs block size bits " + blkBits); }
        blk = 1 << blkBits;
        rootNid = Bytes.le16(sb, 14);
        metaBlk = Bytes.le32(sb, 40);
    }

    public static boolean isErofs(File img) {
        try (RandomAccessFile r = new RandomAccessFile(img, "r")) {
            if (r.length() < 1028) return false;
            r.seek(1024);
            byte[] b = new byte[4];
            r.readFully(b);
            return Bytes.le32(b, 0) == MAGIC;
        } catch (IOException e) {
            return false;
        }
    }

    @Override public void close() throws IOException { f.close(); }

    private byte[] read(long off, int len) throws IOException {
        if (off < 0 || len < 0 || off + len > f.length()) throw new IOException("erofs read out of range");
        byte[] b = new byte[len];
        f.seek(off);
        f.readFully(b);
        return b;
    }

    static final class Inode {
        int layout, mode;
        long size, blkaddr, base;
        int inodeSize, xattrSize;
        boolean isDir() { return (mode & 0xF000) == 0x4000; }
    }

    Inode inode(long nid) throws IOException {
        long base = metaBlk * blk + nid * 32;
        byte[] h = read(base, 32);
        Inode n = new Inode();
        int fmt = Bytes.le16(h, 0);
        boolean ext = (fmt & 1) == 1;
        n.layout = (fmt >> 1) & 7;
        int xcount = Bytes.le16(h, 2);
        n.mode = Bytes.le16(h, 4);
        if (ext) {
            byte[] e = read(base, 64);
            n.size = Bytes.le64(e, 8);
            n.blkaddr = Bytes.le32(e, 16);
            n.inodeSize = 64;
        } else {
            n.size = Bytes.le32(h, 8);
            n.blkaddr = Bytes.le32(h, 16);
            n.inodeSize = 32;
        }
        n.xattrSize = xcount == 0 ? 0 : 12 + (xcount - 1) * 4;
        n.base = base;
        return n;
    }

    /** The bytes of a directory's i-th block (or of its inline tail). */
    private byte[] dirBlock(Inode d, long i) throws IOException {
        long nblocks = (d.size + blk - 1) / blk;
        if (i >= nblocks) throw new IOException("dir block out of range");
        int len = (int) Math.min(blk, d.size - i * blk);
        if (d.layout == 0) return read((d.blkaddr + i) * blk, len);                 // FLAT_PLAIN
        if (d.layout == 2) {                                                          // FLAT_INLINE
            long full = d.size / blk;
            if (i < full) return read((d.blkaddr + i) * blk, blk);
            return read(d.base + d.inodeSize + d.xattrSize, len);
        }
        throw new IOException("directory layout " + d.layout + " not supported");
    }

    /** nid of name inside directory d, or -1. */
    long find(Inode d, String name) throws IOException {
        if (!d.isDir()) return -1;
        long nblocks = (d.size + blk - 1) / blk;
        for (long i = 0; i < nblocks; i++) {
            byte[] b = dirBlock(d, i);
            if (b.length < 12) continue;
            int first = Bytes.le16(b, 8);
            int count = first / 12;
            if (count <= 0 || first > b.length) throw new IOException("bad dirent block");
            for (int k = 0; k < count; k++) {
                int off = k * 12;
                int nameOff = Bytes.le16(b, off + 8);
                int end = k + 1 < count ? Bytes.le16(b, off + 12 + 8) : b.length;
                if (nameOff > end || end > b.length) throw new IOException("bad dirent name");
                int e = nameOff;
                while (e < end && b[e] != 0) e++;
                String n = new String(b, nameOff, e - nameOff, StandardCharsets.UTF_8);
                if (n.equals(name)) return Bytes.le64(b, off);
            }
        }
        return -1;
    }

    /** True when the absolute path exists in the image. */
    public boolean exists(String path) throws IOException {
        Inode cur = inode(rootNid);
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            long nid = find(cur, part);
            if (nid < 0) return false;
            cur = inode(nid);
        }
        return true;
    }

    public static final String[] HANDOVER = {
        "/system/priv-app/LumenDsuInstall/LumenDsuInstall.apk",
        "/system/etc/init/lumen_dsu_install.rc",
    };

    /** 1 = the image can install itself from the trial, 0 = it cannot, -1 = cannot tell (not erofs, odd layout). */
    public static int handover(File img) {
        if (!isErofs(img)) return -1;
        try (Erofs e = new Erofs(img)) {
            if (!e.exists("/system/build.prop")) return -1;   // not a system-as-root image we understand
            for (String p : HANDOVER) if (!e.exists(p)) return 0;
            return 1;
        } catch (IOException ex) {
            return -1;
        }
    }
}
