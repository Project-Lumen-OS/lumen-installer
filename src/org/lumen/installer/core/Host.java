// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.installer.core;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** What the engine needs from the projector. The app implements it with getprop + the stock debugserver root route;
 *  the host tests with a fake projector (a temp tree, stub tools and the real root script). */
public interface Host {
    /** Properties as an ordinary app sees them (getprop). */
    Map<String, String> props();

    /** The app's own files dir (on /data): downloads, the tail, the root script and its status file live here. */
    File workDir();

    /** Free bytes for the app in that dir. */
    long freeBytes(File dir);

    /** Run one root job and wait for its end marker (or the timeout). Lines are passed on to the listener as they come. */
    Root.Result root(Root.Job job, LineSink sink, long timeoutMs) throws IOException;

    interface LineSink { void line(String s); }

    /** An app: its package, version code and signing certificates (sha256 of each, sorted, joined by commas). */
    final class App {
        public final String pkg, signers;
        public final long versionCode;
        public App(String pkg, long versionCode, String signers) { this.pkg = pkg; this.versionCode = versionCode; this.signers = signers; }
    }

    /** The installed app of this package, null = not installed. */
    default App app(String pkg) { return null; }

    /** The app an APK file holds, null = not an app. */
    default App appFile(File apk) { return null; }

    /** Hand an APK to the system installer, which asks the user, and wait for its answer: "ok" or why not. */
    default String installApp(File apk, LineSink sink, long timeoutMs) { return "no installer"; }

    /** What an ordinary app can read about the system without root, for the stock system info (StockDump): pairs of
     *  a file name (getprop.txt, packages.txt ...) and its text. */
    default List<String[]> facts() { return Collections.emptyList(); }
    /** The APK files of an installed app (base, then splits), as any app may read them; empty: not installed. */
    default List<File> apkFiles(String pkg) { return Collections.emptyList(); }
    /** The system app that opens the Settings screen (android.settings.SETTINGS), "" when none answers. */
    default String settingsActivityPackage() { return ""; }

    /** The text of a small system file as an ordinary app may read it (the firmware versions card), null when it
     *  cannot be read. */
    default String file(String path) { return null; }

    /** The kernel release of the running system (uname), "" when unknown. */
    default String kernel() { return ""; }

    /** The light engine line of the projector HAL (getDlpImageVersion: the FPGA and DLP controller firmware), as an
     *  ordinary app may ask it on the XGIMI system; null when the HAL is not there or does not answer. */
    default String dlpImageVersion() { return null; }
}
