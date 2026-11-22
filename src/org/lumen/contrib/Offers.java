package org.lumen.contrib;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** What this unit can still contribute for its model and firmware (GET /v1/pair). */
public final class Offers {
    public static final Offers NONE = new Offers(false, Collections.<String>emptyList());
    public final boolean known;
    public final List<String> open;

    Offers(boolean known, List<String> open) {
        this.known = known;
        this.open = Collections.unmodifiableList(open);
    }

    public boolean has(Kind k) {
        return open.contains(k.wire);
    }

    @SuppressWarnings("unchecked")
    static Offers from(Map<String, Object> m) {
        Object k = m.get("k"), o = m.get("o");
        if (!(k instanceof Long) || !(o instanceof List)) throw new IllegalArgumentException("pair reply");
        return new Offers(((Long) k) == 1L, (List<String>) o);
    }
}
