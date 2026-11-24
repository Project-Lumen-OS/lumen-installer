// SPDX-License-Identifier: Apache-2.0
package org.lumen.report;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * A zip sent in parts (POST &lt;report address&gt;/part, tools/stats/server/lumen_reports.py), each part on a connection
 * of its own, LANES parts on their way at once, LANES_CHOKED on a choked network (there each small part waits mostly
 * for its own TLS handshake, so more of them at once carry more: about 2.5 MB a minute with 4 on a Russian home line). Networks in Russia freeze every connection to a Cloudflare address
 * after about 20 KB: there only parts of PART bytes arrive (the TLS handshake plus one part stays under that), so a
 * dump of about 100 MB is some 8500 parts. Elsewhere and on a direct report address parts of PART_BIG bytes go
 * through. An upload starts with PART_BIG and drops to PART for good at the first part that gets no answer (choked()
 * tells the caller, who keeps it with the upload id). Each part carries the offset, the size and the sha256 of the
 * whole zip; the server keeps the byte ranges it has in any order and answers with how much is complete from the
 * start. A part that failed is sent again (TRIES times, waiting longer each time), and an upload that stopped (no
 * network, the app closed, the projector off) goes on where it stopped: the first request asks the server what it has
 * (X-Lumen-Query, the server keeps an unfinished upload a day). Addresses are tried in order like
 * ReportUploader.uploadAny (a direct one first, Cloudflare after it). Used by Lumen Installer for the stock system
 * info (kind dump). Plain Java.
 */
public final class PartUpload {
    /** a part that passes a choked network: with its TLS handshake under about 20 KB */
    public static final int PART = 12 * 1024;
    /** a part where nothing is choked */
    public static final int PART_BIG = 512 * 1024;
    /** parts on their way at once, each on its own connection */
    static final int LANES = 4;
    /** the same once the network is choked (the report server takes 12 parts at once and 16 connections per sender) */
    static final int LANES_CHOKED = 8;
    /** sends of one part before the address counts as failed */
    static final int TRIES = 6;
    /** one part: this much plus a millisecond per 32 bytes, then its connection is cut */
    static long partDeadlineMs = 20_000;
    private static final Pattern HAVE = Pattern.compile("\"have\"\\s*:\\s*(\\d{1,12})");
    private static final Pattern NO_UPLOAD = Pattern.compile("\"error\"\\s*:\\s*\"no upload\"");

    /** @return false to stop (canceled) */
    public interface Progress {
        boolean onSent(long sent, long total);
    }

    /** Why it did not arrive; retry: a later try may work (network, limits, server), else the server refused it. */
    public static final class Failed extends IOException {
        public final boolean retry;

        Failed(ReportUploader.UploadException e) {
            super(e.getMessage(), e);
            retry = e.failure.retry();
        }
    }

    private final String installId, version, model, kind, serial;
    private final boolean localHttp;
    long retryDelayMs = 1500;
    private final Set<ReportUploader.CutSockets> sockets = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean choked;
    private volatile boolean aborted;

    /**
     * installId: the random report id of the sender (16 to 64 lowercase hex); version and model as the server takes
     * them (cut to its formats); kind: a report kind (dump); serial: only for a dump, "" for none. localHttp: tests
     * may use http://127.0.0.1.
     */
    public PartUpload(String installId, String version, String model, String kind, String serial, boolean localHttp) {
        this.installId = installId;
        this.version = ReportUploader.headerVersion(version);
        this.model = ReportUploader.headerModel(model);
        this.kind = kind;
        this.serial = serial == null || !serial.matches("[A-Za-z0-9._-]{1,40}") ? "" : serial;
        this.localHttp = localHttp;
    }

    /** A new upload id (32 lowercase hex). */
    public static String newUploadId() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    /** Any thread: stops the running parts at once. */
    public void abort() {
        aborted = true;
        cutAll();
    }

    private void cutAll() {
        synchronized (sockets) {
            for (ReportUploader.CutSockets s : sockets) s.cut();
        }
    }

    /** true once this upload found a choked network and went on with parts of PART bytes */
    public boolean choked() {
        return choked;
    }

    /**
     * Worker thread: sends the zip to the first address that takes it and returns the report code. startChoked: an
     * earlier run of this upload already found a choked network (parts of PART bytes from the start).
     */
    public String send(List<String> reportUrls, File zip, String uploadId, boolean startChoked, Progress p) throws IOException {
        String sha = sha256(zip);
        choked = startChoked;
        try {
            return ReportUploader.uploadAny(reportUrls, u -> new Run(u + "/part", zip, sha, uploadId, p).go());
        } catch (ReportUploader.UploadException e) {
            throw new Failed(e);
        }
    }

    /** the same with a fresh upload: parts of PART_BIG first */
    public String send(List<String> reportUrls, File zip, String uploadId, Progress p) throws IOException {
        return send(reportUrls, zip, uploadId, false, p);
    }

