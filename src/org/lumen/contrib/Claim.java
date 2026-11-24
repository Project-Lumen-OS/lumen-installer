package org.lumen.contrib;

import java.util.Map;

/** The answer to one contribution event: a claim code to show, or why there is none. */
public final class Claim {
    public static final Claim OFFLINE = new Claim(null, 0, false, "", "offline", 0);
    /** 8 characters, null when there is nothing to take. */
    public final String code;
    public final long expires;
    /** True: the code is taken now and counts after a moderator checks the sent info. */
    public final boolean waiting;
    /** The bot link the QR carries. */
    public final String link;
    /** Why there is no code: sent_before, pair_known, model_known, trial_full, installed_before, offline, or a refusal code. */
    public final String why;
    /** When the unit got it (unix seconds, 0 = not known: kept by an older app). */
    public final long earned;

    Claim(String code, long expires, boolean waiting, String link, String why, long earned) {
        this.code = code;
        this.expires = expires;
        this.waiting = waiting;
        this.link = link;
        this.why = why;
        this.earned = earned;
    }

    /** An answer the app kept from an earlier event (shown again after a restart, also after the code ran out). */
    public static Claim kept(String code, long expires, boolean waiting, String link, String why, long earned) {
        return new Claim(code, expires, waiting, link, why, earned);
    }

    /** The same answer with the time the unit got it. */
    public Claim at(long earnedSeconds) {
        return new Claim(code, expires, waiting, link, why, earnedSeconds);
    }

    /** The code as people read it: two groups of four. */
    public String shown() {
        return code == null ? "" : code.substring(0, 4) + " " + code.substring(4);
    }

    static Claim from(int status, Map<String, Object> m) {
        if (status != 200) {
            Object e = m.get("e");
            return new Claim(null, 0, false, "", e instanceof String ? (String) e : "http_" + status, 0);
        }
        if ("claim".equals(m.get("st"))) {
            Object c = m.get("code"), x = m.get("exp"), w = m.get("w"), l = m.get("l");
            if (!(c instanceof String) || !((String) c).matches("[A-Z2-9]{8}") || !(x instanceof Long) || !(w instanceof Long)
                    || !(l instanceof String) || !((String) l).startsWith("https://t.me/")) {
                throw new IllegalArgumentException("claim reply");
            }
            return new Claim((String) c, (Long) x, ((Long) w) == 1L, (String) l, "", 0);
        }
        Object why = m.get("why");
        if (!"none".equals(m.get("st")) || !(why instanceof String)) throw new IllegalArgumentException("claim reply");
        return new Claim(null, 0, false, "", (String) why, 0);
    }
}
