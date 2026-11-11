package org.lumen.installer.core;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The signed list of projectors (devices.json + devices.json.sig), the only place that knows models. The app keeps
 * nothing per model: it reads the projector's own model code and board, finds that model's entry here and follows
 * it (its name, its stock firmware versions with what each may do, its release channels and the certificates that
 * sign them). An unknown model is not supported.
 *
 * The list is signed (RSA PKCS#1 v1.5 SHA-256) with the list key, whose certificate is the only one in the app
 * (assets/index/). Its serial only grows: the app never takes a list older than one it has already verified.
 *
 *   {"schema": 1, "kind": "lumen-installer-devices", "serial": N, "models": [ENTRY, ...]}
 *   ENTRY  {"model": "G0000", "board": "mt0000", "name": "...", "status": "supported|not_yet|computer_only",
 *           "message": {"en": "...", "ru": "..."},                           optional, shown for a model that is not
 *                                                                           supported (or for a refused version)
 *           "product": "Product_x" | null, "config_check": true|false,     checked by the root probe
 *           "stock": [{"version": "v1.2.3", "trial": true, "install": true}, ...],
 *           "refused": [{"version": "v1.2.3", "message": {"en": "...", "ru": "..."}}, ...],
 *           "channels": {"stable": CHANNEL, "beta": CHANNEL},               at least one; a model with a beta only
 *                                                                           is read only when the user chose Beta
 *           "certs": ["-----BEGIN CERTIFICATE-----...", ...]}
 *   CHANNEL {"manifest": "https://.../update-x.json", "device": "x", "channel": "x",
 *            "release": "https://.../tag-{version}/", "image": "name-{version}-nogms-system.img"}
 * A supported entry needs board, stock, a channel and certs; the others only model, name and status.
 */
public final class Index {
    public static final String KIND = "lumen-installer-devices";

    public enum Status { SUPPORTED, NOT_YET, COMPUTER_ONLY }

    public static final class Bad extends Exception {
        public final Msg msg;
        public final String arg;
        Bad(Msg msg, String arg) { super(msg + (arg == null ? "" : ": " + arg)); this.msg = msg; this.arg = arg; }
    }

    /** One release channel of a model: its signed manifest and where its releases are. */
    public static final class Channel {
        public final String manifest, device, channel, release, image;

        Channel(String manifest, String device, String channel, String release, String image) {
            this.manifest = manifest; this.device = device; this.channel = channel; this.release = release; this.image = image;
        }

        /** the manifest's file name (also its name in a LumenOS folder) */
        public String manifestName() { return manifest.substring(manifest.lastIndexOf('/') + 1); }

        /** the release folder of a version, ending in '/' */
        public String releaseUrl(String version) { return release.replace("{version}", version); }

        public String imageName(String version) { return image.replace("{version}", version); }

        /** the image name of any release version (Release.VERSION) of this channel, version as group 1 */
        public Pattern imagePattern() {
            int i = image.indexOf("{version}");
            return Pattern.compile(Pattern.quote(image.substring(0, i)) + "(" + Release.VERSION + ")"
                    + Pattern.quote(image.substring(i + "{version}".length())));
        }
    }

    /** One stock firmware version of a model: may the trial start, may the install from the trial run. */
    public static final class Stock {
        public final String version;
        public final boolean trial, install;
        Stock(String version, boolean trial, boolean install) { this.version = version; this.trial = trial; this.install = install; }
    }

    public static final class Entry {
        public final String code, board, name;
        public final Status status;
        /** the stock ro.vendor.xgimi.product.name of this model (another one = a cross-flash), null = not checked */
        public final String product;
        /** the stock vendor must have /mnt/vendor/xgimiconfig/&lt;code&gt;/system.ini (root probe model_config) */
        public final boolean configCheck;
        public final List<Stock> stock;
        /** stock versions refused with their own text (en, ru) */
        public final Map<String, String[]> refused;
        public final Channel stable, beta;
        public final List<X509Certificate> certs;
        /** en, ru; null = the app's own text for the status */
        public final String[] message;
        /** this entry reads its beta channel (made by {@link #asBeta()}) */
        public final boolean isBeta;

        Entry(String code, String board, String name, Status status, String product, boolean configCheck, List<Stock> stock,
              Map<String, String[]> refused, Channel stable, Channel beta, List<X509Certificate> certs, String[] message, boolean isBeta) {
            this.code = code; this.board = board; this.name = name; this.status = status; this.product = product;
            this.configCheck = configCheck; this.stock = stock; this.refused = refused; this.stable = stable; this.beta = beta;
            this.certs = certs; this.message = message; this.isBeta = isBeta;
        }

        /** the channel this entry reads; null for a model with a beta only while Stable is chosen */
        public Channel channel() { return isBeta ? beta : stable; }

        /** no stable channel yet: only a beta (read when the user chose Beta) */
        public boolean betaOnly() { return stable == null; }

        public boolean hasBeta() { return beta != null && !isBeta; }

        /** the same entry reading its beta channel ("Version: Beta"); null when the model has none */
        public Entry asBeta() {
            if (!hasBeta()) return null;
            return new Entry(code, board, name, status, product, configCheck, stock, refused, stable, beta, certs, message, true);
        }

        public Stock stock(String version) {
            for (Stock s : stock) if (s.version.equals(version)) return s;
            return null;
        }

        public List<String> stockVersions() {
            List<String> l = new ArrayList<>();
            for (Stock s : stock) l.add(s.version);
            return l;
        }
    }

    public final long serial;
    public final List<Entry> models;

    private Index(long serial, List<Entry> models) { this.serial = serial; this.models = models; }

    /** The entry of this model code, or null (not in the list: not supported). */
    public Entry find(String code) {
        if (code == null || code.isEmpty()) return null;
        for (Entry e : models) if (e.code.equals(code)) return e;
        return null;
    }

    private static final Pattern CODE = Pattern.compile("G[0-9]{4}");
    private static final Pattern BOARD = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern STOCK = Pattern.compile("[vV][0-9]{1,4}(\\.[0-9]{1,4}){1,4}");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern NAME = Pattern.compile("[^\\p{Cntrl}<>]{1,80}");

    /** Verify the signature with the app's list certificates, then read the list strictly; anything odd is refused. */
    public static Index parse(byte[] json, byte[] sig, List<X509Certificate> listCerts) throws Bad {
        return parse(json, sig, listCerts, false);
    }

    /** allowLocalHttp: the host tests' fake GitHub on http://127.0.0.1 (never in the app, as Http) */
    public static Index parse(byte[] json, byte[] sig, List<X509Certificate> listCerts, boolean allowLocalHttp) throws Bad {
        if (listCerts == null || listCerts.isEmpty() || !Release.verify(json, sig, listCerts)) throw new Bad(Msg.INDEX_BAD_SIG, null);
        try {
            Map<String, Object> o = Json.obj(Json.parse(Bytes.utf8(json)), "list");
            if (!"1".equals(Json.num(o, "schema"))) throw new IllegalArgumentException("schema");
            if (!KIND.equals(Json.str(o, "kind"))) throw new IllegalArgumentException("kind");
            long serial = Long.parseLong(Json.num(o, "serial"));
            if (serial <= 0) throw new IllegalArgumentException("serial");
            List<Entry> out = new ArrayList<>();
            for (Object e : Json.arr(o.get("models"), "models")) {
                Entry en = entry(Json.obj(e, "model entry"), allowLocalHttp);
                for (Entry x : out) if (x.code.equals(en.code)) throw new IllegalArgumentException("model " + en.code + " twice");
                out.add(en);
            }
            return new Index(serial, Collections.unmodifiableList(out));
        } catch (IllegalArgumentException e) {
            throw new Bad(Msg.INDEX_BAD, e.getMessage());
        }
    }

    private static String match(Map<String, Object> m, String k, Pattern p) {
        String v = Json.str(m, k);
        if (!p.matcher(v).matches()) throw new IllegalArgumentException(k + " " + v);
        return v;
    }

    private static boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Boolean)) throw new IllegalArgumentException(k + " is not true or false");
        return (Boolean) v;
    }

    private static String[] text(Object o, String what) {
        if (o == null) return null;
        Map<String, Object> m = Json.obj(o, what);
        String en = Json.str(m, "en"), ru = Json.str(m, "ru");
        if (en.isEmpty() || ru.isEmpty() || en.length() > 400 || ru.length() > 400) throw new IllegalArgumentException(what);
        return new String[] { en, ru };
    }

    private static String https(Map<String, Object> m, String k, boolean local) {
        String v = Json.str(m, k);
        if (!(v.startsWith("https://") || local && v.startsWith("http://127.0.0.1:")) || v.length() > 300 || v.contains("..") || v.matches(".*[\\s\"'<>\\\\].*")) {
            throw new IllegalArgumentException(k + " " + v);
        }
        return v;
    }

    private static Channel channel(Object o, String what, boolean local) {
        Map<String, Object> m = Json.obj(o, what);
        String man = https(m, "manifest", local), rel = https(m, "release", local), img = Json.str(m, "image");
        if (!man.endsWith(".json")) throw new IllegalArgumentException(what + " manifest");
        if (!rel.endsWith("/") || rel.indexOf("{version}") < 0) throw new IllegalArgumentException(what + " release");
        if (img.indexOf("{version}") < 0 || img.indexOf("{version}") != img.lastIndexOf("{version}")
                || !img.replace("{version}", "1.0.0").matches("[A-Za-z0-9._-]{1,120}") || !img.endsWith(".img")) {
            throw new IllegalArgumentException(what + " image");
        }
        return new Channel(man, match(m, "device", WORD), match(m, "channel", WORD), rel, img);
    }

    private static Entry entry(Map<String, Object> m, boolean local) {
        String code = match(m, "model", CODE);
        String name = match(m, "name", NAME);
        String st = Json.str(m, "status");
        Status status;
        if ("supported".equals(st)) status = Status.SUPPORTED;
        else if ("not_yet".equals(st)) status = Status.NOT_YET;
        else if ("computer_only".equals(st)) status = Status.COMPUTER_ONLY;
        else throw new IllegalArgumentException(code + " status " + st);
        String[] message = text(m.get("message"), code + " message");
        if (status != Status.SUPPORTED) {
            String board = m.get("board") == null ? "" : match(m, "board", BOARD);
            return new Entry(code, board, name, status, null, false, Collections.<Stock>emptyList(),
                    Collections.<String, String[]>emptyMap(), null, null, Collections.<X509Certificate>emptyList(), message, false);
        }
        String board = match(m, "board", BOARD);
        String product = m.get("product") == null ? null : match(m, "product", WORD);
        boolean configCheck = bool(m, "config_check");
        List<Stock> stock = new ArrayList<>();
        for (Object s : Json.arr(m.get("stock"), code + " stock")) {
            Map<String, Object> sm = Json.obj(s, code + " stock");
            String v = match(sm, "version", STOCK);
            boolean trial = bool(sm, "trial"), install = bool(sm, "install");
            if (install && !trial) throw new IllegalArgumentException(code + " " + v + " install without trial");
            for (Stock x : stock) if (x.version.equals(v)) throw new IllegalArgumentException(code + " " + v + " twice");
            stock.add(new Stock(v, trial, install));
        }
        if (stock.isEmpty()) throw new IllegalArgumentException(code + " no stock versions");
        Map<String, String[]> refused = new LinkedHashMap<>();
        if (m.get("refused") != null) {
            for (Object r : Json.arr(m.get("refused"), code + " refused")) {
                Map<String, Object> rm = Json.obj(r, code + " refused");
                String v = match(rm, "version", STOCK);
                for (Stock x : stock) if (x.version.equals(v)) throw new IllegalArgumentException(code + " " + v + " both allowed and refused");
                refused.put(v, text(rm.get("message"), code + " refused " + v));
            }
        }
        Map<String, Object> ch = Json.obj(m.get("channels"), code + " channels");
        Channel stable = ch.get("stable") == null ? null : channel(ch.get("stable"), code + " stable", local);
        Channel beta = ch.get("beta") == null ? null : channel(ch.get("beta"), code + " beta", local);
        if (stable == null && beta == null) throw new IllegalArgumentException(code + " has no channel");
        List<byte[]> pems = new ArrayList<>();
        for (Object c : Json.arr(m.get("certs"), code + " certs")) {
            if (!(c instanceof String) || !((String) c).startsWith("-----BEGIN CERTIFICATE-----")) throw new IllegalArgumentException(code + " certs");
            pems.add(Bytes.utf8((String) c));
        }
        if (pems.isEmpty() || pems.size() > 4) throw new IllegalArgumentException(code + " certs");
        List<X509Certificate> certs;
        try {
            certs = Collections.unmodifiableList(Release.certs(pems));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(code + " certs: " + e.getMessage());
        }
        return new Entry(code, board, name, status, product, configCheck, Collections.unmodifiableList(stock),
                Collections.unmodifiableMap(refused), stable, beta, certs, message, false);
    }
}
