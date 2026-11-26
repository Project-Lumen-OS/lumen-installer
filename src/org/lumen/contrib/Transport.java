// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.contrib;

import java.io.IOException;
import java.util.Map;

/** The two calls the client makes. {@link HttpTransport} on a unit, a fake in the tests. */
public interface Transport {
    Reply get(String url) throws IOException;

    Reply post(String url, byte[] body, Map<String, String> headers) throws IOException;
}
