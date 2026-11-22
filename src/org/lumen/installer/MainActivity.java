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
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.lumen.about.AboutRow;
import org.lumen.about.AboutRows;
import org.lumen.about.RowText;
import org.lumen.icons.IconStatus;
import org.lumen.icons.Icons;
import org.lumen.installer.core.ActionGate;
import org.lumen.installer.core.Engine;
import org.lumen.installer.core.Host;
import org.lumen.installer.core.InstallStep;
import org.lumen.installer.core.Msg;
import org.lumen.installer.core.ReleaseChannel;
import org.lumen.installer.core.StepStatus;
import org.lumen.installer.core.Task;
import org.lumen.installer.core.TrialState;

import java.util.List;
import java.util.Locale;

/**
 * One TV screen (D-pad), 960x540 dp: the projector and the verdict on the left with the trial warning and the
 * buttons; the five steps with their progress on the right, under them the card Firmware versions (the same rows as
 * the About page of Lumen OS, apps/core/lumen-about). Every action that writes outside
 * the app (start the trial, restart, remove the trial) asks first, with Cancel focused. Above the buttons, before a
 * step ran: the version (the
 * catalog's stable build, or its newest beta). A button that starts a task works once: until the task ends it
 * says what runs ("Restarting…") and the remote does nothing on it (ActionGate).
 */
