// SPDX-License-Identifier: Apache-2.0
package org.lumen.download;

/**
 * How many connections a ParallelDownload keeps open: it starts with {@code start} and, after each measured window,
 * adds one while the total speed of the window grew by at least {@code gain} over the window before the last addition,
 * up to {@code max}. Once an added connection brings less, the count stays (the line is full). The window right after
 * a change (TLS handshakes, TCP start), a window in which a connection failed and a window without data decide nothing.
 */
final class ConnectionScaler {
    private final int max;
    private final double gain;
    private int target;
    private double before = -1;
    private boolean settled, warmup = true;

    ConnectionScaler(int start, int max, double gain) {
        this.max = Math.max(1, max);
        this.target = Math.max(1, Math.min(start, this.max));
        this.gain = gain;
    }

    int target() { return target; }

    /**
     * One window with {@link #target()} connections at {@code speed} bytes/s, {@code clean} when no connection failed
     * in it: the count for the next one.
     */
    int onWindow(double speed, boolean clean) {
        if (settled || target >= max) return target;
        if (warmup || !clean || speed <= 0) {
            warmup = false;
            return target;
        }
        if (before < 0 || speed >= before * gain) {
            before = speed;
            target++;
            warmup = true;
        } else {
            settled = true;
        }
        return target;
    }
}
