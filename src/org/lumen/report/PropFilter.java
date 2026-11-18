// SPDX-License-Identifier: Apache-2.0
package org.lumen.report;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The system properties a report may carry (props.txt): build, boot, display, Lumen and model keys only. A key that
 * names a serial, an address, a network name, an account or a secret never goes in, even under an allowed prefix
 * (DENY wins). denied() is public for Lumen Installer's stock system info, which keeps every other key. Plain Java.
 */
public final class PropFilter {
    private static final String[] ALLOW = {
            "ro.build.", "ro.system.build.", "ro.vendor.build.", "ro.product.build.", "ro.product.model",
            "ro.product.brand", "ro.product.device", "ro.product.name", "ro.product.manufacturer", "ro.product.cpu.",
            "ro.product.locale", "ro.hardware", "ro.board.platform", "ro.boot.slot_suffix", "ro.boot.bootreason",
            "ro.boot.verifiedbootstate", "ro.boot.vbmeta.device_state", "ro.boot.flash.locked", "ro.boot.hardware",
            "ro.boot.mode", "ro.boot.xgimi.modelname", "ro.gsid.image_running", "sys.boot.reason",
            "persist.sys.boot.reason", "sys.boot_completed", "dev.bootcomplete", "ro.boottime.", "init.svc.",
            "ro.lineage.", "ro.lumen.", "persist.lumen.", "sys.lumen.", "ro.z9x.", "persist.z9x.", "sys.z9x.",
            "vendor.xgimi.ledOn", "ro.vendor.xgimi.watchdog", "ro.sf.", "ro.surface_flinger.", "debug.sf.",
            "persist.sys.sf.", "persist.sys.display", "vendor.display.", "persist.vendor.display.", "ro.vendor.display.",
            "ro.hdmi.", "persist.sys.hdmi.", "ro.lmk.", "dalvik.vm.heap", "ro.config.", "ro.treble.", "ro.debuggable",
            "ro.secure", "ro.adb.secure", "persist.sys.usb.config", "sys.usb.state", "sys.usb.config", "ro.logd.",
            "persist.logd.", "logd.", "ro.crypto.state", "ro.crypto.type", "ro.opengles.version", "ro.hwui.",
            "debug.hwui.renderer", "persist.sys.locale", "vendor.display-size", "vendor.mstar.resize.framebuffer",
            "vendor.mstar.osd_size", "vendor.mtk.gop.fb.",
    };
    private static final Pattern DENY = Pattern.compile(
            "(?i)(serial|(^|[._])sn($|[._])|mac|imei|meid|iccid|msisdn|ssid|hostname|device_?name|bt_?name|bdaddr|uuid"
            + "|android_?id|gsf|account|e_?mail|user_?name|owner|password|token|secret|ip_?addr|dns|gateway|wifi)");
    private static final Pattern LINE = Pattern.compile("^\\[([^\\]]+)\\]: \\[(.*)\\]$");

    private PropFilter() {}

    /** True when a key (a property or a setting) names a serial, an address, a network, an account or a secret. */
    public static boolean denied(String key) {
        return key == null || DENY.matcher(key).find();
    }

    static boolean allowed(String key) {
        if (key == null || DENY.matcher(key).find()) return false;
        for (String a : ALLOW) if (key.startsWith(a)) return true;
        return false;
    }

    /** True when a getprop output line ("[key]: [value]") may go into props.txt. */
    static boolean keep(String line) {
        Matcher m = LINE.matcher(line);
        return m.find() && allowed(m.group(1));
    }
}
