// SPDX-License-Identifier: Apache-2.0
package org.lumen.qr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;

/**
 * A QR code on a white card with the 4-module quiet zone, crisp at any size: every module is a whole number of pixels
 * on a whole-pixel grid and the dark modules are one path drawn without anti-aliasing, so no grey seam shows between
 * neighbours. {@link #quiet} sets the white margin in modules (0 when a white card around the view gives it).
 */
public final class QrView extends View {
    /** the quiet zone of the standard, in modules on each side */
    static final int QUIET = 4;
    private static final int CARD = 0xFFFFFFFF, DARK = 0xFF000000;
    private static final float CARD_RADIUS_DP = 10;

    private final Paint dark = new Paint();
    private final Paint white = new Paint();
    private final GradientDrawable card = new GradientDrawable();
    private final Path path = new Path();
    private int pathFor = -1;
    private int quiet = QUIET;
    private QrCode qr;

    public QrView(Context c, String text) {
        super(c);
        dark.setColor(DARK);
        dark.setAntiAlias(false);
        white.setColor(CARD);
        card.setColor(CARD);
        card.setCornerRadius(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CARD_RADIUS_DP,
                c.getResources().getDisplayMetrics())));
        setText(text);
    }

    /** Shows the code of another text (the same view, drawn again). */
    public void setText(String text) {
        qr = QrCode.encode(text);
        setContentDescription(text);
        pathFor = -1;
        invalidate();
    }

    /** Modules of white around the code inside the view; 0 draws a plain white square for a card that has its own. */
    public QrView quiet(int modules) {
        quiet = Math.max(0, modules);
        pathFor = -1;
        invalidate();
        return this;
    }

    /** Pixels per module: a whole number, so every module edge falls on a pixel. */
    public static int module(int px, int size, int quiet) {
        return Math.max(1, px / (size + 2 * quiet));
    }

    /** The whole-pixel offset that centres {@code size} modules of {@code m} px in {@code px}. */
    public static int offset(int px, int size, int m) {
        return (px - m * size) / 2;
    }

    @Override
    protected void onDraw(Canvas cv) {
        int s = Math.min(getWidth(), getHeight());
        if (quiet > 0) {
            card.setBounds(0, 0, s, s);
            card.draw(cv);
        } else {
            cv.drawRect(0, 0, s, s, white);
        }
        if (pathFor != s) {
            int n = qr.size, m = module(s, n, quiet), o = offset(s, n, m);
            path.reset();
            for (int y = 0; y < n; y++) {
                int x = 0;
                while (x < n) {
                    if (!qr.dark(x, y)) {
                        x++;
                        continue;
                    }
                    int x0 = x;
                    while (x < n && qr.dark(x, y)) x++;    // one rectangle per dark run of the row
                    path.addRect(o + x0 * m, o + y * m, o + x * m, o + (y + 1) * m, Path.Direction.CW);
                }
            }
            pathFor = s;
        }
        cv.drawPath(path, dark);
    }
}
