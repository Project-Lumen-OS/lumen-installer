// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.about;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The versions of the projector's firmware parts that any app may read, on the XGIMI system as an ordinary app
 * (Lumen Installer) and on Lumen OS (the About page): the date of the XGIMI firmware, the Android and kernel of the
 * running system, the boot loader, XGIMI's interface (GMUI), the MediaTek platform release, the remote paired last
 * and the Wi-Fi firmware. Each value is checked against the form the stock firmware writes it in: a placeholder or an
 * odd value gives "" and the row stays hidden. XgimiFacts reads the inputs (properties, the platform file, the kernel).
 */
public final class FirmwareParts {
    static final FirmwareParts NONE = new FirmwareParts("", "", "", "", "", "", "", "", "", "", "");
    /** the build date the XGIMI vendor carries; the system one only while the XGIMI system runs */
    static final String DATE_PROP = "ro.vendor.build.date", SYSTEM_DATE_PROP = "ro.build.date";
    static final String ANDROID_PROP = "ro.build.version.release", PATCH_PROP = "ro.build.version.security_patch",
            BOOT_LOADER_PROP = "ro.bootloader", GMUI_PROP = "ro.xgimi.gmui.version",
            REMOTE_PROP = "persist.xgimi.rc.version", WIFI_PROP = "vendor.wlan.firmware.version";

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    /** what `date` prints in the build: Wed Mar 25 15:55:23 HKT 2026 */
    private static final Pattern DATE = Pattern.compile("[A-Z][a-z]{2} ([A-Z][a-z]{2}) {1,2}([0-9]{1,2}) [0-9:]{8} [A-Za-z+0-9]{1,6} ([0-9]{4})");
    private static final Pattern ANDROID = Pattern.compile("[0-9]{1,2}(?:\\.[0-9]{1,2}){0,2}");
    private static final Pattern DAY = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern KERNEL = Pattern.compile("([0-9]{1,2}\\.[0-9]{1,3}(?:\\.[0-9]{1,4})?)(?:[-+.].*)?");
    /** the MediaTek boot loader's own version (01.01.260325); the 123456789 and 20170621 placeholders do not match */
    private static final Pattern BOOT_LOADER = Pattern.compile("[0-9]{2}\\.[0-9]{2}\\.[0-9]{6}");
    private static final Pattern GMUI = Pattern.compile("(?:[a-z]{2,12}_)?([0-9]{1,3}(?:\\.[0-9]{1,4}){1,4})");
    private static final Pattern PLATFORM = Pattern.compile("(?m)^\\s*export\\s+VERSION\\s*:=\\s*([a-z0-9][a-z0-9._-]{4,79})\\s*$");
    /** the remote as XGIMI's Bluetooth service stores it: model, then its firmware (B940D_CY06_CY_V1.0.3) */
    private static final Pattern REMOTE = Pattern.compile("([A-Z0-9]{3,8})_[A-Z0-9_]{0,24}_V([0-9]{1,3}(?:\\.[0-9]{1,4}){1,3})");
    /** the MediaTek Wi-Fi firmware: its chip and the build stamp at the end (..._MT7920_..._AT-20260205002547) */
    private static final Pattern WIFI = Pattern.compile("[A-Za-z0-9_.-]{0,80}?\\b(MT[0-9]{4})_[A-Za-z0-9_.-]{0,80}-([0-9]{8,14})");

    /** the XGIMI firmware's build date (2026-03-25), "" when unknown */
    public final String date;
    /** the running system's Android version (14) and security update (2025-07-05) */
    public final String android, patch;
    /** the kernel version (5.15.167) */
    public final String kernel;
    public final String bootLoader;
    /** XGIMI's interface version (6.17.200.03) */
    public final String gmui;
    /** the MediaTek platform release the XGIMI vendor is built on */
    public final String platform;
    /** the remote paired last (B940D) and its firmware (1.0.3) */
    public final String remote, remoteFirmware;
    /** the Wi-Fi chip (MT7920) and its firmware build (20260205002547) */
    public final String wifi, wifiFirmware;

    FirmwareParts(String date, String android, String patch, String kernel, String bootLoader, String gmui, String platform,
            String remote, String remoteFirmware, String wifi, String wifiFirmware) {
        this.date = date;
        this.android = android;
        this.patch = patch;
        this.kernel = kernel;
        this.bootLoader = bootLoader;
        this.gmui = gmui;
        this.platform = platform;
        this.remote = remote;
        this.remoteFirmware = remoteFirmware;
        this.wifi = wifi;
        this.wifiFirmware = wifiFirmware;
    }

    /**
     * props gives a property ("" when unset or not readable), platformFile the text of the platform release file
     * (null when it cannot be read), kernel the kernel release (uname), xgimiSystem whether the XGIMI system itself
     * runs (then its system build date stands in for the vendor one an older policy may hide).
     */
    static FirmwareParts parse(Function<String, String> props, String platformFile, String kernel, boolean xgimiSystem) {
        String date = date(props.apply(DATE_PROP));
        if (date.isEmpty() && xgimiSystem) date = date(props.apply(SYSTEM_DATE_PROP));
        String android = whole(ANDROID, props.apply(ANDROID_PROP));
        String[] remote = two(REMOTE, props.apply(REMOTE_PROP));
        String[] wifi = two(WIFI, props.apply(WIFI_PROP));
        String platform = "";
        if (platformFile != null) {
            Matcher m = PLATFORM.matcher(platformFile);
            if (m.find()) platform = m.group(1);
        }
        return new FirmwareParts(date, android, android.isEmpty() ? "" : whole(DAY, props.apply(PATCH_PROP)),
                group(KERNEL, kernel), whole(BOOT_LOADER, props.apply(BOOT_LOADER_PROP)), group(GMUI, props.apply(GMUI_PROP)),
                platform, remote[0], remote[1], wifi[0], wifi[1]);
    }

    /** "2026-03-25" for "Wed Mar 25 15:55:23 HKT 2026", "" for anything else */
    static String date(String raw) {
        Matcher m = DATE.matcher(trim(raw));
        if (!m.matches()) return "";
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(m.group(1))) {
                int day = Integer.parseInt(m.group(2));
                return day < 1 || day > 31 ? "" : String.format(Locale.ROOT, "%s-%02d-%02d", m.group(3), i + 1, day);
            }
        }
        return "";
    }

    private static String whole(Pattern p, String raw) {
        String v = trim(raw);
        return p.matcher(v).matches() ? v : "";
    }

    private static String group(Pattern p, String raw) {
        Matcher m = p.matcher(trim(raw));
        return m.matches() ? m.group(1) : "";
    }

    private static String[] two(Pattern p, String raw) {
        Matcher m = p.matcher(trim(raw));
        return m.matches() ? new String[] {m.group(1), m.group(2)} : new String[] {"", ""};
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
