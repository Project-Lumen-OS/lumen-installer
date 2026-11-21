package org.lumen.installer;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Environment;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.StatFs;
import android.system.Os;

import org.lumen.installer.core.Bytes;
import org.lumen.installer.core.Host;
import org.lumen.installer.core.Props;
import org.lumen.installer.core.Root;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The projector side of the engine on a real stock unit.
 *
 * Root comes from XGIMI's own `debugserver` init service of the stock firmware (user root, oneshot): debugserver.sh ends with
 * `cmd=$(getprop vendor.xgimi.system.debug.cmd); ${cmd}`. The app writes assets/lumen_installer.sh and job.conf into
 * files/root/, sets that property to "/system/bin/sh <script>" and runs `setprop ctl.start debugserver` (stock is
 * SELinux permissive, so an ordinary app may). If the service does not start, it tries the stock property trigger
 * vendor.xgimi.system.debugserver=1 once. The property is cleared after every job.
 *
 * On an installed Lumen OS (1.0.6) it reads installed apps and APK files through the package manager and hands an app
 * update to the system installer (a PackageInstaller session the user confirms on the system screen), never root.
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

    /** A small world readable system file (the model's own XGIMI config, the platform release), never root. */
    @Override public String file(String path) {
        File f = new File(path);
        try {
            if (!f.isFile() || f.length() > 64 * 1024) return null;
            try (InputStream in = new FileInputStream(f)) { return Bytes.utf8(Bytes.read(in, 64 * 1024)); }
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    @Override public String kernel() {
        try {
            return Os.uname().release;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The XGIMI projector HAL's display service: its light engine line comes from one getter, nothing else is called. */
    static final String DISPLAY_HAL = "xgimi.hardware.gmpf.IDisplayManager";
    private static final long HAL_WAIT_MS = 1500;
    private static String dlpImage;
    private static boolean dlpImageRead;

    /** Asked once per start of the app (the line changes only with a firmware update, which restarts the projector),
     *  at most HAL_WAIT_MS: a HAL that does not answer leaves the row without a firmware. */
    @Override public synchronized String dlpImageVersion() {
        if (dlpImageRead) return dlpImage;
        dlpImageRead = true;
        FutureTask<String> ask = new FutureTask<>(AndroidHost::askDlpImage);
        Thread t = new Thread(ask, "lumen-dlp-image");
        t.setDaemon(true);
        t.start();
        try {
            dlpImage = ask.get(HAL_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            dlpImage = null;
        }
        return dlpImage;
    }

    private static String askDlpImage() {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("checkService", String.class)
                    .invoke(null, DISPLAY_HAL + "/default");
            if (b == null || !DISPLAY_HAL.equals(b.getInterfaceDescriptor())) return null;
            data.writeInterfaceToken(DISPLAY_HAL);
            if (!b.transact(HalCodes.DM_GET_DLP_IMAGE_VERSION, data, reply, 0)) return null;
            reply.readException();
            return reply.readString();
        } catch (ReflectiveOperationException | RemoteException | RuntimeException e) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

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
            if (own != null && top != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) out.add(new Volume(top, own, false));
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
            if (own != null && top != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) out.add(new Volume(top, own, false));
        } catch (RuntimeException ignored) {
            // no shared storage: sticks only
        }
        return out;
    }

    /** What an ordinary app reads without root, for the stock system info (the root part is the script's dump job). */
    @Override public List<String[]> facts() {
        List<String[]> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("getprop").redirectErrorStream(true).start();
            out.add(new String[] { "getprop.txt", new String(Bytes.read(p.getInputStream(), 2 << 20), java.nio.charset.StandardCharsets.UTF_8) });
            p.waitFor();
        } catch (IOException | InterruptedException | RuntimeException e) {
            out.add(new String[] { "getprop.txt", "getprop: " + e });
        }
        StringBuilder pk = new StringBuilder();
        try {
            for (PackageInfo i : c.getPackageManager().getInstalledPackages(0)) {
                boolean sys = i.applicationInfo != null && (i.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                pk.append(i.packageName).append(' ').append(i.getLongVersionCode()).append(' ').append(i.versionName)
                        .append(sys ? " system" : " user").append('\n');
            }
        } catch (RuntimeException e) {
            pk.append("packages: ").append(e);
        }
        out.add(new String[] { "packages.txt", pk.toString() });
        StringBuilder ft = new StringBuilder();
        try {
            for (android.content.pm.FeatureInfo f : c.getPackageManager().getSystemAvailableFeatures())
                ft.append(f.name != null ? f.name : "gles " + f.getGlEsVersion()).append('\n');
        } catch (RuntimeException e) {
            ft.append("features: ").append(e);
        }
        out.add(new String[] { "features.txt", ft.toString() });
        StringBuilder dp = new StringBuilder();
        try {
            android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            for (android.view.Display d : dm.getDisplays()) {
                dp.append(d).append('\n');
                for (android.view.Display.Mode m : d.getSupportedModes()) dp.append("  mode ").append(m).append('\n');
                if (d.getHdrCapabilities() != null) dp.append("  hdr ").append(java.util.Arrays.toString(d.getHdrCapabilities().getSupportedHdrTypes())).append('\n');
            }
        } catch (RuntimeException e) {
            dp.append("display: ").append(e);
        }
        out.add(new String[] { "display.txt", dp.toString() });
        for (String f : new String[] { "/proc/cpuinfo", "/proc/meminfo", "/proc/version" }) {
            try (InputStream in = new FileInputStream(f)) {
                out.add(new String[] { f.substring(6) + ".txt", new String(Bytes.read(in, 1 << 20), java.nio.charset.StandardCharsets.UTF_8) });
            } catch (IOException | RuntimeException e) {
                out.add(new String[] { f.substring(6) + ".txt", f + ": " + e });
            }
        }
        out.add(new String[] { "build.txt", "MODEL " + android.os.Build.MODEL + "\nDEVICE " + android.os.Build.DEVICE + "\nPRODUCT "
                + android.os.Build.PRODUCT + "\nHARDWARE " + android.os.Build.HARDWARE + "\nSDK " + android.os.Build.VERSION.SDK_INT
                + "\nABIS " + java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS) + "\nFINGERPRINT " + android.os.Build.FINGERPRINT + "\n" });
        return out;
    }

    /** the APK files of an installed app: base and splits (system apps are readable by any app) */
    @Override public List<File> apkFiles(String pkg) {
        List<File> out = new ArrayList<>();
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.sourceDir != null) out.add(new File(ai.sourceDir));
            if (ai.splitSourceDirs != null) for (String sp : ai.splitSourceDirs) out.add(new File(sp));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            // not installed: none
        }
        return out;
    }

    /** the system app that opens the Settings screen */
    @Override public String settingsActivityPackage() {
        try {
            android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(
                    new android.content.Intent(android.provider.Settings.ACTION_SETTINGS), PackageManager.MATCH_SYSTEM_ONLY);
            return r == null || r.activityInfo == null ? "" : r.activityInfo.packageName;
        } catch (RuntimeException e) {
            return "";
        }
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

    // ------------------------------------------------------------------------------------------- apps
    @Override public App app(String pkg) {
        try {
            return app(c.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    @Override public App appFile(File apk) {
        return app(c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES));
    }

    /** package, version code and the sha256 of each signing certificate (sorted, joined by commas) */
    @SuppressWarnings("deprecation")
    static App app(PackageInfo pi) {
        if (pi == null) return null;
        Signature[] sigs = pi.signingInfo != null ? pi.signingInfo.getApkContentsSigners() : pi.signatures;
        List<String> sha = new ArrayList<>();
        if (sigs != null) for (Signature g : sigs) sha.add(Bytes.sha256Hex(g.toByteArray()));
        Collections.sort(sha);
        return new App(pi.packageName, pi.getLongVersionCode(), String.join(",", sha));
    }

    /**
     * One PackageInstaller session with the APK; its answers come to a receiver of this process. The system asks the
     * user first (STATUS_PENDING_USER_ACTION: its confirm screen is started, and on the first time its question to
     * allow installs from this app); the answer after that ends the wait.
     */
    @SuppressWarnings("deprecation")
    @Override public String installApp(File apk, LineSink sink, long timeoutMs) {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        String action = c.getPackageName() + ".APP_INSTALLED";
        BlockingQueue<Intent> answers = new LinkedBlockingQueue<>();
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent i) { answers.add(i); }
        };
        c.registerReceiver(r, new IntentFilter(action));
        try {
            int id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                    byte[] b = new byte[1 << 16];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    s.fsync(out);
                }
                Intent back = new Intent(action).setPackage(c.getPackageName());
                s.commit(PendingIntent.getBroadcast(c, id, back, PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
            }
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                Intent i = answers.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (i == null) return "no answer";
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                sink.line("install: status " + st + (m == null ? "" : " " + m));
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent ask = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (ask == null) return "no confirm screen";
                    c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    continue;
                }
                if (st == PackageInstaller.STATUS_SUCCESS) return "ok";
                if (st == PackageInstaller.STATUS_FAILURE_ABORTED) return "cancelled";
                return "status " + st + (m == null ? "" : " " + m);
            }
        } catch (IOException | RuntimeException e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        } catch (InterruptedException e) {
            return "interrupted";
        } finally {
            c.unregisterReceiver(r);
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
