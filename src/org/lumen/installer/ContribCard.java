// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.installer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lumen.contrib.Card;
import org.lumen.contrib.Claim;
import org.lumen.contrib.Contrib;
import org.lumen.contrib.HttpTransport;
import org.lumen.contrib.Kind;
import org.lumen.contrib.KeystoreSigner;
import org.lumen.contrib.Mine;
import org.lumen.contrib.Offers;
import org.lumen.contrib.Shown;
import org.lumen.installer.core.Engine;
import org.lumen.installer.core.Msg;
import org.lumen.installer.core.Props;
import org.lumen.installer.core.TrialState;
import org.lumen.qr.QrView;

/** The Sparks card (apps/core/lumen-contrib, apps/LumenInstaller/DESIGN.md "Contribution cards"), in every build
 *  unless the switch LI_CONTRIB=0. Best effort and off the install path: every call runs on its own thread, any
 *  failure shows no card, nothing here changes what the steps do. One event per kind and version, every answer kept.
 *  The card shows a code still to take with the steps in the bot, a Spark this projector already got (asked from the
 *  base by the serial hash, so it comes back after a reinstall too), or the offer to send a report while this firmware
 *  is new to the base: then the report button leads (reportFirst) and names the Spark. */
final class ContribCard {
    private final Activity a;
    private final LinearLayout card;
    private final Runnable changed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final Contrib contrib = new Contrib(Contrib.BASE, new HttpTransport(), new KeystoreSigner());
    private String key = "";
    private volatile boolean reportFirst;

    ContribCard(Activity a, LinearLayout card, Runnable changed) {
        this.a = a;
        this.card = card;
        this.changed = changed;
        this.prefs = a.getSharedPreferences("sparks", Activity.MODE_PRIVATE);
    }

    /** the report button leads and names the Spark (a firmware new to the base, no report from this projector yet) */
    boolean reportFirst() {
        return reportFirst;
    }

    /** the label of the report button */
    String dumpLabel(String plain) {
        return reportFirst ? a.getString(R.string.sparks_btn_report) : plain;
    }

    void render(Snap s) {
        Engine.Detection d = s.det;
        Engine e = WorkService.engine;
        if (d == null || e == null || d.code.isEmpty()) return;
        // the serial: the root probe of the check or the system info's root getprop, kept here (never sent, only
        // its hash); cleared in the engine once the system info went out
        for (String sn : new String[] { e.unitSerial, e.state("dump.serial") }) {
            if (sn != null && !sn.isEmpty() && !sn.equals(prefs.getString("serial", ""))) prefs.edit().putString("serial", sn).apply();
        }
        boolean lumen = d.verdict == Msg.ALREADY_LUMEN || d.verdict == Msg.APP_UPDATE_OFFERED;
        final Kind kind;
        final String version, ref, dumpCode = e.state("dump.code");
        if (lumen && d.args.length > 0) { kind = Kind.INSTALL; version = d.args[0]; ref = ""; }
        else if (s.trial == TrialState.OURS && !e.state("trial.version").isEmpty()) { kind = Kind.TRIAL; version = e.state("trial.version"); ref = ""; }
        else if (!dumpCode.isEmpty()) { kind = Kind.REPORT; version = d.stock; ref = dumpCode; }
        else { kind = null; version = d.stock; ref = ""; }
        final String model = d.code, stock = d.stock, seen = prefs.getString("serial", "");
        String k = model + "|" + version + "|" + kind + "|" + ref + "|" + seen.isEmpty();
        if (k.equals(key) || s.busy) return;
        key = k;
        final String asked = version.isEmpty() ? stock : version;
        net.execute(() -> {
            String sn = seen.isEmpty() ? serialProp() : seen;
            long now = System.currentTimeMillis() / 1000;
            String why = prefs.getString("why", "");
            if (kind != null && !prefs.contains(kind.wire + "|" + version) && !sn.isEmpty()) {
                Claim cl = contrib.send(kind, model, version, sn, ref, now);
                if (!"offline".equals(cl.why)) {
                    prefs.edit().putString(kind.wire + "|" + version, Kept.of(cl.at(now))).putString("why", cl.why).apply();
                    why = cl.why;
                }
            }
            Mine m = sn.isEmpty() ? Mine.OFFLINE : contrib.mine(model, asked.isEmpty() ? "0" : asked, sn, now);
            Offers o = lumen || stock.isEmpty() ? Offers.NONE : contrib.pair(model, stock);
            boolean sent = !dumpCode.isEmpty() || prefs.contains(Kind.REPORT.wire + "|" + stock);
            final Shown shown = Shown.decide(o, m, kept(), sent, why, now);
            ui.post(() -> {
                show(shown);
                if (shown.reportFirst != reportFirst) {
                    reportFirst = shown.reportFirst;
                    changed.run();
                }
            });
        });
    }

