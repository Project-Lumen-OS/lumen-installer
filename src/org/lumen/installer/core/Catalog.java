package org.lumen.installer.core;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The signed catalog of Lumen OS builds (catalog-v2.json + .sig), the only place that knows models. It is made
 * from the published releases (tools/devices/installer_catalog.py): a model is in it only when a build for it is
 * published. The app reads the projector's model code, finds its entry and follows it: name, board, stock versions,
 * the certificates that sign its releases, and its builds (newest stable, newest beta). No entry: no build yet.
 *
 * Signed (RSA PKCS#1 v1.5 SHA-256) with the list key, whose certificate is the only one in the app (assets/index/).
 * Its serial only grows: the app never takes a catalog older than one it has already verified.
 *
 *   {"schema": 1, "kind": "lumen-installer-catalog", "serial": N, "releases": URL, "guide": URL, "models": [ENTRY],
 *    "reports": [URL] (optional: the report addresses of the stock system info, a direct one before Cloudflare)}
 *   ENTRY {"model": "A0000", "name": "...", "board": "mt0000", "product": "Product_x" | null, "config_check": bool,
 *          "stock": [{"version": "v1.2.3", "install": bool}], "refused": [{"version": "v1.2.3", "message": TEXT}],
 *          "certs": [PEM], "builds": [BUILD]}                       TEXT = {"en": "...", "ru": "...", ...}
 *   BUILD {"version": "1.0.10-beta1", "channel": "stable|beta", "edition": "nogms|gms", "way": "trial|computer",
 *          "image": URL, "size": N, "sha256": HEX, "sums": URL of SHA256SUMS (+ .sig), "stock": ["v1.2.3"],
 *          "install": bool, "clears_leftovers": bool}               install = installs for good from the trial,
 *          clears_leftovers = its in-trial install clears what a finished XGIMI update left in the projector's memory
 *   ENTRY may carry "app_updates": [APP] (1.0.6): an app of an installed Lumen OS that this app updates, for the Lumen OS
 *   versions it lists (their own update app refuses every install, so a newer Lumen OS cannot come over the internet)
 *   APP   {"package": "a.b", "version_code": N, "apk": URL, "size": N, "sha256": HEX, "signer": HEX of the signing
 *          certificate, "lumen": ["1.0.10-beta5"]}
 * Unknown keys are ignored; anything odd in a known key refuses the whole catalog.
 */
public final class Catalog {
    public static final String KIND = "lumen-installer-catalog";

    public static final class Bad extends Exception {
        public final Msg msg;
        public final String arg;
        Bad(Msg msg, String arg) { super(msg + (arg == null ? "" : ": " + arg)); this.msg = msg; this.arg = arg; }
    }

    /** One published build of a model. */
    public static final class Build {
        public final String version, edition, image, imageName, sha256, sums;
        public final ReleaseChannel channel;
        /** the app starts it as a trial; otherwise (way "computer") the app does not offer it at all */
        public final boolean trial;
        /** its image carries the installer that installs Lumen OS for good from the trial */
        public final boolean install;
        /** that installer clears what a finished XGIMI update left (older ones refuse to install over it) */
        public final boolean clearsLeftovers;
        public final long size;
        public final List<String> stock;

        Build(String version, ReleaseChannel channel, String edition, boolean trial, String image, long size, String sha256,
              String sums, List<String> stock, boolean install, boolean clearsLeftovers) {
            this.version = version; this.channel = channel; this.edition = edition; this.trial = trial; this.image = image;
            this.imageName = image.substring(image.lastIndexOf('/') + 1); this.size = size; this.sha256 = sha256;
            this.sums = sums; this.stock = stock; this.install = install; this.clearsLeftovers = clearsLeftovers;
        }
    }

    /** One stock firmware version of a model: the trial starts on it; install = the install from the trial is open. */
    public static final class Stock {
        public final String version;
        public final boolean install;
        Stock(String version, boolean install) { this.version = version; this.install = install; }
    }

    /** An app of an installed Lumen OS that this app updates on the Lumen OS versions it lists (the system installer
     *  takes it only over the installed app of the same signature). */
    public static final class AppUpdate {
        public final String pkg, apk, sha256, signer;
        public final long versionCode, size;
        public final List<String> lumen;

        AppUpdate(String pkg, long versionCode, String apk, long size, String sha256, String signer, List<String> lumen) {
            this.pkg = pkg; this.versionCode = versionCode; this.apk = apk; this.size = size; this.sha256 = sha256;
            this.signer = signer; this.lumen = lumen;
        }
    }

    public static final class Entry {
        public final String code, name, board;
        /** the stock ro.vendor.xgimi.product.name of this model (another one = a cross-flash), null = not checked */
        public final String product;
        /** the stock vendor must have /mnt/vendor/xgimiconfig/&lt;code&gt;/system.ini (root probe model_config) */
        public final boolean configCheck;
        public final List<Stock> stock;
        /** stock versions refused with their own text, by language */
        public final Map<String, Map<String, String>> refused;
        public final List<X509Certificate> certs;
        public final List<Build> builds;
        public final List<AppUpdate> apps;

        Entry(String code, String name, String board, String product, boolean configCheck, List<Stock> stock,
              Map<String, Map<String, String>> refused, List<X509Certificate> certs, List<Build> builds, List<AppUpdate> apps) {
            this.code = code; this.name = name; this.board = board; this.product = product; this.configCheck = configCheck;
            this.stock = stock; this.refused = refused; this.certs = certs; this.builds = builds; this.apps = apps;
        }

        /** the app update for this running Lumen OS version, null = none */
        public AppUpdate appUpdate(String lumen) {
            for (AppUpdate a : apps) if (a.lumen.contains(lumen)) return a;
            return null;
        }

        /** the trial build of this channel, null = none */
        public Build build(ReleaseChannel c) {
            for (Build b : builds) if (b.trial && b.channel == c) return b;
            return null;
        }

        /** at least one build the app can start as a trial */
        public boolean hasTrial() { return build(ReleaseChannel.STABLE) != null || build(ReleaseChannel.BETA) != null; }

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
    public final String releases, guide;
    public final List<Entry> models;
    /** our report server's addresses for the stock system info, tried in order (a direct one before Cloudflare), the
     *  place of ro.lumen.report.url on the XGIMI system; empty: the app's default */
    public final List<String> reports;

    private Catalog(long serial, String releases, String guide, List<Entry> models, List<String> reports) {
        this.serial = serial; this.releases = releases; this.guide = guide; this.models = models; this.reports = reports;
    }

    /** The entry of this model code, or null (no build for it yet). */
    public Entry find(String code) {
        if (code == null || code.isEmpty()) return null;
        for (Entry e : models) if (e.code.equals(code)) return e;
        return null;
    }

    /** the text in this language, else English */
    public static String text(Map<String, String> t, String lang) {
        String v = lang == null ? null : t.get(lang);
        return v != null ? v : t.get("en");
    }

    /** an XGIMI model code: a capital letter and four digits (1.0.3 took a G only, see Engine.CATALOG_NAME) */
    private static final Pattern CODE = Pattern.compile("[A-Z][0-9]{4}");
    private static final Pattern BOARD = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern STOCK = Pattern.compile("[vV][0-9]{1,4}(\\.[0-9]{1,4}){1,4}");
    private static final Pattern NAME = Pattern.compile("[^\\p{Cntrl}<>]{1,80}");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LANG = Pattern.compile("[a-z]{2}(-r[A-Z]{2})?");
    private static final Pattern PKG = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,7}");
    static final long MAX_IMAGE = 4L << 30, MAX_APK = 64L << 20;

    /** Verify the signature with the app's list certificates, then read the catalog strictly. */
    public static Catalog parse(byte[] json, byte[] sig, List<X509Certificate> listCerts) throws Bad {
        return parse(json, sig, listCerts, false);
    }

    /** allowLocalHttp: the host tests' fake GitHub on http://127.0.0.1 (never in the app, as Http) */
    public static Catalog parse(byte[] json, byte[] sig, List<X509Certificate> listCerts, boolean allowLocalHttp) throws Bad {
        if (listCerts == null || listCerts.isEmpty() || !Release.verify(json, sig, listCerts)) throw new Bad(Msg.CATALOG_BAD_SIG, null);
        try {
            Map<String, Object> o = Json.obj(Json.parse(Bytes.utf8(json)), "catalog");
            if (!"1".equals(Json.num(o, "schema"))) throw new IllegalArgumentException("schema");
            if (!KIND.equals(Json.str(o, "kind"))) throw new IllegalArgumentException("kind");
            long serial = Long.parseLong(Json.num(o, "serial"));
            if (serial <= 0) throw new IllegalArgumentException("serial");
            String releases = https(o, "releases", allowLocalHttp), guide = https(o, "guide", allowLocalHttp);
            List<Entry> out = new ArrayList<>();
            for (Object e : Json.arr(o.get("models"), "models")) {
                Entry en = entry(Json.obj(e, "model entry"), allowLocalHttp);
                for (Entry x : out) if (x.code.equals(en.code)) throw new IllegalArgumentException("model " + en.code + " twice");
                out.add(en);
            }
            List<String> reports = new ArrayList<>();
            if (o.containsKey("reports")) {
                List<Object> rl = Json.arr(o.get("reports"), "reports");
                if (rl.size() > 4) throw new IllegalArgumentException("reports");
                for (int i = 0; i < rl.size(); i++) {
                    Map<String, Object> one = new java.util.HashMap<>();
                    one.put("report", rl.get(i));
                    String u = https(one, "report", allowLocalHttp);
                    if (!u.endsWith("/lumen/v1/report") || reports.contains(u)) throw new IllegalArgumentException("report " + u);
                    reports.add(u);
                }
            }
            return new Catalog(serial, releases, guide, Collections.unmodifiableList(out), Collections.unmodifiableList(reports));
        } catch (IllegalArgumentException e) {
            throw new Bad(Msg.CATALOG_BAD, e.getMessage());
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

    private static Map<String, String> text(Object o, String what) {
        Map<String, String> t = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.obj(o, what).entrySet()) {
            if (!LANG.matcher(e.getKey()).matches() || !(e.getValue() instanceof String)) throw new IllegalArgumentException(what);
            String v = (String) e.getValue();
            if (v.isEmpty() || v.length() > 400) throw new IllegalArgumentException(what);
            t.put(e.getKey(), v);
        }
        if (!t.containsKey("en")) throw new IllegalArgumentException(what + " has no English text");
        return Collections.unmodifiableMap(t);
    }

    static String https(Map<String, Object> m, String k, boolean local) {
        String v = Json.str(m, k);
        if (!(v.startsWith("https://") || local && v.startsWith("http://127.0.0.1:")) || v.length() > 300 || v.contains("..") || v.matches(".*[\\s\"'<>\\\\].*")) {
            throw new IllegalArgumentException(k + " " + v);
        }
        return v;
    }

    private static Build build(Map<String, Object> m, List<String> modelStock, String code, boolean local) {
        String version = Json.str(m, "version");
        if (!Release.versionOk(version)) throw new IllegalArgumentException(code + " version " + version);
        String ch = Json.str(m, "channel"), way = Json.str(m, "way"), edition = Json.str(m, "edition");
        if (!"stable".equals(ch) && !"beta".equals(ch)) throw new IllegalArgumentException(code + " channel " + ch);
        if (!"trial".equals(way) && !"computer".equals(way)) throw new IllegalArgumentException(code + " way " + way);
        if (!"nogms".equals(edition) && !"gms".equals(edition)) throw new IllegalArgumentException(code + " edition " + edition);
        String image = https(m, "image", local), sums = https(m, "sums", local);
        if (!image.substring(image.lastIndexOf('/') + 1).matches("[A-Za-z0-9._-]{1,120}") || !sums.endsWith("/SHA256SUMS")) {
            throw new IllegalArgumentException(code + " " + version + " files");
        }
        String sha = match(m, "sha256", SHA);
        long size = Long.parseLong(Json.num(m, "size"));
        if (size <= 0 || size % AvbTail.BS != 0 || size > MAX_IMAGE) throw new IllegalArgumentException(code + " " + version + " size " + size);
        List<String> stock = new ArrayList<>();
        for (Object s : Json.arr(m.get("stock"), code + " " + version + " stock")) {
            if (!(s instanceof String) || !modelStock.contains(s) || stock.contains(s)) throw new IllegalArgumentException(code + " " + version + " stock " + s);
            stock.add((String) s);
        }
        if (stock.isEmpty()) throw new IllegalArgumentException(code + " " + version + " no stock versions");
        boolean trial = "trial".equals(way), install = bool(m, "install"), clears = bool(m, "clears_leftovers");
        if (install && !trial) throw new IllegalArgumentException(code + " " + version + " install without trial");
        if (clears && !install) throw new IllegalArgumentException(code + " " + version + " clears leftovers without install");
        return new Build(version, "beta".equals(ch) ? ReleaseChannel.BETA : ReleaseChannel.STABLE, edition, trial, image, size, sha,
                sums, Collections.unmodifiableList(stock), install, clears);
    }

    private static Entry entry(Map<String, Object> m, boolean local) {
        String code = match(m, "model", CODE);
        String name = match(m, "name", NAME);
        String board = match(m, "board", BOARD);
        String product = m.get("product") == null ? null : match(m, "product", WORD);
        boolean configCheck = bool(m, "config_check");
        List<Stock> stock = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        for (Object s : Json.arr(m.get("stock"), code + " stock")) {
            Map<String, Object> sm = Json.obj(s, code + " stock");
            String v = match(sm, "version", STOCK);
            if (versions.contains(v)) throw new IllegalArgumentException(code + " " + v + " twice");
            versions.add(v);
            stock.add(new Stock(v, bool(sm, "install")));
        }
        if (stock.isEmpty()) throw new IllegalArgumentException(code + " no stock versions");
        Map<String, Map<String, String>> refused = new LinkedHashMap<>();
        if (m.get("refused") != null) {
            for (Object r : Json.arr(m.get("refused"), code + " refused")) {
                Map<String, Object> rm = Json.obj(r, code + " refused");
                String v = match(rm, "version", STOCK);
                if (versions.contains(v)) throw new IllegalArgumentException(code + " " + v + " both allowed and refused");
                refused.put(v, text(rm.get("message"), code + " refused " + v));
            }
        }
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
        List<Build> builds = new ArrayList<>();
        for (Object b : Json.arr(m.get("builds"), code + " builds")) {
            Build bu = build(Json.obj(b, code + " build"), versions, code, local);
            for (Build x : builds) if (x.channel == bu.channel) throw new IllegalArgumentException(code + " two builds of one channel");
            builds.add(bu);
        }
        if (builds.isEmpty()) throw new IllegalArgumentException(code + " no build");
        List<AppUpdate> apps = new ArrayList<>();
        if (m.get("app_updates") != null) {
            for (Object a : Json.arr(m.get("app_updates"), code + " app_updates")) apps.add(app(Json.obj(a, code + " app update"), code, local));
        }
        return new Entry(code, name, board, product, configCheck, Collections.unmodifiableList(stock),
                Collections.unmodifiableMap(refused), certs, Collections.unmodifiableList(builds), Collections.unmodifiableList(apps));
    }

    private static AppUpdate app(Map<String, Object> m, String code, boolean local) {
        String pkg = match(m, "package", PKG);
        long vc = Long.parseLong(Json.num(m, "version_code"));
        if (vc <= 0) throw new IllegalArgumentException(code + " " + pkg + " version_code " + vc);
        String apk = https(m, "apk", local);
        if (!apk.substring(apk.lastIndexOf('/') + 1).matches("[A-Za-z0-9._-]{1,120}\\.apk")) throw new IllegalArgumentException(code + " " + pkg + " file");
        long size = Long.parseLong(Json.num(m, "size"));
        if (size <= 0 || size > MAX_APK) throw new IllegalArgumentException(code + " " + pkg + " size " + size);
        String sha = match(m, "sha256", SHA), signer = match(m, "signer", SHA);
        List<String> lumen = new ArrayList<>();
        for (Object v : Json.arr(m.get("lumen"), code + " " + pkg + " lumen")) {
            if (!(v instanceof String) || !Release.versionOk((String) v) || lumen.contains(v)) throw new IllegalArgumentException(code + " " + pkg + " lumen " + v);
            lumen.add((String) v);
        }
        if (lumen.isEmpty()) throw new IllegalArgumentException(code + " " + pkg + " no Lumen OS versions");
        return new AppUpdate(pkg, vc, apk, size, sha, signer, Collections.unmodifiableList(lumen));
    }
}
