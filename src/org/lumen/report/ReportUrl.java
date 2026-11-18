// SPDX-License-Identifier: Apache-2.0
package org.lumen.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The upload addresses, tried in order (ReportUploader.uploadAny). By default our own server (the statistics server,
 * tools/stats/server/lumen_reports.py) through Cloudflare. Networks in Russia freeze every connection to a Cloudflare
 * address after about 20 KB, so there a report (minutes of log) needs an address that reaches the server without
 * Cloudflare: an image lists it first in ro.lumen.report.url, with the Cloudflare one after it (README "Where it
 * goes"). ro.lumen.report.url holds one or more https addresses separated by spaces or commas, or "off" (any value
 * with an address that is not https) to switch problem reports off on that image; persist.lumen.report.url overrides
 * it on one projector (tests). Lumen Installer uses the same rule on the XGIMI system (no ro.lumen.report.url there:
 * its signed catalog's "reports" list takes that place). Plain Java.
 */
public final class ReportUrl {
    /** Our report server behind Cloudflare. */
    public static final String CLOUDFLARE = "https://prompt.az/lumen/v1/report";
    static final List<String> DEFAULTS = Collections.singletonList(CLOUDFLARE);

    private ReportUrl() {}

    /** The addresses to try, in order; empty when problem reports are off. */
    public static List<String> choose(String debug, String image) {
        String d = debug == null ? "" : debug.trim(), i = image == null ? "" : image.trim();
        String v = !d.isEmpty() ? d : i;
        if (v.isEmpty()) return DEFAULTS;
        List<String> out = new ArrayList<>();
        for (String u : v.split("[\\s,]+")) {
            if (!u.startsWith("https://") || u.length() == "https://".length()) return Collections.emptyList();
            if (!out.contains(u)) out.add(u);
        }
        return Collections.unmodifiableList(out);
    }
}
