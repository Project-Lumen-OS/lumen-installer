// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.report;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * One HTTPS POST of the report zip to the report server (ReportUrl). Sends only the zip and five headers: the format
 * version, the random install id of the reports (not the serial, not the statistics id), the Lumen OS version, the
 * model codename and why the report was made. No redirects are followed (a redirect could leave https). The server
 * answers {"code":"LR-7K2Q9M", ...}. One upload is cut after deadlineMs: a connection that freezes half way (networks
 * in Russia do that to every Cloudflare address after about 20 KB) then fails in seconds, and uploadAny tries the
 * next address. Plain Java.
 */
final class ReportUploader {
    static final class UploadException extends IOException {
        final ReportFailure failure;
        /** The HTTP status of the answer, 0 when there was none. */
        final int status;

        UploadException(ReportFailure f, String msg) {
            this(f, 0, msg);
        }

        UploadException(ReportFailure f, int status, String msg) {
            super(msg);
            failure = f;
            this.status = status;
        }
    }

    interface Progress {
        /** @return false to stop (canceled) */
        boolean onSent(long sent, long total);
    }

    /** One upload to one address (uploadAny). */
    interface Attempt {
        String to(String url) throws IOException;
    }

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"(LR-[0-9A-Z]{4,8})\"");

    private volatile HttpURLConnection conn;
    private volatile CutSockets sockets;

    /**
     * Any thread: aborts a running upload (the worker then sees an IOException). The sockets go first: a write that
     * hangs on a frozen network ignores a polite close (the JDK's SSL close waits for the writer's lock).
     */
    void abort() {
        CutSockets s = sockets;
        if (s != null) s.cut();
        HttpURLConnection c = conn;
        if (c != null) c.disconnect();
    }

    /** The default TLS sockets, remembered per upload so abort() can close them hard (linger 0) from any thread. */
    static final class CutSockets extends SSLSocketFactory {
        private final SSLSocketFactory d;
        private final List<Socket> made = new ArrayList<>();
        private boolean closed;

        CutSockets(SSLSocketFactory d) {
            this.d = d;
        }

        private Socket keep(Socket s) throws IOException {
            synchronized (this) {
                if (!closed) {
                    made.add(s);
                    return s;
                }
            }
            s.close();
            throw new SocketException("upload aborted");
        }

        void cut() {
            List<Socket> all;
            synchronized (this) {
                closed = true;
                all = new ArrayList<>(made);
            }
            for (Socket s : all) {
                try {
                    s.setSoLinger(true, 0);
                } catch (IOException | RuntimeException ignored) {
                }
                try {
                    s.close();
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }

        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            keep(s);
            return keep(d.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket() throws IOException { return keep(d.createSocket()); }
        @Override public Socket createSocket(String h, int p) throws IOException { return keep(d.createSocket(h, p)); }
        @Override public Socket createSocket(String h, int p, InetAddress la, int lp) throws IOException {
            return keep(d.createSocket(h, p, la, lp));
        }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return keep(d.createSocket(h, p)); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress la, int lp) throws IOException {
            return keep(d.createSocket(h, p, la, lp));
        }
        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
    }

    /**
     * Tries the addresses in order and returns the first report code. The next address gets its turn when this one
     * may be the only problem (tryNext); a judgement of the report itself ends it at once. When every address failed,
     * the first failure that a later try may fix wins (the report stays queued), else the last one.
     */
    static String uploadAny(List<String> urls, Attempt a) throws IOException {
        UploadException keep = null, last = null;
        for (String u : urls) {
            try {
                return a.to(u);
            } catch (UploadException e) {
                if (!tryNext(e)) throw e;
                last = e;
                if (keep == null && e.failure.retry()) keep = e;
            }
        }
        if (keep != null) throw keep;
        if (last != null) throw last;
        throw new UploadException(ReportFailure.REJECTED, "no address");
    }

    /**
     * True when another address may do better: no answer (network, a frozen connection), a server failure, or an
     * answer that is not our report server's (3xx, 404, 405, 200 without a code). 400, 411, 413 and 415 judge the
     * report and 429 the sender: the same on every address.
     */
    static boolean tryNext(UploadException e) {
        int s = e.status;
        return e.failure == ReportFailure.NETWORK || e.failure == ReportFailure.SERVER || s == 404 || s == 405
                || (s >= 300 && s < 400);
    }

    /** The longest one upload may take: 30 seconds plus 16 KB a second of the zip. */
    static long deadlineMs(long bytes) {
        return 30_000L + bytes / 16;
    }

    /** Worker thread. Returns the report code. */
    String upload(String url, File zip, String installId, String version, String model, ReportKind kind, Progress p)
            throws IOException {
        return upload(url, zip, installId, version, model, kind, p, deadlineMs(zip.length()));
    }

    String upload(String url, File zip, String installId, String version, String model, ReportKind kind, Progress p,
            long deadlineMs) throws IOException {
        if (url == null || !url.startsWith("https://")) throw new UploadException(ReportFailure.REJECTED, "no https url");
        long total = zip.length();
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IOException | IllegalArgumentException e) {
            throw new UploadException(ReportFailure.REJECTED, "bad url: " + e);
        }
        conn = c;
        CutSockets cs = new CutSockets(HttpsURLConnection.getDefaultSSLSocketFactory());
        if (c instanceof HttpsURLConnection) ((HttpsURLConnection) c).setSSLSocketFactory(cs);
        sockets = cs;
        AtomicBoolean cut = new AtomicBoolean();
        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(deadlineMs);
            } catch (InterruptedException e) {
                return;
            }
            cut.set(true);
            abort();
        }, "lumen-report-deadline");
        guard.setDaemon(true);
        guard.start();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setFixedLengthStreamingMode(total);
            c.setRequestProperty("Content-Type", "application/zip");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Lumen-OS-Report/2");
            c.setRequestProperty("X-Lumen-Report", "1");
            c.setRequestProperty("X-Lumen-Install", installId);
            c.setRequestProperty("X-Lumen-Version", headerVersion(version));
            c.setRequestProperty("X-Lumen-Model", headerModel(model));
            c.setRequestProperty("X-Lumen-Kind", kind.wire);
            try (OutputStream out = c.getOutputStream(); InputStream in = new FileInputStream(zip)) {
                byte[] b = new byte[16 * 1024];
                long sent = 0;
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    sent += n;
                    if (!p.onSent(sent, total)) throw new CanceledException();
                }
            }
            int status = c.getResponseCode();
            String body = readBody(c, status);
            if (status == 200 || status == 201) {
                String code = parseCode(body);
                if (code == null) throw new UploadException(ReportFailure.SERVER, status, "no code in answer");
                return code;
            }
            throw new UploadException(failureFor(status), status, "HTTP " + status);
        } catch (UploadException | CanceledException e) {
            throw e;
        } catch (IOException e) {
            if (cut.get()) throw new UploadException(ReportFailure.NETWORK, "no answer within " + deadlineMs + " ms");
            throw new UploadException(ReportFailure.NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            guard.interrupt();
            conn = null;
            sockets = null;
            c.disconnect();
        }
    }

    static ReportFailure failureFor(int status) {
        if (status == 429) return ReportFailure.RATE_LIMITED;
        if (status >= 500) return ReportFailure.SERVER;
        return ReportFailure.REJECTED;                         // 400, 404, 405, 411, 413, 415, 3xx
    }

    /**
     * The version header within what the server accepts ([0-9A-Za-z._+-]{1,32}, lumen_reports.VERSION_RE): an
     * odd ro.lumen.version must not turn every report into a 400.
     */
    static String headerVersion(String v) {
        if (v == null || v.trim().isEmpty()) return "unknown";
        String s = v.trim().replaceAll("[^0-9A-Za-z._+-]", "_");
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    /** The model codename within what the server accepts ([a-z0-9_]{1,32}), else "unknown". */
    static String headerModel(String m) {
        return m != null && m.matches("[a-z0-9_]{1,32}") ? m : "unknown";
    }

    /** The report code of the server's JSON answer, or null. */
    static String parseCode(String body) {
        if (body == null) return null;
        Matcher m = CODE.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String readBody(HttpURLConnection c, int status) {
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (in == null) return "";
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0 && bo.size() < 4096) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
