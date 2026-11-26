// SPDX-License-Identifier: Apache-2.0
package org.lumen.download;

import java.util.ArrayList;
import java.util.List;

/**
 * The byte ranges of one download that are still missing, shared by the connections of a ParallelDownload. A range
 * belongs to at most one connection at a time. A connection that finds no free range takes the back half of the
 * largest range another connection is still reading, so every connection stays busy to the last byte. The last ranges
 * are too small to halve: a connection that is at least twice as fast then takes over the whole unread rest of the one
 * that would end last, so one slow connection never holds the end of the file. Plain Java.
 */
final class Segments {
    /** A connection this new on its range has no fair speed yet (TLS, the first bytes). */
    static final long GRACE_MS = 2000;
    /** A rest its own connection reads in less than this stays with it: a new request costs about as much. */
    static final long STEAL_MS = 2000;

    /** Missing bytes [pos, end). Only the owner moves pos; a split moves end down to a byte the owner has not reserved. */
    static final class Seg {
        long pos, end;
        long reserved;
        boolean owned;
        /** When its connection took it and the bytes that connection wrote since: its speed. */
        long since, got;

        Seg(long pos, long end) {
            this.pos = pos;
            this.end = end;
        }
    }

    final long total;
    private final long minSplit;
    private final List<Seg> segs = new ArrayList<>();

    private Segments(long total, long minSplit) {
        this.total = total;
        this.minSplit = Math.max(1, minSplit);
    }

    /** [0, total) in n equal ranges, fewer when a range would be shorter than minSplit. */
    static Segments plan(long total, int n, long minSplit) {
        Segments s = new Segments(total, minSplit);
        int parts = (int) Math.max(1, Math.min(n, total / s.minSplit));
        long at = 0;
        for (int i = 1; i <= parts; i++) {
            long end = i == parts ? total : total * i / parts;
            s.segs.add(new Seg(at, end));
            at = end;
        }
        return s;
    }

    /** The missing ranges a saved download left, each {pos, end}; null when they do not fit in [0, total). */
    static Segments of(long total, long minSplit, List<long[]> missing) {
        Segments s = new Segments(total, minSplit);
        long last = 0;
        for (long[] r : missing) {
            if (r.length != 2 || r[0] < last || r[1] <= r[0] || r[1] > total) return null;
            s.segs.add(new Seg(r[0], r[1]));
            last = r[1];
        }
        return s;
    }

    /** A range for a connection whose speed is not known yet: never the rest of a slow one. */
    synchronized Seg claim() { return claim(0, 0); }

    /**
     * A range for a connection reading bps bytes/s at nowMs: a free one, else the back half of the largest one in use,
     * else the whole unread rest of the one that would end last when its connection is less than half as fast; null
     * when none is left.
     */
    synchronized Seg claim(long nowMs, long bps) {
        Seg big = null;
        for (Seg s : segs) {
            if (s.pos >= s.end) continue;
            if (!s.owned) {
                take(s, nowMs);
                return s;
            }
            if (big == null || free(s) > free(big)) big = s;
        }
        if (big == null) return null;
        if (free(big) >= 2 * minSplit) return cut(big, big.pos + big.reserved + free(big) / 2, nowMs);
        Seg slow = slowest(nowMs, bps);
        return slow == null ? null : cut(slow, slow.pos + slow.reserved, nowMs);
    }

    /** The back of s from byte at on, for a new connection; s keeps what its connection reserved before at. */
    private Seg cut(Seg s, long at, long nowMs) {
        Seg back = new Seg(at, s.end);
        take(back, nowMs);
        s.end = at;
        segs.add(segs.indexOf(s) + 1, back);
        return back;
    }

    private static void take(Seg s, long nowMs) {
        s.owned = true;
        s.since = nowMs;
        s.got = 0;
    }

    /** The range in use that ends last at its own connection's speed, when that is under half of bps and it ends late. */
    private Seg slowest(long nowMs, long bps) {
        Seg worst = null;
        double late = STEAL_MS;
        for (Seg s : segs) {
            long age = nowMs - s.since;
            if (!s.owned || free(s) <= 0 || age < GRACE_MS) continue;
            double own = s.got * 1000.0 / age;
            if (own * 2 >= bps) continue;
            double left = free(s) * 1000.0 / Math.max(1, own);
            if (left > late) {
                worst = s;
                late = left;
            }
        }
        return worst;
    }

    /** Another connection would get work now (a free range, or one large enough to split). */
    synchronized boolean hasWork() {
        for (Seg s : segs) {
            if (s.pos < s.end && (!s.owned || free(s) >= 2 * minSplit)) return true;
        }
        return false;
    }

    private static long free(Seg s) { return s.end - s.pos - s.reserved; }

    synchronized void release(Seg s) {
        s.owned = false;
        s.reserved = 0;
        segs.removeIf(x -> x.pos >= x.end && !x.owned);
    }

    /** How many of n bytes just read may be written at s.pos: fewer once a split cut the range, 0 when it is done. */
    synchronized long reserve(Seg s, long n) {
        s.reserved = Math.max(0, Math.min(n, s.end - s.pos));
        return s.reserved;
    }

    /** The k reserved bytes are written. */
    synchronized void commit(Seg s, long k) {
        s.pos += k;
        s.got += k;
        s.reserved = 0;
    }

    /** Bytes the connection of s wrote into it since it took it. */
    synchronized long got(Seg s) { return s.got; }

    synchronized long left(Seg s) { return s.end - s.pos; }

    /** Bytes already written. */
    synchronized long done() {
        long left = 0;
        for (Seg s : segs) left += Math.max(0, s.end - s.pos);
        return total - left;
    }

    synchronized boolean complete() { return done() == total; }

    /** The missing ranges in file order, each {pos, end}: what the state file keeps. */
    synchronized List<long[]> missing() {
        List<long[]> out = new ArrayList<>();
        for (Seg s : segs) if (s.pos < s.end) out.add(new long[] {s.pos, s.end});
        return out;
    }
}
