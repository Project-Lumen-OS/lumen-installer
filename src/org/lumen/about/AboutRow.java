// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.about;

/** One row the system Settings About page shows (TvSettings asks AboutProvider for them). */
public final class AboutRow {
    public final String key, title, summary;
    /** the row opens the Back to the XGIMI system screen */
    public final boolean opensScreen;

    AboutRow(String key, String title, String summary, boolean opensScreen) {
        this.key = key;
        this.title = title;
        this.summary = summary;
        this.opensScreen = opensScreen;
    }
}