    /** every answer this app kept (one per kind and version) */
    private List<Claim> kept() {
        List<Claim> out = new ArrayList<>();
        for (Map.Entry<String, ?> en : prefs.getAll().entrySet()) {
            if (!en.getKey().contains("|") || !(en.getValue() instanceof String)) continue;
            Claim c = Kept.parse((String) en.getValue());
            if (c != null) out.add(c);
        }
        return out;
    }

    /** The serial where an app may read it (Lumen OS), else "" (the stock system shows it only to root). */
    private String serialProp() {
        Map<String, String> p = new AndroidHost(a).props();
        for (String n : new String[] { "ro.boot.serialno", "ro.serialno" }) {
            String v = Props.get(p, n);
            if (!v.isEmpty() && !"unknown".equals(v)) {
                prefs.edit().putString("serial", v).apply();
                return v;
            }
        }
        return "";
    }

    private String day(long secs) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM, a.getResources().getConfiguration().locale).format(new Date(secs * 1000L));
    }

    private void show(Shown sh) {
        card.removeAllViews();
        if (sh.card == Card.NONE) {
            card.setVisibility(View.GONE);
            return;
        }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        if (sh.card == Card.CODE) {
            // compact: the buttons and the status lines below must stay on the screen
            col.addView(Look.text(a, a.getString(R.string.sparks_title_code), 14, Look.TEXT, 700));
            TextView big = Look.text(a, sh.shown(), 22, Look.TEXT, 700);
            big.setPadding(0, Look.dp(a, 2), 0, Look.dp(a, 2));
            col.addView(big);
            col.addView(line(a.getString(R.string.sparks_steps, sh.code), 13, Look.TEXT));
            String dim = (sh.waiting ? a.getString(R.string.sparks_waiting) + ". " : "")
                    + (sh.earned > 0 ? a.getString(R.string.sparks_received, day(sh.earned)) + " " : "")
                    + a.getString(R.string.sparks_until, day(sh.expires));
            col.addView(line(dim, 12, Look.DIM));
        } else if (sh.card == Card.TAKEN) {
            String t = sh.code.isEmpty() ? a.getString(R.string.sparks_taken_nocode, day(sh.earned))
                    : a.getString(R.string.sparks_taken, sh.shown(), day(sh.earned));
            col.addView(line(t, 13, Look.TEXT));
        } else {
            col.addView(line(a.getString(sh.card == Card.SEND_INFO ? R.string.sparks_send_info : R.string.sparks_installed_before), 13, Look.TEXT));
        }
        card.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (sh.card == Card.CODE && !sh.link.isEmpty()) {
            QrView q = new QrView(a, sh.link);
            int side = Look.dp(a, 104);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
            lp.leftMargin = Look.dp(a, 14);
            lp.gravity = Gravity.CENTER_VERTICAL;
            card.addView(q, lp);
        }
        card.setVisibility(View.VISIBLE);
    }

    private TextView line(String s, int sp, int color) {
        TextView t = Look.text(a, s, sp, color, 400);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** The kept answer of one event: code, expiry, waiting, link, why, when it came (the code and the link carry no tab;
     *  an answer kept by Installer 1.0.9 has no time). */
    static final class Kept {
        static String of(Claim c) {
            return (c.code == null ? "" : c.code) + "\t" + c.expires + "\t" + (c.waiting ? 1 : 0) + "\t" + c.link + "\t" + c.why
                    + "\t" + c.earned;
        }

        static Claim parse(String s) {
            String[] p = s.split("\t", -1);
            if (p.length != 5 && p.length != 6) return null;
            try {
                return Claim.kept(p[0].isEmpty() ? null : p[0], Long.parseLong(p[1]), "1".equals(p[2]), p[3], p[4],
                        p.length == 6 ? Long.parseLong(p[5]) : 0);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
