package org.lumen.installer;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

/**
 * The D-pad focus look of every button in this app, the same as the Lumen projector menu:
 * the focused button is an accent fill with a white outline, white text and a 1.04 scale; the others are a muted dark
 * pill with grey text and no outline, so the remote's position is obvious on a projected picture. Text weight does
 * not change with focus, so a row of wrap_content buttons never shifts.
 */
final class TvFocus {
    // Lumen tokens: accent #3B78E7, surfaces of the quick menu
    static final int ACCENT = Look.ACC, ACCENT_PRESSED = 0xFF2F63C4, IDLE = Look.CARD2, IDLE_TEXT = Look.TEXT,
            OFF = 0xFF1A1E25, OFF_TEXT = 0xFF5D6570, PANEL = Look.CARD;
    static final float SCALE = 1.04f;

    private TvFocus() {}

    /** Styles one button: focused = accent + white outline + scale, unfocused = muted, disabled = darker still. */
    static void button(Button b) {
        int r = dp(b, 24), w = dp(b, 2);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] { -android.R.attr.state_enabled }, pill(OFF, r, 0));
        bg.addState(new int[] { android.R.attr.state_pressed }, pill(ACCENT_PRESSED, r, w));
        bg.addState(new int[] { android.R.attr.state_focused }, pill(ACCENT, r, w));
        bg.addState(new int[0], pill(IDLE, r, 0));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(
                new int[][] { { -android.R.attr.state_enabled }, { android.R.attr.state_focused }, { android.R.attr.state_pressed }, {} },
                new int[] { OFF_TEXT, Color.WHITE, Color.WHITE, IDLE_TEXT }));
        b.setTypeface(Look.font(b.getContext(), 560));
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, has) -> scale(v, has));
        scale(b, b.isFocused());
    }

    /** The buttons of a shown dialog (call from its OnShowListener): same look, a gap so a scaled one never overlaps. */
    static void dialog(AlertDialog d) {
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(card(Look.CARD, Look.dp(d.getContext(), 18), 0, 0));
        android.widget.TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) { msg.setTypeface(Look.font(d.getContext(), 400)); msg.setTextColor(Look.TEXT); msg.setLineSpacing(0, 1.18f); }
        int tid = d.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        android.widget.TextView title = tid == 0 ? null : d.findViewById(tid);
        if (title != null) { title.setTypeface(Look.font(d.getContext(), 700)); title.setTextColor(Look.TEXT); }
        for (int which : new int[] { AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE }) {
            Button b = d.getButton(which);
            if (b == null || b.getVisibility() != View.VISIBLE) continue;
            button(b);
            b.setPadding(dp(b, 20), 0, dp(b, 20), 0);
            b.setMinHeight(dp(b, 44));
            b.setMinimumHeight(dp(b, 44));
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                m.setMargins(dp(b, 8), dp(b, 6), dp(b, 8), dp(b, 6));
                b.setLayoutParams(m);
            }
        }
    }

    /** A focusable panel that is not a button (a scrollable log): muted card, a white outline while focused. */
    static void panel(View v) {
        int r = dp(v, 10);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] { android.R.attr.state_focused }, card(PANEL, r, dp(v, 2), Color.WHITE));
        bg.addState(new int[0], card(PANEL, r, 0, 0));
        v.setBackground(bg);
    }

    static void scale(View v, boolean has) {
        float s = has ? SCALE : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(90).start();
    }

    /** The focused button grows (scale + white outline) past its own bounds: no container may cut it off. */
    static void unclip(View v) {
        if (!(v instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        g.setClipChildren(false);
        g.setClipToPadding(false);
        for (int i = 0; i < g.getChildCount(); i++) unclip(g.getChildAt(i));
    }

    static GradientDrawable pill(int color, int r, int stroke) { return card(color, r, stroke, Color.WHITE); }

    static GradientDrawable card(int color, int r, int stroke, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        if (stroke > 0) g.setStroke(stroke, strokeColor);
        return g;
    }

    static int dp(View v, int x) { return Math.round(x * v.getResources().getDisplayMetrics().density); }
}
