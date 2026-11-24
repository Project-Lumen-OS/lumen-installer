package org.lumen.installer.core;

import org.lumen.about.XgimiFacts;
import org.lumen.download.HttpError;
import org.lumen.download.ParallelDownload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The installer's steps, pure Java (no android.*), so the host tests run exactly this code against a fake projector
 * and a fake GitHub. The app calls it from a worker thread.
 *
 *   detect   props only: model, board, stock version, Lumen/trial already running, orange, looked up in the signed
 *            catalog of builds (Catalog: the last verified one the app keeps).
 *            The app knows no model: the catalog lists the models that have a published build, and each build.
 *   check    a fresh signed catalog, root probe (debugserver), the build's signed SHA256SUMS (it must give the
 *            catalog's sha256) and the image size on the server (the catalog's), free space
 *   download the image over the network, resumable (Range + If-Range), into the app's files dir on /data. The network
 *            is the only source (1.0.9): no USB stick, no LumenOS folder, no computer.
 *   verify   sha256 against the catalog and the signed SHA256SUMS; whether the build installs itself from the trial
 *   prepare  the AVB hashtree footer as a separate tail file (AvbTail), checked again after writing
 *   start    ONLY after the user confirmed: root `cat image tail | gsi_tool install ... --no-reboot`, then
 *            `gsi_tool enable --single-boot`; the user restarts. One-shot: the restart after that is stock again.
 *   app      on an installed Lumen OS only (1.0.6), when the catalog updates one of its apps for this Lumen OS version
 *            (its own update app refuses every install): the app's file, checked, then the system installer asks the
 *            user. No root. After it Lumen OS updates itself over the internet again.
 *   force    a known model on a stock version the catalog does not list: the user may tick "start the trial anyway"
 *            (never remembered), then the newest trial build of the model runs the same steps. The install from the
 *            trial stays closed (the in-trial installer refuses that version itself), the catalog's own refusals, the
 *            board, orange and the root probe's model checks stay. An unknown model code never gets the choice.
 *   dump     the stock system info (StockDump, a few MB, up to about 25 MB for a new firmware): the stock settings app, the read-only root commands of
 *            the script's dump table and what the app reads itself, personal data removed, sent in parts to our report
 *            server (PartUpload), going on after a break where it stopped. No writes outside the app's own dirs. The
 *            full firmware copy to a USB stick is LumenDump's, not this app's.
 * Nothing is written outside the app's own dirs before start, and start writes only /data/gsi and /metadata/gsi
 * (the trial's own files, on /data). Models that are not supported never reach the release or the root side.
 */
public final class Engine {

    public interface Listener {
        void step(InstallStep s, StepStatus st);
        void message(Msg m, String... args);
        void progress(InstallStep s, long done, long total);
        /** a download: also its speed over the last seconds (bytes/s, 0 = not known yet), for the time left */
        default void progress(InstallStep s, long done, long total, long speed) { progress(s, done, total); }
        void log(String line);
    }

    /**
     * the signed catalog of builds: its address, and the certificates of its key (the only ones in the app). 1.0.3 reads
     * catalog.json and refuses that whole file for a model code that is not a G and four digits, so the codes of other
     * letters are only in catalog-v2.json. Each file has its own serial: the kept copy and the
     * download use this one name, so a serial is only ever compared with one of the same file.
     */
    public static final String CATALOG_NAME = "catalog-v2.json";
    public static final String CATALOG_URL = "https://github.com/Project-Lumen-OS/lumen-installer/releases/download/index/" + CATALOG_NAME;

    public static final class Config {
        public String catalogUrl = CATALOG_URL;
        public List<X509Certificate> catalogCerts;
        /** the user's language for texts the catalog carries (en when it has none in this language) */
        public String lang = "en";
        public boolean allowLocalHttp = false;
        public long userdataBytes = 8L << 30;      // the trial's own userdata (8 GiB)
        public long marginBytes = 512L << 20;
        public int retries = 5;
        public long retryDelayMs = 2000;
        /** the image and app update downloads: several range connections (apps/core/lumen-download) */
        public ParallelDownload.Tuning download = ParallelDownload.Tuning.DEFAULT;
        public long rootTimeoutMs = 45 * 60 * 1000L;
        public long probeTimeoutMs = 3 * 60 * 1000L;
        /** how long the restart request may take, and then the projector's going down: still running = no restart */
        public long rebootWaitMs = 60 * 1000L;
        /** how long the system installer may take for an app update, the user's confirmation included */
        public long appInstallTimeoutMs = 15 * 60 * 1000L;
        /** the stock system info: our report server's addresses (tried in order) when the signed catalog names none,
         *  how long the root part may run, the free space it needs on /data (the root texts and the zip), how long a
         *  zip that did not go out is kept to go on with (the server keeps an unfinished upload a day) */
        public List<String> dumpUrls = org.lumen.report.ReportUrl.DEFAULTS;
        public long dumpTimeoutMs = 20 * 60 * 1000L;
        public long dumpSpaceBytes = 600L << 20;
        public long dumpKeepMs = 20 * 3600 * 1000L;
        /** this app's version, written into the system info */
        public String appVersion = "";
        public KeyPair avbKey;                     // tests only; the app makes a new key per run
        public byte[] avbSalt;                     // tests only
    }

    public static final class Detection {
        /** the catalog entry of this model; null = no build for it (or no catalog yet) */
        public Catalog.Entry model;
        /** the build a download reads: the stable one, or the beta one when the user chose Beta (or when only a beta
         *  exists) */
        public Catalog.Build build;
        /** the entry's line for this stock version, null when the version is not in it */
        public Catalog.Stock stockRow;
        /** a stable AND a beta build: the Version choice is offered */
        public boolean betaOffered;
        /** a beta build only and the download source is on: no Version choice, the beta is read directly and the
         *  screen says that only a beta version exists */
        public boolean betaOnly;
        /** the versions the choice shows ("" = none) */
        public String stableVersion = "", betaVersion = "";
        public String code = "", board = "", stock = "", name = "";
        /** the serial of the catalog the verdict came from, 0 = no catalog */
        public long catalogSerial;
        /** an installed Lumen OS (verdict APP_UPDATE_OFFERED): the catalog's update of one of its apps, null = none */
        public Catalog.AppUpdate appUpdate;
        public Msg verdict;
        public String[] args = new String[0];
        /** the stock version is not in the catalog for this known model: "start the trial anyway" is offered */
        public boolean forceOffered;
        /** ... and ticked: the verdict is FORCED, the build the newest trial build of the model */
        public boolean forced;
        /** the projector's firmware versions: the rows of lumen-about, the same as on the About page of Lumen OS */
        public XgimiFacts facts;
        public boolean ok() { return verdict == Msg.SUPPORTED || verdict == Msg.FORCED; }
    }

    private final Host host;
    private final Config cfg;
    private final Listener l;
    private final Http http;
    private final ParallelDownload downloader;
    private volatile boolean stop;

    public Detection det;
    /** the signed catalog in use (null: none verified yet) */
    public Catalog catalog;
    /** the build the last check found (the chosen one), null before */
    public Catalog.Build build;
    public TrialState trial = TrialState.NONE;
    public String version = "", imageName = "", imageSha = "", imageUrl = "";
    public long imageSize, superBytes, dataFreeBytes;
    /** KiB of super a finished XGIMI update's leftovers hold, which the trial's install clears (0: none) */
    public long leftoverKib;
    public boolean virtualAb;
    /** the space plan of the last check, bytes: what /data must hold and its parts */
    public long needData, needSystem, needUserdata, needReserve, needDownload, needMargin;
    private final File dir;
    private final Properties st = new Properties();

    public Engine(Host host, Config cfg, Listener l) {
        this.host = host;
        this.cfg = cfg;
        this.l = l;
        this.http = new Http(cfg.allowLocalHttp);
        this.downloader = new ParallelDownload(http, r -> {
            Thread t = new Thread(r, "lumen-download");
            t.setDaemon(true);
            return t;
        }, cfg.download, l::log);
        this.dir = host.workDir();
        loadState();
    }

    public void stop() { stop = true; }

    public void resetStop() { stop = false; }

    private final Bytes.Stop stopper = new Bytes.Stop() {
        @Override public boolean stopped() { return stop; }
    };

    // ------------------------------------------------------------------------------------------------- state
    File stateFile() { return new File(dir, "state.properties"); }
    /** the image: always the download, in the app's dir on /data */
    public File imageFile() { return new File(dir, "image.img"); }
    public File tailFile() { return new File(dir, "image.tail"); }

    /** the last verified signed catalog (the newest serial the app has seen), kept for the next start */
    File catalogFile() { return new File(dir, CATALOG_NAME); }
    File catalogSigFile() { return new File(dir, CATALOG_NAME + ".sig"); }

    /** Stable unless the user chose Beta (any other stored value is Stable). */
    public ReleaseChannel channel() { return "beta".equals(state("channel")) ? ReleaseChannel.BETA : ReleaseChannel.STABLE; }

    public void setChannel(ReleaseChannel c) { set("channel", c == ReleaseChannel.BETA ? "beta" : "stable"); saveState(); }

    private volatile boolean force;

    /** "start the trial anyway": only for this run of the app, never saved */
    public void setForce(boolean on) { force = on; }

    public boolean force() { return force; }

    private void loadState() {
        st.clear();
        File f = stateFile();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            st.load(in);
        } catch (IOException e) {
            st.clear();
        }
        // 1.0.8 and older could read the image from a USB stick or a LumenOS folder: that file is not ours, so its
        // record is dropped (the file stays untouched) and the next check downloads the image
        if ("stick".equals(st.getProperty("image.from"))) for (String k : IMAGE_KEYS) st.remove(k);
        st.remove("source");
    }

    private void saveState() {
        File t = new File(dir, "state.properties.tmp");
        try (FileOutputStream out = new FileOutputStream(t)) {
            st.store(out, "LumenInstaller");
        } catch (IOException e) {
            l.log("state not saved: " + e.getMessage());
            return;
        }
        if (!t.renameTo(stateFile())) l.log("state not saved: rename");
    }

    public String state(String k) { return st.getProperty(k, ""); }

    private void set(String k, String v) { st.setProperty(k, v == null ? "" : v); }

    /** what the state says about the image (image.path and image.from: 1.0.8 and older, a stick file) */
    private static final String[] IMAGE_KEYS = { "image.name", "image.sha", "image.size", "image.url", "image.verified",
            "image.mtime", "image.install", "image.clears", "image.path", "image.from", "tail.sha", "tail.size", "total", "version",
            "model" };

    private void forgetImage() {
        ParallelDownload.discard(imageFile());   // with a partial download (image.img.part and its ranges)
        tailFile().delete();
        for (String k : IMAGE_KEYS) st.remove(k);
        saveState();
    }

    private void forgetTail() {
        tailFile().delete();
        for (String k : new String[] { "tail.sha", "tail.size", "total" }) st.remove(k);
        saveState();
    }

    // ------------------------------------------------------------------------------------------------- the catalog
    /** Read and verify a catalog (json + sig); null when missing or refused (the reason goes to the log). */
    Catalog readCatalog(File json, File sig, String what) {
        if (!json.isFile() || !sig.isFile()) return null;
        try {
            return Catalog.parse(Bytes.readFile(json, 1 << 20), Bytes.readFile(sig, 4096), cfg.catalogCerts, cfg.allowLocalHttp);
        } catch (Catalog.Bad b) {
            l.log(what + ": catalog refused: " + b.getMessage());
        } catch (IOException e) {
            l.log(what + ": catalog not read: " + e.getMessage());
        }
        return null;
    }

    /** Keep a verified catalog as the app's own copy (only a newer or equal serial ever replaces it). */
    private void keepCatalog(byte[] json, byte[] sig) {
        try {
            File t = new File(dir, CATALOG_NAME + ".tmp"), ts = new File(dir, CATALOG_NAME + ".sig.tmp");
            Bytes.writeFile(t, json);
            Bytes.writeFile(ts, sig);
            if (!ts.renameTo(catalogSigFile()) || !t.renameTo(catalogFile())) l.log("catalog not kept: rename");
        } catch (IOException e) {
            l.log("catalog not kept: " + e.getMessage());
        }
    }

    /**
     * The catalog in use: the newest verified one of this run and the app's own kept copy. null = none.
     */
    public Catalog currentCatalog() {
        Catalog best = catalog;
        Catalog own = readCatalog(catalogFile(), catalogSigFile(), "kept");
        if (own != null && (best == null || own.serial > best.serial)) best = own;
        catalog = best;
        return best;
    }

    /**
     * Download the signed catalog, verify it with the app's list certificates and keep it. A catalog older than the
     * one already verified (a smaller serial) is refused. Offline with a kept catalog: says so (with its date) and the
     * kept one stays in use. false = not refreshed (said why, as a message).
     */
    public boolean refreshCatalog() {
        Catalog old = currentCatalog();
        byte[] json, sig;
        try {
            json = http.fetch(cfg.catalogUrl, 1 << 20);
            sig = http.fetch(cfg.catalogUrl + ".sig", 4096);
        } catch (IOException e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            l.log("catalog not downloaded: " + why);
            if (old != null) l.message(Msg.CATALOG_OFFLINE, day(old.serial));
            else l.message(Msg.NET_FAILED, why);
            return false;
        }
        Catalog got;
        try {
            got = Catalog.parse(json, sig, cfg.catalogCerts, cfg.allowLocalHttp);
        } catch (Catalog.Bad b) {
            l.message(b.msg, b.msg.args == 0 ? new String[0] : new String[] { b.arg == null ? "" : b.arg });
            return false;
        }
        if (old != null && got.serial < old.serial) {
            l.message(Msg.CATALOG_OLD, String.valueOf(got.serial), String.valueOf(old.serial));
            return false;
        }
        keepCatalog(json, sig);
        catalog = got;
        l.log("catalog " + got.serial + " (" + got.models.size() + " models)");
        return true;
    }

    /** the day of a catalog serial (its UTC seconds), as the offline message shows it */
    static String day(long serial) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(serial * 1000L));
    }

    // ------------------------------------------------------------------------------------------------- detect
    public Detection detect() {
        Map<String, String> p = host.props();
        Detection d = new Detection();
        d.code = Props.get(p, "ro.boot.xgimi.modelname");
        if (d.code.isEmpty()) d.code = Props.get(p, "ro.xgimi.modelname");
        d.board = Props.get(p, "ro.boot.hardware");
        d.stock = Props.get(p, "ro.vendor.build.version.incremental");
        d.facts = XgimiFacts.read(k -> Props.get(p, k), host::file, host.kernel(), host::dlpImageVersion);
        Catalog cat = currentCatalog();
        d.catalogSerial = cat == null ? 0 : cat.serial;
        d.model = cat == null ? null : cat.find(d.code);
        // the builds this stock version can take: a stable one and the newest beta, each only when it lists the version
        Catalog.Build stable = fits(d.model, ReleaseChannel.STABLE, d.stock), beta = fits(d.model, ReleaseChannel.BETA, d.stock);
        d.betaOffered = stable != null && beta != null;
        d.betaOnly = stable == null && beta != null;
        d.stableVersion = stable == null ? "" : stable.version;
        d.betaVersion = beta == null ? "" : beta.version;
        d.build = row(stable, beta);
        d.stockRow = d.model == null ? null : d.model.stock(d.stock);
        d.name = d.model != null ? d.model.name : (d.code.isEmpty() ? "?" : d.code);
        String lumen = Props.get(p, "ro.lumen.version");
        if (lumen.isEmpty()) lumen = Props.get(p, "ro.z9x.version");
        String code = d.code.isEmpty() ? "?" : d.code;
        if ("1".equals(Props.get(p, "ro.gsid.image_running"))) {
            d.verdict = Msg.IN_TRIAL;
        } else if (!lumen.isEmpty()) {
            d.appUpdate = appUpdate(d.model, lumen);
            d.verdict = d.appUpdate != null ? Msg.APP_UPDATE_OFFERED : Msg.ALREADY_LUMEN; d.args = new String[] { lumen };
        } else if (cat == null) {
            d.verdict = Msg.CATALOG_MISSING;
        } else if (d.model == null || !d.model.hasTrial()) {
            // no build, or only builds a computer installs (way "computer"): the app installs over the network only
            d.verdict = Msg.NO_BUILD; d.args = new String[] { code, cat.releases };
        } else if (!d.model.board.equals(d.board)) {
            d.verdict = Msg.BOARD_MISMATCH; d.args = new String[] { code, d.board.isEmpty() ? "?" : d.board };
        } else if (d.model.refused.containsKey(d.stock)) {
            d.verdict = Msg.CATALOG_NOTE; d.args = new String[] { Catalog.text(d.model.refused.get(d.stock), cfg.lang) };
        } else if ((d.stockRow == null || d.build == null) && !forceTaken(d)) {
            if (d.stockRow == null) {
                // the catalog's stock versions of this model code (another one may be a firmware of another model)
                d.verdict = Msg.MODEL_STOCK_UNSUPPORTED;
                d.args = new String[] { code, d.stock.isEmpty() ? "?" : d.stock, join(d.model.stockVersions()) };
            } else {
                d.verdict = Msg.STOCK_UNSUPPORTED; d.args = new String[] { d.stock, join(buildStock(d.model)) };
            }
        } else if (!"orange".equals(Props.get(p, "ro.boot.verifiedbootstate"))) {
            String v = Props.get(p, "ro.boot.verifiedbootstate");
            d.verdict = Msg.NOT_ORANGE; d.args = new String[] { v.isEmpty() ? "?" : v };
        } else if (d.forced) {
            d.verdict = Msg.FORCED;
            d.args = new String[] { d.stock.isEmpty() ? "?" : d.stock, d.build != null ? d.build.version : "?" };
        } else {
            d.verdict = Msg.SUPPORTED;
        }
        det = d;
        return d;
    }

    /** A stock version the catalog does not list for this known model (it has a trial build): the choice is offered,
     *  and when the user ticked it the build becomes the newest trial build. */
    private boolean forceTaken(Detection d) {
        Catalog.Build b = forceBuild(d.model);
        if (b == null) return false;
        d.forceOffered = true;
        if (!force) return false;
        d.forced = true;
        d.betaOffered = d.betaOnly = false;
        d.stableVersion = d.betaVersion = "";
        d.build = b;
        return true;
    }

    /** the trial build a forced trial starts: the newest beta (a trial starts from the newest beta), else the stable */
    static Catalog.Build forceBuild(Catalog.Entry m) {
        for (ReleaseChannel c : new ReleaseChannel[] { ReleaseChannel.BETA, ReleaseChannel.STABLE }) {
            Catalog.Build b = m == null ? null : m.build(c);
            if (b != null && b.trial) return b;
        }
        return null;
    }

    /** The build a download reads: the stable one, or the beta one when the user chose Beta and there is one. With a
     *  beta only, the beta is read without a choice. */
    Catalog.Build row(Catalog.Build s, Catalog.Build b) {
        if (s == null) return b;
        return channel() == ReleaseChannel.BETA && b != null ? b : s;
    }

    /** the trial build of this channel when it takes this stock version, else null */
    static Catalog.Build fits(Catalog.Entry m, ReleaseChannel c, String stock) {
        Catalog.Build b = m == null ? null : m.build(c);
        return b != null && b.stock.contains(stock) ? b : null;
    }

    /** The catalog's update of an app of this running Lumen OS version, when that app is installed with an older
     *  version code; null = none (no row for this version, the app is not there, or it is that version already). */
    Catalog.AppUpdate appUpdate(Catalog.Entry m, String lumen) {
        Catalog.AppUpdate u = m == null ? null : m.appUpdate(lumen);
        if (u == null) return null;
        Host.App have = host.app(u.pkg);
        return have != null && have.versionCode < u.versionCode ? u : null;
    }

    /** every stock version some trial build of the model takes */
    static List<String> buildStock(Catalog.Entry m) {
        List<String> out = new java.util.ArrayList<>();
        for (Catalog.Build b : m.builds) if (b.trial) for (String v : b.stock) if (!out.contains(v)) out.add(v);
        return out;
    }

    // ------------------------------------------------------------------------------------------------- check
    private boolean fail(InstallStep s, Msg m, String... args) {
        l.message(m, args);
        l.step(s, StepStatus.FAILED);
        return false;
    }

    /**
     * Detection, root probe, the build's signed files, free space. Writes nothing except the app's own state file.
     */
    public boolean check() {
        l.step(InstallStep.CHECK, StepStatus.RUNNING);
        // the catalog first, every time: what this model may get is the catalog's
        if (!refreshCatalog()) {
            l.step(InstallStep.CHECK, StepStatus.FAILED);
            det = detect();
            return false;
        }
        Detection d = detect();
        if (!d.ok()) return fail(InstallStep.CHECK, d.verdict, d.args);
        Catalog.Entry m = d.model;
        List<X509Certificate> certs = m.certs;

        // root probe: proves the route, reads what an app cannot see
        Root.Job probe = new Root.Job("probe", Root.newNonce());
        Root.Result pr = runRoot(probe, cfg.probeTimeoutMs);
        if (pr == null) return fail(InstallStep.CHECK, Msg.ROOT_UNAVAILABLE);
        if (!pr.done) return fail(InstallStep.CHECK, rootMsg(pr), rootArgs(pr));
        if (!m.code.equals(pr.kv("model"))) return fail(InstallStep.CHECK, Msg.ROOT_FAILED, "model " + pr.kv("model"));
        String sn = pr.kv("serial");
        if (sn != null && sn.matches(SERIAL) && !sn.equals("unknown")) unitSerial = sn;
        // the stock really is this model's (the catalog says): its product name and its own config folder
        String prod = pr.kv("product");
        if (m.product != null && prod != null && !prod.isEmpty() && !prod.equals(m.product)) {
            return fail(InstallStep.CHECK, Msg.PRODUCT_MISMATCH, m.code, prod, m.product);
        }
        if (m.product != null && (prod == null || prod.isEmpty())) l.log("stock product name empty: not cross-checked with " + m.product);
        if (m.configCheck && !"1".equals(pr.kv("model_config"))) return fail(InstallStep.CHECK, Msg.MODEL_CONFIG_MISSING, m.code);
        if (!"1".equals(pr.kv("gsi_tool")) || !"1".equals(pr.kv("metadata_gsi"))) return fail(InstallStep.CHECK, Msg.NO_TRIAL_TOOL);
        if ("1".equals(pr.kv("trial_running"))) return fail(InstallStep.CHECK, Msg.IN_TRIAL);
        // a stock update: still being finished (wait in XGIMI), or the leftovers of a finished one (the trial's install
        // clears them, said here before the ten-minute trial; the trial checks again and alone decides)
        StockUpdate.Result su = StockUpdate.judge(pr.kv("ota_pending"), pr.kv("ota_state"), pr.kv("ota_markers"), pr.kv("cow_parts"),
                pr.kv("snap_devs"), pr.kv("slot"));
        if (su.verdict == StockVerdict.PENDING) { l.log("XGIMI update pending: " + su.why); return fail(InstallStep.CHECK, Msg.OTA_PENDING); }
        if (su.verdict == StockVerdict.LEFTOVERS_UNSURE) {
            l.log("leftovers of an XGIMI update (" + su.names + "), not cleared by the install: " + su.why);
            return fail(InstallStep.CHECK, Msg.LEFTOVERS_STICK, mb(su.cowKib * 1024));
        }
        leftoverKib = su.verdict == StockVerdict.LEFTOVERS ? su.cowKib : 0;
        if (leftoverKib > 0) l.log("leftovers of a finished XGIMI update, cleared by the install: " + su.names);
        superBytes = pr.kvLong("super_kib", 0) * 1024;
        if (superBytes <= 0) {   // unknown: assume a large super, so the check errs on the safe side
            superBytes = 10L << 30;
            l.log("super size unknown, assuming 10 GiB for the space check");
        }
        dataFreeBytes = pr.kvLong("data_free_kib", 0) * 1024;
        virtualAb = "1".equals(pr.kv("virtual_ab"));
        set("super.kib", String.valueOf(superBytes / 1024));
        set("virtual_ab", virtualAb ? "1" : "0");
        if ("1".equals(pr.kv("trial_installed"))) {
            boolean ours = m.code.equals(state("trial.model")) && !state("trial.version").isEmpty();
            trial = ours ? TrialState.OURS : TrialState.OTHER;
            saveState();
            if (ours) return fail(InstallStep.CHECK, Msg.TRIAL_INSTALLED, state("trial.version"));
            return fail(InstallStep.CHECK, Msg.TRIAL_INSTALLED_OTHER);
        }
        trial = TrialState.NONE;
        if (!state("trial.version").isEmpty()) { st.remove("trial.version"); st.remove("trial.model"); }

        // the build of the catalog, checked against its own signed SHA256SUMS and the file on the server
        Catalog.Build b = d.build;
        l.log("build " + b.channel + " " + b.version + " (" + b.image + ")");
        try {
            byte[] sums = http.fetch(b.sums, 64 * 1024);
            byte[] ssig = http.fetch(b.sums + ".sig", 4096);
            String sha = Release.imageSha(sums, ssig, certs, b.imageName);
            if (!sha.equals(b.sha256)) return fail(InstallStep.CHECK, Msg.CATALOG_MISMATCH, b.imageName + " sha256");
            imageUrl = b.image;
            imageSize = http.size(imageUrl);
        } catch (Release.Bad x) {
            return fail(InstallStep.CHECK, x.msg, x.msg.args == 0 ? new String[0] : new String[] { x.arg == null ? "" : x.arg });
        } catch (IOException e) {
            return fail(InstallStep.CHECK, Msg.NET_FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (imageSize != b.size) return fail(InstallStep.CHECK, Msg.CATALOG_MISMATCH, b.imageName + " " + imageSize + " bytes");
        build = b;
        version = b.version;
        imageName = b.imageName;
        imageSha = b.sha256;
        if (imageSize <= 0 || imageSize % AvbTail.BS != 0 || imageSize > Catalog.MAX_IMAGE) {
            return fail(InstallStep.CHECK, Msg.IMAGE_SIZE_BAD, String.valueOf(imageSize));
        }
        // another build or another model: the old download is dropped
        if (!imageSha.equals(state("image.sha")) || !m.code.equals(state("model"))) forgetImage();
        set("model", m.code);
        set("version", version);
        set("image.name", imageName);
        set("image.sha", imageSha);
        set("image.size", String.valueOf(imageSize));
        set("image.url", imageUrl);
        set("image.install", b.install ? "1" : "0");
        set("image.clears", b.clearsLeftovers ? "1" : "0");
        set("leftovers.kib", String.valueOf(leftoverKib));
        saveState();
        l.message(Msg.RELEASE_FOUND, version, mb(imageSize));

        // space on /data: the trial's files must be there (they are only ever made on /data; pinned, allocated in
        // full): the trial system (image + tail), the trial's userdata, and on Virtual A/B the size of super that is
        // kept free on top of each partition it creates (partition_installer.cpp
        // GetMinimumFreeSpaceThreshold, so the peak is userdata + system + super), the tail file, what is still to be
        // downloaded, and a margin.
        long tail = tailEstimate(imageSize);
        long have = ParallelDownload.have(imageFile(), imageSize);
        needSystem = imageSize + tail;
        needUserdata = cfg.userdataBytes;
        needReserve = virtualAb ? superBytes : 0;
        needDownload = imageSize - have;
        needMargin = tail + cfg.marginBytes;
        needData = needSystem + needUserdata + needReserve + needDownload + needMargin;
        long free = Math.min(dataFreeBytes, host.freeBytes(dir));
        set("need.bytes", String.valueOf(needData));
        set("free.bytes", String.valueOf(free));
        saveState();
        l.message(Msg.SPACE_PLAN, gb(needData), gb(free), gb(needSystem), gb(needUserdata), gb(needReserve), gb(needDownload),
                gb(needMargin));
        if (free < needData) return fail(InstallStep.CHECK, Msg.NO_SPACE, gb(needData), gb(free));
        // what the check says about the leftovers is what this build's install really does (the catalog says)
        if (leftoverKib == 0) l.message(Msg.CHECK_OK, gb(needData));
        else if (handover() == 1) l.message(Msg.CHECK_OK_LEFTOVERS, gb(needData), mb(leftoverKib * 1024));
        else l.message(Msg.CHECK_OK_LEFTOVERS_KEPT, gb(needData), mb(leftoverKib * 1024));
        l.step(InstallStep.CHECK, StepStatus.DONE);
        return true;
    }

    static long tailEstimate(long imageSize) {
        long t = 0;
        for (long s : AvbTail.levelSizes(imageSize)) t += s;
        return t + 3 * AvbTail.BS;
    }

    // ------------------------------------------------------------------------------------------------- download
    public boolean download() {
        l.step(InstallStep.DOWNLOAD, StepStatus.RUNNING);
        if (imageUrl.isEmpty() || imageSize <= 0) return fail(InstallStep.DOWNLOAD, Msg.NOT_READY);
        File f = imageFile();
        if (state("image.verified").equals(imageSha) && f.length() == imageSize) {
            l.progress(InstallStep.DOWNLOAD, imageSize, imageSize);
            l.message(Msg.DOWNLOADED, mb(imageSize));
            l.step(InstallStep.DOWNLOAD, StepStatus.DONE);
            return true;
        }
        long have = ParallelDownload.have(f, imageSize);
        // the download and the tail need room in the app's dir on /data
        long rest = imageSize - Math.min(have, imageSize);
        if (host.freeBytes(dir) - rest - tailEstimate(imageSize) < 0) {
            return fail(InstallStep.DOWNLOAD, Msg.NO_SPACE, gb(imageSize + tailEstimate(imageSize)), gb(host.freeBytes(dir)));
        }
        String last;
        try {
            last = fetch(InstallStep.DOWNLOAD, imageUrl, f, imageSize, imageSha);
        } catch (Bytes.Stopped e) {
            return stopped(InstallStep.DOWNLOAD);
        }
        if (last != null) return fail(InstallStep.DOWNLOAD, Msg.DOWNLOAD_INTERRUPTED, last);
        l.message(Msg.DOWNLOADED, mb(imageSize));
        l.step(InstallStep.DOWNLOAD, StepStatus.DONE);
        return true;
    }

    /** Download url into f (size bytes) over several range connections at once (apps/core/lumen-download: providers
     *  shape GitHub per connection), every range resumable after a pause, a failure or a restart of the app (f.part
     *  and its ranges, bound to sha256), with the config's retries. Checking the file stays with the verify step.
     *  null = done, else the last error. Bytes.Stopped = paused (what came is kept). */
    private String fetch(InstallStep s, String url, File f, long size, String sha256) throws Bytes.Stopped {
        String last = "";
        for (int attempt = 0; attempt <= cfg.retries; attempt++) {
            if (stop) throw new Bytes.Stopped();
            try {
                downloader.run(url, f, size, sha256, (done, total, speed) -> {
                    l.progress(s, done, total, speed);
                    return !stop;
                });
                if (f.length() != size) throw new IOException("size " + f.length());
                return null;
            } catch (ParallelDownload.Stopped e) {
                throw new Bytes.Stopped();
            } catch (IOException e) {
                last = e.getMessage() == null ? e.toString() : e.getMessage();
                l.log("download: " + last + " (" + ParallelDownload.have(f, size) + " of " + size + " bytes kept)");
                if (e instanceof HttpError) {
                    int c = ((HttpError) e).code;
                    if (c >= 400 && c < 500 && c != 408 && c != 429) break;
                }
                sleep(cfg.retryDelayMs * (1L << Math.min(attempt, 4)));
            }
        }
        return last;
    }

    private boolean stopped(InstallStep s) {
        l.message(Msg.STOPPED);
        l.step(s, StepStatus.WAITING);
        return false;
    }

    // ------------------------------------------------------------------------------------------------- verify
    public boolean verify() {
        l.step(InstallStep.VERIFY, StepStatus.RUNNING);
        File f = imageFile();
        if (imageSha.isEmpty() || f.length() != imageSize) return fail(InstallStep.VERIFY, Msg.NOT_READY);
        if (verified()) {   // verified earlier in this download (the file is in the app's private dir)
            l.message(Msg.VERIFIED);
            handoverMessage();
            l.step(InstallStep.VERIFY, StepStatus.DONE);
            return true;
        }
        String got;
        try {
            got = Bytes.sha256File(f, new Bytes.Progress() {
                @Override public void at(long done, long total) { l.progress(InstallStep.VERIFY, done, total); }
            }, stopper);
        } catch (Bytes.Stopped e) {
            return stopped(InstallStep.VERIFY);
        } catch (IOException e) {
            return fail(InstallStep.VERIFY, Msg.ERROR, e.getMessage());
        }
        if (!got.equals(imageSha)) {
            l.log("sha256 " + got + " != " + imageSha);
            forgetImage();   // the download is deleted, the next run fetches it again
            return fail(InstallStep.VERIFY, Msg.SHA_MISMATCH);
        }
        set("image.verified", imageSha);
        set("image.mtime", String.valueOf(f.lastModified()));
        saveState();
        l.message(Msg.VERIFIED);
        handoverMessage();
        l.step(InstallStep.VERIFY, StepStatus.DONE);
        return true;
    }

    private void handoverMessage() {
        if (handover() == 1) l.message(Msg.HANDOVER_YES);
        else l.message(Msg.HANDOVER_NO, version);
    }

    /** 1: the build installs itself from the trial on this stock version (the catalog says both), and what a finished
     *  XGIMI update left is either not there or cleared by this build's install; 0: it does not. */
    public int handover() {
        boolean open = "1".equals(state("image.install")) && det != null && det.stockRow != null && det.stockRow.install;
        boolean left = parseLong(state("leftovers.kib")) > 0 && !"1".equals(state("image.clears"));
        return open && !left ? 1 : 0;
    }

    private boolean verified() {
        File f = imageFile();
        return !imageSha.isEmpty() && imageSha.equals(state("image.verified")) && f.length() == imageSize
                && String.valueOf(f.lastModified()).equals(state("image.mtime"));
    }

    // ------------------------------------------------------------------------------------------------- prepare
    public boolean prepare() {
        l.step(InstallStep.PREPARE, StepStatus.RUNNING);
        if (!verified()) return fail(InstallStep.PREPARE, Msg.NOT_READY);
        if (prepared()) {
            l.message(Msg.PREPARED);
            l.step(InstallStep.PREPARE, StepStatus.DONE);
            return true;
        }
        File tail = tailFile();
        try {
            KeyPair key = cfg.avbKey != null ? cfg.avbKey : AvbTail.newKey();
            byte[] salt = cfg.avbSalt != null ? cfg.avbSalt : AvbTail.newSalt();
            AvbTail.Result r = AvbTail.build(imageFile(), tail, salt, key, new Bytes.Progress() {
                @Override public void at(long done, long total) { l.progress(InstallStep.PREPARE, done, total); }
            }, stopper);
            AvbTail.selfCheck(tail, imageSize);
            set("tail.sha", r.tailSha256);
            set("tail.size", String.valueOf(r.tailSize));
            set("total", String.valueOf(r.total));
            saveState();
            l.log("AVB footer: tree " + r.treeSize + " B, vbmeta " + r.vbmetaSize + " B, trial image " + r.total + " B");
        } catch (Bytes.Stopped e) {
            tail.delete();
            return stopped(InstallStep.PREPARE);
        } catch (Exception e) {
            tail.delete();
            return fail(InstallStep.PREPARE, Msg.PREPARE_FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        l.message(Msg.PREPARED);
        l.step(InstallStep.PREPARE, StepStatus.DONE);
        return true;
    }

    /** check + download + verify + prepare. Stops before start: that one needs the user's confirmation. */
    public boolean prepareAll() {
        return check() && download() && verify() && prepare();
    }

    public boolean prepared() {
        if (!verified()) return false;
        File t = tailFile();
        String sz = state("tail.size");
        if (sz.isEmpty() || t.length() != Long.parseLong(sz)) return false;
        try {
            return Bytes.sha256File(t, null, null).equals(state("tail.sha"));
        } catch (IOException e) {
            return false;
        }
    }

    /** the catalog has a trial build for this model and lists this stock version */
    public boolean canStart() { return det != null && det.ok() && (det.stockRow != null || det.forced); }

    /** the trial may start, installing for good from it is not open for this build and stock version */
    public boolean trialOnly() { return canStart() && handover() != 1; }

    // ------------------------------------------------------------------------------------------------- dump
    /** the stock system info may be made here: on the XGIMI system, not in a trial or on Lumen OS */
    public static boolean dumpAllowed(Detection d) {
        return d != null && d.verdict != Msg.IN_TRIAL && d.verdict != Msg.ALREADY_LUMEN && d.verdict != Msg.APP_UPDATE_OFFERED;
    }

    /** the serial a root probe read (memory only: the Sparks card keeps it for its own hash, it is never sent) */
    public volatile String unitSerial = "";
    static final String SERIAL = "[A-Za-z0-9._-]{4,40}";

    /** The random id of this app's reports (not the serial), made once. */
    String reportInstallId() {
        String id = state("dump.install");
        if (!id.matches("[0-9a-f]{24}")) {
            id = Root.newNonce() + Root.newNonce().substring(0, 8);
            set("dump.install", id);
            saveState();
        }
        return id;
    }

    /** where the stock system info goes, in order: persist.lumen.report.url on this unit (tests), else the signed
     *  catalog's "reports" (a direct address before Cloudflare, the place of ro.lumen.report.url on the XGIMI system),
     *  else the app's default */
    public List<String> dumpUrls(Map<String, String> p) {
        Catalog cat = currentCatalog();
        String image = cat == null ? "" : String.join(" ", cat.reports), debug = Props.get(p, "persist.lumen.report.url");
        return debug.isEmpty() && image.isEmpty() ? cfg.dumpUrls : org.lumen.report.ReportUrl.choose(debug, image);
    }

    /** the stock settings app of this firmware: a known name, else the system app that opens the Settings screen */
    String settingsPackage() {
        for (String k : StockDump.SETTINGS_APPS) if (host.app(k) != null) return k;
        String s = host.settingsActivityPackage();
        return s == null || !s.matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+){1,7}") || s.startsWith("org.lumen.") ? "" : s;
    }

    File dumpZip() { return new File(new File(dir, "dump"), "lumen-dump.zip"); }

    /** a zip made before that did not go out yet and may go on: the same file (size and sha256), not too old */
    boolean dumpPending(File zip) {
        String up = state("dump.upload");
        if (!up.matches("[0-9a-f]{32}") || !zip.isFile() || zip.length() != parseLong(state("dump.size"))) return false;
        if (System.currentTimeMillis() - parseLong(state("dump.made")) > cfg.dumpKeepMs) return false;
        try {
            return org.lumen.report.PartUpload.sha256(zip).equals(state("dump.sha"));
        } catch (IOException e) {
            return false;
        }
    }

    void dumpForget(File zip) {
        for (String k : new String[] { "dump.upload", "dump.size", "dump.made", "dump.sha", "dump.serial", "dump.choked" }) set(k, "");
        saveState();
        zip.delete();
    }

    /**
     * The stock system info (a few MB, up to about 25 MB for a firmware the catalog does not know): the stock settings app's APK files, what the app reads itself
     * (Host.facts), the read-only root commands of the script's dump table (when the root route answers), personal
     * data removed (StockDump), sent in parts to our report server (PartUpload). A zip that did not go out stays in the
     * app's dir with its upload id, and the next press goes on where the upload stopped (also after a restart of the
     * app or the projector). Writes nothing outside the app's own dirs. The report code is kept as state dump.code.
     */
    public boolean dump() {
        Map<String, String> p = host.props();
        String code = Props.get(p, "ro.boot.xgimi.modelname");
        if (code.isEmpty()) code = Props.get(p, "ro.xgimi.modelname");
        String stock = Props.get(p, "ro.vendor.build.version.incremental");
        File zip = dumpZip();
        if (dumpPending(zip)) {
            l.message(Msg.DUMP_RESUMING, mb(zip.length()));
        } else {
            dumpForget(zip);
            long free = host.freeBytes(dir);
            if (free < cfg.dumpSpaceBytes) {
                l.message(Msg.DUMP_NO_SPACE, mb(free), mb(cfg.dumpSpaceBytes));
                return false;
            }
            if (!dumpCollect(zip, p, code, stock)) return false;
        }
        long total = zip.length();
        l.message(Msg.DUMP_SENDING, "0", mb(total));
        org.lumen.report.PartUpload u = new org.lumen.report.PartUpload(reportInstallId(), stock, code.toLowerCase(Locale.ROOT),
                "dump", state("dump.serial"), cfg.allowLocalHttp);
        boolean choked = "1".equals(state("dump.choked"));
        long[] shown = { -1 };
        Rate rate = new Rate();
        try {
            String got = u.send(dumpUrls(p), zip, state("dump.upload"), choked, (sent, t) -> {
                long sp = rate.at(System.currentTimeMillis(), sent);
                if (sent >> 20 != shown[0]) {
                    shown[0] = sent >> 20;
                    l.message(Msg.DUMP_SENDING, String.valueOf(sent >> 20), mb(t));
                }
                l.progress(null, sent, t, sp);
                return !stop;
            });
            set("dump.code", got);
            dumpForget(zip);
            l.log("dump sent: " + got + " (" + total + " B)");
            l.message(Msg.DUMP_SENT, got);
            return true;
        } catch (org.lumen.report.PartUpload.Failed e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            l.log("dump not sent: " + why + (e.retry ? " (kept to go on)" : ""));
            if (e.retry) {
                set("dump.choked", u.choked() ? "1" : "");
                saveState();
                l.message(Msg.DUMP_PAUSED, why);
            } else {
                dumpForget(zip);
                l.message(Msg.DUMP_FAILED, why);
            }
            return false;
        } catch (IOException e) {
            // stopped (Pause, the app closed) or the zip could not be read: kept, the next press goes on
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            set("dump.choked", u.choked() ? "1" : "");
            saveState();
            l.log("dump stopped: " + why);
            l.message(Msg.DUMP_PAUSED, why);
            return false;
        }
    }

    /** The speed of an upload for the minutes left: bytes per second over the last WINDOW_MS (0 until MIN_MS passed,
     *  a resumed upload counts from its first answer, not from zero). */
    public static final class Rate {
        public static final long WINDOW_MS = 20_000, MIN_MS = 3_000;
        private final java.util.ArrayDeque<long[]> seen = new java.util.ArrayDeque<>();

        public long at(long now, long sent) {
            seen.addLast(new long[] { now, sent });
            while (seen.size() > 2 && now - seen.peekFirst()[0] > WINDOW_MS) seen.pollFirst();
            long[] first = seen.peekFirst();
            long ms = now - first[0];
            return ms < MIN_MS ? 0 : (sent - first[1]) * 1000 / ms;
        }
    }

    /** the signed catalog lists this model's stock version (as a build's base or refused): the base has this firmware's
     *  files already, so the stock settings app is not sent again (the same file on every unit of a firmware) */
    boolean stockKnown() {
        if (det == null || det.model == null || det.stock.isEmpty()) return false;
        if (det.model.refused.containsKey(det.stock)) return true;
        for (String v : det.model.stockVersions()) if (v.equalsIgnoreCase(det.stock)) return true;
        for (String v : det.model.refused.keySet()) if (v.equalsIgnoreCase(det.stock)) return true;
        return false;
    }

    /** makes the zip and keeps its upload state; false (said why) when it could not be made */
    private boolean dumpCollect(File zip, Map<String, String> p, String code, String stock) {
        l.message(Msg.DUMP_COLLECTING);
        List<StockDump.Item> items = new ArrayList<>();
        for (String[] f : host.facts()) items.add(StockDump.Item.text("app/" + f[0], f[1]));
        String spkg = settingsPackage();
        long apks = 0;
        boolean known = stockKnown();
        for (File f : spkg.isEmpty() ? java.util.Collections.<File>emptyList() : host.apkFiles(spkg)) {
            // a firmware the base does not have: its code and texts without pictures, videos and large libraries
            if (!known) items.add(StockDump.Item.slim("apk/" + spkg + "/" + StockDump.slimName(f.getName()), f));
            apks += f.length();
        }
        Root.Job job = new Root.Job("dump", Root.newNonce());
        if (!spkg.isEmpty()) job.put("SETTINGS_PKG", spkg);
        Root.Result r = det == null || dumpAllowed(det) ? runRoot(job, cfg.dumpTimeoutMs) : null;
        boolean rooted = r != null && r.done;
        File rd = rooted ? new File(r.kv("dump_dir")) : null;
        Map<String, String> all = new java.util.HashMap<>(p);
        String settingsText = "";
        if (rooted && rd.isDirectory() && under(rd, dir)) {
            File[] list = rd.listFiles();
            if (list != null) {
                Arrays.sort(list);
                for (File f : list) {
                    String n = f.getName();
                    if (!f.isFile() || !n.matches("[a-z0-9_]+\\.txt")) continue;
                    items.add(StockDump.Item.textFile("root/" + n, f));
                    try {   // root's getprop sees what an app may not (the serial): its values feed the Scrubber too
                        if (n.equals("getprop.txt")) all.putAll(Props.parse(StockDump.read(f, 8 << 20)));
                        if (n.equals("settings.txt")) settingsText = StockDump.read(f, 8 << 20);
                    } catch (IOException e) {
                        l.log("dump: " + n + ": " + e.getMessage());
                    }
                }
            }
        } else {
            l.message(Msg.DUMP_NO_ROOT);
            if (r != null) l.log("dump: root part not made (" + (r.failed ? r.failCode + " " + r.failDetail : "no answer") + ")");
        }
        String serial = Props.get(all, "ro.serialno");
        if (serial.isEmpty()) serial = Props.get(all, "ro.boot.serialno");
        StringBuilder sum = new StringBuilder("Lumen Installer stock system info (format 2)\n");
        sum.append("model: ").append(code).append('\n').append("board: ").append(Props.get(p, "ro.boot.hardware")).append('\n')
                .append("stock: ").append(stock).append('\n').append("product: ").append(Props.get(p, "ro.vendor.xgimi.product.name")).append('\n')
                .append("installer: ").append(cfg.appVersion).append('\n')
                .append("verdict: ").append(det == null ? "-" : det.verdict).append('\n')
                .append("catalog: ").append(det == null ? 0 : det.catalogSerial).append('\n')
                .append("root: ").append(rooted ? "yes" : "no").append('\n')
                .append("settings app: ").append(spkg.isEmpty() ? "-" : spkg).append(" (").append(apks).append(" bytes, ")
                .append(spkg.isEmpty() ? "-" : known ? "not sent: the catalog knows this firmware" : "sent without media").append(")\n")
                .append("made: ").append(day(System.currentTimeMillis() / 1000)).append('\n')
                .append("files: ").append(items.size()).append('\n');
        try {
            org.lumen.report.Scrubber sc = StockDump.scrubber(all, serial);
            StockDump.settingLiterals(sc, settingsText);
            StockDump.Result res = StockDump.write(zip, sum.toString(), items, sc);
            for (String left : res.left) l.log("dump: left out " + left);
            set("dump.upload", org.lumen.report.PartUpload.newUploadId());
            set("dump.size", String.valueOf(zip.length()));
            set("dump.made", String.valueOf(System.currentTimeMillis()));
            set("dump.sha", org.lumen.report.PartUpload.sha256(zip));
            set("dump.serial", serial);
            set("dump.choked", "");
            saveState();
            l.log("dump made: " + res.files + " files, " + zip.length() + " B");
            return true;
        } catch (IOException e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            l.log("dump not made: " + why);
            l.message(Msg.DUMP_FAILED, why);
            zip.delete();
            return false;
        } finally {
            if (rd != null && under(rd, dir)) deleteTree(rd);
        }
    }

    static boolean under(File f, File root) {
        try {
            return f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteTree(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File c : in) deleteTree(c);
        f.delete();
    }

    // ------------------------------------------------------------------------------------------------- start
    /** The only step that writes outside the app: the trial goes into /data/gsi and one boot is armed. */
    public boolean start() {
        l.step(InstallStep.START, StepStatus.RUNNING);
        if (det == null || !det.ok()) return fail(InstallStep.START, Msg.NOT_READY);
        if (!canStart()) return fail(InstallStep.START, Msg.NOT_READY);
        if (!prepared()) return fail(InstallStep.START, Msg.NOT_READY);
        long total = imageSize + Long.parseLong(state("tail.size"));
        long superB = parseLong(state("super.kib")) * 1024;
        long need = total + cfg.userdataBytes + ("1".equals(state("virtual_ab")) ? superB : 0) + cfg.marginBytes;
        Root.Job j = new Root.Job("install", Root.newNonce())
                .put("IMG", imageFile().getAbsolutePath())
                .put("TAIL", tailFile().getAbsolutePath())
                .put("IMG_SIZE", String.valueOf(imageSize))
                .put("TAIL_SIZE", state("tail.size"))
                .put("TOTAL", String.valueOf(total))
                .put("USERDATA", String.valueOf(cfg.userdataBytes))
                .put("IMG_SHA", imageSha)
                .put("TAIL_SHA", state("tail.sha"))
                .put("NEED_KIB", String.valueOf(need / 1024))
                .put("MODEL", det.model.code);
        Root.Result r = runRoot(j, cfg.rootTimeoutMs);
        if (r == null) return fail(InstallStep.START, Msg.ROOT_UNAVAILABLE);
        if (!r.done) return fail(InstallStep.START, Msg.START_FAILED, r.failCode.isEmpty() ? "timeout" : r.failCode);
        set("trial.version", version);
        set("trial.model", det.model.code);
        saveState();
        trial = TrialState.OURS;
        // the trial has its own copy now: free the /data the download took
        forgetImage();
        l.message(Msg.STARTED);
        l.step(InstallStep.START, StepStatus.DONE);
        return true;
    }

    /** Arm the installed trial for one more boot (gsi_tool enable --single-boot). */
    public boolean enableAgain() {
        l.step(InstallStep.START, StepStatus.RUNNING);
        if (det == null || det.model == null || !canStart()) return fail(InstallStep.START, Msg.NOT_READY);
        // only a trial this app installed for this model (never another one)
        if (state("trial.version").isEmpty() || !det.model.code.equals(state("trial.model"))) return fail(InstallStep.START, Msg.NOT_READY);
        Root.Result r = runRoot(new Root.Job("enable", Root.newNonce()).put("MODEL", det.model.code), cfg.probeTimeoutMs);
        if (r == null) return fail(InstallStep.START, Msg.ROOT_UNAVAILABLE);
        if (!r.done) return fail(InstallStep.START, Msg.START_FAILED, r.failCode);
        l.message(Msg.STARTED);
        l.step(InstallStep.START, StepStatus.DONE);
        return true;
    }

    public boolean removeTrial() {
        Root.Result r = runRoot(new Root.Job("remove", Root.newNonce()), cfg.probeTimeoutMs);
        if (r == null) { l.message(Msg.ROOT_UNAVAILABLE); return false; }
        if (!r.done) { l.message(Msg.REMOVE_FAILED, r.failCode); return false; }
        st.remove("trial.version");
        st.remove("trial.model");
        saveState();
        trial = TrialState.NONE;
        l.message(Msg.REMOVED);
        return true;
    }

    /** Restart (only from the Restart button). The projector goes down within seconds of the request, so this returns
     *  only when it did not: the reason is on the screen and the button is offered again. */
    public void reboot() {
        l.message(Msg.REBOOTING);
        Root.Result r = runRoot(new Root.Job("reboot", Root.newNonce()), cfg.rebootWaitMs);
        if (r == null || !r.started) { l.message(Msg.ROOT_UNAVAILABLE); return; }
        if (r.done) sleep(cfg.rebootWaitMs);
        l.log("restart: " + (r.done ? "still running after " + cfg.rebootWaitMs + " ms" : r.failCode.isEmpty() ? "no answer" : r.failCode));
        l.message(Msg.REBOOT_FAILED);
    }

    // ------------------------------------------------------------------------------------------------- app update
    /** the app update's file, in the app's own dir (deleted after the install) */
    File appApk() { return new File(dir, "app-update.apk"); }

    /** the package of the last app update that went in ("" = none yet) */
    public String updatedPkg = "";

    /**
     * An installed Lumen OS whose app the catalog updates (its own update app refuses every install): a fresh catalog,
     * the file (resumable), its sha256, then the file must be that app at that version, signed by the catalog's
     * certificate exactly like the installed app; only then the system installer, which asks the user. Root is never
     * used and nothing outside the app's own dir is written before the user confirms on the system screen.
     */
    public boolean updateApp() {
        l.step(InstallStep.CHECK, StepStatus.RUNNING);
        if (!refreshCatalog()) {
            l.step(InstallStep.CHECK, StepStatus.FAILED);
            det = detect();
            return false;
        }
        Detection d = detect();
        Catalog.AppUpdate u = d.appUpdate;
        if (u == null) return fail(InstallStep.CHECK, d.verdict, d.args);
        l.step(InstallStep.CHECK, StepStatus.DONE);

        l.step(InstallStep.DOWNLOAD, StepStatus.RUNNING);
        File f = appApk();
        if (!u.sha256.equals(state("app.sha"))) {   // another file than the one a pause kept
            ParallelDownload.discard(f);
            set("app.sha", u.sha256);
            saveState();
        }
        String last;
        try {
            last = fetch(InstallStep.DOWNLOAD, u.apk, f, u.size, u.sha256);
        } catch (Bytes.Stopped e) {
            return stopped(InstallStep.DOWNLOAD);
        }
        if (last != null) return fail(InstallStep.DOWNLOAD, Msg.DOWNLOAD_INTERRUPTED, last);
        l.message(Msg.DOWNLOADED, mb(u.size));
        l.step(InstallStep.DOWNLOAD, StepStatus.DONE);

        l.step(InstallStep.VERIFY, StepStatus.RUNNING);
        String got;
        try {
            got = Bytes.sha256File(f, null, stopper);
        } catch (Bytes.Stopped e) {
            return stopped(InstallStep.VERIFY);
        } catch (IOException e) {
            return fail(InstallStep.VERIFY, Msg.ERROR, e.getMessage());
        }
        if (!got.equals(u.sha256)) {
            l.log("sha256 " + got + " != " + u.sha256);
            f.delete();
            return fail(InstallStep.VERIFY, Msg.SHA_MISMATCH);
        }
        l.message(Msg.VERIFIED);
        l.step(InstallStep.VERIFY, StepStatus.DONE);

        l.step(InstallStep.PREPARE, StepStatus.RUNNING);
        String why = appMismatch(u, host.appFile(f), host.app(u.pkg));
        if (why != null) {
            f.delete();
            return fail(InstallStep.PREPARE, Msg.APP_UPDATE_BAD, why);
        }
        l.step(InstallStep.PREPARE, StepStatus.DONE);

        l.step(InstallStep.START, StepStatus.RUNNING);
        l.message(Msg.APP_UPDATE_CONFIRM);
        String r = host.installApp(f, new Host.LineSink() {
            @Override public void line(String s) { l.log(s); }
        }, cfg.appInstallTimeoutMs);
        f.delete();
        Host.App now = host.app(u.pkg);
        if (!"ok".equals(r)) return fail(InstallStep.START, Msg.APP_UPDATE_FAILED, r);
        if (now == null || now.versionCode != u.versionCode) {
            return fail(InstallStep.START, Msg.APP_UPDATE_FAILED, "version " + (now == null ? "none" : String.valueOf(now.versionCode)));
        }
        updatedPkg = u.pkg;
        l.message(Msg.APP_UPDATED);
        l.step(InstallStep.START, StepStatus.DONE);
        det = detect();
        return true;
    }

    /** null when the file is the catalog's app: its package and version, signed by the catalog's certificate and by
     *  exactly the installed app's certificates, over an older installed version; else why not */
    static String appMismatch(Catalog.AppUpdate u, Host.App file, Host.App have) {
        if (file == null) return "not an app";
        if (!u.pkg.equals(file.pkg)) return "package " + file.pkg;
        if (file.versionCode != u.versionCode) return "version " + file.versionCode;
        if (!java.util.Arrays.asList(file.signers.split(",")).contains(u.signer)) return "not signed by the catalog's certificate";
        if (have == null) return "not installed";
        if (have.versionCode >= u.versionCode) return "installed " + have.versionCode;
        if (!file.signers.equals(have.signers)) return "signed by another certificate than the installed app";
        return null;
    }

    // ------------------------------------------------------------------------------------------------- helpers
    private Root.Result runRoot(Root.Job j, long timeout) {
        return runRoot(j, timeout, l::log);
    }

    private Root.Result runRoot(Root.Job j, long timeout, Host.LineSink sink) {
        try {
            return host.root(j, sink, timeout);
        } catch (IOException e) {
            l.log("root: " + e.getMessage());
            return null;
        }
    }

    static Msg rootMsg(Root.Result r) {
        if (!r.started) return Msg.ROOT_UNAVAILABLE;
        if (!r.failed) return Msg.ROOT_TIMEOUT;
        return Msg.ROOT_FAILED;
    }

    static String[] rootArgs(Root.Result r) {
        if (!r.started || !r.failed) return new String[0];
        return new String[] { r.failCode + (r.failDetail.isEmpty() ? "" : " " + r.failDetail) };
    }

    public static long parseLongSafe(String s) { return parseLong(s); }

    static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append(", "); b.append(s); }
        return b.toString();
    }

    public static String gb(long bytes) { return String.format(Locale.ROOT, "%.1f", bytes / (double) (1L << 30)); }

    public static String mb(long bytes) { return String.valueOf((bytes + (1 << 20) - 1) >> 20); }

    private void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stop && System.currentTimeMillis() < end) {
            try { Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis()))); } catch (InterruptedException e) { return; }
        }
    }
}
