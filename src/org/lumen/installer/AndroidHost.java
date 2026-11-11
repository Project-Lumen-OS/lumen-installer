package org.lumen.installer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.StatFs;
import android.system.Os;

import org.lumen.installer.core.Bytes;
import org.lumen.installer.core.Host;
import org.lumen.installer.core.Props;
import org.lumen.installer.core.Root;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The projector side of the engine on a real stock unit.
 *
 * Root comes from XGIMI's own `debugserver` init service of the stock firmware (user root, oneshot): debugserver.sh ends with
 * `cmd=$(getprop vendor.xgimi.system.debug.cmd); ${cmd}`. The app writes assets/lumen_installer.sh and job.conf into
 * files/root/, sets that property to "/system/bin/sh <script>" and runs `setprop ctl.start debugserver` (stock is
 * SELinux permissive, so an ordinary app may). If the service does not start, it tries the stock property trigger
 * vendor.xgimi.system.debugserver=1 once. The property is cleared after every job.
 */
final class AndroidHost implements Host {
    static final String DEBUG_CMD = "vendor.xgimi.system.debug.cmd";
    private final Context c;

    AndroidHost(Context c) { this.c = c.getApplicationContext(); }

    @Override public Map<String, String> props() {
        try {
            Process p = new ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out;
            try (InputStream in = p.getInputStream()) { out = Bytes.read(in, 4 << 20); }
            p.waitFor();
            return Props.parse(Bytes.utf8(out));
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    @Override public File workDir() { return c.getFilesDir(); }

    /** USB sticks: every removable volume of getExternalFilesDirs (which also creates the app's own folder on it). */
    @Override public List<Volume> volumes() {
        List<Volume> out = new ArrayList<>();
        File[] dirs;
        try {
            dirs = c.getExternalFilesDirs(null);
        } catch (RuntimeException e) {
            return out;
        }
        for (File d : dirs) {
            if (d == null) continue;
            try {
                if (!Environment.isExternalStorageRemovable(d)) continue;   // the built-in shared storage
            } catch (IllegalArgumentException e) {
                continue;
            }
            String p = d.getAbsolutePath();
            int i = p.indexOf("/Android/data/");
            if (i <= 0) continue;
            out.add(new Volume(new File(p.substring(0, i)), d));
        }
        // the built-in shared storage last: a LumenOS folder copied there (adb push to
        // /sdcard/LumenOS, or a file manager); the same checks as a stick, root reads it under /data/media/<user>
        try {
            File own = c.getExternalFilesDir(null);
            File top = Environment.getExternalStorageDirectory();
            if (own != null && top != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) out.add(new Volume(top, own));
        } catch (RuntimeException ignored) {
            // no shared storage: sticks only
        }
        return out;
    }

    /** Where a LumenOS folder may be: the sticks, then the built-in shared storage (/sdcard/LumenOS, e.g. an adb push
     *  or a file manager copy). The same checks as a stick; root reads it under /data/media/&lt;user&gt;. Never a place for
     *  "keep the download on the stick": that is /data itself. */
    @Override public List<Volume> kitVolumes() {
        List<Volume> out = volumes();
        try {
            File own = c.getExternalFilesDir(null);
            File top = Environment.getExternalStorageDirectory();
            if (own != null && top != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) out.add(new Volume(top, own));
        } catch (RuntimeException ignored) {
            // no shared storage: sticks only
        }
        return out;
    }

    @Override public boolean canReadSticks() {
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public long freeBytes(File dir) {
        try {
            return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    @Override public Root.Result root(Root.Job job, LineSink sink, long timeoutMs) throws IOException {
        File rd = new File(c.getFilesDir(), "root");
        if (!rd.isDirectory() && !rd.mkdirs()) throw new IOException("cannot create " + rd);
        File script = new File(rd, "lumen_installer.sh");
        File conf = new File(rd, "job.conf");
        File status = new File(rd, "status.txt");
        try (InputStream in = c.getAssets().open("lumen_installer.sh")) {
            Bytes.writeFile(script, Bytes.read(in, 1 << 20));
        }
        Bytes.writeFile(conf, Bytes.utf8(job.conf()));
        Bytes.writeFile(status, new byte[0]);
        try {   // root reads and writes them anyway (DAC override); only the app may change the script
            Os.chmod(script.getAbsolutePath(), 0755);
            Os.chmod(conf.getAbsolutePath(), 0644);
            Os.chmod(status.getAbsolutePath(), 0666);
        } catch (Exception e) {
            sink.line("chmod: " + e.getMessage());
        }
        String cmd = "/system/bin/sh " + script.getAbsolutePath();
        if (cmd.length() > 91) throw new IOException("command too long for a property: " + cmd);
        try {
            if (!setprop(DEBUG_CMD, cmd)) return new Root.Result();
            setprop("ctl.start", "debugserver");
            Root.Result r = tail(status, job.nonce, sink, 15_000L, timeoutMs);
            if (!r.started) {
                // the stock rc also starts the service on this property
                setprop("vendor.xgimi.system.debugserver", "0");
                setprop("vendor.xgimi.system.debugserver", "1");
                r = tail(status, job.nonce, sink, 15_000L, timeoutMs);
                setprop("vendor.xgimi.system.debugserver", "0");
            }
            return r;
        } finally {
            setprop(DEBUG_CMD, "");   // leave nothing armed
        }
    }

    /** Follow status.txt: wait startMs for this job's START line, then until DONE / FAIL or the timeout. */
    private Root.Result tail(File status, String nonce, LineSink sink, long startMs, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        int shown = 0;
        Root.Result r = new Root.Result();
        while (true) {
            String text = "";
            try (RandomAccessFile f = new RandomAccessFile(status, "r")) {
                byte[] b = new byte[(int) Math.min(f.length(), 4 << 20)];
                f.readFully(b);
                text = Bytes.utf8(b);
            } catch (IOException ignored) {
                // being truncated by the script
            }
            r = Root.parse(text, nonce);
            for (int i = shown; i < r.log.size(); i++) sink.line(r.log.get(i));
            shown = r.log.size();
            if (r.done || r.failed) return r;
            long el = System.currentTimeMillis() - t0;
            if (!r.started && el > startMs) return r;
            if (el > timeoutMs) return r;
            try { Thread.sleep(400); } catch (InterruptedException e) { return r; }
        }
    }

    static boolean setprop(String name, String value) {
        try {
            Process p = new ProcessBuilder("/system/bin/setprop", name, value).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (InputStream in = p.getInputStream()) { while (in.read() >= 0) { /* drain */ } }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
