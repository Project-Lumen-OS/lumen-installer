// SPDX-License-Identifier: Apache-2.0
package org.lumen.icons;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * A text view for a remote key hint written in a layout ("Manual focus \u25C0 \u25B6 OK/Back: done"): every key arrow of its text
 * is drawn as a vector icon in the text's colour ({@link IconText#keys}), the string itself keeps the arrow character.
 */
public class KeyHintView extends TextView {
    public KeyHintView(Context c) {
        super(c);
    }

    public KeyHintView(Context c, AttributeSet a) {
        super(c, a);
    }

    public KeyHintView(Context c, AttributeSet a, int style) {
        super(c, a, style);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(IconText.keys(getContext(), text), type);
    }
}
