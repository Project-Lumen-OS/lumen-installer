// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.report;

/** Reasons the screen can explain; NETWORK covers DNS, connect, TLS and timeouts. */
enum ReportFailure {
    NO_NETWORK, NETWORK, RATE_LIMITED, REJECTED, SERVER, COLLECT;

    /** True when a later try may succeed: the report waits in the queue. False: the server will never take it. */
    boolean retry() {
        return this == NO_NETWORK || this == NETWORK || this == RATE_LIMITED || this == SERVER;
    }
}
