// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.download;

import java.io.IOException;

/** An HTTP answer other than the one asked for, with its status code (404 and 410: the file is gone). */
public final class HttpError extends IOException {
    public final int code;

    public HttpError(int code, String msg) {
        super(msg);
        this.code = code;
    }
}
