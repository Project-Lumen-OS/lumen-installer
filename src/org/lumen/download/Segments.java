// SPDX-License-Identifier: Apache-2.0
package org.lumen.download;

import java.util.ArrayList;
import java.util.List;

/**
 * The byte ranges of one download that are still missing, shared by the connections of a ParallelDownload. A range
 * belongs to at most one connection at a time. A connection that finds no free range takes the back half of the
 * largest range another connection is still reading, so every connection stays busy to the last byte. Plain Java.
 */
final class Segments {
    /** Missing bytes [pos, end). Only the owner moves pos; a split moves end down to a byte the owner has not reserved. */
    static final class Seg {
        long pos, end;
        long reserved;
        boolean owned;

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

    /** A range for a connection: a free one, else the back half of the largest one in use; null when none is left. */
    synchronized Seg claim() {
        Seg big = null;
        for (Seg s : segs) {
            if (s.pos >= s.end) continue;
            if (!s.owned) {
                s.owned = true;
                return s;
            }
            if (big == null || free(s) > free(big)) big = s;
        }
        if (big == null || free(big) < 2 * minSplit) return null;
        long mid = big.pos + big.reserved + free(big) / 2;
        Seg back = new Seg(mid, big.end);
        back.owned = true;
        big.end = mid;
        segs.add(segs.indexOf(big) + 1, back);
        return back;
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
        s.reserved = 0;
    }

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
