// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.contrib;

import java.util.Map;

/** What this projector earned before (POST /v1/mine): the base links it to the unit by the serial hash only. The Spark
 *  still to take comes first, else the newest one. */
public final class Mine {
    /** The base was not reached (or not asked: no serial): the app keeps showing what it kept itself. */
    public static final Mine OFFLINE = new Mine(false, false, "", "", 0, false, false, 0, "", false);
    /** The base knows no Spark of this projector. */
    public static final Mine NONE = new Mine(true, false, "", "", 0, false, false, 0, "", false);
    /** true: the base answered */
    public final boolean asked;
    /** true: this projector earned a Spark */
    public final boolean has;
    /** report, trial or install */
    public final String kind;
    /** 8 characters, "" when the base did not keep it (a Spark taken before codes were kept) */
    public final String code;
    /** when the projector earned it (unix seconds) */
    public final long earned;
    /** taken by a person in the bot or on the site */
    public final boolean taken;
    /** counts after a moderator checks the report */
    public final boolean waiting;
    public final long expires;
    /** the bot link of a code still to take, else "" */
    public final String link;
    /** this projector already sent a report of this model and firmware */
    public final boolean reportSent;

    Mine(boolean asked, boolean has, String kind, String code, long earned, boolean taken, boolean waiting, long expires,
         String link, boolean reportSent) {
        this.asked = asked;
        this.has = has;
        this.kind = kind;
        this.code = code;
        this.earned = earned;
        this.taken = taken;
        this.waiting = waiting;
        this.expires = expires;
        this.link = link;
        this.reportSent = reportSent;
    }

    static Mine from(int status, Map<String, Object> m) {
        if (status != 200) return OFFLINE;
        Object rs = m.get("rs");
        if (!(rs instanceof Long)) throw new IllegalArgumentException("mine reply");
        boolean sent = (Long) rs == 1L;
        if ("none".equals(m.get("st"))) return sent ? new Mine(true, false, "", "", 0, false, false, 0, "", true) : NONE;
        if (!"have".equals(m.get("st"))) throw new IllegalArgumentException("mine reply");
        Object k = m.get("k"), c = m.get("code"), d = m.get("d"), t = m.get("t"), w = m.get("w"), x = m.get("exp"), l = m.get("l");
        if (!(k instanceof String) || !((String) k).matches("report|trial|install") || !(c instanceof String)
                || !((String) c).matches("([A-Z2-9]{8})?") || !(d instanceof Long) || !(t instanceof Long)
                || !(w instanceof Long) || !(x instanceof Long) || !(l instanceof String)
                || !(((String) l).isEmpty() || ((String) l).startsWith("https://t.me/"))) {
            throw new IllegalArgumentException("mine reply");
        }
        return new Mine(true, true, (String) k, (String) c, (Long) d, (Long) t == 1L, (Long) w == 1L, (Long) x, (String) l, sent);
    }
}
