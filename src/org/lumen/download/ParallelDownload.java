// SPDX-License-Identifier: Apache-2.0
package org.lumen.download;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * Downloads one file over several HTTP range connections at once, for the updater (OTA packages) and Lumen Installer
 * (trial images, app updates). Russian providers shape GitHub per connection (a projector on 2026-11-19: the updater got
 * 1.1 MB/s while a second curl beside it got 2.5 MB/s), so the total speed
 * grows with the connections: {@link Tuning#start} at first, one more per measured window while the total keeps
 * growing, up to {@link Tuning#max}. The missing ranges are saved beside the file ({@code name.part.state}), so a
 * network loss, a pause or a restart continues every range where it stopped. The stable GitHub URL redirects to a
 * signed CDN URL that expires: it is resolved at the start of every round and again when the CDN refuses it. A server
 * that ignores Range gets one plain connection from the first byte. The file appears under its own name only once
 * every byte is in: checking it (the sha256 of the manifest or of the signed list) stays with the caller. Plain
 * Java, no Android.
 */
public final class ParallelDownload {
    /** Opens a connection; each app's own Http opens HTTPS only with its User-Agent (the updater, Lumen Installer). */
    public interface Opener {
        HttpURLConnection open(String url) throws IOException;

        /** Where a redirect may lead. */
        default boolean allowed(URL u) { return "https".equals(u.getProtocol()); }
    }

    public interface Progress {
        /** @return false to stop */
        boolean onProgress(long bytes, long total, long speed);
    }

    public static final class Tuning {
        final int start, max;
        final long minSplit, windowMs, saveMs, stallMs;

        public Tuning(int start, int max, long minSplit, long windowMs, long saveMs, long stallMs) {
            this.start = start;
            this.max = max;
            this.minSplit = minSplit;
            this.windowMs = windowMs;
            this.saveMs = saveMs;
            this.stallMs = stallMs;
        }

        /** 4 connections, up to 8, ranges of at least 2 MB, 4 s windows, saved every 2 s, no data for 60 s = a failed round. */
        public static final Tuning DEFAULT = new Tuning(4, 8, 2L << 20, 4000, 2000, 60000);
    }

    /** Stopped by the caller (pause, cancel). */
    public static final class Stopped extends IOException {
        Stopped() { super("stopped"); }
    }

    /** The CDN refused the link and it was resolved again: the connection tries at once. */
    private static final class Renewed extends IOException {
        Renewed(int code) { super("HTTP " + code + " from the CDN, link renewed"); }
    }

    /** The server answered a range request with the whole file (it changed, or it ignores Range after all). */
    private static final class RangeLost extends IOException {
        RangeLost(String why) { super(why); }
    }

    private static final String MAGIC = "lumen-download 1";
    private static final double GAIN = 1.10;
    private static final int RESETS = 2;
    private static final int BUF = 256 << 10;
    private static final long IDLE_MS = 250;

    private final Opener opener;
    private final ThreadFactory threads;
    private final Tuning tune;
    private final Consumer<String> log;

    public ParallelDownload(Opener opener, ThreadFactory threads, Tuning tune, Consumer<String> log) {
        this.opener = opener;
        this.threads = threads;
        this.tune = tune;
        this.log = log;
    }

    public static File part(File dst) { return new File(dst.getPath() + ".part"); }

    public static File state(File dst) { return new File(dst.getPath() + ".part.state"); }

    /** Bytes of dst already on disk: all of it once complete, else what its state file counts as written. */
    public static long have(File dst, long total) {
        if (dst.isFile() && dst.length() == total) return total;
        Saved s = Saved.read(state(dst), null, total);
        return s == null || !part(dst).isFile() ? 0 : total - s.missingBytes();
    }

    /** Remove dst with its partial download (a sha256 mismatch, a cancel). */
    public static void discard(File dst) {
        dst.delete();
        part(dst).delete();
        state(dst).delete();
    }

    /**
     * Download url (total bytes, key = the manifest's sha256 binding the saved ranges to this package) into dst.
     * @return the ETag the server gave ("" if none)
     */
    public String run(String url, File dst, long total, String key, Progress p) throws IOException {
        File part = part(dst), state = state(dst);
        if (dst.isFile()) {
            if (dst.length() == total) {
                part.delete();
                state.delete();
                return "";
            }
            // an earlier one-connection download kept its first bytes under the final name: they continue here
            part.delete();
            if (dst.length() < total && dst.length() > 0 && dst.renameTo(part)) {
                List<long[]> rest = new ArrayList<>();
                rest.add(new long[] {part.length(), total});
                new Saved(key, total, "", rest).write(state);
            } else {
                dst.delete();
            }
        }
        boolean ranges = true;
        for (int round = 0; ; round++) {
            Probe pr = probe(url, null);
            if (pr.size != total) throw new IOException("server has " + pr.size + " bytes, the manifest " + total);
            ranges &= pr.ranges;
            Saved saved = ranges ? Saved.read(state, key, total) : null;
            if (saved != null && !saved.etag.isEmpty() && !pr.etag.equals(saved.etag)) {
                log.accept("download: the file changed on the server, starting over");
                saved = null;
            }
            Segments segs = saved != null && part.isFile() ? Segments.of(total, tune.minSplit, saved.missing) : null;
            if (segs == null) {
                part.delete();
                state.delete();
                segs = Segments.plan(total, ranges ? tune.start : 1, ranges ? tune.minSplit : total);
            }
            log.accept("download: " + (ranges ? "ranges" : "one connection, the server ignores Range") + ", "
                    + (total - segs.done()) + " of " + total + " bytes left");
            try {
                new Round(url, pr, ranges, segs, part, state, key, p).run();
            } catch (RangeLost e) {
                log.accept("download: " + e.getMessage() + ", starting over");
                part.delete();
                state.delete();
                if (round + 1 >= RESETS) ranges = false;
                continue;
            } catch (Stopped e) {
                throw e;
            } catch (IOException e) {
                log.accept("download: round failed: " + e);
                throw e;
            }
            state.delete();
            if (!part.renameTo(dst)) throw new IOException("cannot rename " + part);
            return pr.etag;
        }
    }

    /** What the server says about the file: the resolved URL (after the GitHub redirect), size, ETag, Range support. */
    private static final class Probe {
        final String url, etag;
        final long size;
        final boolean ranges;

        Probe(String url, String etag, long size, boolean ranges) {
            this.url = url;
            this.etag = etag;
            this.size = size;
            this.ranges = ranges;
        }
    }

    /** A Range lost on the redirect (a client that drops it) must not cost the ranges: ask the CDN link itself. */
    private Probe probe(String url, Set<HttpURLConnection> track) throws IOException {
        Probe p = probeOnce(url, track);
        if (p.ranges || p.url.equals(url)) return p;
        Probe direct = probeOnce(p.url, track);
        return direct.ranges ? direct : p;
    }

    private Probe probeOnce(String url, Set<HttpURLConnection> track) throws IOException {
        HttpURLConnection c = opener.open(url);
        if (track != null) track.add(c);
        try {
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            if (!opener.allowed(c.getURL())) throw new IOException("redirected off https");
            String etag = c.getHeaderField("ETag");
            etag = etag == null ? "" : etag;
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                int slash = cr == null ? -1 : cr.lastIndexOf('/');
                if (slash < 0 || !cr.startsWith("bytes 0-0/")) throw new IOException("unexpected Content-Range " + cr);
                return new Probe(c.getURL().toString(), etag, Long.parseLong(cr.substring(slash + 1).trim()), true);
            }
            if (code == 200) return new Probe(c.getURL().toString(), etag, c.getContentLengthLong(), false);
            throw new HttpError(code, "HTTP " + code + " for " + url);
        } catch (NumberFormatException e) {
            throw new IOException("bad Content-Range", e);
        } finally {
            if (track != null) track.remove(c);
            c.disconnect();
        }
    }

    /** One attempt: the connections run until every byte is in, the caller stops, or the round fails. */
    private final class Round {
        private final String stable;
        private final boolean ranges;
        private final Segments segs;
        private final File state;
        private final String key, etag;
        private final Progress p;
        private final FileChannel ch;
        private final Set<HttpURLConnection> open = ConcurrentHashMap.newKeySet();
        private final List<Thread> workers = new ArrayList<>();
        private final Object urlLock = new Object();
        private volatile String url;
        private volatile int urlGen;
        private volatile boolean abort, hiccup;
        private volatile IOException failure;

        Round(String stable, Probe pr, boolean ranges, Segments segs, File part, File state, String key, Progress p)
                throws IOException {
            this.stable = stable;
            this.url = pr.url;
            this.etag = pr.etag;
            this.ranges = ranges;
            this.segs = segs;
            this.state = state;
            this.key = key;
            this.p = p;
            this.ch = new RandomAccessFile(part, "rw").getChannel();
        }

        void run() throws IOException {
            try {
                ConnectionScaler scaler = new ConnectionScaler(ranges ? tune.start : 1, ranges ? tune.max : 1, GAIN);
                Rate rate = new Rate(10000);
                long t0 = now(), lastSave = t0, lastTick = 0, winStart = t0, lastMove = t0;
                long done = segs.done(), winBytes = done, lastDone = done, speed = 0;
                addWorkers(scaler.target());
                while (!segs.complete()) {
                    sleep(100);
                    if (Thread.currentThread().isInterrupted()) throw new Stopped();
                    long t = now();
                    if (failure != null) throw failure;
                    done = segs.done();
                    if (done > lastDone) {
                        lastDone = done;
                        lastMove = t;
                    } else if (t - lastMove > tune.stallMs) {
                        throw new IOException("no data for " + tune.stallMs + " ms");
                    }
                    if (t - lastTick >= 500) {
                        lastTick = t;
                        speed = rate.add(t, done);
                        if (!p.onProgress(done, segs.total, speed)) throw new Stopped();
                    }
                    if (ranges && t - lastSave >= tune.saveMs) {
                        save();
                        lastSave = t;
                    }
                    if (ranges && t - winStart >= tune.windowMs) {
                        double bps = (done - winBytes) * 1000.0 / (t - winStart);
                        winStart = t;
                        winBytes = done;
                        boolean clean = !hiccup;
                        hiccup = false;
                        if (segs.hasWork()) addWorkers(scaler.onWindow(bps, clean));
                    }
                }
                abort = true;           // a connection waiting to try again has nothing left to fetch
                for (HttpURLConnection c : open) c.disconnect();
                joinAll();
                ch.force(true);
                p.onProgress(segs.total, segs.total, speed);
                log.accept("download: complete with " + workers.size() + " connections");
            } catch (IOException e) {
                throw stopAll(e);
            } finally {
                ch.close();
            }
        }

        private int addWorkers(int target) {
            while (workers.size() < target) {
                Thread t = threads.newThread(this::work);
                workers.add(t);
                t.start();
            }
            return workers.size();
        }

        private IOException stopAll(IOException why) {
            abort = true;
            for (HttpURLConnection c : open) c.disconnect();
            joinAll();
            if (ranges && !(why instanceof RangeLost)) save();
            return why;
        }

        /** Waits up to 5 s in all for the connections to end. */
        private void joinAll() {
            long end = now() + 5000;
            for (Thread w : workers) {
                try {
                    w.join(Math.max(1, end - now()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** Data first, then the state file: the saved ranges never claim bytes that are not on disk. */
        private void save() {
            List<long[]> missing = segs.missing();
            try {
                ch.force(false);
                new Saved(key, segs.total, etag, missing).write(state);
            } catch (IOException e) {
                log.accept("download: cannot save progress: " + e);
            }
        }

        private void work() {
            byte[] buf = new byte[BUF];
            int fails = 0;
            long bytes = 0, busy = 0;
            while (!abort) {
                Segments.Seg s = segs.claim(now(), busy == 0 ? 0 : bytes * 1000 / busy);
                if (s == null) {
                    if (!ranges || segs.complete()) return;
                    pause(IDLE_MS);     // the last ranges: stay to take over the rest of one that turns out slow
                    continue;
                }
                long t = now();
                try {
                    fetch(s, buf);
                    fails = 0;
                } catch (IOException e) {
                    if (abort) return;
                    boolean fatal = !ranges || e instanceof RangeLost
                            || e instanceof HttpError && gone(((HttpError) e).code);
                    if (fatal) {
                        if (failure == null) failure = e;
                        return;
                    }
                    fails++;
                    hiccup = true;
                    log.accept("download: connection failed (" + e.getMessage() + "), again in a moment");
                    // the first refusal of a link that was just renewed goes on at once, every other failure waits longer
                    pause(e instanceof Renewed && fails == 1 ? 0 : Math.min(15000, 500L << Math.min(fails, 5)));
                } finally {
                    bytes += segs.got(s);
                    busy += now() - t;
                    segs.release(s);
                }
            }
        }

        private void fetch(Segments.Seg s, byte[] buf) throws IOException {
            int gen = urlGen;
            HttpURLConnection c = opener.open(url);
            open.add(c);
            try {
                if (abort) return;
                if (ranges) {
                    c.setRequestProperty("Range", "bytes=" + s.pos + "-" + (s.end - 1));
                    if (!etag.isEmpty() && !etag.startsWith("W/")) c.setRequestProperty("If-Range", etag);
                }
                int code = c.getResponseCode();
                if (!opener.allowed(c.getURL())) throw new IOException("redirected off https");
                if (ranges && code == 200) throw new RangeLost("the server sent the whole file to a range request");
                if (code != (ranges ? 206 : 200)) {
                    if (ranges && code / 100 == 4) {
                        renew(gen);
                        throw new Renewed(code);
                    }
                    throw new HttpError(code, "HTTP " + code);
                }
                if (ranges) {
                    String cr = c.getHeaderField("Content-Range");
                    if (cr == null || !cr.startsWith("bytes " + s.pos + "-") || !cr.endsWith("/" + segs.total)) {
                        throw new IOException("unexpected Content-Range " + cr);
                    }
                }
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while (!abort && (n = in.read(buf)) > 0) {
                        long k = segs.reserve(s, n);
                        if (k > 0) {
                            write(buf, (int) k, s.pos);
                            segs.commit(s, k);
                        }
                        if (k < n) return;      // the range is done: it was cut for another connection
                    }
                }
                if (!abort && segs.left(s) > 0) throw new IOException("closed with " + segs.left(s) + " bytes left");
            } finally {
                open.remove(c);
                c.disconnect();
            }
        }

        private void write(byte[] b, int n, long at) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(b, 0, n);
            while (bb.hasRemaining()) at += ch.write(bb, at);
        }

        /** The signed CDN URL expired or was refused: resolve the stable URL again (once for all connections). */
        private void renew(int gen) throws IOException {
            synchronized (urlLock) {
                if (gen != urlGen || abort) return;
                Probe pr = probe(stable, open);
                if (!pr.ranges || !etag.isEmpty() && !etag.equals(pr.etag)) throw new RangeLost("the file changed on the server");
                url = pr.url;
                urlGen++;
                log.accept("download: the CDN refused the link, renewed it");
            }
        }

        private void pause(long ms) {
            long end = now() + ms;
            while (!abort && now() < end && !Thread.currentThread().isInterrupted()) sleep(100);
        }
    }

    private static boolean gone(int code) { return code == 404 || code == 410; }

    private static long now() { return System.nanoTime() / 1_000_000L; }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The state file: which package (sha256, size, ETag) and the missing ranges, one "pos end" per line. */
    private static final class Saved {
        final String key, etag;
        final long total;
        final List<long[]> missing;

        Saved(String key, long total, String etag, List<long[]> missing) {
            this.key = key;
            this.total = total;
            this.etag = etag;
            this.missing = missing;
        }

        long missingBytes() {
            long n = 0;
            for (long[] r : missing) n += r[1] - r[0];
            return n;
        }

        void write(File f) throws IOException {
            StringBuilder b = new StringBuilder(MAGIC).append('\n').append(key).append('\n').append(total).append('\n')
                    .append(etag.replace('\n', ' ')).append('\n');
            for (long[] r : missing) b.append(r[0]).append(' ').append(r[1]).append('\n');
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(b.toString().getBytes(StandardCharsets.UTF_8));
                o.getFD().sync();
            }
            if (!tmp.renameTo(f)) throw new IOException("cannot rename " + tmp);
        }

        /** null when missing, unreadable, or of another package (key null = any package of that size). */
        static Saved read(File f, String key, long total) {
            if (!f.isFile()) return null;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                if (!MAGIC.equals(r.readLine())) return null;
                String k = r.readLine();
                long t = Long.parseLong(r.readLine().trim());
                String etag = r.readLine();
                if (k == null || etag == null || t != total || key != null && !key.equals(k)) return null;
                List<long[]> missing = new ArrayList<>();
                for (String l; (l = r.readLine()) != null; ) {
                    String[] a = l.trim().split(" ");
                    if (a.length != 2) return null;
                    missing.add(new long[] {Long.parseLong(a[0]), Long.parseLong(a[1])});
                }
                if (Segments.of(total, 1, missing) == null) return null;
                return new Saved(k, t, etag, missing);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }
}
