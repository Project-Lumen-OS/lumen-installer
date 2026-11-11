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
        public Volume(File root, File appDir) { this.root = root; this.appDir = appDir; }
        @Override public String toString() { return root.getPath(); }
    }

    /** The USB sticks mounted now (empty: none). */
    default List<Volume> volumes() { return Collections.emptyList(); }

    /** Where the stick source looks for a LumenOS folder: the sticks, then the built-in shared storage
     *  (/sdcard/LumenOS). Only for reading a folder, never a download target. */
    default List<Volume> kitVolumes() { return volumes(); }

    /** May the app read files outside its own folder on a stick (the storage permission)? */
    default boolean canReadSticks() { return false; }
}
