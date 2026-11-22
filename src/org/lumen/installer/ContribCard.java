package org.lumen.installer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lumen.contrib.Card;
import org.lumen.contrib.Claim;
import org.lumen.contrib.Contrib;
import org.lumen.contrib.HttpTransport;
import org.lumen.contrib.Kind;
import org.lumen.contrib.KeystoreSigner;
import org.lumen.installer.core.Engine;
import org.lumen.installer.core.Msg;
import org.lumen.installer.core.Props;
import org.lumen.installer.core.TrialState;
import org.lumen.qr.QrView;

/** The Sparks card (apps/core/lumen-contrib, apps/LumenInstaller/DESIGN.md \"Contribution cards\"), in every build
 *  unless the switch LI_CONTRIB=0. Best effort and off the install path: every call runs on its own thread, any
 *  failure shows no card, nothing here changes what the steps do. One event per kind and version: the answer is kept,
 *  so the code shows again after a restart until it expires. */
final class ContribCard {
    private final Activity a;
    private final LinearLayout card;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final Contrib contrib = new Contrib(Contrib.BASE, new HttpTransport(), new KeystoreSigner());
    private String key = "";

    ContribCard(Activity a, LinearLayout card) {
        this.a = a;
        this.card = card;
        this.prefs = a.getSharedPreferences("sparks", Activity.MODE_PRIVATE);
    }

    void render(Snap s) {
        Engine.Detection d = s.det;
        Engine e = WorkService.engine;
        if (d == null || e == null || d.code.isEmpty()) return;
        String serial = e.state("dump.serial");   // root getprop of the system info, cleared once it went out
        if (!serial.isEmpty()) prefs.edit().putString("serial", serial).apply();
        boolean lumen = d.verdict == Msg.ALREADY_LUMEN || d.verdict == Msg.APP_UPDATE_OFFERED;
        final Kind kind;
        final String version, ref;
        if (lumen && d.args.length > 0) { kind = Kind.INSTALL; version = d.args[0]; ref = ""; }
        else if (s.trial == TrialState.OURS && !e.state("trial.version").isEmpty()) { kind = Kind.TRIAL; version = e.state("trial.version"); ref = ""; }
        else if (!e.state("dump.code").isEmpty()) { kind = Kind.REPORT; version = d.stock; ref = e.state("dump.code"); }
        else { kind = null; version = d.stock; ref = ""; }
        String k = d.code + "|" + version + "|" + kind + "|" + ref;
        if (k.equals(key) || s.busy) return;
        key = k;
        final String model = d.code, seen = prefs.getString("serial", "");
        final String kept = kind == null ? "" : prefs.getString(kind.wire + "|" + version, "");
        net.execute(() -> {
            String sn = seen.isEmpty() ? serialProp() : seen;
            Card c;
            Claim cl = null;
            if (kind == null) {
                c = Contrib.card(contrib.pair(model, version), false);
            } else if (!kept.isEmpty()) {
                cl = Kept.parse(kept);
                c = cl == null ? Card.NONE : Contrib.card(cl);
            } else if (sn.isEmpty()) {
                c = Card.NONE;
            } else {
                cl = contrib.send(kind, model, version, sn, ref, System.currentTimeMillis() / 1000);
                if (!"offline".equals(cl.why)) prefs.edit().putString(kind.wire + "|" + version, Kept.of(cl)).apply();
                c = Contrib.card(cl);
            }
            final Card shown = c;
            final Claim claim = cl;
            ui.post(() -> show(shown, claim));
        });
    }

    /** The serial where an app may read it (Lumen OS), else "" (the stock system shows it only to root). */
    private String serialProp() {
        Map<String, String> p = new AndroidHost(a).props();
        for (String n : new String[] { "ro.boot.serialno", "ro.serialno" }) {
            String v = Props.get(p, n);
            if (!v.isEmpty() && !"unknown".equals(v)) return v;
        }
        return "";
    }

    private void show(Card c, Claim cl) {
        card.removeAllViews();
        boolean code = c == Card.CODE && cl != null && cl.expires * 1000 > System.currentTimeMillis();
        if (c == Card.NONE || (c == Card.CODE && !code)) {
            card.setVisibility(View.GONE);
            return;
        }
        int text = c == Card.SEND_INFO ? R.string.sparks_send_info : c == Card.CODE ? R.string.sparks_code : R.string.sparks_installed_before;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Look.text(a, a.getString(text), 13, Look.TEXT, 400);
        t.setLineSpacing(0, 1.15f);
        col.addView(t);
        if (code) {
            TextView big = Look.text(a, cl.shown(), 22, Look.TEXT, 700);
            big.setPadding(0, Look.dp(a, 6), 0, 0);
            col.addView(big);
            if (cl.waiting) col.addView(Look.text(a, a.getString(R.string.sparks_waiting), 12, Look.DIM, 400));
        }
        card.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (code) {
            QrView q = new QrView(a, cl.link);
            int side = Look.dp(a, 96);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
            lp.leftMargin = Look.dp(a, 12);
            lp.gravity = Gravity.CENTER_VERTICAL;
            card.addView(q, lp);
        }
        card.setVisibility(View.VISIBLE);
    }

    /** The kept answer of one event: code, expiry, waiting, link, why (the code and the link carry no tab). */
    static final class Kept {
        static String of(Claim c) {
            return (c.code == null ? "" : c.code) + "\t" + c.expires + "\t" + (c.waiting ? 1 : 0) + "\t" + c.link + "\t" + c.why;
        }

        static Claim parse(String s) {
            String[] p = s.split("\t", -1);
            if (p.length != 5) return null;
            try {
                return Claim.kept(p[0].isEmpty() ? null : p[0], Long.parseLong(p[1]), "1".equals(p[2]), p[3], p[4]);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
