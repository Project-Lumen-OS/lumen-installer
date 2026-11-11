package org.lumen.installer;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Lumen look of this app: the Lumen brand tokens (dark surfaces, text #E8EAED, accent #3B78E7) and the
 * quick-menu style (dark rounded cards, 36 px corners on a card, a pill per button, the focused element filled with
 * the accent), font Onest. Onest comes from this APK's assets when the build staged it (build.sh), else the system
 * sans-serif (on the stock XGIMI system there is no Lumen Home to borrow it from).
 */
final class Look {
    static final int BG = 0xFF0F1115, CARD = 0xFF1E232C, CARD2 = 0xFF263041, CARD3 = 0xFF323C4F,
            TEXT = 0xFFE8EAED, DIM = 0xFF9AA0A6, ACC = 0xFF3B78E7, ACC_SOFT = 0xFF8AB4F8,
            OK = 0xFF81C995, BAD = 0xFFF28B82, WARN = 0xFFFDD663;
    static final String FONT = "fonts/Onest-Variable.ttf";
    private static Typeface regular, medium, bold;

    private Look() {}

    static synchronized Typeface font(Context c, int weight) {
        if (regular == null) {
            regular = load(c, 400);
            medium = load(c, 560);
            bold = load(c, 700);
        }
        return weight >= 650 ? bold : weight >= 500 ? medium : regular;
    }

    private static Typeface load(Context c, int w) {
        for (String pkg : new String[] {null, "org.lumen.home"}) {
            try {
                AssetManager am = pkg == null ? c.getAssets() : c.createPackageContext(pkg, 0).getAssets();
                Typeface t = new Typeface.Builder(am, FONT).setFontVariationSettings("'wght' " + w).build();
                if (t != null) return t;
            } catch (Exception ignored) {
                // not in this APK / no Lumen Home: next source
            }
        }
        return Typeface.create(w >= 650 ? "sans-serif" : w >= 500 ? "sans-serif-medium" : "sans-serif",
                w >= 650 ? Typeface.BOLD : Typeface.NORMAL);
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(int color, int radius, int stroke, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke > 0) g.setStroke(stroke, strokeColor);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(c, weight));
        t.setLineSpacing(0, 1.18f);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A dark rounded card (36 px corners at the TV's 2x density), content column inside. */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, dp(c, 18), 0, 0));
        int p = dp(c, 22);
        l.setPadding(p, dp(c, 18), p, dp(c, 18));
        return l;
    }

    /** A focusable row (the Google services switch): card look, accent outline and fill while focused. */
    static void focusRow(View v) {
        Context c = v.getContext();
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(CARD2, dp(c, 18), dp(c, 2), ACC));
        bg.addState(new int[0], round(CARD, dp(c, 18), 0, 0));
        v.setBackground(bg);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((x, has) -> x.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(90).start());
    }

    /** The yes / no pill at the end of a switch row. */
    static TextView pill(Context c, String s, boolean on) {
        TextView t = text(c, s, 17, on ? 0xFFFFFFFF : TEXT, 560);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(on ? ACC : CARD3, dp(c, 16), 0, 0));
        t.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        t.setMinWidth(dp(c, 72));
        return t;
    }

    /** The round "i" (About) of the header. */
    static TextView infoButton(Context c) {
        TextView t = text(c, "i", 20, TEXT, 700);
        t.setGravity(Gravity.CENTER);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable f = round(ACC, dp(c, 22), dp(c, 2), 0xFFFFFFFF);
        f.setShape(GradientDrawable.OVAL);
        GradientDrawable n = round(CARD2, dp(c, 22), 0, 0);
        n.setShape(GradientDrawable.OVAL);
        bg.addState(new int[] {android.R.attr.state_focused}, f);
        bg.addState(new int[0], n);
        t.setBackground(bg);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((x, has) -> x.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(90).start());
        return t;
    }
}
