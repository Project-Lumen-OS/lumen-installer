// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.icons;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

/**
 * An icon inside a line of text: a remote key in a hint ({@link #key}), a state mark at the start of a line
 * ({@link #mark}). Its size follows the text size: the icon box is {@code box} times the text size, centred on the
 * middle of the lower case letters, and it advances {@code advance} times the text size. A key takes the colour of the
 * text around it at draw time (white with the text of a focused row); a mark keeps its own colour.
 */
public final class IconSpan extends ReplacementSpan {
    /** A key arrow fills about 0.4 of its box: a box of 1.8 text sizes makes it as tall as a capital letter. */
    static final float KEY_BOX = 1.8f, KEY_ADVANCE = 0.8f;
    /** A mark is a full circle: a box of 1.15 text sizes, a gap of 0.45 after it. */
    static final float MARK_BOX = 1.15f, MARK_ADVANCE = 1.6f;

    private final Drawable icon;
    private final float box, advance;
    private final int color;

    IconSpan(Drawable icon, float box, float advance, int color) {
        this.icon = icon.mutate();
        this.box = box;
        this.advance = advance;
        this.color = color;
    }

    /** A key arrow in the text's colour. */
    public static IconSpan key(Drawable icon) {
        return new IconSpan(icon, KEY_BOX, KEY_ADVANCE, 0);
    }

    /** A state mark in its own colour, followed by a gap. */
    public static IconSpan mark(Drawable icon, int color) {
        return new IconSpan(icon, MARK_BOX, MARK_ADVANCE, color);
    }

    @Override
    public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (fm != null) {
            Paint.FontMetricsInt m = p.getFontMetricsInt();
            fm.ascent = m.ascent;
            fm.descent = m.descent;
            fm.top = m.top;
            fm.bottom = m.bottom;
            fm.leading = m.leading;
        }
        return Math.round(p.getTextSize() * advance);
    }

    @Override
    public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint p) {
        float size = p.getTextSize() * box;
        boolean mark = color != 0;
        float cx = mark ? x + size / 2f : x + p.getTextSize() * advance / 2f;
        float cy = y + (p.ascent() + p.descent()) / 2f;
        icon.setBounds(Math.round(cx - size / 2f), Math.round(cy - size / 2f), Math.round(cx + size / 2f),
                Math.round(cy + size / 2f));
        icon.setTint(mark ? color : p.getColor());
        if (!mark) icon.setAlpha(p.getAlpha());
        icon.draw(c);
    }
}
