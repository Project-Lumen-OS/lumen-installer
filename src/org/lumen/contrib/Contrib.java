package org.lumen.contrib;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client of the contribution base: asks what this unit can still contribute for its model and firmware, and sends
 *  one signed event (stock system info sent, trial ran, install done). Best effort by design: no call blocks or
 *  changes the install path, every failure (offline, refused, a strange answer) is simply "no card". */
public final class Contrib {
    public static final String BASE = "https://spark.lumen-os.net";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String base;
    private final Transport net;
    private final Signer signer;

    public Contrib(String base, Transport net, Signer signer) {
        this.base = base;
        this.net = net;
        this.signer = signer;
    }

    public Offers pair(String model, String version) {
        if (!ContribBody.MODEL.matcher(model == null ? "" : model).matches()
                || !ContribBody.VERSION.matcher(version == null ? "" : version).matches()) {
            return Offers.NONE;
        }
        try {
            Reply r = net.get(base + "/v1/pair?m=" + model + "&fw=" + version);
            return r.status == 200 ? Offers.from(FlatJson.parse(r.body)) : Offers.NONE;
        } catch (IOException | RuntimeException e) {
            return Offers.NONE;
        }
    }

    public Claim send(Kind kind, String model, String version, String serial, String ref, long nowSeconds) {
        try {
            byte[] body = ContribBody.build(kind, model, version, ContribBody.serialHash(serial), ref, nowSeconds, nonce());
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "text/plain; charset=us-ascii");
            h.put("X-Lumen-Key", Hex.b64url(signer.publicKey()));
            h.put("X-Lumen-Sig", Hex.b64url(signer.sign(body)));
            Reply r = net.post(base + "/v1/earn", body, h);
            return Claim.from(r.status, FlatJson.parse(r.body));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return Claim.OFFLINE;
        }
    }

    /** The start screen: offer to send the system info only while the base lacks this firmware and it was not sent. */
    public static Card card(Offers o, boolean infoSent) {
        return !infoSent && o.has(Kind.REPORT) ? Card.SEND_INFO : Card.NONE;
    }

    /** After an event: the code, the "installed before" notice, or nothing. */
    public static Card card(Claim c) {
        if (c.code != null) return Card.CODE;
        return "installed_before".equals(c.why) ? Card.INSTALLED_BEFORE : Card.NONE;
    }

    static String nonce() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return Hex.of(b);
    }
}
