// SPDX-License-Identifier: Apache-2.0
package org.lumen.icons;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BulletSpan;

/**
 * Icons in text. A hint names a remote key with the key's arrow (\u25C0 \u25B6 \u25B2 \u25BC, or \u2190 \u2192 \u2191 \u2193): the strings keep
 * that character, so every translation reads the same, and {@link #keys} draws a vector arrow over it
 * ({@link IconSpan}). A text drawn on a canvas row goes through {@link #draw}. A list item gets a drawn bullet
 * ({@link #bullet}), never a bullet character.
 */
public final class IconText {
    /** The remote keys in a text that {@link #keys} draws (the strings write them so). */
    public static final char LEFT = '\u25C0', RIGHT = '\u25B6', UP = '\u25B2', DOWN = '\u25BC';
    /** The key arrows {@link #keys} replaces, in the order of {@link #KEY_ICONS}. */
    public static final String KEYS = "\u25C0\u2190\u25B6\u2192\u25B2\u2191\u25BC\u2193";
    private static final int[] KEY_ICONS = {
            R.drawable.lumen_ic_key_left, R.drawable.lumen_ic_key_left,
            R.drawable.lumen_ic_key_right, R.drawable.lumen_ic_key_right,
            R.drawable.lumen_ic_key_up, R.drawable.lumen_ic_key_up,
            R.drawable.lumen_ic_key_down, R.drawable.lumen_ic_key_down};

    /** The character under an icon at a line start (U+FFFC, what Android puts under an inline image). */
    static final String OBJECT = "\uFFFC";

    private IconText() {}

    /** The text with every key arrow drawn as a vector icon in the text's colour; other text unchanged. */
    public static CharSequence keys(Context c, CharSequence text) {
        if (text == null || !hasKey(text)) return text;
        SpannableString s = new SpannableString(text);
        for (int i = 0; i < s.length(); i++) {
            int k = KEYS.indexOf(s.charAt(i));
            if (k < 0 || s.getSpans(i, i + 1, IconSpan.class).length > 0) continue;   // drawn once, however often keyed
            s.setSpan(IconSpan.key(c.getDrawable(KEY_ICONS[k])), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    /** One remote key as an icon in text (a key cap of a hint bar): {@code icon} is lumen_ic_key_*. */
    public static CharSequence key(Context c, int icon) {
        SpannableString s = new SpannableString(OBJECT);
        s.setSpan(IconSpan.key(c.getDrawable(icon)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    public static boolean hasKey(CharSequence text) {
        for (int i = 0; i < text.length(); i++) if (KEYS.indexOf(text.charAt(i)) >= 0) return true;
        return false;
    }

    /** Draws a line with its icons (Canvas.drawText ignores spans); returns its width. */
    public static float draw(Canvas c, CharSequence text, float x, float baseline, Paint p) {
        if (!(text instanceof Spanned)) {
            c.drawText(text, 0, text.length(), x, baseline, p);
            return p.measureText(text, 0, text.length());
        }
        Spanned s = (Spanned) text;
        float at = x;
        int i = 0;
        while (i < s.length()) {
            int next = s.nextSpanTransition(i, s.length(), IconSpan.class);
            IconSpan[] spans = s.getSpans(i, next, IconSpan.class);
            if (spans.length > 0) {
                int fontTop = Math.round(baseline + p.ascent()), fontBottom = Math.round(baseline + p.descent());
                spans[0].draw(c, s, i, next, at, fontTop, Math.round(baseline), fontBottom, p);
                at += spans[0].getSize(p, s, i, next, null);
            } else {
                c.drawText(s, i, next, at, baseline, p);
                at += p.measureText(s, i, next);
            }
            i = next;
        }
        return at - x;
    }

    /** The width {@link #draw} takes. */
    public static float measure(CharSequence text, Paint p) {
        if (!(text instanceof Spanned)) return p.measureText(text, 0, text.length());
        Spanned s = (Spanned) text;
        float w = 0;
        int i = 0;
        while (i < s.length()) {
            int next = s.nextSpanTransition(i, s.length(), IconSpan.class);
            IconSpan[] spans = s.getSpans(i, next, IconSpan.class);
            w += spans.length > 0 ? spans[0].getSize(p, s, i, next, null) : p.measureText(s, i, next);
            i = next;
        }
        return w;
    }

    /** A line that starts with the mark of a check state ({@link Icons#statusIcon}) in {@code color}. */
    public static CharSequence mark(Context c, IconStatus s, int color, CharSequence text) {
        SpannableString line = new SpannableString(OBJECT + text);
        line.setSpan(IconSpan.mark(c.getDrawable(Icons.statusIcon(s)), color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return line;
    }

    /** A list item with a drawn round bullet in {@code color} at its start. */
    public static CharSequence bullet(CharSequence text, int color, int radiusPx, int gapPx) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new BulletSpan(gapPx, color, radiusPx), 0, s.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        return s;
    }
}
