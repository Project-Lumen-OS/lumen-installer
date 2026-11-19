// SPDX-License-Identifier: Apache-2.0
package org.lumen.download;

import java.util.ArrayDeque;

/** Download speed over the last few seconds (what the screen shows and the time left is computed from). */
final class Rate {
    private final long windowMs;
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();

    Rate(long windowMs) { this.windowMs = windowMs; }

    /** @return bytes per second over the window, 0 until a second of samples is in */
    long add(long nowMs, long bytes) {
        samples.addLast(new long[] {nowMs, bytes});
        while (samples.size() > 2 && nowMs - samples.peekFirst()[0] > windowMs) samples.removeFirst();
        long[] first = samples.peekFirst();
        long dt = nowMs - first[0];
        return dt < 1000 ? 0 : Math.max(0, (bytes - first[1]) * 1000 / dt);
    }

}
