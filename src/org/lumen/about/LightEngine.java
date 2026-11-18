// SPDX-License-Identifier: Apache-2.0
package org.lumen.about;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The DLP controller and the FPGA of the projector as its own XGIMI config names them (dlp/dlp.ini and
 * dlp/DlpFlashImageInfo.ini of the model's folder, XgimiFacts reads them), read only. The numbers in dlp.ini are
 * XGIMI's enums, and the same file lists them in its comments (EN_DLP_TI8445 = 13, E_FPGA_DEV_TYPE_160T = 3), so the
 * names come from the unit itself and nothing about a model lives in the code: a value the file does not name, or a
 * firmware without the file, gives "" and the row stays hidden.
 */
public final class LightEngine {
    static final LightEngine NONE = new LightEngine("", "", "");
    private static final Pattern DLP_TYPE = Pattern.compile("(?m)^\\s*DlpType\\s*=\\s*([0-9]{1,3})\\s*;?");
    private static final Pattern DLP_ENUM = Pattern.compile("(?m)^\\s*#+\\s*EN_DLP_TI([0-9]{4})[A-Za-z0-9_]*\\s*=\\s*([0-9]{1,3})\\b");
    private static final Pattern FPGA_TYPE = Pattern.compile("(?m)^\\s*extFPGAType\\s*=\\s*([0-9]{1,3})\\s*;?");
    private static final Pattern FPGA_ENUM = Pattern.compile("(?m)^\\s*#+\\s*E_FPGA_DEV_TYPE_([A-Z0-9]{2,12})\\s*=\\s*([0-9]{1,3})\\b");
    private static final Pattern FLASH = Pattern.compile("(?m)^\\s*DlpFlashImageVersion\\s*=\\s*([0-9]{1,4}(?:\\.[0-9]{1,5}){1,3})\\s*(?:;|$)");

    /** the controller as TI names it (TI DLPC8445), "" when unknown */
    public final String controller;
    /** the controller firmware the XGIMI firmware carries for it (its flash image), "" when unknown */
    public final String firmware;
    /** the FPGA as XGIMI names its type (160T, PH1A400), "" when the model has none or it is unknown */
    public final String fpga;

    LightEngine(String controller, String firmware, String fpga) {
        this.controller = controller;
        this.firmware = firmware;
        this.fpga = fpga;
    }

    /** dlpIni, flashIni: the texts of the two files, null when a file cannot be read */
    public static LightEngine parse(String dlpIni, String flashIni) {
        if (dlpIni == null) return NONE;
        String chip = named(dlpIni, DLP_TYPE, DLP_ENUM);
        String fpga = named(dlpIni, FPGA_TYPE, FPGA_ENUM);
        if ("NONE".equals(fpga)) fpga = "";
        String fw = "";
        if (!chip.isEmpty() && flashIni != null) {
            Matcher m = FLASH.matcher(flashIni);
            if (m.find()) fw = m.group(1);
        }
        return new LightEngine(chip.isEmpty() ? "" : "TI DLPC" + chip, fw, fpga);
    }

    /** the name the file's own comments give the value of key, "" when it has no value or no name for it */
    private static String named(String ini, Pattern key, Pattern names) {
        Matcher v = key.matcher(ini);
        if (!v.find()) return "";
        Matcher n = names.matcher(ini);
        while (n.find()) if (n.group(2).equals(v.group(1))) return n.group(1);
        return "";
    }
}
