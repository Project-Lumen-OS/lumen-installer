package org.lumen.installer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.lumen.installer.core.Engine;
import org.lumen.installer.core.ImageSource;
import org.lumen.installer.core.Msg;
import org.lumen.installer.core.ReleaseChannel;
import org.lumen.installer.core.StepStatus;
import org.lumen.installer.core.TrialState;

import java.util.List;

/**
 * One TV screen (D-pad), 960x540 dp: the projector and the verdict on the left with the trial warning and the
 * buttons; the five steps with their progress and the last log lines on the right. Every action that writes outside
 * the app (start the trial, restart, remove the trial) asks first, with Cancel focused. Above the buttons, before a
 * step ran: where the image comes from (download, or a LumenOS folder on a USB stick); in the steps card the version
 * (the catalog's stable build, or its newest beta).
 */
public class MainActivity extends Activity {
    // Lumen tokens (apps/common/tokens.xml) and the quick-menu look: dark rounded cards, accent #3B78E7
    static final int BG = Look.BG, CARD = Look.CARD, TEXT = Look.TEXT, DIM = Look.DIM, ACCENT = Look.ACC_SOFT,
            OK = Look.OK, BAD = Look.BAD, WARN = Look.WARN;
    final Handler h = new Handler(Looper.getMainLooper());
    TextView device, verdict, status, logView;
    TextView[] stepMark = new TextView[5], stepName = new TextView[5], stepInfo = new TextView[5];
    ProgressBar bar;
    LinearLayout buttons, options, version;
    String buttonKey = "", optionsKey = "";
    int seenSeq = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(44), dp(30), dp(44), dp(26));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        // header: the title and the round "i" (About: version, projector, licenses, project page)
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        TextView title = text(getString(R.string.title), 28, TEXT);
        title.setTypeface(Look.font(this, 700));
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView about = Look.infoButton(this);
        about.setContentDescription(getString(R.string.about_title));
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        head.addView(about, new LinearLayout.LayoutParams(dp(40), dp(40)));
        left.addView(head);
        device = text("", 15, DIM);
        left.addView(device, lp(0, dp(4), 0, 0));
        verdict = text("", 17, TEXT);
        left.addView(verdict, lp(0, dp(14), 0, 0));
        TextView info = text(getString(R.string.trial_info), 13, TEXT);
        info.setLineSpacing(0, 1.15f);
        info.setPadding(dp(14), dp(12), dp(14), dp(12));
        info.setBackground(round(CARD, dp(18)));
        left.addView(info, lp(0, dp(14), 0, 0));
        status = text("", 14, ACCENT);
        status.setLineSpacing(0, 1.1f);
        left.addView(status, lp(0, dp(14), 0, 0));
        View spacer = new View(this);
        left.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        left.addView(options, lp(0, dp(10), 0, 0));
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        left.addView(buttons, lp(0, dp(10), 0, 0));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(18), dp(16), dp(18), dp(16));
        right.setBackground(round(CARD, dp(18)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(330), LinearLayout.LayoutParams.MATCH_PARENT);
        rp.leftMargin = dp(28);
        root.addView(right, rp);
        String[] names = { getString(R.string.step_check), getString(R.string.step_download), getString(R.string.step_verify),
                getString(R.string.step_prepare), getString(R.string.step_start) };
        for (int i = 0; i < 5; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            stepMark[i] = text("\u25CB", 16, DIM);
            stepMark[i].setGravity(Gravity.CENTER);
            row.addView(stepMark[i], new LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT));
            stepName[i] = text((i + 1) + ". " + names[i], 15, TEXT);
            row.addView(stepName[i], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            stepInfo[i] = text("", 12, DIM);
            row.addView(stepInfo[i]);
            right.addView(row, lp(0, i == 0 ? 0 : dp(9), 0, 0));
        }
        // Version: Stable / Beta (a model with a stable and a beta build, download source, before the first step):
        // under the steps, where the card has room; the line under it says what the choice means. A model with a beta
        // only gets one line there instead (no choice: its beta is read)
        version = new LinearLayout(this);
        version.setOrientation(LinearLayout.VERTICAL);
        version.setClipChildren(false);
        version.setClipToPadding(false);
        right.addView(version, lp(0, dp(16), 0, 0));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Look.ACC));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Look.CARD2));
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Look.ACC));
        right.addView(bar, lp(0, dp(16), 0, 0));
        // the technical log is not shown here (plain words only): About shows its last lines under "Details for help"
        logView = text("", 12, DIM);
        right.addView(logView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        TvFocus.unclip(root);
    }

    @Override protected void onResume() {
        super.onResume();
        Snap s = Snap.I;
        Engine e = WorkService.engine(this);
        if (s.det == null) s.det = e.detect();
        s.source = e.source();
        // the signed catalog: asked for once per start of the app (it lists the models that have a build, and their
        // builds); the last verified copy is used until it arrives
        if (!s.catalogTried && !s.busy && s.source == ImageSource.NET) {
            s.catalogTried = true;
            run("catalog");
        }
        s.channel = e.channel();
        seenSeq = -1;
        render();
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (Snap.I.seq != seenSeq) render();
            h.postDelayed(this, 300);
        }
    };

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && Snap.I.busy && "start".equals(Snap.I.task)) return true;   // gsi_tool runs
        return super.onKeyDown(code, e);
    }

    // ------------------------------------------------------------------------------------------- render
    void render() {
        Snap s = Snap.I;
        seenSeq = s.seq;
        Engine.Detection d = s.det;
        if (d != null) {
            String code = d.code.isEmpty() ? "?" : d.code, stock = d.stock.isEmpty() ? "?" : d.stock;
            // the code once: a name from the list may carry it already, an unknown model has only its code
            device.setText(d.name.contains(code) ? getString(R.string.device_line_short, d.name, stock)
                    : getString(R.string.device_line, d.name, code, stock));
            boolean loading = d.verdict == Msg.CATALOG_MISSING && s.busy && "catalog".equals(s.task);
            verdict.setText(loading ? msg(Msg.CATALOG_LOADING, null) : msg(d.verdict, d.args));
            verdict.setTextColor(d.ok() ? OK : loading || d.verdict == Msg.CATALOG_MISSING ? WARN : BAD);
        }
        // the last three messages of this run, newest last (e.g. the release found, the space check, the step result)
        List<Snap.Line> ms = s.messages();
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, ms.size() - 3); i < ms.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(msg(ms.get(i).msg, ms.get(i).args));
        }
        status.setText(sb.toString());
        for (int i = 0; i < 5; i++) {
            StepStatus st = s.steps[i];
            stepMark[i].setText(st == StepStatus.DONE ? "\u2713" : st == StepStatus.FAILED ? "\u2717"
                    : st == StepStatus.RUNNING ? "\u25CF" : "\u25CB");
            stepMark[i].setTextColor(st == StepStatus.DONE ? OK : st == StepStatus.FAILED ? BAD
                    : st == StepStatus.RUNNING ? ACCENT : DIM);
            stepInfo[i].setText("");
        }
        int cur = s.curStep;
        if (cur >= 0 && s.total > 0 && s.steps[cur] == StepStatus.RUNNING) {
            bar.setIndeterminate(false);
            bar.setProgress((int) (s.done * 1000 / s.total));
            stepInfo[cur].setText(cur == 1 ? (s.done >> 20) + " / " + (s.total >> 20) + " MB" : (s.done * 100 / s.total) + "%");
        } else if (s.busy && cur >= 0 && s.steps[cur] == StepStatus.RUNNING) {
            bar.setIndeterminate(true);
        } else {
            bar.setIndeterminate(false);
            bar.setProgress(0);
        }
        logView.setText("");
        renderButtons(s);
    }

    void renderButtons(Snap s) {
        Engine.Detection d = s.det;
        String key;
        if (s.busy) key = "busy:" + s.task;
        else if (d != null && d.verdict == Msg.CATALOG_MISSING) key = "nocatalog";
        else if (d == null || !d.ok()) key = "close";
        else if (s.armed) key = "armed";
        else if (s.trial == TrialState.OURS) key = "ours";
        else if (s.trial == TrialState.OTHER) key = "other";
        else if (s.prepared && s.canStart) key = "ready";
        else key = s.steps[0] == StepStatus.WAITING ? "idle" : "continue";
        renderOptions(s, key);
        if (key.equals(buttonKey)) { linkFocus(); return; }
        buttonKey = key;
        buttons.removeAllViews();
        Button first = null;
        if (key.startsWith("busy")) {
            first = add(R.string.btn_pause, v -> startService(WorkService.task(this, "stop")));
            if ("busy:start".equals(key) || "busy:restart".equals(key) || "busy:remove".equals(key) || "busy:again".equals(key)
                    || "busy:catalog".equals(key)) {
                buttons.removeAllViews();   // root work runs to its end; the catalog is a short download
                first = null;
            }
        } else if ("nocatalog".equals(key)) {
            // no verified catalog yet: ask again (download), or read the LumenOS folder's catalog (stick)
            first = add(R.string.btn_retry, v -> {
                if (Snap.I.busy) return;
                if (Snap.I.source == ImageSource.NET) { run("catalog"); return; }
                Snap.I.det = WorkService.engine(this).detect();
                Snap.I.seq++;
                render();
            });
            add(R.string.btn_close, v -> finish());
        } else if ("close".equals(key)) {
            first = add(R.string.btn_close, v -> finish());
        } else if ("idle".equals(key)) {
            first = add(R.string.btn_prepare, v -> run("prepare"));
            add(R.string.btn_close, v -> finish());
        } else if ("continue".equals(key)) {
            first = add(R.string.btn_continue, v -> run("prepare"));
            add(R.string.btn_close, v -> finish());
        } else if ("ready".equals(key)) {
            String keep = getString(s.handover == 1 ? R.string.confirm_keep_yes : R.string.confirm_keep_no);
            String space = s.need.isEmpty() ? "" : " " + getString(R.string.confirm_space, Engine.gb(Engine.parseLongSafe(s.need)),
                    Engine.gb(Engine.parseLongSafe(s.free)));
            String stick = s.source == ImageSource.STICK ? " " + getString(R.string.confirm_stick) : "";
            // the catalog says the install from the trial is not open for this build and stock: said before the start
            String only = s.trialOnly ? " " + getString(R.string.confirm_trial_only, d.name) : "";
            first = add(R.string.btn_start, v -> confirm(R.string.confirm_start_title,
                    getString(R.string.confirm_start_body) + " " + keep + space + stick + only, R.string.btn_start, () -> run("start")));
            add(R.string.btn_later, v -> finish());
        } else if ("armed".equals(key)) {
            first = add(R.string.btn_restart, v -> run("restart"));
            add(R.string.btn_later, v -> finish());
        } else if ("ours".equals(key)) {
            // a newer image (stick folder / release) replaces the old trial in one go: remove, prepare, start
            first = add(R.string.btn_replace, v -> confirm(R.string.confirm_start_title, getString(R.string.confirm_replace_body),
                    R.string.btn_replace, () -> run("replace")));
            add(R.string.btn_again, v -> run("again"));
            add(R.string.btn_remove, v -> confirm(R.string.confirm_remove_title, getString(R.string.confirm_remove_body),
                    R.string.btn_remove, () -> run("remove")));
            add(R.string.btn_close, v -> finish());
        } else if ("other".equals(key)) {
            first = add(R.string.btn_remove, v -> confirm(R.string.confirm_remove_title, getString(R.string.confirm_remove_body),
                    R.string.btn_remove, () -> run("remove")));
            add(R.string.btn_close, v -> finish());
        }
        final Button f = first;
        if (f != null) buttons.post(() -> f.requestFocus());
        linkFocus();
    }

    /** D-pad: RIGHT from the buttons and the source choice goes to the choices in the steps card (the nearest view to
     *  the right would be the round "i" otherwise), LEFT from there back to the first button. */
    void linkFocus() {
        View first = null;
        for (int i = 0; i < version.getChildCount() && first == null; i++) {
            View r = version.getChildAt(i);
            if (r instanceof LinearLayout && ((LinearLayout) r).getChildCount() > 0) first = ((LinearLayout) r).getChildAt(0);
        }
        int to = View.NO_ID;
        if (first != null && version.getVisibility() == View.VISIBLE) {
            if (first.getId() == View.NO_ID) first.setId(View.generateViewId());
            to = first.getId();
        }
        View back = buttons.getChildCount() > 0 ? buttons.getChildAt(0) : options.getChildCount() > 0 ? options.getChildAt(0) : null;
        if (back != null && back.getId() == View.NO_ID) back.setId(View.generateViewId());
        for (LinearLayout row : new LinearLayout[] { options, buttons }) {
            for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).setNextFocusRightId(i == row.getChildCount() - 1 ? to : View.NO_ID);
        }
        for (int i = 0; i < version.getChildCount(); i++) {
            View r = version.getChildAt(i);
            if (r instanceof LinearLayout && ((LinearLayout) r).getChildCount() > 0) {
                ((LinearLayout) r).getChildAt(0).setNextFocusLeftId(back != null ? back.getId() : View.NO_ID);
            }
        }
    }

    /** The choices, only before the first step ran (nothing to undo then): the source on the left above the buttons;
     *  in the steps card, where there is room, the version (a model with a stable and a beta build, download source:
     *  the stable build by default, Beta switches to the newest beta) with a line on what it means, or for a model with
     *  a beta only the line that only a beta exists (no choice). */
    void renderOptions(Snap s, String key) {
        boolean show = "idle".equals(key) || "continue".equals(key) || "nocatalog".equals(key);
        boolean beta = show && s.source == ImageSource.NET && s.det != null && s.det.betaOffered;
        boolean only = show && s.source == ImageSource.NET && s.det != null && s.det.betaOnly;
        String k = show ? s.source + "|" + (beta ? s.channel + s.det.stableVersion + s.det.betaVersion : only ? "only" : "-") : "";
        if (k.equals(optionsKey)) return;
        optionsKey = k;
        View cur = getCurrentFocus();   // a toggle keeps the focus where it was
        int fi = options.indexOfChild(cur);
        Object tag = cur != null ? cur.getTag() : null;
        options.removeAllViews();
        version.removeAllViews();
        version.setVisibility(beta || only ? View.VISIBLE : View.GONE);
        if (!show) return;
        addTo(options, s.source == ImageSource.STICK ? R.string.opt_source_stick : R.string.opt_source_net, v -> {
            if (Snap.I.busy) return;
            ImageSource n = Snap.I.source == ImageSource.STICK ? ImageSource.NET : ImageSource.STICK;
            Engine e = WorkService.engine(this);
            e.setSource(n);
            Snap.I.source = n;
            Snap.I.det = e.detect();   // the row in use depends on the source (Beta never applies to a stick folder)
            Snap.I.resetSteps();
            Snap.I.prepared = false;
            if (n == ImageSource.STICK && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, 1);
            }
            render();
        });
        View focus = null;
        if (beta) {
            // Version: Stable (the catalog's stable build) or Beta (its newest beta, the same safety check); remembered
            Button b = addTo(row(), s.channel == ReleaseChannel.BETA ? getString(R.string.opt_version_beta, s.det.betaVersion)
                    : getString(R.string.opt_version_stable, s.det.stableVersion), v -> {
                if (Snap.I.busy) return;
                ReleaseChannel n = Snap.I.channel == ReleaseChannel.BETA ? ReleaseChannel.STABLE : ReleaseChannel.BETA;
                Engine e = WorkService.engine(this);
                e.setChannel(n);
                Snap.I.channel = n;
                Snap.I.det = e.detect();
                Snap.I.resetSteps();
                Snap.I.prepared = false;
                render();
            });
            b.setTag("version");
            if ("version".equals(tag)) focus = b;
            TextView hint = text(getString(s.channel == ReleaseChannel.BETA ? R.string.opt_version_beta_hint : R.string.opt_version_stable_hint), 12, DIM);
            version.addView(hint, lp(0, dp(8), 0, 0));
        } else if (only) {
            // a model with a beta only: no Stable / Beta choice, one line (the beta is read without a choice)
            version.addView(text(getString(R.string.opt_version_beta_only), 14, TEXT), lp(0, 0, 0, 0));
        }
        if (focus != null) { final View f = focus; version.post(f::requestFocus); }
        else if (fi >= 0 && fi < options.getChildCount()) { final View f = options.getChildAt(fi); options.post(f::requestFocus); }
    }

    /** one row of the choices in the steps card */
    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        version.addView(r, lp(0, version.getChildCount() == 0 ? 0 : dp(12), 0, 0));
        return r;
    }

    void run(String task) {
        Intent i = WorkService.task(this, task);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    void confirm(int title, String body, int yes, Runnable r) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(yes, (x, w) -> r.run())
                .create();
        d.setOnShowListener(x -> {
            TvFocus.dialog(d);
            d.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
        });
        d.show();
    }

    // ------------------------------------------------------------------------------------------- helpers
    String msg(Msg m, String[] args) {
        if (m == null) return "";
        int id = getResources().getIdentifier(m.key(), "string", getPackageName());
        if (id == 0) return m.name();
        Object[] a = new Object[m.args];
        for (int i = 0; i < m.args; i++) a[i] = args != null && i < args.length ? args[i] : "";
        return getString(id, a);
    }

    Button add(int label, View.OnClickListener l) { return addTo(buttons, label, l); }

    Button addTo(LinearLayout row, int label, View.OnClickListener l) { return addTo(row, getString(label), l); }

    /** A TV button in the TvFocus look: accent fill, white outline and 1.04 scale when focused, muted otherwise. */
    Button addTo(LinearLayout row, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        TvFocus.button(b);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setOnClickListener(l);
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setMinWidth(dp(120));
        b.setMinimumWidth(dp(120));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        p.rightMargin = dp(14);
        row.addView(b, p);
        return b;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Look.font(this, 400));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
