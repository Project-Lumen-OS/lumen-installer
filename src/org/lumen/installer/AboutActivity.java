package org.lumen.installer;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.lumen.installer.core.Engine;
import org.lumen.installer.core.Msg;

import java.util.List;

/**
 * About (the round "i" of the main screen): the installer and Lumen OS versions, the projector and the XGIMI firmware
 * it found, what the trial does in plain words, how to go back, the open-source licenses, the project page, and the
 * last lines of the technical log folded away under "Details for help". Read-only.
 * Every card is focusable so the D-pad scrolls through them.
 */
public class AboutActivity extends Activity {
    LinearLayout col;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Look.BG);
        sv.setDefaultFocusHighlightEnabled(false);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(56), dp(32), dp(56), dp(32));
        sv.addView(col);
        setContentView(sv);
        TvFocus.unclip(sv);
        col.addView(Look.text(this, getString(R.string.about_title), 30, Look.TEXT, 700), lp(0, 0, 0, dp(16)));

        Snap s = Snap.I;
        Engine.Detection d = s.det;
        String unknown = getString(R.string.about_unknown);
        String app = unknown;
        try { app = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { /* stays unknown */ }
        String lumen = null;
        for (Snap.Line l : s.messages())
            if ((l.msg == Msg.RELEASE_FOUND || l.msg == Msg.STICK_FOUND) && l.args != null && l.args.length > 0) lumen = l.args[0];
        card(R.string.about_device_title,
                getString(R.string.about_device, d == null || d.name.isEmpty() ? unknown : d.name, d == null || d.code.isEmpty() ? "?" : d.code),
                getString(R.string.about_stock, d == null || d.stock.isEmpty() ? unknown : d.stock),
                lumen == null ? getString(R.string.about_lumen_unknown) : getString(R.string.about_lumen, lumen),
                getString(R.string.about_installer, app));
        card(R.string.about_what_title, getString(R.string.about_what_1), getString(R.string.about_what_2), getString(R.string.about_what_3));
        card(R.string.about_back_title, getString(R.string.about_back_1), getString(R.string.about_back_2), getString(R.string.about_back_3));
        card(R.string.about_lic_title, getString(R.string.about_lic_installer), getString(R.string.about_lic_aosp), getString(R.string.about_lic_font));
        card(R.string.about_project_title, getString(R.string.about_project_1), getString(R.string.about_project_2));

        // the technical log, folded away (for whoever helps)
        StringBuilder lb = new StringBuilder();
        List<String> tail = s.tail(30);
        for (String l : tail) lb.append(l).append('\n');
        TextView log = Look.text(this, lb.length() == 0 ? getString(R.string.about_log_empty) : lb.toString().trim(), 12, Look.DIM, 400);
        log.setVisibility(View.GONE);
        log.setPadding(dp(16), dp(12), dp(16), dp(12));
        log.setBackground(Look.round(Look.CARD, dp(14), 0, 0));
        col.addView(log, lp(0, 0, 0, dp(10)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        Button back = button(R.string.btn_back);
        back.setOnClickListener(v -> finish());
        Button det = button(R.string.about_log);
        det.setOnClickListener(v -> {
            boolean show = log.getVisibility() != View.VISIBLE;
            log.setVisibility(show ? View.VISIBLE : View.GONE);
            det.setText(show ? R.string.about_log_hide : R.string.about_log);
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        bp.rightMargin = dp(14);
        row.addView(back, bp);
        row.addView(det, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        col.addView(row, lp(0, dp(8), 0, 0));
        back.post(back::requestFocus);
    }

    Button button(int label) {
        Button b = new Button(this);
        b.setText(label);
        TvFocus.button(b);
        b.setTextSize(16);
        b.setMinWidth(dp(120));
        b.setPadding(dp(22), 0, dp(22), 0);
        return b;
    }

    void card(int title, String... lines) {
        LinearLayout c = Look.card(this);
        Look.focusRow(c);
        c.addView(Look.text(this, getString(title), 19, Look.ACC_SOFT, 560));
        for (String l : lines) c.addView(Look.text(this, l, 15, Look.TEXT, 400), lp(0, dp(7), 0, 0));
        col.addView(c, lp(0, 0, 0, dp(12)));
    }

    int dp(int v) { return Look.dp(this, v); }

    LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }
}