public class MainActivity extends Activity {
    // Lumen tokens (apps/common/tokens.xml) and the quick-menu look: dark rounded cards, accent #3B78E7
    static final int BG = Look.BG, CARD = Look.CARD, TEXT = Look.TEXT, DIM = Look.DIM, ACCENT = Look.ACC_SOFT,
            OK = Look.OK, BAD = Look.BAD, WARN = Look.WARN;
    final Handler h = new Handler(Looper.getMainLooper());
    TextView device, verdict, status, trialInfo, forceWarn;
    /** the messages the status line may show, newest last (fitStatus picks the newest that fit) */
    final List<String> statusLines = new java.util.ArrayList<>();
    /** the card Firmware versions: the rows of lumen-about (the About page of Lumen OS shows the same ones) */
    LinearLayout steps, versionRows;
    ScrollView versions;
    String versionsKey = "";
    /** "start the trial anyway": a row with the check on the right and the warning under it */
    LinearLayout forceBox, forceRow;
    CheckBox forceCheck;
    ImageView[] stepMark = new ImageView[5];
    TextView[] stepName = new TextView[5], stepInfo = new TextView[5];
    ProgressBar bar;
    /** under the bar while a download runs: its speed and the time left */
    TextView rate;
    LinearLayout buttons, options, version;
    String buttonKey = "", optionsKey = "";
    int seenSeq = -1;
    /** BACK went down on this screen (its release closes the app) */
    boolean backDown;
    /** the controls that work while a task runs (Pause of a download, About): OK on any other view does nothing */
    static final Object OFFERED = new Object();
    AlertDialog asking;
    /** the Sparks card (none in a build with the build.sh switch LI_CONTRIB=0) */
    LinearLayout contribCard;
    ContribCard contrib;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        // inside the TV title safe area (27 dp at the top and the bottom)
        root.setPadding(dp(44), dp(30), dp(44), dp(27));

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
        about.setTag(OFFERED);
        head.addView(about, new LinearLayout.LayoutParams(dp(40), dp(40)));
        left.addView(head);
        device = text("", 15, DIM);
        left.addView(device, lp(0, dp(4), 0, 0));
        verdict = text("", 17, TEXT);
        left.addView(verdict, lp(0, dp(14), 0, 0));
        trialInfo = text(getString(R.string.trial_info), 13, TEXT);
        trialInfo.setLineSpacing(0, 1.15f);
        trialInfo.setPadding(dp(14), dp(12), dp(14), dp(12));
        trialInfo.setBackground(round(CARD, dp(18)));
        left.addView(trialInfo, lp(0, dp(14), 0, 0));
        contribCard = new LinearLayout(this);
        contribCard.setOrientation(LinearLayout.HORIZONTAL);
        contribCard.setGravity(Gravity.CENTER_VERTICAL);
        contribCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        contribCard.setBackground(round(CARD, dp(18)));
        contribCard.setVisibility(View.GONE);
        left.addView(contribCard, lp(0, dp(12), 0, 0));
        contrib = new ContribCard(this, contribCard);
        forceBox = new LinearLayout(this);
        forceBox.setOrientation(LinearLayout.VERTICAL);
        forceRow = new LinearLayout(this);
        forceRow.setOrientation(LinearLayout.HORIZONTAL);
        forceRow.setGravity(Gravity.CENTER_VERTICAL);
        forceRow.setFocusable(true);
        forceRow.setClickable(true);
        forceRow.setPadding(dp(14), dp(6), dp(8), dp(6));
        forceRow.setBackground(round(CARD, dp(14)));
        forceRow.setOnFocusChangeListener((v, has) -> v.setBackground(round(has ? ACCENT : CARD, dp(14))));
        forceRow.addView(text(getString(R.string.force_trial), 15, TEXT), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        forceCheck = new CheckBox(this);
        forceCheck.setFocusable(false);
        forceCheck.setClickable(false);
        forceRow.addView(forceCheck);
        forceRow.setOnClickListener(v -> toggleForce());
        forceBox.addView(forceRow, lp(0, 0, 0, 0));
        forceWarn = text(getString(R.string.force_warning), 12, DIM);
        forceBox.addView(forceWarn, lp(dp(4), dp(6), 0, 0));
        forceBox.setVisibility(View.GONE);
        left.addView(forceBox, lp(0, dp(12), 0, 0));
        // the messages take the room between the warning and the choices: the newest ones that fit (fitStatus), so
        // the choices and the buttons always keep their place
        status = text("", 14, ACCENT);
        status.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = dp(14);
        left.addView(status, sp);
        status.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fitStatus());
        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        left.addView(options, lp(0, dp(10), 0, 0));
        // Version: Stable / Beta (a model with a stable and a beta build, download source, before the first step): a
        // row under the source choice, the line beside the button says what the choice means. A model with a beta
        // only gets that line alone (no choice: its beta is read)
        version = new LinearLayout(this);
        version.setOrientation(LinearLayout.HORIZONTAL);
        version.setGravity(Gravity.CENTER_VERTICAL);
        version.setClipChildren(false);
        version.setClipToPadding(false);
        version.setVisibility(View.GONE);
        left.addView(version, lp(0, dp(10), 0, 0));
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        left.addView(buttons, lp(0, dp(10), 0, 0));