    /** One address: the query, then LANES threads that take the next part until the server gives the code. */
    private final class Run {
        final String url, sha, uploadId;
        final File zip;
        final Progress progress;
        final long total;
        final ArrayDeque<long[]> again = new ArrayDeque<>();   // {offset, length, tries}
        final List<Thread> lanes = Collections.synchronizedList(new ArrayList<>());
        long cursor, base, acked;
        int busy;
        String code;
        IOException fatal;

        Run(String url, File zip, String sha, String uploadId, Progress progress) {
            this.url = url;
            this.zip = zip;
            this.sha = sha;
            this.uploadId = uploadId;
            this.progress = progress;
            this.total = zip.length();
        }

        String go() throws IOException {
            boolean ok = url.startsWith("https://") || (localHttp && url.startsWith("http://127.0.0.1:"));
            if (!ok) throw new ReportUploader.UploadException(ReportFailure.REJECTED, "no https url");
            if (total <= 0) throw new ReportUploader.UploadException(ReportFailure.REJECTED, "empty zip");
            Answer q = post(url, null, 0, total, sha, uploadId);
            if (q.code != null) return q.code;
            base = cursor = Math.max(0, Math.min(q.have, total));
            grow();
            for (int i = 0; i < lanes.size(); i++) {   // a lane may add lanes when it finds the network choked
                Thread t = lanes.get(i);
                try {
                    t.join();
                } catch (InterruptedException e) {
                    abort();
                    Thread.currentThread().interrupt();
                    throw new ReportUploader.UploadException(ReportFailure.NETWORK, "interrupted");
                }
            }
            if (fatal != null) throw fatal;
            if (code != null) return code;
            throw new ReportUploader.UploadException(ReportFailure.SERVER, "no code at the end");
        }

        /** starts lanes up to LANES, or LANES_CHOKED once the network is choked */
        private void grow() {
            synchronized (lanes) {
                for (int i = lanes.size(); i < (choked ? LANES_CHOKED : LANES); i++) {
                    Thread t = new Thread(this::lane, "lumen-part-" + i);
                    t.setDaemon(true);
                    lanes.add(t);
                    t.start();
                }
            }
        }

        /** the next part to send, null when there is none now and none will come */
        private synchronized long[] take() throws InterruptedException {
            while (true) {
                if (code != null || fatal != null) return null;
                if (aborted) {
                    fatal = new CanceledException();
                    notifyAll();
                    return null;
                }
                long[] w = again.poll();
                if (w == null && cursor < total) {
                    long n = Math.min(choked ? PART : PART_BIG, total - cursor);
                    w = new long[] { cursor, n, 0 };
                    cursor += n;
                }
                if (w != null) {
                    busy++;
                    return w;
                }
                if (busy == 0) return askAgain() ? take() : null;
                wait(1000);
            }
        }

        /** every part was sent and none gave the code (its answer was lost): the server says what it still needs */
        private boolean askAgain() {
            try {
                Answer a = post(url, null, 0, total, sha, uploadId);
                if (a.code != null) { code = a.code; return false; }
                if (a.have >= total) throw new ReportUploader.UploadException(ReportFailure.SERVER, a.status, "complete but no code");
                cursor = base = Math.max(0, a.have);
                acked = 0;
                return true;
            } catch (IOException e) {
                fatal = e;
                return false;
            }
        }

        private void lane() {
            try {
                long[] w;
                while ((w = take()) != null) send(w);
            } catch (InterruptedException e) {
                synchronized (this) { if (fatal == null) fatal = new ReportUploader.UploadException(ReportFailure.NETWORK, "interrupted"); notifyAll(); }
            }
        }

        private void send(long[] w) throws InterruptedException {
            Answer a = null;
            ReportUploader.UploadException err = null;
            try {
                byte[] part = new byte[(int) w[1]];
                try (RandomAccessFile f = new RandomAccessFile(zip, "r")) {
                    f.seek(w[0]);
                    f.readFully(part);
                }
                a = post(url, part, w[0], total, sha, uploadId);
            } catch (ReportUploader.UploadException e) {
                err = e;
            } catch (IOException e) {
                err = new ReportUploader.UploadException(ReportFailure.REJECTED, "zip: " + e.getMessage());
            }
            long wait = 0;
            boolean nowChoked = false;
            synchronized (this) {
                busy--;
                if (code != null || fatal != null) {
                    // over already: a slower part of a finished or failed upload, its answer does not matter
                } else if (a != null && a.code != null) {
                    code = a.code;
                    cutAll();   // the other parts on their way are not needed any more
                } else if (a != null && a.status == 409) {
                    // the server keeps too many separate pieces: everything from what it has complete again
                    again.clear();
                    cursor = base = Math.max(0, Math.min(a.have, total));
                    acked = 0;
                } else if (a != null) {
                    acked += w[1];
                    long sent = Math.min(total, Math.max(base + acked, a.have));
                    if (!progress.onSent(sent, total)) fatal = new CanceledException();
                } else if (err.failure != ReportFailure.NETWORK && err.failure != ReportFailure.SERVER) {
                    fatal = err;
                } else if (w[1] > PART && !choked) {
                    // no answer to a large part: a network that freezes a connection after a few KB, small parts from now on
                    choked = true;
                    nowChoked = true;
                    for (long o = w[0]; o < w[0] + w[1]; o += PART) again.add(new long[] { o, Math.min(PART, w[0] + w[1] - o), 0 });
                } else if (++w[2] >= TRIES) {
                    fatal = err;
                } else {
                    if (w[1] > PART) {   // already choked: the rest of a large part goes in small ones
                        for (long o = w[0]; o < w[0] + w[1]; o += PART) again.add(new long[] { o, Math.min(PART, w[0] + w[1] - o), w[2] });
                    } else {
                        again.addFirst(w);
                    }
                    wait = Math.min(30_000L, retryDelayMs * w[2]);
                }
                notifyAll();
            }
            if (nowChoked) grow();
            if (wait > 0) Thread.sleep(wait);
        }
    }

