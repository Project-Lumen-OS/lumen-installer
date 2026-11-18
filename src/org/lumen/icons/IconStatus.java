// SPDX-License-Identifier: Apache-2.0
package org.lumen.icons;

/** The state of a step or a check, drawn by {@link Icons#status}. */
public enum IconStatus {
    /** Not started: an empty circle. */
    PENDING,
    /** Running now: a turning arc. */
    RUNNING,
    /** Passed: a filled circle with a tick. */
    DONE,
    /** Failed: a filled circle with a cross. */
    FAILED,
    /** Passed with something to know: a warning sign. */
    WARN,
    /** Only a note: an info sign. */
    INFO
}
