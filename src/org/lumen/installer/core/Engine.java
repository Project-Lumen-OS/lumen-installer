package org.lumen.installer.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The installer's steps, pure Java (no android.*), so the host tests run exactly this code against a fake projector
 * and a fake GitHub. The app calls it from a worker thread.
 *
 *   detect   props only: model, board, stock version, Lumen/trial already running, orange, looked up in the signed
 *            catalog of builds (Catalog: the last verified one the app keeps, or the one of a LumenOS folder).
 *            The app knows no model: the catalog lists the models that have a published build, and each build.
 *   check    a fresh signed catalog (download source), root probe (debugserver), the build's signed SHA256SUMS (it must
 *            give the catalog's sha256) and the image size on the server (the catalog's), free space; or, with the
 *            USB stick source, the same signed SHA256SUMS read from the stick, naming exactly a build of the catalog
 *   download the image, resumable (Range + If-Range), into the app's files dir on /data; nothing with the stick source
 *   verify   sha256 against the catalog and the signed SHA256SUMS; whether the build installs itself from the trial
 *   prepare  the AVB hashtree footer as a separate tail file (AvbTail), checked again after writing
 *   start    ONLY after the user confirmed: root `cat image tail | gsi_tool install ... --no-reboot`, then
 *            `gsi_tool enable --single-boot`; the user restarts. One-shot: the restart after that is stock again.
 * Nothing is written outside the app's own dirs before start, and start writes only /data/gsi and /metadata/gsi
 * (the trial's own files; they must be on /data, never on a stick). Models that are not supported never reach the
 * release, the stick or the root side.
 */
public final class Engine {

    /** the folder on the stick: at its root (needs the storage permission) or in the app's own folder on it */
    public static final String KIT_DIR = "LumenOS";

    public interface Listener {
        void step(InstallStep s, StepStatus st);
        void message(Msg m, String... args);
        void progress(InstallStep s, long done, long total);
        void log(String line);
    }

    /**
     * the signed catalog of builds: its address, and the certificates of its key (the only ones in the app). 1.0.3 reads
     * catalog.json and refuses that whole file for a model code that is not a G and four digits, so the codes of other
     * letters are only in catalog-v2.json. Each file has its own serial: the kept copy, the LumenOS folder's copy and the
     * download all use this one name, so a serial is only ever compared with one of the same file.
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
        public long rootTimeoutMs = 45 * 60 * 1000L;
        public long probeTimeoutMs = 3 * 60 * 1000L;
        /** how long the restart request may take, and then the projector's going down: still running = no restart */
        public long rebootWaitMs = 60 * 1000L;
        public KeyPair avbKey;                     // tests only; the app makes a new key per run
        public byte[] avbSalt;                     // tests only
    }

    public static final class Detection {
        /** the catalog entry of this model; null = no build for it (or no catalog yet) */
        public Catalog.Entry model;
        /** the build a download reads: the stable one, or the beta one when the user chose Beta (or when only a beta
         *  exists); null with the stick source (the folder's file names the build) */
        public Catalog.Build build;
        /** the entry's line for this stock version, null when the version is not in it */
        public Catalog.Stock stockRow;
        /** a stable AND a beta build and the download source is on: the Version choice is offered */
        public boolean betaOffered;
        /** a beta build only and the download source is on: no Version choice, the beta is read directly and the
         *  screen says that only a beta version exists */
        public boolean betaOnly;
        /** the versions the choice shows ("" = none) */
        public String stableVersion = "", betaVersion = "";
        public String code = "", board = "", stock = "", name = "";
        /** the serial of the catalog the verdict came from, 0 = no catalog */
        public long catalogSerial;
        public Msg verdict;
        public String[] args = new String[0];
        public boolean ok() { return verdict == Msg.SUPPORTED; }
    }

    private final Host host;
    private final Config cfg;
    private final Listener l;
    private final Http http;
    private volatile boolean stop;

    public Detection det;
    /** the signed catalog in use (null: none verified yet) */
    public Catalog catalog;
    /** the build the last check found (download: the chosen one, stick: the folder's), null before */
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
    /** the image in use: the download (in the app's dir) or the stick folder's file */
    public File imageFile() { String p = state("image.path"); return p.isEmpty() ? internalImage() : new File(p); }
    File internalImage() { return new File(dir, "image.img"); }
    public File tailFile() { return new File(dir, "image.tail"); }

    /** the last verified signed catalog (the newest serial the app has seen), kept for the next start */
    File catalogFile() { return new File(dir, CATALOG_NAME); }
    File catalogSigFile() { return new File(dir, CATALOG_NAME + ".sig"); }

    public ImageSource source() { return "stick".equals(state("source")) ? ImageSource.STICK : ImageSource.NET; }

    public void setSource(ImageSource s) { set("source", s == ImageSource.STICK ? "stick" : "net"); saveState(); }

    /** Stable unless the user chose Beta (any other stored value is Stable). */
    public ReleaseChannel channel() { return "beta".equals(state("channel")) ? ReleaseChannel.BETA : ReleaseChannel.STABLE; }

    public void setChannel(ReleaseChannel c) { set("channel", c == ReleaseChannel.BETA ? "beta" : "stable"); saveState(); }

    /** is the image our own download, which we may delete? Never a stick folder file. */
    boolean imageIsDownload() { return !"stick".equals(state("image.from")); }

    private void loadState() {
        st.clear();
        File f = stateFile();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            st.load(in);
        } catch (IOException e) {
            st.clear();
        }
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

    private void forgetImage() {
        if (imageIsDownload()) imageFile().delete();   // a file of the user's stick folder is never deleted
        internalImage().delete();
        tailFile().delete();
        for (String k : new String[] { "image.name", "image.sha", "image.size", "image.url", "image.etag", "image.verified",
                "image.mtime", "image.install", "image.clears", "image.path", "image.from", "tail.sha", "tail.size", "total", "version",
                "model" }) st.remove(k);
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

    /** The catalog a LumenOS folder carries (catalog-v2.json + .sig next to the release files), when one is there. */
    private File kitCatalogDir() {
        for (Host.Volume v : host.kitVolumes()) {
            for (File d : new File[] { new File(v.root, KIT_DIR), new File(v.appDir, KIT_DIR) }) {
                if (new File(d, CATALOG_NAME).canRead()) return d;
            }
        }
        return null;
    }

    /**
     * The catalog in use: the newest verified one of the app's own copy and (stick source) the LumenOS folder's. A
     * folder catalog newer than the app's copy becomes the app's copy. null = none.
     */
    public Catalog currentCatalog() {
        Catalog best = catalog;
        Catalog own = readCatalog(catalogFile(), catalogSigFile(), "kept");
        if (own != null && (best == null || own.serial > best.serial)) best = own;
        if (source() == ImageSource.STICK) {
            File kd = kitCatalogDir();
            Catalog k = kd == null ? null : readCatalog(new File(kd, CATALOG_NAME), new File(kd, CATALOG_NAME + ".sig"), "LumenOS folder");
            if (k != null && (best == null || k.serial > best.serial)) {
                best = k;
                try {
                    keepCatalog(Bytes.readFile(new File(kd, CATALOG_NAME), 1 << 20), Bytes.readFile(new File(kd, CATALOG_NAME + ".sig"), 4096));
                } catch (IOException e) {
                    l.log("catalog not kept: " + e.getMessage());
                }
            } else if (k != null && best != null && k.serial < best.serial) {
                l.log("LumenOS folder: its catalog (" + k.serial + ") is older than the one in use (" + best.serial + ")");
            }
        }
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
        Catalog cat = currentCatalog();
        d.catalogSerial = cat == null ? 0 : cat.serial;
        d.model = cat == null ? null : cat.find(d.code);
        // the builds this stock version can take: a stable one and the newest beta, each only when it lists the version
        Catalog.Build stable = fits(d.model, ReleaseChannel.STABLE, d.stock), beta = fits(d.model, ReleaseChannel.BETA, d.stock);
        boolean net = source() == ImageSource.NET;
        d.betaOffered = net && stable != null && beta != null;
        d.betaOnly = net && stable == null && beta != null;
        d.stableVersion = stable == null ? "" : stable.version;
        d.betaVersion = beta == null ? "" : beta.version;
        d.build = net ? row(stable, beta) : null;
        d.stockRow = d.model == null ? null : d.model.stock(d.stock);
        d.name = d.model != null ? d.model.name : (d.code.isEmpty() ? "?" : d.code);
        String lumen = Props.get(p, "ro.lumen.version");
        if (lumen.isEmpty()) lumen = Props.get(p, "ro.z9x.version");
        String code = d.code.isEmpty() ? "?" : d.code;
        if ("1".equals(Props.get(p, "ro.gsid.image_running"))) {
            d.verdict = Msg.IN_TRIAL;
        } else if (!lumen.isEmpty()) {
            d.verdict = Msg.ALREADY_LUMEN; d.args = new String[] { lumen };
        } else if (cat == null) {
            d.verdict = Msg.CATALOG_MISSING;
        } else if (d.model == null) {
            d.verdict = Msg.NO_BUILD; d.args = new String[] { code, cat.releases };
        } else if (!d.model.hasTrial()) {
            d.verdict = Msg.COMPUTER_ONLY; d.args = new String[] { d.model.name, cat.guide };
        } else if (!d.model.board.equals(d.board)) {
            d.verdict = Msg.BOARD_MISMATCH; d.args = new String[] { code, d.board.isEmpty() ? "?" : d.board };
        } else if (d.model.refused.containsKey(d.stock)) {
            d.verdict = Msg.CATALOG_NOTE; d.args = new String[] { Catalog.text(d.model.refused.get(d.stock), cfg.lang) };
        } else if (d.stockRow == null) {
            // the catalog's stock versions of this model code (another one may be a firmware of another model)
            d.verdict = Msg.MODEL_STOCK_UNSUPPORTED;
            d.args = new String[] { code, d.stock.isEmpty() ? "?" : d.stock, join(d.model.stockVersions()) };
        } else if (net && d.build == null) {
            d.verdict = Msg.STOCK_UNSUPPORTED; d.args = new String[] { d.stock, join(buildStock(d.model)) };
        } else if (!"orange".equals(Props.get(p, "ro.boot.verifiedbootstate"))) {
            String v = Props.get(p, "ro.boot.verifiedbootstate");
            d.verdict = Msg.NOT_ORANGE; d.args = new String[] { v.isEmpty() ? "?" : v };
        } else {
            d.verdict = Msg.SUPPORTED;
        }
        det = d;
        return d;
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

    /** The stick folder found by {@link #findKit}: a build of the catalog, read from the stick. */
    static final class Kit {
        File dir, image;
        Catalog.Build build;
    }

    /**
     * Detection, the stick folder (stick source), root probe, the build's signed files (download source), free
     * space. Writes nothing except the app's own state file.
     */
    public boolean check() {
        l.step(InstallStep.CHECK, StepStatus.RUNNING);
        ImageSource src = source();
        // the download source asks the catalog first, every time: what this model may get is the catalog's
        if (src == ImageSource.NET && !refreshCatalog()) {
            l.step(InstallStep.CHECK, StepStatus.FAILED);
            det = detect();
            return false;
        }
        Detection d = detect();
        if (!d.ok()) return fail(InstallStep.CHECK, d.verdict, d.args);
        Catalog.Entry m = d.model;
        List<X509Certificate> certs = m.certs;
        // the stick folder first: pure file reads, verified exactly like the download (same certificates, same build)
        Kit kit = null;
        if (src == ImageSource.STICK) {
            kit = findKit(m, certs, d);
            if (kit == null) return false;
        }

        // root probe: proves the route, reads what an app cannot see (and, for a stick, that root reads the file)
        Root.Job probe = new Root.Job("probe", Root.newNonce());
        if (kit != null) probe.put("IMG", kit.image.getAbsolutePath()).put("IMG_SIZE", String.valueOf(kit.image.length()));
        Root.Result pr = runRoot(probe, cfg.probeTimeoutMs);
        if (pr == null) return fail(InstallStep.CHECK, Msg.ROOT_UNAVAILABLE);
        if (!pr.done) return fail(InstallStep.CHECK, rootMsg(pr), rootArgs(pr));
        if (!m.code.equals(pr.kv("model"))) return fail(InstallStep.CHECK, Msg.ROOT_FAILED, "model " + pr.kv("model"));
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
        if (kit != null && !"1".equals(pr.kv("img_readable"))) {
            return fail(InstallStep.CHECK, Msg.STICK_ROOT_UNREADABLE, kit.image.getPath() + " (" + pr.kv("img_path") + ")");
        }

        File target;   // where the image is (stick) or goes (download)
        String from;
        Catalog.Build b;
        if (kit != null) {
            b = kit.build;
            imageUrl = "";
            imageSize = kit.image.length();
            target = kit.image;
            from = "stick";
        } else {
            // the build of the catalog, checked against its own signed SHA256SUMS and the file on the server
            b = d.build;
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
            target = internalImage();
            from = "download";
        }
        build = b;
        version = b.version;
        imageName = b.imageName;
        imageSha = b.sha256;
        if (imageSize <= 0 || imageSize % AvbTail.BS != 0 || imageSize > Catalog.MAX_IMAGE) {
            return fail(InstallStep.CHECK, Msg.IMAGE_SIZE_BAD, String.valueOf(imageSize));
        }
        // another build, another model or another place: the old download is dropped (a stick folder file never)
        if (!imageSha.equals(state("image.sha")) || !m.code.equals(state("model"))
                || !target.getAbsolutePath().equals(imageFile().getAbsolutePath()) || !from.equals(state("image.from").isEmpty() ? "download" : state("image.from"))) {
            forgetImage();
        }
        set("model", m.code);
        set("version", version);
        set("image.name", imageName);
        set("image.sha", imageSha);
        set("image.size", String.valueOf(imageSize));
        set("image.url", imageUrl);
        set("image.path", target.getAbsolutePath());
        set("image.from", from);
        set("image.install", b.install ? "1" : "0");
        set("image.clears", b.clearsLeftovers ? "1" : "0");
        set("leftovers.kib", String.valueOf(leftoverKib));
        saveState();
        if (kit == null) l.message(Msg.RELEASE_FOUND, version, mb(imageSize));

        // space on /data: the trial's files must be there (they are only ever made on /data; pinned, allocated in
        // full): the trial system (image + tail), the trial's userdata, and on Virtual A/B the size of super that is
        // kept free on top of each partition it creates (partition_installer.cpp
        // GetMinimumFreeSpaceThreshold, so the peak is userdata + system + super), the tail file, what is still to be
        // downloaded, and a margin. A stick folder costs no /data space for the image itself.
        long tail = tailEstimate(imageSize);
        boolean download = kit == null;
        long have = download && imageFile().exists() ? Math.min(imageFile().length(), imageSize) : 0;
        needSystem = imageSize + tail;
        needUserdata = cfg.userdataBytes;
        needReserve = virtualAb ? superBytes : 0;
        needDownload = download ? imageSize - have : 0;
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

    /**
     * The stick folder: KIT_DIR at the root of a stick, or in the app's own folder on it. It holds what a release
     * holds: SHA256SUMS + SHA256SUMS.sig (signed with this model's key) and an image that is a build of the catalog for
     * this model (same name, same sha256); that build's stock versions count. null = refused (said why).
     */
    Kit findKit(Catalog.Entry m, List<X509Certificate> certs, Detection d) {
        List<Host.Volume> vols = host.kitVolumes();
        if (vols.isEmpty()) { fail(InstallStep.CHECK, Msg.STICK_NOT_FOUND); return null; }
        File dirFound = null;
        boolean perm = host.canReadSticks(), blocked = false;
        for (Host.Volume v : vols) {
            File top = new File(v.root, KIT_DIR), own = new File(v.appDir, KIT_DIR);
            // the stick's root needs the storage permission (legacy storage); the app's own folder never does
            File ts = new File(top, "SHA256SUMS");
            if (dirFound == null) {
                if (perm && ts.canRead()) dirFound = top;
                else if (top.exists() && (!perm || ts.exists())) blocked = true;   // there, but not readable
            }
            if (dirFound == null && new File(own, "SHA256SUMS").canRead()) dirFound = own;
        }
        if (dirFound == null) {
            fail(InstallStep.CHECK, blocked || !perm ? Msg.STICK_NO_ACCESS : Msg.STICK_NOT_FOUND);
            return null;
        }
        l.log("stick folder " + dirFound);
        Kit k = new Kit();
        k.dir = dirFound;
        try {
            byte[] sums = Bytes.readFile(new File(dirFound, "SHA256SUMS"), 64 * 1024);
            File sf = new File(dirFound, "SHA256SUMS.sig");
            byte[] ssig = sf.canRead() ? Bytes.readFile(sf, 4096) : null;
            for (Map.Entry<String, String> e : Release.listed(sums, ssig, certs).entrySet()) {
                Catalog.Build b = m.byImage(e.getKey(), e.getValue());
                if (b != null) { k.build = b; break; }
            }
        } catch (Release.Bad b) {
            fail(InstallStep.CHECK, b.msg, b.msg.args == 0 ? new String[0] : new String[] { b.arg == null ? "" : b.arg });
            return null;
        } catch (IOException e) {
            fail(InstallStep.CHECK, Msg.STICK_NO_ACCESS);
            l.log("stick: " + e.getMessage());
            return null;
        }
        if (k.build == null) { fail(InstallStep.CHECK, Msg.STICK_NOT_LISTED); return null; }
        if (!k.build.stock.contains(d.stock)) {
            fail(InstallStep.CHECK, Msg.STOCK_UNSUPPORTED, d.stock.isEmpty() ? "?" : d.stock, join(k.build.stock));
            return null;
        }
        k.image = new File(dirFound, k.build.imageName);
        if (!k.image.isFile() || !k.image.canRead()) { fail(InstallStep.CHECK, Msg.STICK_NO_IMAGE, k.build.imageName); return null; }
        l.message(Msg.STICK_FOUND, k.build.version, mb(k.image.length()), dirFound.getPath());
        return k;
    }

    static long tailEstimate(long imageSize) {
        long t = 0;
        for (long s : AvbTail.levelSizes(imageSize)) t += s;
        return t + 3 * AvbTail.BS;
    }

    // ------------------------------------------------------------------------------------------------- download
    public boolean download() {
        l.step(InstallStep.DOWNLOAD, StepStatus.RUNNING);
        if ("stick".equals(state("image.from"))) {   // the stick folder's image is used where it is: nothing to download
            File f = imageFile();
            if (imageSize <= 0 || f.length() != imageSize) return fail(InstallStep.DOWNLOAD, Msg.NOT_READY);
            l.progress(InstallStep.DOWNLOAD, imageSize, imageSize);
            l.message(Msg.FROM_STICK, mb(imageSize));
            l.step(InstallStep.DOWNLOAD, StepStatus.DONE);
            return true;
        }
        if (imageUrl.isEmpty() || imageSize <= 0) return fail(InstallStep.DOWNLOAD, Msg.NOT_READY);
        File f = imageFile();
        if (state("image.verified").equals(imageSha) && f.length() == imageSize) {
            l.progress(InstallStep.DOWNLOAD, imageSize, imageSize);
            l.message(Msg.DOWNLOADED, mb(imageSize));
            l.step(InstallStep.DOWNLOAD, StepStatus.DONE);
            return true;
        }
        long have = f.exists() ? f.length() : 0;
        // the download and the tail need room in the app's dir on /data
        long rest = imageSize - Math.min(have, imageSize);
        if (host.freeBytes(dir) - rest - tailEstimate(imageSize) < 0) {
            return fail(InstallStep.DOWNLOAD, Msg.NO_SPACE, gb(imageSize + tailEstimate(imageSize)), gb(host.freeBytes(dir)));
        }
        String last = "";
        for (int attempt = 0; attempt <= cfg.retries; attempt++) {
            if (stop) return stopped(InstallStep.DOWNLOAD);
            try {
                http.lastEtag = "";
                String tag = http.download(imageUrl, f, imageSize, state("image.etag"), new Http.Progress() {
                    @Override public void at(long done, long total) { l.progress(InstallStep.DOWNLOAD, done, total); }
                }, stopper);
                set("image.etag", tag);
                saveState();
                if (f.length() != imageSize) throw new IOException("size " + f.length());
                l.message(Msg.DOWNLOADED, mb(imageSize));
                l.step(InstallStep.DOWNLOAD, StepStatus.DONE);
                return true;
            } catch (Bytes.Stopped e) {
                if (!http.lastEtag.isEmpty()) { set("image.etag", http.lastEtag); saveState(); }
                return stopped(InstallStep.DOWNLOAD);
            } catch (IOException e) {
                if (!http.lastEtag.isEmpty()) { set("image.etag", http.lastEtag); saveState(); }
                last = e.getMessage() == null ? e.toString() : e.getMessage();
                l.log("download: " + last + " (" + (f.exists() ? f.length() : 0) + " of " + imageSize + " bytes kept)");
                if (e instanceof Http.HttpException) {
                    int c = ((Http.HttpException) e).code;
                    if (c >= 400 && c < 500 && c != 408 && c != 429) break;
                }
                sleep(cfg.retryDelayMs * (1L << Math.min(attempt, 4)));
            }
        }
        return fail(InstallStep.DOWNLOAD, Msg.DOWNLOAD_INTERRUPTED, last);
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
            forgetImage();   // a download is deleted; a stick folder file stays where it is (the user replaces it)
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
    public boolean canStart() { return det != null && det.ok() && det.stockRow != null; }

    /** the trial may start, installing for good from it is not open for this build and stock version */
    public boolean trialOnly() { return canStart() && handover() != 1; }

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
        // the trial has its own copy now: free the /data the download took; an image in a stick folder stays for another
        // trial, only the tail goes
        if ("download".equals(state("image.from").isEmpty() ? "download" : state("image.from"))) forgetImage();
        else forgetTail();
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

    // ------------------------------------------------------------------------------------------------- helpers
    private Root.Result runRoot(Root.Job j, long timeout) {
        try {
            return host.root(j, new Host.LineSink() {
                @Override public void line(String s) { l.log(s); }
            }, timeout);
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
