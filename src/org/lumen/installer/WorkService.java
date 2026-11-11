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
import org.lumen.installer.core.Msg;
import org.lumen.installer.core.Release;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the engine off the UI thread, in the foreground (a 1 GB download must survive the screen going to the
 * launcher). One task at a time; the activity reads {@link Snap}.
 *   index    download the signed list of projectors again (at the app's start and on Try again)
 *   prepare  check + download + verify + prepare (never writes outside the app's own folders: its dir on /data,
 *            and with "keep on stick" its own folder on the USB stick)
 *   start    the same (each step returns at once when already done) + start: gsi_tool install + enable
 *   again    gsi_tool enable --single-boot for an installed trial
 *   remove   gsi_tool wipe
 *   restart  reboot (only from the Restart button)
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
            cfg.indexCerts = indexCerts(app);
            engine = new Engine(new AndroidHost(app), cfg, new Engine.Listener() {
                @Override public void step(Engine.Step s, Engine.Status st) { Snap.I.step(s, st); }
                @Override public void message(Msg m, String... args) { Snap.I.message(m, args); }
                @Override public void progress(Engine.Step s, long d, long t) { Snap.I.progress(s, d, t); }
                @Override public void log(String line) { Snap.I.log(line); }
            });
        }
        return engine;
    }

    /** The certificates of the signed list of projectors (assets/index/*.x509.pem): the only certificates in the app.
     *  Every model's own certificates come from its entry in that list. Empty = nothing verifies. */
    static List<X509Certificate> indexCerts(Context app) {
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
        final String t = intent == null ? "" : String.valueOf(intent.getAction());
        final Engine e = engine(this);
        if ("stop".equals(t)) {
            e.stop();
            if (!Snap.I.busy) stopSelf();
            return START_NOT_STICKY;
        }
        // started with startForegroundService: go foreground at once, even when a task already runs
        startForeground(1, notification());
        synchronized (WorkService.class) {
            if (Snap.I.busy) return START_NOT_STICKY;
            Snap.I.busy = true;
            Snap.I.task = t;
        }
        worker = new Thread(new Runnable() { @Override public void run() { work(e, t); } }, "lumeninstaller");
        worker.start();
        return START_NOT_STICKY;
    }

    private void work(Engine e, String t) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumeninstaller:work");
        wl.acquire(3 * 60 * 60 * 1000L);
        try {
            e.resetStop();
            Snap.I.det = e.detect();
            if ("index".equals(t)) {
                if (e.source() == Engine.Source.NET) e.refreshIndex();
                Snap.I.det = e.detect();
            } else if ("prepare".equals(t)) {
                Snap.I.resetSteps();
                Snap.I.prepared = e.prepareAll();
                if (Snap.I.prepared && !e.canStart()) Snap.I.message(Msg.START_PILOT, new String[] { e.det.name });
                else if (Snap.I.prepared && e.trialOnly()) Snap.I.message(Msg.TRIAL_ONLY, new String[] { e.det.name });
            } else if ("start".equals(t)) {
                Snap.I.armed = e.prepareAll() && e.start();
                Snap.I.prepared = false;
            } else if ("replace".equals(t)) {
                Snap.I.resetSteps();
                Snap.I.armed = e.removeTrial() && e.prepareAll() && e.start();
                Snap.I.prepared = false;
            } else if ("again".equals(t)) {
                Snap.I.armed = e.enableAgain();
            } else if ("remove".equals(t)) {
                if (e.removeTrial()) { Snap.I.resetSteps(); Snap.I.armed = false; }
            } else if ("restart".equals(t)) {
                e.reboot();
            }
            Snap.I.trial = e.trial;
            Snap.I.canStart = e.canStart();
            Snap.I.trialOnly = e.trialOnly();
            Snap.I.need = e.state("need.bytes");
            Snap.I.free = e.state("free.bytes");
            Snap.I.source = e.source();
            Snap.I.keepOnStick = e.keepOnStick();
            Snap.I.channel = e.channel();
            if (Snap.I.prepared) Snap.I.handover = e.handover();
        } catch (RuntimeException ex) {
            Snap.I.message(Msg.ERROR, new String[] { String.valueOf(ex) });
        } finally {
            try { wl.release(); } catch (RuntimeException ignored) { }
            Snap.I.busy = false;
            Snap.I.seq++;
            stopForeground(true);
            stopSelf();
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
