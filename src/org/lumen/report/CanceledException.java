// SPDX-License-Identifier: Apache-2.0
package org.lumen.report;

import java.io.IOException;

/** The user canceled the running report (Back / Cancel); the worker stops and deletes the report files. */
final class CanceledException extends IOException {
    CanceledException() {
        super("canceled");
    }
}
