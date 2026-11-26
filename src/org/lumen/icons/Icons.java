// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.icons;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * The Lumen icons in views (README.md). An icon view never takes the focus and draws in the state of its row
 * (duplicateParentState): white on the focused accent row, grey when the row is disabled, the text colour otherwise.
 * The marks of the stock Android TV rows sit at the row's end: {@link #check}, {@link #radio}, {@link #toggle},
 * {@link #chevron}.
 */
public final class Icons {
    /** The size of a mark at the end of a list row, in dp (the stock rows draw their marks at 24 dp). */
    public static final int ROW_DP = 24;
    /** A switch is drawn wider: the toggle icon's track is 40 dp long at this size. */
    public static final int TOGGLE_DP = 40;

    private Icons() {}

    /** An icon of this set at {@code dp}, tinted by the state of its row ({@code R.color.lumen_icon_tint}). */
    public static ImageView view(Context c, int icon, int dp) {
        ImageView v = new ImageView(c);
        v.setFocusable(false);
        v.setDuplicateParentStateEnabled(true);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setImageResource(icon);
        int px = px(c, dp);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        return v;
    }

    /** The same, in the given colours (a row whose text has its own colours: the icon follows the text). */
    public static ImageView view(Context c, int icon, int dp, ColorStateList tint) {
        ImageView v = view(c, icon, dp);
        v.setImageTintList(tint);
        return v;
    }

    /** A drawable of this set in one colour (a canvas or a span draws it). */
    public static Drawable drawable(Context c, int icon, int color) {
        Drawable d = c.getDrawable(icon).mutate();
        d.setTintList(ColorStateList.valueOf(color));
        return d;
    }

    /** The colours of a row: focused, disabled, otherwise. */
    public static ColorStateList tint(int normal, int focused, int disabled) {
        return new ColorStateList(new int[][] {
                {-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[] {disabled, focused, focused, normal});
    }

    /** A tick at the row's end of a list where several entries can be chosen; invisible while not chosen. */
    public static void check(ImageView v, boolean on) {
        v.setImageResource(R.drawable.lumen_ic_check);
        v.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    /** The radio button at the row's end of a single choice list: the chosen one in the accent. */
    public static void radio(ImageView v, boolean on) {
        v.setImageResource(on ? R.drawable.lumen_ic_radio_on : R.drawable.lumen_ic_radio_off);
        v.setImageTintList(v.getContext().getColorStateList(on ? R.color.lumen_icon_tint_on : R.color.lumen_icon_tint));
    }

    /** The switch at the row's end of a setting that is on or off: on in the accent, as the stock lists. */
    public static void toggle(ImageView v, boolean on) {
        v.setImageResource(on ? R.drawable.lumen_ic_toggle_on : R.drawable.lumen_ic_toggle_off);
        v.setImageTintList(v.getContext().getColorStateList(on ? R.color.lumen_icon_tint_on : R.color.lumen_icon_tint));
    }

    /** The icon of a step or check state. */
    public static int statusIcon(IconStatus s) {
        switch (s) {
            case RUNNING: return R.drawable.lumen_ic_progress;
            case DONE: return R.drawable.lumen_ic_check_circle;
            case FAILED: return R.drawable.lumen_ic_error;
            case WARN: return R.drawable.lumen_ic_warning;
            case INFO: return R.drawable.lumen_ic_info;
            default: return R.drawable.lumen_ic_pending;
        }
    }

    /** Shows a step or check state in {@code color}; a running step's arc turns until the state changes. */
    public static void status(ImageView v, IconStatus s, int color) {
        v.setImageResource(statusIcon(s));
        v.setImageTintList(ColorStateList.valueOf(color));
        Object running = v.getTag(R.drawable.lumen_ic_progress);
        if (s == IconStatus.RUNNING && running == null) {
            ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f);
            a.setDuration(1000);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new LinearInterpolator());
            a.start();
            v.setTag(R.drawable.lumen_ic_progress, a);
        } else if (s != IconStatus.RUNNING && running != null) {
            ((ObjectAnimator) running).cancel();
            v.setRotation(0f);
            v.setTag(R.drawable.lumen_ic_progress, null);
        }
    }

    /** A row that opens a page: a chevron at its end (mirrored in a right to left UI). */
    public static ImageView chevron(Context c) {
        return view(c, R.drawable.lumen_ic_chevron_right, ROW_DP);
    }

    public static int px(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }
}
