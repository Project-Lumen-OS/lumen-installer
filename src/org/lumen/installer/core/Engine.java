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
 *            list of projectors (Index: the last verified one the app keeps, or the one of a LumenOS folder).
 *            The app knows no model: the list says which ones exist, what each may do and where its releases are.
 *   check    a fresh signed list (download source), root probe (debugserver), signed channel manifest + SHA256SUMS,
 *            stock version, free space; or, with the USB stick source, the same signed SHA256SUMS (+ the manifest when
 *            the folder has it) read from the stick
 *   download the image, resumable (Range + If-Range), into the app's files dir on /data, or into the app's own
 *            folder on the USB stick (keep on stick: no /data space for the download); nothing with the stick source
 *   verify   sha256 against the signed SHA256SUMS; can the release install itself from the trial (erofs lookup)
 *   prepare  the AVB hashtree footer as a separate tail file (AvbTail), checked again after writing
 *   start    ONLY after the user confirmed: root `cat image tail | gsi_tool install ... --no-reboot`, then
 *            `gsi_tool enable --single-boot`; the user restarts. One-shot: the restart after that is stock again.
 * Nothing is written outside the app's own dirs before start, and start writes only /data/gsi and /metadata/gsi
 * (the trial's own files; they must be on /data, never on a stick). Models that are not supported never reach the
 * release, the stick or the root side.
 */
public final class Engine {
    public enum Step { CHECK, DOWNLOAD, VERIFY, PREPARE, START }
    public enum Status { WAITING, RUNNING, DONE, FAILED }
    public enum Trial { NONE, OURS, OTHER }
    /** where the image comes from: the signed release on GitHub, or a folder of the same files on a USB stick */
    public enum Source { NET, STICK }
    /** which release a download reads ("Version: Stable / Beta", remembered in the state file): the model's stable
     *  channel, or its beta channel when its entry has one (Index.Entry.asBeta). An entry with a beta only has no choice:
     *  its beta is read whatever is stored. Beta never applies to a stick folder. */
    public enum Channel { STABLE, BETA }

    /** the folder on the stick: at its root (needs the storage permission) or in the app's own folder on it */
    public static final String KIT_DIR = "LumenOS";

    public interface Listener {
        void step(Step s, Status st);
        void message(Msg m, String... args);
        void progress(Step s, long done, long total);
        void log(String line);
    }

    /** the signed list of projectors: its address, and the certificates of its key (the only ones in the app) */
    public static final String INDEX_URL = "https://github.com/Project-Lumen-OS/lumen-installer/releases/download/index/devices.json";
    public static final String INDEX_NAME = "devices.json";

    public static final class Config {
        public String indexUrl = INDEX_URL;
        public List<X509Certificate> indexCerts;
        public boolean allowLocalHttp = false;
        public long userdataBytes = 8L << 30;      // the trial's own userdata (8 GiB)
        public long marginBytes = 512L << 20;
        public int retries = 5;
        public long retryDelayMs = 2000;
        public long rootTimeoutMs = 45 * 60 * 1000L;
        public long probeTimeoutMs = 3 * 60 * 1000L;
        public KeyPair avbKey;                     // tests only; the app makes a new key per run
        public byte[] avbSalt;                     // tests only
    }

    public static final class Detection {
        /** the list entry in use: the model's entry, or the same entry reading its beta channel (Version: Beta,
         *  download source, an entry with a beta channel); null = the list has no such model (or no list yet) */
        public Index.Entry model;
        /** the entry's line for this stock version (trial, install), null when the version is not in it */
        public Index.Stock stockRow;
        /** the entry has a stable AND a beta channel and the download source is on: the Version choice is offered */
        public boolean betaOffered;
        /** the entry has a beta channel only and the download source is on: no Version choice, the beta is read
         *  directly and the screen says that only a beta version exists */
        public boolean betaOnly;
        public String code = "", board = "", stock = "", name = "";
        /** the serial of the list the verdict came from, 0 = no list */
        public long indexSerial;
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
    /** the signed list of projectors in use (null: none verified yet) */
    public Index index;
    public Trial trial = Trial.NONE;
    public String version = "", imageName = "", imageSha = "", imageUrl = "";
    public long imageSize, superBytes, dataFreeBytes;
    public boolean virtualAb;
    /** the space plan of the last check, bytes: what /data must hold and its parts */
    public long needData, needSystem, needUserdata, needReserve, needDownload, needMargin, stickFree;
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
    /** the image in use: the download (in the app's dir, or in the app's folder on a stick) or the stick folder's file */
    public File imageFile() { String p = state("image.path"); return p.isEmpty() ? internalImage() : new File(p); }
    File internalImage() { return new File(dir, "image.img"); }
    public File tailFile() { return new File(dir, "image.tail"); }

    /** the last verified signed list of projectors (the newest serial the app has seen), kept for the next start */
    File indexFile() { return new File(dir, INDEX_NAME); }
    File indexSigFile() { return new File(dir, INDEX_NAME + ".sig"); }

    public Source source() { return "stick".equals(state("source")) ? Source.STICK : Source.NET; }

    /** net source only: download into the app's own folder on the USB stick instead of /data */
    public boolean keepOnStick() { return "1".equals(state("keep.stick")); }

    public void setSource(Source s) { set("source", s == Source.STICK ? "stick" : "net"); saveState(); }

    public void setKeepOnStick(boolean k) { set("keep.stick", k ? "1" : "0"); saveState(); }

    /** Stable unless the user chose Beta (any other stored value is Stable). */
    public Channel channel() { return "beta".equals(state("channel")) ? Channel.BETA : Channel.STABLE; }

    public void setChannel(Channel c) { set("channel", c == Channel.BETA ? "beta" : "stable"); saveState(); }

    /** is the image our own download (internal or on the stick), which we may delete? Never a stick folder file. */
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
                "image.mtime", "image.handover", "image.path", "image.from", "tail.sha", "tail.size", "total", "version",
                "model" }) st.remove(k);
        saveState();
    }

    private void forgetTail() {
        tailFile().delete();
        for (String k : new String[] { "tail.sha", "tail.size", "total" }) st.remove(k);
        saveState();
    }

    // ------------------------------------------------------------------------------------------------- the list
    /** Read and verify a list (json + sig); null when missing or refused (the reason goes to the log). */
    Index readIndex(File json, File sig, String what) {
        if (!json.isFile() || !sig.isFile()) return null;
        try {
            return Index.parse(Bytes.readFile(json, 1 << 20), Bytes.readFile(sig, 4096), cfg.indexCerts, cfg.allowLocalHttp);
        } catch (Index.Bad b) {
            l.log(what + ": list of projectors refused: " + b.getMessage());
        } catch (IOException e) {
            l.log(what + ": list of projectors not read: " + e.getMessage());
        }
        return null;
    }

    /** Keep a verified list as the app's own copy (only a newer or equal serial ever replaces it). */
    private void keepIndex(byte[] json, byte[] sig) {
        try {
            File t = new File(dir, INDEX_NAME + ".tmp"), ts = new File(dir, INDEX_NAME + ".sig.tmp");
            Bytes.writeFile(t, json);
            Bytes.writeFile(ts, sig);
            if (!ts.renameTo(indexSigFile()) || !t.renameTo(indexFile())) l.log("list of projectors not kept: rename");
        } catch (IOException e) {
            l.log("list of projectors not kept: " + e.getMessage());
        }
    }

    /** The list a LumenOS folder carries (devices.json + .sig next to the release files), when one is there. */
    private File kitIndexDir() {
        for (Host.Volume v : host.kitVolumes()) {
            for (File d : new File[] { new File(v.root, KIT_DIR), new File(v.appDir, KIT_DIR) }) {
                if (new File(d, INDEX_NAME).canRead()) return d;
            }
        }
        return null;
    }

    /**
     * The list in use: the newest verified one of the app's own copy and (stick source) the LumenOS folder's. A
     * folder list newer than the app's copy becomes the app's copy. null = none.
     */
    public Index currentIndex() {
        Index best = index;
        Index own = readIndex(indexFile(), indexSigFile(), "kept");
        if (own != null && (best == null || own.serial > best.serial)) best = own;
        if (source() == Source.STICK) {
            File kd = kitIndexDir();
            Index k = kd == null ? null : readIndex(new File(kd, INDEX_NAME), new File(kd, INDEX_NAME + ".sig"), "LumenOS folder");
            if (k != null && (best == null || k.serial > best.serial)) {
                best = k;
                try {
                    keepIndex(Bytes.readFile(new File(kd, INDEX_NAME), 1 << 20), Bytes.readFile(new File(kd, INDEX_NAME + ".sig"), 4096));
                } catch (IOException e) {
                    l.log("list of projectors not kept: " + e.getMessage());
                }
            } else if (k != null && best != null && k.serial < best.serial) {
                l.log("LumenOS folder: its list of projectors (" + k.serial + ") is older than the one in use (" + best.serial + ")");
            }
        }
        index = best;
        return best;
    }

    /**
     * Download the signed list of projectors, verify it with the app's list certificates and keep it. A list older
     * than the one already verified (a smaller serial) is refused. false = not refreshed (said why, as a message).
     */
    public boolean refreshIndex() {
        Index old = currentIndex();
        byte[] json, sig;
        try {
            json = http.fetch(cfg.indexUrl, 1 << 20);
            sig = http.fetch(cfg.indexUrl + ".sig", 4096);
        } catch (IOException e) {
            l.message(Msg.NET_FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
            return false;
        }
        Index got;
        try {
            got = Index.parse(json, sig, cfg.indexCerts, cfg.allowLocalHttp);
        } catch (Index.Bad b) {
            l.message(b.msg, b.msg.args == 0 ? new String[0] : new String[] { b.arg == null ? "" : b.arg });
            return false;
        }
        if (old != null && got.serial < old.serial) {
            l.message(Msg.INDEX_OLD, String.valueOf(got.serial), String.valueOf(old.serial));
            return false;
        }
        keepIndex(json, sig);
        index = got;
        l.log("list of projectors " + got.serial + " (" + got.models.size() + " models)");
        return true;
    }

    // ------------------------------------------------------------------------------------------------- detect
    public Detection detect() {
        Map<String, String> p = host.props();
        Detection d = new Detection();
        d.code = Props.get(p, "ro.boot.xgimi.modelname");
        if (d.code.isEmpty()) d.code = Props.get(p, "ro.xgimi.modelname");
        d.board = Props.get(p, "ro.boot.hardware");
        d.stock = Props.get(p, "ro.vendor.build.version.incremental");
        Index ix = currentIndex();
        d.indexSerial = ix == null ? 0 : ix.serial;
        Index.Entry base = ix == null ? null : ix.find(d.code);
        boolean beta = base != null && base.status == Index.Status.SUPPORTED && base.hasBeta() && source() == Source.NET;
        d.betaOffered = beta && !base.betaOnly();
        d.betaOnly = beta && base.betaOnly();
        d.model = row(base);
        d.stockRow = d.model == null ? null : d.model.stock(d.stock);
        d.name = d.model != null ? d.model.name : (d.code.isEmpty() ? "?" : d.code);
        String lumen = Props.get(p, "ro.lumen.version");
        if (lumen.isEmpty()) lumen = Props.get(p, "ro.z9x.version");
        String code = d.code.isEmpty() ? "?" : d.code;
        if ("1".equals(Props.get(p, "ro.gsid.image_running"))) {
            d.verdict = Msg.IN_TRIAL;
        } else if (!lumen.isEmpty()) {
            d.verdict = Msg.ALREADY_LUMEN; d.args = new String[] { lumen };
        } else if (ix == null) {
            d.verdict = Msg.INDEX_MISSING;
        } else if (d.model == null) {
            d.verdict = Msg.UNKNOWN_MODEL; d.args = new String[] { code };
        } else if (d.model.status != Index.Status.SUPPORTED) {
            if (d.model.message != null) { d.verdict = Msg.INDEX_NOTE; d.args = d.model.message.clone(); }
            else if (d.model.status == Index.Status.COMPUTER_ONLY) { d.verdict = Msg.COMPUTER_ONLY; d.args = new String[] { d.model.name }; }
            else { d.verdict = Msg.NOT_YET; d.args = new String[] { d.model.name }; }
        } else if (!d.model.board.equals(d.board)) {
            d.verdict = Msg.BOARD_MISMATCH; d.args = new String[] { code, d.board.isEmpty() ? "?" : d.board };
        } else if (d.model.refused.containsKey(d.stock)) {
            d.verdict = Msg.INDEX_NOTE; d.args = d.model.refused.get(d.stock).clone();
        } else if (d.stockRow == null) {
            // the list's stock versions of this model code (another one may be a firmware of another model)
            d.verdict = Msg.MODEL_STOCK_UNSUPPORTED;
            d.args = new String[] { code, d.stock.isEmpty() ? "?" : d.stock, join(d.model.stockVersions()) };
        } else if (!"orange".equals(Props.get(p, "ro.boot.verifiedbootstate"))) {
            String v = Props.get(p, "ro.boot.verifiedbootstate");
            d.verdict = Msg.NOT_ORANGE; d.args = new String[] { v.isEmpty() ? "?" : v };
        } else {
            d.verdict = Msg.SUPPORTED;
        }
        det = d;
        return d;
    }

    /** The entry a download reads: the model's stable channel, or its beta channel when the user chose Beta and the
     *  entry has one. An entry with a beta only reads its beta without a choice. A stick folder always uses the
     *  model's entry. */
    Index.Entry row(Index.Entry m) {
        if (m == null || source() != Source.NET || !m.hasBeta()) return m;
        if (m.betaOnly()) return m.asBeta();
        return channel() == Channel.BETA ? m.asBeta() : m;
    }

    // ------------------------------------------------------------------------------------------------- check
    private boolean fail(Step s, Msg m, String... args) {
        l.message(m, args);
        l.step(s, Status.FAILED);
        return false;
    }

    /** The stick folder found by {@link #findKit}: the signed list's one image of this model, read from the stick. */
    static final class Kit {
        File dir, image;
        String name, version, sha;
        java.util.List<String> vendorOk;
    }

    /**
     * Detection, the stick folder (stick source), root probe, signed release lookup (net source), free space. Writes
     * nothing except the app's own state file.
     */
    public boolean check() {
        l.step(Step.CHECK, Status.RUNNING);
        Source src = source();
        // the download source asks the list of projectors first, every time: what this model may do is the list's
        if (src == Source.NET && !refreshIndex()) {
            l.step(Step.CHECK, Status.FAILED);
            det = detect();
            return false;
        }
        Detection d = detect();
        if (!d.ok()) return fail(Step.CHECK, d.verdict, d.args);
        Index.Entry m = d.model;
        List<X509Certificate> certs = m.certs;
        // the stick folder first: pure file reads, verified exactly like the download (same certificates, same rules)
        Kit kit = null;
        if (src == Source.STICK) {
            kit = findKit(m, certs, d);
            if (kit == null) return false;
        }

        // root probe: proves the route, reads what an app cannot see (and, for a stick, that root reads the file)
        Root.Job probe = new Root.Job("probe", Root.newNonce());
        if (kit != null) probe.put("IMG", kit.image.getAbsolutePath()).put("IMG_SIZE", String.valueOf(kit.image.length()));
        Root.Result pr = runRoot(probe, cfg.probeTimeoutMs);
        if (pr == null) return fail(Step.CHECK, Msg.ROOT_UNAVAILABLE);
        if (!pr.done) return fail(Step.CHECK, rootMsg(pr), rootArgs(pr));
        if (!m.code.equals(pr.kv("model"))) return fail(Step.CHECK, Msg.ROOT_FAILED, "model " + pr.kv("model"));
        // the stock really is this model's (the list says): its product name and its own config folder
        String prod = pr.kv("product");
        if (m.product != null && prod != null && !prod.isEmpty() && !prod.equals(m.product)) {
            return fail(Step.CHECK, Msg.PRODUCT_MISMATCH, m.code, prod, m.product);
        }
        if (m.product != null && (prod == null || prod.isEmpty())) l.log("stock product name empty: not cross-checked with " + m.product);
        if (m.configCheck && !"1".equals(pr.kv("model_config"))) return fail(Step.CHECK, Msg.MODEL_CONFIG_MISSING, m.code);
        if (!"1".equals(pr.kv("gsi_tool")) || !"1".equals(pr.kv("metadata_gsi"))) return fail(Step.CHECK, Msg.NO_TRIAL_TOOL);
        if ("1".equals(pr.kv("trial_running"))) return fail(Step.CHECK, Msg.IN_TRIAL);
        if ("1".equals(pr.kv("ota_pending"))) return fail(Step.CHECK, Msg.OTA_PENDING);
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
            trial = ours ? Trial.OURS : Trial.OTHER;
            saveState();
            if (ours) return fail(Step.CHECK, Msg.TRIAL_INSTALLED, state("trial.version"));
            return fail(Step.CHECK, Msg.TRIAL_INSTALLED_OTHER);
        }
        trial = Trial.NONE;
        if (!state("trial.version").isEmpty()) { st.remove("trial.version"); st.remove("trial.model"); }
        if (kit != null && !"1".equals(pr.kv("img_readable"))) {
            return fail(Step.CHECK, Msg.STICK_ROOT_UNREADABLE, kit.image.getPath() + " (" + pr.kv("img_path") + ")");
        }

        File target;   // where the image is (stick) or goes (download)
        String from;
        if (kit != null) {
            version = kit.version;
            imageName = kit.name;
            imageSha = kit.sha;
            imageUrl = "";
            imageSize = kit.image.length();
            target = kit.image;
            from = "stick";
        } else {
            // the signed release chain
            Release.Manifest man;
            Index.Channel ch = m.channel();
            String chan = ch.manifest;
            l.log("channel " + (m.isBeta ? "beta " : "stable ") + ch.channel + " (" + chan + ")");
            try {
                byte[] json = http.fetch(chan, 256 * 1024);
                byte[] sig = http.fetch(chan + ".sig", 4096);
                man = Release.manifest(json, sig, certs, ch);
                if (!man.vendorOk.contains(d.stock)) {
                    return fail(Step.CHECK, Msg.STOCK_UNSUPPORTED, d.stock.isEmpty() ? "?" : d.stock, join(man.vendorOk));
                }
                String tag = ch.releaseUrl(man.version);
                byte[] sums = http.fetch(tag + "SHA256SUMS", 64 * 1024);
                byte[] ssig = http.fetch(tag + "SHA256SUMS.sig", 4096);
                imageName = ch.imageName(man.version);
                imageSha = Release.imageSha(sums, ssig, certs, imageName);
                imageUrl = tag + imageName;
                imageSize = http.size(imageUrl);
            } catch (Release.Bad b) {
                return fail(Step.CHECK, b.msg, b.msg.args == 0 ? new String[0] : new String[] { b.arg == null ? "" : b.arg });
            } catch (IOException e) {
                return fail(Step.CHECK, Msg.NET_FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            version = man.version;
            if (keepOnStick()) {
                List<Host.Volume> v = host.volumes();
                if (v.isEmpty()) return fail(Step.CHECK, Msg.NO_STICK);
                target = new File(v.get(0).appDir, "image.img");
                from = "download-stick";
            } else {
                target = internalImage();
                from = "download";
            }
        }
        if (imageSize <= 0 || imageSize % AvbTail.BS != 0 || imageSize > (4L << 30)) {
            return fail(Step.CHECK, Msg.IMAGE_SIZE_BAD, String.valueOf(imageSize));
        }
        // another release, another model or another place: the old download is dropped (a stick folder file never)
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
        saveState();
        if (kit == null) l.message(Msg.RELEASE_FOUND, version, mb(imageSize));

        // space on /data: the trial's files must be there (they are only ever made on /data; pinned, allocated in
        // full): the trial system (image + tail), the trial's userdata, and on Virtual A/B the size of super that is
        // kept free on top of each partition it creates (partition_installer.cpp
        // GetMinimumFreeSpaceThreshold, so the peak is userdata + system + super), the tail file, what is still to be
        // downloaded into /data, and a margin. A download kept on the stick and a stick folder cost no /data space
        // for the image itself.
        long tail = tailEstimate(imageSize);
        boolean download = kit == null, onStick = "download-stick".equals(from);
        long have = download && imageFile().exists() ? Math.min(imageFile().length(), imageSize) : 0;
        long rest = download ? imageSize - have : 0;
        needSystem = imageSize + tail;
        needUserdata = cfg.userdataBytes;
        needReserve = virtualAb ? superBytes : 0;
        needDownload = download && !onStick ? rest : 0;
        needMargin = tail + cfg.marginBytes;
        needData = needSystem + needUserdata + needReserve + needDownload + needMargin;
        long free = Math.min(dataFreeBytes, host.freeBytes(dir));
        set("need.bytes", String.valueOf(needData));
        set("free.bytes", String.valueOf(free));
        saveState();
        l.message(Msg.SPACE_PLAN, gb(needData), gb(free), gb(needSystem), gb(needUserdata), gb(needReserve), gb(needDownload),
                gb(needMargin));
        if (free < needData) return fail(Step.CHECK, Msg.NO_SPACE, gb(needData), gb(free));
        if (onStick) {
            File sd = target.getParentFile();
            if (sd != null && !sd.isDirectory()) sd.mkdirs();
            stickFree = host.freeBytes(sd);
            if (stickFree < rest) return fail(Step.CHECK, Msg.STICK_NO_ROOM, gb(rest), gb(stickFree));
        }
        l.message(Msg.CHECK_OK, gb(needData));
        l.step(Step.CHECK, Status.DONE);
        return true;
    }

    /**
     * The stick folder: KIT_DIR at the root of a stick, or in the app's own folder on it. It holds what a release
     * holds: SHA256SUMS + SHA256SUMS.sig (signed with this model's OTA key, listing exactly one image of this model,
     * whose name gives the version) and that image; optionally the model's signed channel manifest (then its version
     * must be the same and its stock versions count), else the stock versions of the model's entry in the signed list
     * of projectors. null = refused (said why).
     */
    Kit findKit(Index.Entry m, List<X509Certificate> certs, Detection d) {
        List<Host.Volume> vols = host.kitVolumes();
        if (vols.isEmpty()) { fail(Step.CHECK, Msg.STICK_NOT_FOUND); return null; }
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
            fail(Step.CHECK, blocked || !perm ? Msg.STICK_NO_ACCESS : Msg.STICK_NOT_FOUND);
            return null;
        }
        l.log("stick folder " + dirFound);
        Kit k = new Kit();
        k.dir = dirFound;
        try {
            byte[] sums = Bytes.readFile(new File(dirFound, "SHA256SUMS"), 64 * 1024);
            File sf = new File(dirFound, "SHA256SUMS.sig");
            byte[] ssig = sf.canRead() ? Bytes.readFile(sf, 4096) : null;
            // the names of the model's stable channel, or of its beta when it has a beta only (the folder was chosen)
            Index.Channel sc = m.stable != null ? m.stable : m.beta;
            Release.Listed li = Release.onlyImage(sums, ssig, certs, sc);
            k.name = li.name;
            k.version = li.version;
            k.sha = li.sha;
            // the folder's channel manifest, when it has one: the stable one, else the beta one (a beta folder)
            Index.Channel mc = sc;
            File mf = new File(dirFound, sc.manifestName());
            if (!mf.exists() && m.beta != null && sc != m.beta && new File(dirFound, m.beta.manifestName()).exists()) {
                mc = m.beta;
                mf = new File(dirFound, m.beta.manifestName());
            }
            File ms = new File(mf.getPath() + ".sig");
            if (mf.exists()) {
                Release.Manifest man = Release.manifest(Bytes.readFile(mf, 256 * 1024), ms.canRead() ? Bytes.readFile(ms, 4096) : null, certs, mc);
                if (!man.version.equals(k.version)) { fail(Step.CHECK, Msg.STICK_MISMATCH, man.version, k.version); return null; }
                k.vendorOk = man.vendorOk;
            } else {
                k.vendorOk = m.stockVersions();
            }
        } catch (Release.Bad b) {
            fail(Step.CHECK, b.msg, b.msg.args == 0 ? new String[0] : new String[] { b.arg == null ? "" : b.arg });
            return null;
        } catch (IOException e) {
            fail(Step.CHECK, Msg.STICK_NO_ACCESS);
            l.log("stick: " + e.getMessage());
            return null;
        }
        if (!k.vendorOk.contains(d.stock)) {
            fail(Step.CHECK, Msg.STOCK_UNSUPPORTED, d.stock.isEmpty() ? "?" : d.stock, join(k.vendorOk));
            return null;
        }
        k.image = new File(dirFound, k.name);
        if (!k.image.isFile() || !k.image.canRead()) { fail(Step.CHECK, Msg.STICK_NO_IMAGE, k.name); return null; }
        l.message(Msg.STICK_FOUND, k.version, mb(k.image.length()), dirFound.getPath());
        return k;
    }

    static long tailEstimate(long imageSize) {
        long t = 0;
        for (long s : AvbTail.levelSizes(imageSize)) t += s;
        return t + 3 * AvbTail.BS;
    }

    // ------------------------------------------------------------------------------------------------- download
    public boolean download() {
        l.step(Step.DOWNLOAD, Status.RUNNING);
        if ("stick".equals(state("image.from"))) {   // the stick folder's image is used where it is: nothing to download
            File f = imageFile();
            if (imageSize <= 0 || f.length() != imageSize) return fail(Step.DOWNLOAD, Msg.NOT_READY);
            l.progress(Step.DOWNLOAD, imageSize, imageSize);
            l.message(Msg.FROM_STICK, mb(imageSize));
            l.step(Step.DOWNLOAD, Status.DONE);
            return true;
        }
        if (imageUrl.isEmpty() || imageSize <= 0) return fail(Step.DOWNLOAD, Msg.NOT_READY);
        File f = imageFile();
        if (state("image.verified").equals(imageSha) && f.length() == imageSize) {
            l.progress(Step.DOWNLOAD, imageSize, imageSize);
            l.message(Msg.DOWNLOADED, mb(imageSize));
            l.step(Step.DOWNLOAD, Status.DONE);
            return true;
        }
        long have = f.exists() ? f.length() : 0;
        boolean onStick = "download-stick".equals(state("image.from"));
        File fd = f.getParentFile();
        if (fd != null && !fd.isDirectory() && !fd.mkdirs()) return fail(Step.DOWNLOAD, Msg.NO_STICK);
        // the download needs room where it goes; the tail always goes to the app's dir on /data
        long rest = imageSize - Math.min(have, imageSize);
        long room = onStick ? host.freeBytes(fd) - rest : host.freeBytes(dir) - rest - tailEstimate(imageSize);
        if (room < 0) {
            if (onStick) return fail(Step.DOWNLOAD, Msg.STICK_NO_ROOM, gb(rest), gb(host.freeBytes(fd)));
            return fail(Step.DOWNLOAD, Msg.NO_SPACE, gb(imageSize + tailEstimate(imageSize)), gb(host.freeBytes(dir)));
        }
        String last = "";
        for (int attempt = 0; attempt <= cfg.retries; attempt++) {
            if (stop) return stopped(Step.DOWNLOAD);
            try {
                http.lastEtag = "";
                String tag = http.download(imageUrl, f, imageSize, state("image.etag"), new Http.Progress() {
                    @Override public void at(long done, long total) { l.progress(Step.DOWNLOAD, done, total); }
                }, stopper);
                set("image.etag", tag);
                saveState();
                if (f.length() != imageSize) throw new IOException("size " + f.length());
                l.message(Msg.DOWNLOADED, mb(imageSize));
                l.step(Step.DOWNLOAD, Status.DONE);
                return true;
            } catch (Bytes.Stopped e) {
                if (!http.lastEtag.isEmpty()) { set("image.etag", http.lastEtag); saveState(); }
                return stopped(Step.DOWNLOAD);
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
        return fail(Step.DOWNLOAD, Msg.DOWNLOAD_INTERRUPTED, last);
    }

    private boolean stopped(Step s) {
        l.message(Msg.STOPPED);
        l.step(s, Status.WAITING);
        return false;
    }

    // ------------------------------------------------------------------------------------------------- verify
    public boolean verify() {
        l.step(Step.VERIFY, Status.RUNNING);
        File f = imageFile();
        if (imageSha.isEmpty() || f.length() != imageSize) return fail(Step.VERIFY, Msg.NOT_READY);
        if (verified()) {   // verified earlier in this download (the file is in the app's private dir)
            l.message(Msg.VERIFIED);
            handoverMessage(parseLong(state("image.handover")));
            l.step(Step.VERIFY, Status.DONE);
            return true;
        }
        String got;
        try {
            got = Bytes.sha256File(f, new Bytes.Progress() {
                @Override public void at(long done, long total) { l.progress(Step.VERIFY, done, total); }
            }, stopper);
        } catch (Bytes.Stopped e) {
            return stopped(Step.VERIFY);
        } catch (IOException e) {
            return fail(Step.VERIFY, Msg.ERROR, e.getMessage());
        }
        if (!got.equals(imageSha)) {
            l.log("sha256 " + got + " != " + imageSha);
            forgetImage();   // a download is deleted; a stick folder file stays where it is (the user replaces it)
            return fail(Step.VERIFY, Msg.SHA_MISMATCH);
        }
        set("image.verified", imageSha);
        set("image.mtime", String.valueOf(f.lastModified()));
        int h = Erofs.handover(f);
        set("image.handover", String.valueOf(h));
        saveState();
        l.message(Msg.VERIFIED);
        handoverMessage(h);
        l.step(Step.VERIFY, Status.DONE);
        return true;
    }

    private void handoverMessage(long h) {
        if (h == 1) l.message(Msg.HANDOVER_YES);
        else if (h == 0) l.message(Msg.HANDOVER_NO, version);
        else l.message(Msg.HANDOVER_UNKNOWN);
    }

    /** 1: the release can install itself from the trial, 0: it cannot, -1: unknown. */
    public int handover() { return (int) parseLong(state("image.handover").isEmpty() ? "-1" : state("image.handover")); }

    private boolean verified() {
        File f = imageFile();
        return !imageSha.isEmpty() && imageSha.equals(state("image.verified")) && f.length() == imageSize
                && String.valueOf(f.lastModified()).equals(state("image.mtime"));
    }

    // ------------------------------------------------------------------------------------------------- prepare
    public boolean prepare() {
        l.step(Step.PREPARE, Status.RUNNING);
        if (!verified()) return fail(Step.PREPARE, Msg.NOT_READY);
        if (prepared()) {
            l.message(Msg.PREPARED);
            l.step(Step.PREPARE, Status.DONE);
            return true;
        }
        File tail = tailFile();
        try {
            KeyPair key = cfg.avbKey != null ? cfg.avbKey : AvbTail.newKey();
            byte[] salt = cfg.avbSalt != null ? cfg.avbSalt : AvbTail.newSalt();
            AvbTail.Result r = AvbTail.build(imageFile(), tail, salt, key, new Bytes.Progress() {
                @Override public void at(long done, long total) { l.progress(Step.PREPARE, done, total); }
            }, stopper);
            AvbTail.selfCheck(tail, imageSize);
            set("tail.sha", r.tailSha256);
            set("tail.size", String.valueOf(r.tailSize));
            set("total", String.valueOf(r.total));
            saveState();
            l.log("AVB footer: tree " + r.treeSize + " B, vbmeta " + r.vbmetaSize + " B, trial image " + r.total + " B");
        } catch (Bytes.Stopped e) {
            tail.delete();
            return stopped(Step.PREPARE);
        } catch (Exception e) {
            tail.delete();
            return fail(Step.PREPARE, Msg.PREPARE_FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        l.message(Msg.PREPARED);
        l.step(Step.PREPARE, Status.DONE);
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

    /** the list says the trial may start on this model with this stock version */
    public boolean canStart() { return det != null && det.ok() && det.stockRow != null && det.stockRow.trial; }

    /** the trial may start, installing for good from it is not open for this model and stock version yet */
    public boolean trialOnly() { return canStart() && !det.stockRow.install; }

    // ------------------------------------------------------------------------------------------------- start
    /** The only step that writes outside the app: the trial goes into /data/gsi and one boot is armed. */
    public boolean start() {
        l.step(Step.START, Status.RUNNING);
        if (det == null || !det.ok()) return fail(Step.START, Msg.NOT_READY);
        if (!canStart()) return fail(Step.START, Msg.START_PILOT, det.model.name);
        if (!prepared()) return fail(Step.START, Msg.NOT_READY);
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
        if (r == null) return fail(Step.START, Msg.ROOT_UNAVAILABLE);
        if (!r.done) return fail(Step.START, Msg.START_FAILED, r.failCode.isEmpty() ? "timeout" : r.failCode);
        set("trial.version", version);
        set("trial.model", det.model.code);
        saveState();
        trial = Trial.OURS;
        // the trial has its own copy now: free the /data the download took; an image on the stick (its folder, or the
        // download kept there) stays for another trial, only the tail goes
        if ("download".equals(state("image.from").isEmpty() ? "download" : state("image.from"))) forgetImage();
        else forgetTail();
        l.message(Msg.STARTED);
        l.step(Step.START, Status.DONE);
        return true;
    }

    /** Arm the installed trial for one more boot (gsi_tool enable --single-boot). */
    public boolean enableAgain() {
        l.step(Step.START, Status.RUNNING);
        if (det == null || det.model == null || !canStart()) return fail(Step.START, Msg.START_PILOT, det == null ? "?" : det.name);
        // only a trial this app installed for this model (never another one)
        if (state("trial.version").isEmpty() || !det.model.code.equals(state("trial.model"))) return fail(Step.START, Msg.NOT_READY);
        Root.Result r = runRoot(new Root.Job("enable", Root.newNonce()).put("MODEL", det.model.code), cfg.probeTimeoutMs);
        if (r == null) return fail(Step.START, Msg.ROOT_UNAVAILABLE);
        if (!r.done) return fail(Step.START, Msg.START_FAILED, r.failCode);
        l.message(Msg.STARTED);
        l.step(Step.START, Status.DONE);
        return true;
    }

    public boolean removeTrial() {
        Root.Result r = runRoot(new Root.Job("remove", Root.newNonce()), cfg.probeTimeoutMs);
        if (r == null) { l.message(Msg.ROOT_UNAVAILABLE); return false; }
        if (!r.done) { l.message(Msg.REMOVE_FAILED, r.failCode); return false; }
        st.remove("trial.version");
        st.remove("trial.model");
        saveState();
        trial = Trial.NONE;
        l.message(Msg.REMOVED);
        return true;
    }

    public boolean reboot() {
        l.message(Msg.REBOOTING);
        Root.Result r = runRoot(new Root.Job("reboot", Root.newNonce()), 60_000L);
        return r != null && r.done;
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
