package org.lumen.installer.core;

import org.lumen.snapshot.UpdateState;

/**
 * What a stock XGIMI update left, judged from the root probe the way the installer inside the trial judges it later
 * (the rules of Android's libsnapshot): so Lumen Installer can say BEFORE the ten-minute trial whether an update is
 * still being finished (leave XGIMI on), or whether its leftovers will simply be cleared by the install. The trial
 * checks everything again and alone decides, this is only the early word. Both read the state file and name the files
 * and devices through one module (org.lumen.snapshot.UpdateState).
 */
public final class StockUpdate {
    private StockUpdate() {}

    public static final class Result {
        public StockVerdict verdict;
        public String names = "", why = "";
        public long cowKib;
    }

    /**
     * otaPending: the probe's ota_pending (snapshot-boot or a snapshot status file); stateHex: /metadata/ota/state as
     * hex ("" absent or empty); markers: the UpdateState.MARKERS present, comma list; cow: name:KiB of each mapped *-cow device;
     * snapDevs: mapped snapshot devices; slot: ro.boot.slot_suffix.
     */
    public static Result judge(String otaPending, String stateHex, String markers, String cow, String snapDevs, String slot) {
        Result r = new Result();
        int state = UpdateState.decode(unhex(nz(stateHex)));
        if ("1".equals(otaPending) || UpdateState.pending(state) || !nz(snapDevs).isEmpty()) {
            r.verdict = StockVerdict.PENDING;
            r.why = "state " + UpdateState.name(state) + (nz(snapDevs).isEmpty() ? "" : ", snapshot devices " + snapDevs);
            return r;
        }
        StringBuilder names = new StringBuilder();
        boolean odd = false;
        for (String e : nz(cow).split(",")) {
            if (e.isEmpty()) continue;
            int c = e.lastIndexOf(':');
            String n = c < 0 ? e : e.substring(0, c);
            try {
                r.cowKib += c < 0 ? 0 : Long.parseLong(e.substring(c + 1));
            } catch (NumberFormatException x) {
                odd = true;
            }
            if (names.length() > 0) names.append(", ");
            names.append(n);
            if (slot == null || slot.isEmpty() || !n.endsWith(slot + UpdateState.COW)) odd = true;
        }
        r.names = names.toString();
        if (r.names.isEmpty()) { r.verdict = StockVerdict.NONE; return r; }
        if (!UpdateState.finished(state)) r.why = "state " + UpdateState.name(state);
        else if (!nz(markers).isEmpty()) r.why = "left " + markers;
        else if (odd) r.why = "names " + r.names;
        r.verdict = r.why.isEmpty() ? StockVerdict.LEFTOVERS : StockVerdict.LEFTOVERS_UNSURE;
        return r;
    }

    static String nz(String s) { return s == null ? "" : s.trim(); }

    static byte[] unhex(String h) {
        if (h.length() % 2 != 0) return new byte[] {(byte) 0xff};
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int v = Character.digit(h.charAt(2 * i), 16) << 4 | Character.digit(h.charAt(2 * i + 1), 16);
            if (v < 0) return new byte[] {(byte) 0xff};
            b[i] = (byte) v;
        }
        return b;
    }
}
