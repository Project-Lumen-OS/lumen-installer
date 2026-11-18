// SPDX-License-Identifier: Apache-2.0
package org.lumen.about;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * The rows of the projector's XGIMI side, one list for two screens: the system Settings About page of Lumen OS (right
 * after Model: the model code, the firmware versions, the row that opens Back to the XGIMI system) and the card
 * Firmware versions of Lumen Installer on the XGIMI system (the firmware versions with the running system's Android
 * and kernel, which the About page lists on its own). A unit without a model code is no XGIMI projector: no rows at
 * all. A version the unit does not name, or names in an odd form, has no row.
 */
public final class AboutRows {
    public static final String KEY_MODEL = "lumen_model_code", KEY_FIRMWARE = "lumen_xgimi_firmware",
            KEY_ANDROID = "lumen_android", KEY_KERNEL = "lumen_kernel", KEY_BOOT_LOADER = "lumen_boot_loader",
            KEY_GMUI = "lumen_gmui", KEY_PLATFORM = "lumen_platform", KEY_DLP = "lumen_dlp_controller",
            KEY_FPGA = "lumen_fpga", KEY_REMOTE = "lumen_remote", KEY_WIFI = "lumen_wifi", KEY_BACK = "lumen_back_to_xgimi";

    private AboutRows() {}

    /** The About page; texts gives each RowText in the projector's language (formats with %1$s placeholders). */
    public static List<AboutRow> of(XgimiFacts f, Function<RowText, String> texts) {
        if (f.model.isEmpty()) return Collections.emptyList();
        List<AboutRow> rows = new ArrayList<>();
        rows.add(new AboutRow(KEY_MODEL, texts.apply(RowText.MODEL_CODE), f.model, false));
        rows.addAll(versions(f, texts, false));
        String back = f.usbFile.isEmpty() ? texts.apply(RowText.BACK_SUMMARY)
                : String.format(texts.apply(RowText.BACK_SUMMARY_FILE), f.usbFile);
        rows.add(new AboutRow(KEY_BACK, texts.apply(RowText.BACK), back, true));
        return rows;
    }

    /**
     * The firmware versions, in one order on both screens. system: with the Android version and the kernel of the
     * running system (Lumen Installer; the About page shows its own rows for them).
     */
    public static List<AboutRow> versions(XgimiFacts f, Function<RowText, String> texts, boolean system) {
        if (f.model.isEmpty()) return Collections.emptyList();
        List<AboutRow> rows = new ArrayList<>();
        FirmwareParts p = f.parts;
        if (!f.firmware.isEmpty()) {
            rows.add(row(KEY_FIRMWARE, texts.apply(RowText.FIRMWARE), p.date.isEmpty() ? f.firmwareShown()
                    : String.format(texts.apply(RowText.FIRMWARE_DATED), f.firmwareShown(), p.date)));
        }
        if (system && !p.android.isEmpty()) {
            rows.add(row(KEY_ANDROID, texts.apply(RowText.ANDROID), p.patch.isEmpty() ? p.android
                    : String.format(texts.apply(RowText.ANDROID_PATCH), p.android, p.patch)));
        }
        if (system && !p.kernel.isEmpty()) rows.add(row(KEY_KERNEL, texts.apply(RowText.KERNEL), p.kernel));
        if (!p.bootLoader.isEmpty()) rows.add(row(KEY_BOOT_LOADER, texts.apply(RowText.BOOT_LOADER), p.bootLoader));
        if (!p.gmui.isEmpty()) rows.add(row(KEY_GMUI, texts.apply(RowText.GMUI), "GMUI " + p.gmui));
        if (!p.platform.isEmpty()) rows.add(row(KEY_PLATFORM, texts.apply(RowText.PLATFORM), p.platform));
        LightEngine le = f.engine;
        if (!le.controller.isEmpty()) rows.add(row(KEY_DLP, texts.apply(RowText.DLP), part(texts, le.controller, le.firmware)));
        if (!le.fpga.isEmpty()) rows.add(row(KEY_FPGA, texts.apply(RowText.FPGA), le.fpga));
        if (!p.remote.isEmpty()) rows.add(row(KEY_REMOTE, texts.apply(RowText.REMOTE), part(texts, p.remote, p.remoteFirmware)));
        if (!p.wifi.isEmpty()) rows.add(row(KEY_WIFI, texts.apply(RowText.WIFI), part(texts, p.wifi, p.wifiFirmware)));
        return rows;
    }

    /** a chip or device with its firmware (TI DLPC8445, firmware 9.4.128), the name alone when the firmware is unknown */
    private static String part(Function<RowText, String> texts, String name, String firmware) {
        return firmware.isEmpty() ? name : String.format(texts.apply(RowText.PART_FIRMWARE), name, firmware);
    }

    private static AboutRow row(String key, String title, String summary) {
        return new AboutRow(key, title, summary, false);
    }
}
