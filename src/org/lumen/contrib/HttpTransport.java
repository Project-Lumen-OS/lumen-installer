package org.lumen.contrib;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** HTTPS with short timeouts and a small answer cap: the base answers a few hundred bytes. */
public final class HttpTransport implements Transport {
    static final int TIMEOUT_MS = 10_000;
    static final int ANSWER_MAX = 4096;

    @Override
    public Reply get(String url) throws IOException {
        return call(url, null, null);
    }

    @Override
    public Reply post(String url, byte[] body, Map<String, String> headers) throws IOException {
        return call(url, body, headers);
    }

    private static Reply call(String url, byte[] body, Map<String, String> headers) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("https only");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Reply(status, in == null ? "{}" : read(in));
        } finally {
            c.disconnect();
        }
    }

    static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            for (int n; (n = i.read(buf)) > 0; ) {
                if (b.size() + n > ANSWER_MAX) throw new IOException("answer too long");
                b.write(buf, 0, n);
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
