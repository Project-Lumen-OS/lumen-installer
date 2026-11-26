// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.contrib;

/** An HTTP answer: status and the (small) body. */
public final class Reply {
    public final int status;
    public final String body;

    public Reply(int status, String body) {
        this.status = status;
        this.body = body;
    }
}