        // the right column: the steps card, under it the card Firmware versions (facts only, no choice in this column,
        // so every version row has room on every model)
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(330), LinearLayout.LayoutParams.MATCH_PARENT);
        rp.leftMargin = dp(28);
        root.addView(side, rp);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(18), dp(16), dp(18), dp(16));
        right.setBackground(round(CARD, dp(18)));
        side.addView(right, lp(0, 0, 0, 0));
        steps = right;
        String[] names = { getString(R.string.step_check), getString(R.string.step_download), getString(R.string.step_verify),
                getString(R.string.step_prepare), getString(R.string.step_start) };
        for (int i = 0; i < 5; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            stepMark[i] = Icons.view(this, R.drawable.lumen_ic_pending, 18);
            Icons.status(stepMark[i], IconStatus.PENDING, DIM);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(18), dp(18));
            mp.setMarginEnd(dp(8));
            row.addView(stepMark[i], mp);
            stepName[i] = text((i + 1) + ". " + names[i], 15, TEXT);
            row.addView(stepName[i], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            stepInfo[i] = text("", 12, DIM);
            row.addView(stepInfo[i]);
            right.addView(row, lp(0, i == 0 ? 0 : dp(9), 0, 0));
        }
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Look.ACC));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Look.CARD2));
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Look.ACC));
        right.addView(bar, lp(0, dp(16), 0, 0));
        rate = text("", 12, DIM);
        rate.setVisibility(View.GONE);
        right.addView(rate, lp(0, dp(6), 0, 0));
        // the technical log is not shown here (plain words only): About shows its last lines under "Details for help"
        // as tall as its rows (no empty block under them), at most the room left under the steps card
        side.addView(versionsCard(), lp(0, dp(12), 0, 0));
        setContentView(root);
        TvFocus.unclip(root);
    }

    @Override protected void onResume() {
        super.onResume();
        Snap s = Snap.I;
        Engine e = WorkService.engine(this);
        if (s.det == null) s.det = e.detect();
        // the signed catalog: asked for once per start of the app (it lists the models that have a build, and their
        // builds); the last verified copy is used until it arrives
        if (!s.catalogTried && !s.busy && s.gate.held() == null) {
            s.catalogTried = true;
            act(Task.CATALOG);
        }
        s.channel = e.channel();
        seenSeq = -1;
        render();
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        backDown = false;
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (Snap.I.seq != seenSeq) render();
            h.postDelayed(this, 300);
        }
    };

    /** While a task runs the remote does nothing on its button, see {@link ActionGate#swallows}. There is no Close
     *  button: BACK closes the app wherever the gate lets it through (pressed and let go on this screen). */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        View f = getCurrentFocus();
        if (Snap.I.gate.swallows(key(e.getKeyCode()), f != null && f.getTag() == OFFERED)) return true;
        if (key(e.getKeyCode()) == ActionGate.Key.BACK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) backDown = true;
            else if (e.getAction() == KeyEvent.ACTION_UP && backDown && !e.isCanceled()) finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    static ActionGate.Key key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_SPACE:
                return ActionGate.Key.SELECT;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                return ActionGate.Key.BACK;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_TAB:
                return ActionGate.Key.MOVE;
            default:
                return ActionGate.Key.OTHER;
        }
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
            boolean loading = d.verdict == Msg.CATALOG_MISSING && s.gate.held() == Task.CATALOG;
            verdict.setText(loading ? msg(Msg.CATALOG_LOADING, null) : msg(d.verdict, d.args));
            verdict.setTextColor(d.ok() || d.verdict == Msg.APP_UPDATE_OFFERED ? OK : loading || d.verdict == Msg.CATALOG_MISSING ? WARN : BAD);
            // an installed Lumen OS: the trial text does not apply
            boolean lumen = d.verdict == Msg.ALREADY_LUMEN || d.verdict == Msg.APP_UPDATE_OFFERED;
            trialInfo.setVisibility(lumen ? View.GONE : View.VISIBLE);
            // only before the first step ran and while nothing runs (nothing to undo then)
            boolean force = d.forceOffered && !s.busy && s.gate.held() == null && !s.prepared && !s.armed
                    && s.trial == TrialState.NONE;
            forceBox.setVisibility(force ? View.VISIBLE : View.GONE);
            forceCheck.setChecked(d.forced);
        }
        // the last three messages of this run, newest last (e.g. the release found, the space check, the step result)
        List<Snap.Line> ms = s.messages();
        statusLines.clear();
        for (int i = Math.max(0, ms.size() - 3); i < ms.size(); i++) statusLines.add(msg(ms.get(i).msg, ms.get(i).args));
        fitStatus();
        for (int i = 0; i < 5; i++) {
            StepStatus st = s.steps[i];
            Icons.status(stepMark[i], st == StepStatus.DONE ? IconStatus.DONE : st == StepStatus.FAILED ? IconStatus.FAILED
                    : st == StepStatus.RUNNING ? IconStatus.RUNNING : IconStatus.PENDING,
                    st == StepStatus.DONE ? OK : st == StepStatus.FAILED ? BAD : st == StepStatus.RUNNING ? ACCENT : DIM);
            stepInfo[i].setText("");
        }
        int cur = s.curStep;
        if (cur >= 0 && s.total > 0 && s.steps[cur] == StepStatus.RUNNING) {
            bar.setIndeterminate(false);
            bar.setProgress((int) (s.done * 1000 / s.total));
            stepInfo[cur].setText(cur == 1 ? (s.done >> 20) + " / " + (s.total >> 20) + " MB" : (s.done * 100 / s.total) + "%");
        } else if (s.busy && s.task == Task.DUMP && s.total > 0) {
            // the stock system info goes out outside the five steps: the bar alone, bytes sent of the zip
            bar.setIndeterminate(false);
            bar.setProgress((int) (Math.min(s.done, s.total) * 1000 / s.total));
        } else if (s.busy && cur >= 0 && s.steps[cur] == StepStatus.RUNNING) {
            bar.setIndeterminate(true);
        } else {
            bar.setIndeterminate(false);
            bar.setProgress(0);
        }
        renderRate(s);
        renderVersions(d);
        renderButtons(s);
        contrib.render(s);
    }

    /** While the image or an app update downloads: the speed of the last seconds and about how many minutes are left
     *  (the updater's texts), the line kept free until the first speed is known so the card does not jump. */
    void renderRate(Snap s) {
        int dl = InstallStep.DOWNLOAD.ordinal();
        boolean running = s.busy && s.curStep == dl && s.steps[dl] == StepStatus.RUNNING && s.total > 0;
        long sp = s.speed, left = s.total - s.done;
        rate.setVisibility(!running ? View.GONE : sp > 0 && left > 0 ? View.VISIBLE : View.INVISIBLE);
        if (!running || sp <= 0 || left <= 0) return;
        int min = (int) Math.max(1, left / sp / 60);
        rate.setText(getString(R.string.dl_rate, Formatter.formatShortFileSize(this, sp),
                getResources().getQuantityString(R.plurals.dl_eta, min, min)));
    }

    void renderButtons(Snap s) {
        Engine.Detection d = s.det;
        Task held = s.gate.held();
        String key;
        if (held != null) key = "held:" + held + (held.pausable && s.busy && s.task == held ? ":pause" : "");
        else if (s.busy) key = "busy";
        else if (d != null && d.verdict == Msg.CATALOG_MISSING) key = "nocatalog";
        else if (s.appUpdated) key = "appdone";
        else if (d != null && d.appUpdate != null) key = "appupdate";
        else if (d == null || !d.ok()) key = "refused";
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
        if (held != null) {
            // the task runs: its button says so and does nothing, the other buttons are gone until it ends (a failure
            // brings them back with the reason); a download offers Pause
            first = add(busyLabel(held), v -> { });
            if (key.endsWith(":pause")) add(R.string.btn_pause, v -> startService(WorkService.task(this, "stop"))).setTag(OFFERED);
            first.requestFocus();
        } else if ("nocatalog".equals(key)) {
            // no verified catalog yet: ask again
            first = add(R.string.btn_retry, v -> {
                if (Snap.I.busy) return;
                act(Task.CATALOG);
                Snap.I.det = WorkService.engine(this).detect();
                Snap.I.seq++;
                render();
            });
            addDumpButtons(d);
        } else if ("refused".equals(key)) {
            // an unknown model or stock version: the system info helps us make a build for it
            first = addDumpButtons(d);
        } else if ("appupdate".equals(key)) {
            // an installed Lumen OS whose update app the catalog fixes: that app first, then Lumen OS updates itself
            first = add(R.string.btn_app_update, v -> act(Task.APP_UPDATE));
        } else if ("appdone".equals(key)) {
            first = add(R.string.btn_open_updater, v -> openUpdater());
        } else if ("idle".equals(key)) {
            first = add(R.string.btn_prepare, v -> act(Task.PREPARE));
            addDumpButtons(d);
        } else if ("continue".equals(key)) {
            first = add(R.string.btn_continue, v -> act(Task.PREPARE));
            addDumpButtons(d);
        } else if ("ready".equals(key)) {
            String keep = getString(s.handover == 1 ? R.string.confirm_keep_yes : R.string.confirm_keep_no);
            String space = s.need.isEmpty() ? "" : " " + getString(R.string.confirm_space, Engine.gb(Engine.parseLongSafe(s.need)),
                    Engine.gb(Engine.parseLongSafe(s.free)));
            // the catalog says the install from the trial is not open for this build and stock: said before the start
            String only = s.trialOnly ? " " + getString(R.string.confirm_trial_only, d.name) : "";
            first = add(R.string.btn_start, v -> confirm(R.string.confirm_start_title,
                    getString(R.string.confirm_start_body) + " " + keep + space + only, R.string.btn_start, () -> act(Task.START)));
            add(R.string.btn_later, v -> finish());
        } else if ("armed".equals(key)) {
            first = add(R.string.btn_restart, v -> act(Task.RESTART));
            add(R.string.btn_later, v -> finish());
        } else if ("ours".equals(key)) {
            // a newer image (stick folder / release) replaces the old trial in one go: remove, prepare, start
            first = add(R.string.btn_replace, v -> confirm(R.string.confirm_start_title, getString(R.string.confirm_replace_body),
                    R.string.btn_replace, () -> act(Task.REPLACE)));
            add(R.string.btn_again, v -> act(Task.AGAIN));
            add(R.string.btn_remove, v -> confirm(R.string.confirm_remove_title, getString(R.string.confirm_remove_body),
                    R.string.btn_remove, () -> act(Task.REMOVE)));
        } else if ("other".equals(key)) {
            first = add(R.string.btn_remove, v -> confirm(R.string.confirm_remove_title, getString(R.string.confirm_remove_body),
                    R.string.btn_remove, () -> act(Task.REMOVE)));
        }
        // no button (a refused model without the system info): the "start anyway" row when it is offered
        final View f = first != null ? first : forceBox.getVisibility() == View.VISIBLE ? forceRow : null;
        if (f != null) buttons.post(() -> f.requestFocus());
        linkFocus();
    }

    /** D-pad: the "start anyway" row, the source choice, the version choice and the buttons are rows of one column
     *  (UP / DOWN between them, UP from the top one reaches the round "i"); LEFT on the first control of a row and
     *  RIGHT on the last stay there, so the sideways keys never jump up to the "i". */
    void linkFocus() {
        for (LinearLayout row : new LinearLayout[] { forceBox, options, version, buttons }) {
            View first = null, last = null;
            for (int i = 0; i < row.getChildCount(); i++) {
                View v = row.getChildAt(i);
                v.setNextFocusLeftId(View.NO_ID);
                v.setNextFocusRightId(View.NO_ID);
                if (!v.isFocusable()) continue;
                if (first == null) first = v;
                last = v;
            }
            if (first == null) continue;
            first.setNextFocusLeftId(ownId(first));
            last.setNextFocusRightId(ownId(last));
        }
    }

    static int ownId(View v) {
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        return v.getId();
    }

    /** The choices, only before the first step ran (nothing to undo then), above the buttons: the version (a model
     *  with a stable and a beta build: the stable build by default, Beta switches to the newest beta) with a line beside it on what it means, or for a model with a beta only the line that only a
     *  beta exists (no choice). */
    void renderOptions(Snap s, String key) {
        boolean show = "idle".equals(key) || "continue".equals(key) || "nocatalog".equals(key);
        boolean beta = show && s.det != null && s.det.betaOffered;
        boolean only = show && s.det != null && s.det.betaOnly;
        String k = show ? "|" + (beta ? s.channel + s.det.stableVersion + s.det.betaVersion : only ? "only" : "-") : "";
        if (k.equals(optionsKey)) return;
        optionsKey = k;
        View cur = getCurrentFocus();   // a toggle keeps the focus where it was
        int fi = options.indexOfChild(cur);
        Object tag = cur != null ? cur.getTag() : null;
        options.removeAllViews();
        version.removeAllViews();
        version.setVisibility(beta || only ? View.VISIBLE : View.GONE);
        if (!show) return;
        View focus = null;
        if (beta) {
            // Version: Stable (the catalog's stable build) or Beta (its newest beta, the same safety check); remembered
            Button b = addTo(version, s.channel == ReleaseChannel.BETA ? getString(R.string.opt_version_beta, s.det.betaVersion)
                    : getString(R.string.opt_version_stable, s.det.stableVersion), v -> {
                if (Snap.I.busy || Snap.I.gate.held() != null) return;
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
            TextView hint = text(getString(s.channel == ReleaseChannel.BETA ? R.string.opt_version_beta_hint : R.string.opt_version_stable_hint), 13, DIM);
            hint.setLineSpacing(0, 1.05f);
            version.addView(hint, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        } else if (only) {
            // a model with a beta only: no Stable / Beta choice, one line (the beta is read without a choice)
            version.addView(text(getString(R.string.opt_version_beta_only), 14, TEXT), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        if (focus != null) { final View f = focus; version.post(f::requestFocus); }
        else if (fi >= 0 && fi < options.getChildCount()) { final View f = options.getChildAt(fi); options.post(f::requestFocus); }
    }

    /** "start the trial anyway" on or off: the verdict and the build follow at once, nothing ran yet */
    void toggleForce() {
        if (Snap.I.busy || Snap.I.gate.held() != null) return;
        Engine e = WorkService.engine(this);
        e.setForce(!e.force());
        Snap.I.det = e.detect();
        Snap.I.resetSteps();
        Snap.I.prepared = false;
        render();
        forceRow.requestFocus();
    }

    /** the stock system info: asked first, sent by the service */
    void askDump() {
        confirm(R.string.dump_title, getString(R.string.dump_body), R.string.btn_send, () -> act(Task.DUMP));
    }

    /** on the XGIMI system (not in a trial, not on Lumen OS): send the system info (the full firmware copy to a USB
     *  stick is LumenDump's) */
    Button addDumpButtons(Engine.Detection d) {
        return Engine.dumpAllowed(d) ? add(R.string.btn_dump, v -> askDump()) : null;
    }

    /** Every button that starts a task comes here: the first press takes the gate and hands the task to the service,
     *  every later press does nothing until the task ends (ActionGate). */
    void act(Task t) {
        boolean taken = Snap.I.gate.press(t, () -> {
            Intent i = WorkService.task(this, t.id());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        });
        if (!taken) return;
        Snap.I.seq++;
        render();
    }

    /** what the button of a running task says */
    static int busyLabel(Task t) {
        switch (t) {
            case CATALOG: return R.string.btn_busy_catalog;
            case PREPARE: case REPLACE: return R.string.btn_busy_prepare;
            case START: case AGAIN: return R.string.btn_busy_start;
            case REMOVE: return R.string.btn_busy_remove;
            case APP_UPDATE: return R.string.btn_busy_app_update;
            case DUMP: return R.string.btn_busy_dump;
            default: return R.string.btn_busy_restart;
        }
    }

    /** The update screen of Lumen OS (the system update settings), of the app just updated when it has one. */
    void openUpdater() {
        Intent i = new Intent("android.settings.SYSTEM_UPDATE_SETTINGS");
        Intent own = new Intent(i).setPackage(Snap.I.appPkg);
        if (!Snap.I.appPkg.isEmpty() && own.resolveActivity(getPackageManager()) != null) i = own;
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Snap.I.message(Msg.ERROR, new String[] { String.valueOf(e.getMessage()) });
            render();
        }
    }

    /** asks once: a second press while the question is open does not open another one */
    void confirm(int title, String body, int yes, Runnable r) {
        if (asking != null && asking.isShowing()) return;
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
        asking = d;
        d.show();
    }

    // ------------------------------------------------------------------------------------------- firmware versions
    /** The card under the steps: its title and the rows. It is as tall as its rows and never takes focus (read only
     *  facts): a list longer than the room left under the steps card fades out at the card's lower edge. */
    View versionsCard() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView title = Look.text(this, getString(R.string.versions_title), 15, TEXT, 560);
        content.addView(title);
        versionRows = new LinearLayout(this);
        versionRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(versionRows, lp(0, dp(2), 0, 0));
        versions = new ScrollView(this);
        versions.setBackground(round(CARD, dp(18)));
        versions.setPadding(dp(18), dp(12), dp(18), dp(10));
        versions.setFocusable(false);
        versions.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        versions.setDefaultFocusHighlightEnabled(false);
        versions.setVerticalScrollBarEnabled(false);
        versions.setVerticalFadingEdgeEnabled(true);
        versions.setFadingEdgeLength(dp(28));
        versions.addView(content);
        versions.setVisibility(View.GONE);
        return versions;
    }

    /** One row per version the projector names (lumen-about AboutRows.versions), the card hidden when it names none. */
    void renderVersions(Engine.Detection d) {
        List<AboutRow> rows = d == null || d.facts == null ? java.util.Collections.emptyList()
                : AboutRows.versions(d.facts, this::rowText, true);
        StringBuilder key = new StringBuilder();
        for (AboutRow r : rows) key.append(r.title).append('\t').append(r.summary).append('\n');
        if (key.toString().equals(versionsKey)) return;
        versionsKey = key.toString();
        versionRows.removeAllViews();
        // one row view per version: the name and its value side by side on the first line, a long value wraps inside
        // its own row (the name stays at the row's top) and a thin line closes every row, so a wrapped value never
        // reads as the value of the next name
        for (AboutRow r : rows) {
            if (versionRows.getChildCount() > 0) {
                View line = new View(this);
                line.setBackgroundColor(Look.CARD3);
                versionRows.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(2), 0, dp(2));
            TextView name = Look.text(this, r.title, 12, DIM, 400);
            name.setLineSpacing(0, 1f);
            name.setMaxWidth(dp(150));
            row.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            // a hyphen before a letter may end a line (a long platform name breaks between its words), a date never does
            TextView value = Look.text(this, r.summary.replaceAll("-(?=\\p{L})", "-\u200B"), 12, TEXT, 400);
            value.setLineSpacing(0, 1f);
            value.setGravity(Gravity.END);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            vp.setMarginStart(dp(12));
            row.addView(value, vp);
            versionRows.addView(row, lp(0, 0, 0, 0));
        }
        // no versions: the steps card fills the column as before
        boolean none = rows.isEmpty();
        versions.setVisibility(none ? View.GONE : View.VISIBLE);
        steps.setLayoutParams(none ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) : lp(0, 0, 0, 0));
    }

    /** a row text of lumen-about: RowText X is the string lumen_about_x (the module's texts are part of this app) */
    String rowText(RowText t) {
        int id = getResources().getIdentifier("lumen_about_" + t.name().toLowerCase(Locale.ROOT), "string", getPackageName());
        return id == 0 ? t.name() : getString(id);
    }

    /** The newest messages whose lines fit the room of the status line (at least the newest one), newest last. */
    void fitStatus() {
        int w = status.getWidth() - status.getTotalPaddingLeft() - status.getTotalPaddingRight(), room = status.getHeight();
        int n = statusLines.size();
        while (n > 1 && w > 0 && room > 0 && textHeight(newest(n), w) > room) n--;
        String t = newest(n);
        if (!t.contentEquals(status.getText())) status.setText(t);
    }

    String newest(int n) {
        return String.join("\n", statusLines.subList(statusLines.size() - n, statusLines.size()));
    }

    int textHeight(String t, int w) {
        return android.text.StaticLayout.Builder.obtain(t, 0, t.length(), status.getPaint(), w)
                .setLineSpacing(status.getLineSpacingExtra(), status.getLineSpacingMultiplier())
                .setIncludePad(status.getIncludeFontPadding()).build().getHeight();
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
