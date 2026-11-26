// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.report;

/** Why a report was made: the user's press, or an incident the projector noticed by itself. DUMP: the stock system
 *  info Lumen Installer sends (PartUpload), never queued by the projector apps. */
public enum ReportKind {
    MANUAL("manual"), CRASH("crash"), ANR("anr"), BOOT("boot"), DUMP("dump");

    /** The value of the X-Lumen-Kind header and of the queue files. */
    public final String wire;

    ReportKind(String wire) {
        this.wire = wire;
    }

    public boolean automatic() {
        return this == CRASH || this == ANR || this == BOOT;
    }

    /** The kind of a wire value, or null. */
    public static ReportKind of(String wire) {
        for (ReportKind k : values()) if (k.wire.equals(wire)) return k;
        return null;
    }
}
