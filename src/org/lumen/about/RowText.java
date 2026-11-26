// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.about;

/**
 * The texts of the About rows; each value X is the string lumen_about_x of the app (AboutProvider maps it, Lumen
 * Installer looks it up by that name).
 */
public enum RowText {
    MODEL_CODE,
    FIRMWARE,
    /** the firmware with its build date: two placeholders, the version and the date */
    FIRMWARE_DATED,
    ANDROID,
    /** the Android version with its security update: two placeholders, the version and the date */
    ANDROID_PATCH,
    KERNEL,
    BOOT_LOADER,
    /** XGIMI's interface (GMUI) */
    GMUI,
    /** the MediaTek platform release */
    PLATFORM,
    /** the DLP controller row (TI DLPC8445) */
    DLP,
    FPGA,
    REMOTE,
    WIFI,
    /** a part with its firmware: two placeholders, the part and the version */
    PART_FIRMWARE,
    BACK,
    /** the Back row's summary when the stick file name is known: one placeholder, the name */
    BACK_SUMMARY_FILE,
    BACK_SUMMARY
}