    static final class Answer {
        int status;
        long have = -1;
        String code;
    }

    /** One part (null: the state query) on a new connection (Connection: close), cut after its deadline. */
    Answer post(String url, byte[] part, long off, long total, String sha, String uploadId) throws IOException {
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IOException | IllegalArgumentException e) {
            throw new ReportUploader.UploadException(ReportFailure.REJECTED, "bad url: " + e);
        }
        int len = part == null ? 0 : part.length;
        long deadline = partDeadlineMs + len / 32;
        ReportUploader.CutSockets cs = new ReportUploader.CutSockets(HttpsURLConnection.getDefaultSSLSocketFactory());
        if (c instanceof HttpsURLConnection) ((HttpsURLConnection) c).setSSLSocketFactory(cs);
        sockets.add(cs);
        AtomicBoolean cut = new AtomicBoolean();
        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(deadline);
            } catch (InterruptedException e) {
                return;
            }
            cut.set(true);
            cs.cut();
            c.disconnect();
        }, "lumen-part-deadline");
        guard.setDaemon(true);
        guard.start();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout((int) Math.min(deadline, 60_000));
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setFixedLengthStreamingMode(len);
            c.setRequestProperty("Connection", "close");
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Lumen-OS-Report/2");
            c.setRequestProperty("X-Lumen-Report", "1");
            c.setRequestProperty("X-Lumen-Install", installId);
            c.setRequestProperty("X-Lumen-Version", version);
            c.setRequestProperty("X-Lumen-Model", model);
            c.setRequestProperty("X-Lumen-Kind", kind);
            if (!serial.isEmpty()) c.setRequestProperty("X-Lumen-Serial", serial);
            c.setRequestProperty("X-Lumen-Upload", uploadId);
            c.setRequestProperty("X-Lumen-Offset", String.valueOf(off));
            c.setRequestProperty("X-Lumen-Total", String.valueOf(total));
            c.setRequestProperty("X-Lumen-Sha256", sha);
            if (part == null) c.setRequestProperty("X-Lumen-Query", "1");
            try (OutputStream out = c.getOutputStream()) {
                if (part != null) out.write(part);
            }
            Answer a = new Answer();
            a.status = c.getResponseCode();
            String body = read(c, a.status);
            if (a.status == 200 || a.status == 201) {
                a.code = ReportUploader.parseCode(body);
                if (a.code == null) throw new ReportUploader.UploadException(ReportFailure.SERVER, a.status, "no code in answer");
            } else if (a.status == 202 || a.status == 409) {
                Matcher m = HAVE.matcher(body);
                if (!m.find()) throw new ReportUploader.UploadException(ReportFailure.SERVER, a.status, "no have in answer");
                a.have = Long.parseLong(m.group(1));
                if (a.have > total) throw new ReportUploader.UploadException(ReportFailure.SERVER, a.status, "odd answer");
            } else if (a.status == 404 && part == null && NO_UPLOAD.matcher(body).find()) {
                a.have = 0;   // the server has no such upload yet (or dropped it): from the start
            } else {
                throw new ReportUploader.UploadException(ReportUploader.failureFor(a.status), a.status, "HTTP " + a.status);
            }
            return a;
        } catch (ReportUploader.UploadException e) {
            throw e;
        } catch (IOException e) {
            if (cut.get()) throw new ReportUploader.UploadException(ReportFailure.NETWORK, "no answer within " + deadline + " ms");
            throw new ReportUploader.UploadException(ReportFailure.NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            guard.interrupt();
            sockets.remove(cs);
            c.disconnect();
        }
    }

    private static String read(HttpURLConnection c, int status) {
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (in == null) return "";
            byte[] b = new byte[4096];
            int n, len = 0;
            while (len < b.length && (n = in.read(b, len, b.length - len)) > 0) len += n;
            return new String(b, 0, len, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** the sha256 of a file, lowercase hex */
    public static String sha256(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            StringBuilder s = new StringBuilder();
            for (byte x : md.digest()) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
