// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.installer.core;

import org.lumen.download.HttpError;
import org.lumen.download.ParallelDownload;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HTTPS only (GitHub release URLs redirect to a short-lived CDN URL). The host tests use a local fake GitHub on
 * http://127.0.0.1, allowed only when the engine is built with {@code allowLocalHttp} (never in the app). Only a
 * User-Agent is sent, no device id. It opens the connections of the shared parallel download (apps/core/lumen-download)
 * that brings the images and app updates.
 */
public final class Http implements ParallelDownload.Opener {
    public static final String USER_AGENT = "LumenInstaller/1.0";
    private final boolean allowLocalHttp;

    public Http(boolean allowLocalHttp) { this.allowLocalHttp = allowLocalHttp; }

    @Override public boolean allowed(URL u) {
        if ("https".equals(u.getProtocol())) return true;
        return allowLocalHttp && "http".equals(u.getProtocol()) && "127.0.0.1".equals(u.getHost());
    }

    @Override public HttpURLConnection open(String url) throws IOException {
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
            if (code != 200) throw new HttpError(code, "HTTP " + code + " for " + url);
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
            throw new HttpError(code, "HTTP " + code + " for " + url);
        } finally {
            c.disconnect();
        }
    }
}
