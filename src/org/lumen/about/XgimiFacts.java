// SPDX-License-Identifier: Apache-2.0
package org.lumen.about;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the projector says about its XGIMI side, read only: the model code the boot loader sets (the stock config
 * folder of this unit), the XGIMI firmware under Lumen OS (the stock vendor, the version the unit came from) and the
 * name of the USB stick file the stock boot loader looks for (system.ini UpdateBinName of the model's config), the
 * DLP controller and FPGA its config names (LightEngine) and the versions of the other firmware parts (FirmwareParts).
 */
public final class XgimiFacts {
    public static final String MODEL_PROP = "ro.boot.xgimi.modelname";
    /** what older stock builds and the trial carry when the boot property is missing */
    public static final String MODEL_PROP_OLD = "ro.xgimi.modelname";
    public static final String FIRMWARE_PROP = "ro.vendor.build.version.incremental";
    /** the same version as the system names it: XGIMI's own only while the XGIMI system runs */
    static final String SYSTEM_FIRMWARE_PROP = "ro.build.version.incremental";
    /** set by Lumen OS (and by its older builds), and while a trial runs: the system is not XGIMI's then */
    static final String[] LUMEN_PROPS = {"ro.lumen.version", "ro.z9x.version"};
    static final String TRIAL_PROP = "ro.gsid.image_running";
    /** the MediaTek platform release of the XGIMI vendor, a world readable file */
    public static final String PLATFORM_FILE = "/vendor/etc/version.txt";
    public static final String CONFIG_ROOT = "/mnt/vendor/xgimiconfig/";
    public static final String SYSTEM_INI = "system.ini";
    /** the light engine files of the model's folder: the DLP controller and FPGA types, the controller's flash image */
    public static final String DLP_INI = "/dlp/dlp.ini", DLP_FLASH_INI = "/dlp/DlpFlashImageInfo.ini";
    static final String BIN_KEY = "UpdateBinName";

    static final Pattern CODE = Pattern.compile("[A-Z][0-9]{4}");
    static final Pattern VERSION = Pattern.compile("[vV]?([0-9]{1,4}(?:\\.[0-9]{1,4}){1,4})");
    static final Pattern BIN = Pattern.compile("XGIMI_[A-Z0-9_]{1,48}\\.bin");
    private static final Pattern BIN_LINE = Pattern.compile("\\s*" + BIN_KEY + "\\s*=\\s*\"?([^\";\\s]*)\"?\\s*;?\\s*");

    /** the model code (one letter and four digits), "" when the unit names none */
    public final String model;
    /** the XGIMI firmware as the catalog writes it (v6.15.62), "" when unknown */
    public final String firmware;
    /** the stick file name of the model's own config, "" when it cannot be read */
    public final String usbFile;
    /** the DLP controller and FPGA of the model's own config, empty fields when unknown */
    public final LightEngine engine;
    /** the versions of the other firmware parts, empty fields when unknown */
    public final FirmwareParts parts;

    XgimiFacts(String model, String firmware, String usbFile) {
        this(model, firmware, usbFile, LightEngine.NONE, FirmwareParts.NONE);
    }

    XgimiFacts(String model, String firmware, String usbFile, LightEngine engine, FirmwareParts parts) {
        this.model = model;
        this.firmware = firmware;
        this.usbFile = usbFile;
        this.engine = engine;
        this.parts = parts;
    }

    /**
     * Reads the facts: props gives a property ("" when unset), file the text of a file (null when it cannot be read).
     */
    public static XgimiFacts read(Function<String, String> props, Function<String, String> file) {
        return read(props, file, "");
    }

    /** The same with the kernel release (uname) of the running system. */
    public static XgimiFacts read(Function<String, String> props, Function<String, String> file, String kernel) {
        String m = model(props);
        boolean xgimi = xgimiSystem(props);
        String fw = firmware(props.apply(FIRMWARE_PROP));
        // an ordinary app on an older XGIMI system may not see the vendor's property: the system names the same build
        if (fw.isEmpty() && xgimi) fw = firmware(props.apply(SYSTEM_FIRMWARE_PROP));
        if (m.isEmpty()) return new XgimiFacts(m, fw, "");
        LightEngine le = LightEngine.parse(file.apply(CONFIG_ROOT + m + DLP_INI), file.apply(CONFIG_ROOT + m + DLP_FLASH_INI));
        String ini = file.apply(CONFIG_ROOT + m + "/" + SYSTEM_INI);
        FirmwareParts parts = FirmwareParts.parse(props, file.apply(PLATFORM_FILE), kernel, xgimi);
        return new XgimiFacts(m, fw, ini == null ? "" : updateBinName(ini), le, parts);
    }

    /** the XGIMI system itself runs: no Lumen OS, no trial */
    static boolean xgimiSystem(Function<String, String> props) {
        for (String p : LUMEN_PROPS) if (!trim(props.apply(p)).isEmpty()) return false;
        return !"1".equals(trim(props.apply(TRIAL_PROP)));
    }

    static String model(Function<String, String> props) {
        for (String p : new String[] {MODEL_PROP, MODEL_PROP_OLD}) {
            String v = trim(props.apply(p));
            if (CODE.matcher(v).matches()) return v;
        }
        return "";
    }

    /** "v6.15.62" for V6.15.62, v6.15.62 or 6.15.62; "" for anything else */
    static String firmware(String raw) {
        Matcher m = VERSION.matcher(trim(raw));
        return m.matches() ? "v" + m.group(1) : "";
    }

    /** the UpdateBinName value of a system.ini text, "" when it has none or an odd one */
    static String updateBinName(String ini) {
        for (String line : ini.split("\r?\n|\r")) {
            int hash = line.indexOf('#');
            Matcher m = BIN_LINE.matcher(hash < 0 ? line : line.substring(0, hash));
            if (m.matches()) return BIN.matcher(m.group(1)).matches() ? m.group(1) : "";
        }
        return "";
    }

    /** the firmware as XGIMI names its versions (V6.15.62), "" when unknown */
    public String firmwareShown() {
        return shown(firmware);
    }

    /** a catalog version (v6.15.19) as XGIMI shows it (V6.15.19) */
    public static String shown(String version) {
        return version.isEmpty() ? "" : "V" + version.substring(1).toUpperCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
