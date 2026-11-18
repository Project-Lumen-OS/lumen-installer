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

    /** A removable volume (USB stick) as the app sees it: its root (/storage/&lt;id&gt;) and the app's own folder on it
     *  (/storage/&lt;id&gt;/Android/data/org.lumen.installer/files), which the app may write without any permission. */
    final class Volume {
        public final File root, appDir;
        /** a USB stick (false: the built-in shared storage) */
        public final boolean removable;
        public Volume(File root, File appDir) { this(root, appDir, true); }
        public Volume(File root, File appDir, boolean removable) { this.root = root; this.appDir = appDir; this.removable = removable; }
        @Override public String toString() { return root.getPath(); }
    }

    /** The USB sticks mounted now (empty: none). */
    default List<Volume> volumes() { return Collections.emptyList(); }

    /** Where the stick source looks for a LumenOS folder: the sticks, then the built-in shared storage
     *  (/sdcard/LumenOS). Only for reading a folder, never a download target. */
    default List<Volume> kitVolumes() { return volumes(); }

    /** May the app read files outside its own folder on a stick (the storage permission)? */
    default boolean canReadSticks() { return false; }

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
}
