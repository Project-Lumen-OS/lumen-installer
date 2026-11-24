package org.lumen.contrib;

import java.util.Collection;

/** What the Sparks card shows and whether the report button leads, from what the app kept and what the base said.
 *  The base wins when it answered (it knows the projector by its serial hash, also after a reinstall of the app), else
 *  the app's own kept answers. A code still to take comes first, then a Spark already taken, then the offer to send
 *  a report while this firmware is new to the base and none went from this projector. One code per projector: the
 *  base answers every repeat (another report, a retry, a reinstalled app) with the oldest code still to take, and the
 *  kept answers follow the same order, so the screen never swaps one code for another before it is taken. */
public final class Shown {
    public static final Shown NONE = new Shown(Card.NONE, "", 0, 0, false, "", false);
    public final Card card;
    /** the code (8 characters), "" for none or not kept */
    public final String code;
    /** when the projector earned it (unix seconds, 0 = not known) */
    public final long earned;
    public final long expires;
    public final boolean waiting;
    /** the bot link the QR carries */
    public final String link;
    /** the report button leads, its label names the Spark */
    public final boolean reportFirst;

    Shown(Card card, String code, long earned, long expires, boolean waiting, String link, boolean reportFirst) {
        this.card = card;
        this.code = code;
        this.earned = earned;
        this.expires = expires;
        this.waiting = waiting;
        this.link = link;
        this.reportFirst = reportFirst;
    }

    /** The code as people read it: two groups of four. */
    public String shown() {
        return code.length() == 8 ? code.substring(0, 4) + " " + code.substring(4) : code;
    }

    /**
     * offers: GET /v1/pair of this model and stock firmware (NONE on Lumen OS or offline); mine: POST /v1/mine;
     * kept: the answers the app kept; reportSent: a report of this firmware went from this unit (the app knows it);
     * why: the reason of the newest event without a code (installed_before shows its notice).
     */
    public static Shown decide(Offers offers, Mine mine, Collection<Claim> kept, boolean reportSent, String why, long now) {
        if (mine.asked && mine.has) {
            if (!mine.taken && !mine.code.isEmpty() && mine.expires >= now) {
                return new Shown(Card.CODE, mine.code, mine.earned, mine.expires, mine.waiting, mine.link, false);
            }
            if (mine.taken) return new Shown(Card.TAKEN, mine.code, mine.earned, 0, false, "", false);
        } else if (!mine.asked) {
            Claim best = null;
            for (Claim c : kept) {
                if (c.code == null || c.expires < now) continue;
                if (best == null || c.earned < best.earned) best = c;
            }
            if (best != null) return new Shown(Card.CODE, best.code, best.earned, best.expires, best.waiting, best.link, false);
        }
        if (offers.has(Kind.REPORT) && !reportSent && !mine.reportSent) return new Shown(Card.SEND_INFO, "", 0, 0, false, "", true);
        return "installed_before".equals(why) ? new Shown(Card.INSTALLED_BEFORE, "", 0, 0, false, "", false) : NONE;
    }
}
