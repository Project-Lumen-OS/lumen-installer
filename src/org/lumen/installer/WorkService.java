package org.lumen.installer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.lumen.installer.core.Bytes;
import org.lumen.installer.core.Engine;
import org.lumen.installer.core.ImageSource;
import org.lumen.installer.core.InstallStep;
import org.lumen.installer.core.Msg;
import org.lumen.installer.core.Release;
import org.lumen.installer.core.StepStatus;
import org.lumen.installer.core.Task;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the engine off the UI thread, in the foreground (a 1 GB download must survive the screen going to the
 * launcher). One task at a time ({@link Task}, started by the screen behind {@link Snap#gate}, which this service opens
 * again when the task ends); the activity reads {@link Snap}.
 *   catalog  download the signed catalog of builds again (at the app's start and on Try again)
 *   prepare  check + download + verify + prepare (never writes outside the app's own dir on /data)
 *   start    the same (each step returns at once when already done) + start: gsi_tool install + enable
 *   again    gsi_tool enable --single-boot for an installed trial
 *   remove   gsi_tool wipe
 *   restart  reboot (only from the Restart button), returns only when the projector did not go down
 *   stop     pause the running task (the download keeps what it has)
 */
public class WorkService extends Service {
    static final String CH = "lumeninstaller";
    static Engine engine;
    private static Thread worker;

    static Intent task(Context c, String t) { return new Intent(c, WorkService.class).setAction(t); }

    static synchronized Engine engine(Context c) {
        if (engine == null) {
            final Context app = c.getApplicationContext();
            Engine.Config cfg = new Engine.Config();
            cfg.catalogCerts = catalogCerts(app);
            cfg.lang = lang(java.util.Locale.getDefault());
            engine = new Engine(new AndroidHost(app), cfg, new Engine.Listener() {
                @Override public void step(InstallStep s, StepStatus st) { Snap.I.step(s, st); }
                @Override public void message(Msg m, String... args) { Snap.I.message(m, args); }
                @Override public void progress(InstallStep s, long d, long t) { Snap.I.progress(s, d, t); }
                @Override public void log(String line) { Snap.I.log(line); }
            });
        }
        return engine;
    }

    /** the language key of the catalog's texts: "zh-rCN" / "zh-rTW" for Chinese, else the language ("pt", "ru") */
    static String lang(java.util.Locale l) {
        String lg = l.getLanguage();
        if ("zh".equals(lg)) return "TW".equals(l.getCountry()) || "Hant".equals(l.getScript()) ? "zh-rTW" : "zh-rCN";
        return lg;
    }

    /** The certificates of the signed catalog (assets/index/*.x509.pem): the only certificates in the app.
     *  Every model's own certificates come from its entry in the catalog. Empty = nothing verifies. */
    static List<X509Certificate> catalogCerts(Context app) {
        List<byte[]> pems = new ArrayList<>();
        try {
            String[] names = app.getAssets().list("index");
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names) {
                    if (!n.endsWith(".x509.pem")) continue;
                    try (InputStream in = app.getAssets().open("index/" + n)) { pems.add(Bytes.read(in, 64 * 1024)); }
                }
            }
            return Release.certs(pems);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final String a = intent == null ? "" : String.valueOf(intent.getAction());
        final Engine e = engine(this);
        if ("stop".equals(a)) {
            e.stop();
            if (!Snap.I.busy) stopSelf();
            return START_NOT_STICKY;
        }
        // started with startForegroundService: go foreground at once, even when a task already runs
        startForeground(1, notification());
        final Task t = Task.of(a);
        synchronized (WorkService.class) {
            if (t == null || Snap.I.busy) {
                // not run: give the screen its buttons back (the task that runs keeps the gate)
                if (t != null && t != Snap.I.task) Snap.I.gate.end(t);
                Snap.I.seq++;
                if (!Snap.I.busy) { stopForeground(true); stopSelf(); }
                return START_NOT_STICKY;
            }
            Snap.I.busy = true;
            Snap.I.task = t;
        }
        worker = new Thread(new Runnable() { @Override public void run() { work(e, t); } }, "lumeninstaller");
        worker.start();
        return START_NOT_STICKY;
    }

    private void work(Engine e, Task t) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumeninstaller:work");
        wl.acquire(3 * 60 * 60 * 1000L);
        try {
            e.resetStop();
            Snap.I.det = e.detect();
            if (t == Task.CATALOG) {
                if (e.source() == ImageSource.NET) e.refreshCatalog();
                Snap.I.det = e.detect();
            } else if (t == Task.PREPARE) {
                Snap.I.resetSteps();
                Snap.I.prepared = e.prepareAll();
                if (Snap.I.prepared && e.trialOnly()) Snap.I.message(Msg.TRIAL_ONLY, new String[] { e.det.name });
            } else if (t == Task.START) {
                Snap.I.armed = e.prepareAll() && e.start();
                Snap.I.prepared = false;
            } else if (t == Task.REPLACE) {
                Snap.I.resetSteps();
                Snap.I.armed = e.removeTrial() && e.prepareAll() && e.start();
                Snap.I.prepared = false;
            } else if (t == Task.AGAIN) {
                Snap.I.armed = e.enableAgain();
            } else if (t == Task.REMOVE) {
                if (e.removeTrial()) { Snap.I.resetSteps(); Snap.I.armed = false; }
            } else if (t == Task.RESTART) {
                e.reboot();
            }
            Snap.I.trial = e.trial;
            Snap.I.canStart = e.canStart();
            Snap.I.trialOnly = e.trialOnly();
            Snap.I.need = e.state("need.bytes");
            Snap.I.free = e.state("free.bytes");
            Snap.I.source = e.source();
            Snap.I.channel = e.channel();
            if (Snap.I.prepared) Snap.I.handover = e.handover();
        } catch (RuntimeException ex) {
            Snap.I.message(Msg.ERROR, new String[] { String.valueOf(ex) });
        } finally {
            try { wl.release(); } catch (RuntimeException ignored) { }
            stopForeground(true);
            stopSelf();
            Snap.I.busy = false;
            // the task ended, done or failed: the screen's buttons work again (a restart that went through never
            // gets here)
            Snap.I.gate.end(t);
            Snap.I.seq++;
        }
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.working))
                .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build();
    }
}
