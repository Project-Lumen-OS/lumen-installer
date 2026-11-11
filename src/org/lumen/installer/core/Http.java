package org.lumen.installer.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HTTPS only (GitHub release URLs redirect to a short-lived CDN URL, so every resumed request gets a fresh one).
 * The host tests use a local fake GitHub on http://127.0.0.1, allowed only when the engine is built with
 * {@code allowLocalHttp} (never in the app). Only a User-Agent is sent, no device id.
 */
public final class Http {
    public static final String USER_AGENT = "LumenInstaller/1.0";
    private final boolean allowLocalHttp;
    /** ETag of the last download response (set before the body is read, so a broken transfer can still resume). */
    public String lastEtag = "";

    public Http(boolean allowLocalHttp) { this.allowLocalHttp = allowLocalHttp; }

    public static final class HttpException extends IOException {
        public final int code;
        HttpException(int code, String msg) { super(msg); this.code = code; }
    }

    boolean allowed(URL u) {
        if ("https".equals(u.getProtocol())) return true;
        return allowLocalHttp && "http".equals(u.getProtocol()) && "127.0.0.1".equals(u.getHost());
    }

    private HttpURLConnection open(String url) throws IOException {
        URL u = new URL(url);
        if (!allowed(u)) throw new IOException("not https: " + url);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    private void checkFinal(HttpURLConnection c) throws IOException {
        if (!allowed(c.getURL())) throw new IOException("redirected off https: " + c.getURL());
    }

    public byte[] fetch(String url, int maxBytes) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            checkFinal(c);
            if (code != 200) throw new HttpException(code, "HTTP " + code + " for " + url);
            try (InputStream in = c.getInputStream()) {
                return Bytes.read(in, maxBytes);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Size of a remote file, from a one-byte range request (or the Content-Length of a plain answer). */
    public long size(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            checkFinal(c);
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                int slash = cr == null ? -1 : cr.lastIndexOf('/');
                if (slash < 0) throw new IOException("no Content-Range total");
                return Long.parseLong(cr.substring(slash + 1).trim());
            }
            if (code == 200) {
                long n = c.getContentLengthLong();
                if (n <= 0) throw new IOException("no Content-Length");
                return n;
            }
            throw new HttpException(code, "HTTP " + code + " for " + url);
        } finally {
            c.disconnect();
        }
    }

    public interface Progress { void at(long done, long total); }

    /**
     * Download url into f, resuming at f.length() (Range + If-Range with the stored ETag). A server that ignores the
     * range (200) restarts the file from 0. Never writes more than total bytes.
     * @return the ETag of the resource ("" if none)
     */
    public String download(String url, File f, long total, String etag, Progress p, Bytes.Stop stop) throws IOException {
        long have = f.exists() ? f.length() : 0;
        if (have > total) {
            if (!f.delete()) throw new IOException("cannot delete " + f);
            have = 0;
        }
        if (have == total && total > 0) return etag == null ? "" : etag;
        HttpURLConnection c = open(url);
        try {
            if (have > 0) {
                c.setRequestProperty("Range", "bytes=" + have + "-");
                if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-Range", etag);
            }
            int code = c.getResponseCode();
            checkFinal(c);
            String newTag = c.getHeaderField("ETag");
            if (newTag == null) newTag = "";
            lastEtag = newTag;
            if (code == 200) {
                have = 0;
                long n = c.getContentLengthLong();
                if (n > 0 && n != total) throw new IOException("size changed: " + n + " != " + total);
            } else if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr == null || !cr.startsWith("bytes " + have + "-") || !cr.endsWith("/" + total)) {
                    throw new IOException("unexpected Content-Range " + cr);
                }
            } else if (code == 416 && have == total) {
                return etag == null ? "" : etag;
            } else {
                throw new HttpException(code, "HTTP " + code);
            }
            try (InputStream in = c.getInputStream(); RandomAccessFile out = new RandomAccessFile(f, "rw")) {
                out.setLength(have);
                out.seek(have);
                byte[] b = new byte[1 << 18];
                int n;
                long last = 0;
                while ((n = in.read(b)) > 0) {
                    if (have + n > total) throw new IOException("server sent more than " + total + " bytes");
                    out.write(b, 0, n);
                    have += n;
                    long now = System.nanoTime();
                    if (now - last > 300_000_000L || have == total) {
                        last = now;
                        if (p != null) p.at(have, total);
                        if (stop != null && stop.stopped()) throw new Bytes.Stopped();
                    }
                }
            }
            if (have != total) throw new IOException("connection closed at " + have + " of " + total);
            return newTag;
        } finally {
            c.disconnect();
        }
    }
}
