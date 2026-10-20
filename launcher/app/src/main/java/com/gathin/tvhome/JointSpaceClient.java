package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A small client for the TV's own JointSpace API at https://127.0.0.1:1926/6/. The launcher runs on the
 * TV, so it only ever talks to itself: any other host is refused.
 *
 * TLS: the TV's certificate is self-signed. Trust on first use: while pairing ({@code pinnedSha256} null)
 * the SHA-256 of the leaf certificate is recorded ({@link #observedSha256()}), afterwards only that hash is
 * accepted. The trust manager and hostname check are set on each connection, never as process defaults.
 *
 * Auth: HTTP Digest (RFC 2617, MD5, qop=auth), which HttpURLConnection does not do itself. Blocking: call
 * it off the main thread.
 */
final class JointSpaceClient {
    static final String HOST = "127.0.0.1";
    private static final int PORT = 1926;
    private static final String PREFIX = "/6/";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_BODY = 64 * 1024;

    /** 401 after a fresh challenge, or a certificate other than the paired one: the pairing is gone. */
    static final class AuthException extends IOException {
        AuthException(String message) {
            super(message);
        }
    }

    private final String user;
    private final String password;
    private final PinningTrustManager trust;
    private final SSLSocketFactory sslFactory;
    private final SecureRandom random = new SecureRandom();
    private Map<String, String> challenge;
    private int nonceCount;

    /** {@code user}/{@code password} may be null for the unauthenticated pair/request call. */
    JointSpaceClient(String user, String password, String pinnedSha256) throws IOException {
        this.user = user;
        this.password = password;
        this.trust = new PinningTrustManager(pinnedSha256);
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{trust}, null);
            sslFactory = ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IOException("TLS setup failed", e);
        }
    }

    /** The certificate hash seen on the last connection (lowercase hex), for pinning after pairing. */
    String observedSha256() {
        return trust.observed;
    }

    JSONObject get(String path) throws IOException {
        return request("GET", path, null, true);
    }

    JSONObject post(String path, JSONObject body) throws IOException {
        return request("POST", path, body, true);
    }

    JSONObject postNoAuth(String path, JSONObject body) throws IOException {
        return request("POST", path, body, false);
    }

    /** Sends one request; with auth, retries once after a 401 with the server's fresh challenge. */
    private JSONObject request(String method, String path, JSONObject body, boolean auth) throws IOException {
        if (path.contains(":") || path.contains("..") || path.startsWith("/")) {
            throw new IOException("bad path");
        }
        URL url = new URL("https", HOST, PORT, PREFIX + path);
        if (!HOST.equals(url.getHost())) throw new IOException("host refused");
        String uri = url.getPath();
        byte[] payload = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        boolean canAuth = auth && user != null && password != null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
            try {
                c.setSSLSocketFactory(sslFactory);
                c.setHostnameVerifier((hostname, session) -> HOST.equals(hostname));
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod(method);
                c.setRequestProperty("Accept", "application/json");
                if (canAuth && challenge != null) {
                    c.setRequestProperty("Authorization", JointSpaceAuth.authorization(user, password, method,
                            uri, challenge, ++nonceCount, JointSpaceAuth.cnonce(random)));
                }
                if (payload != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(payload);
                    }
                }
                int code = c.getResponseCode();
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    Map<String, String> fresh = JointSpaceAuth.parseChallenge(c.getHeaderField("WWW-Authenticate"));
                    drain(c.getErrorStream());
                    if (!canAuth || fresh == null || attempt == 1) throw new AuthException("unauthorized");
                    challenge = fresh;
                    nonceCount = 0;
                    continue;
                }
                if (code < 200 || code >= 300) {
                    drain(c.getErrorStream());
                    throw new IOException("HTTP " + code);
                }
                return parse(readBody(c.getInputStream()));
            } catch (IOException e) {
                if (trust.mismatch) throw new AuthException("certificate changed");
                throw e;
            } finally {
                c.disconnect();
            }
        }
        throw new AuthException("unauthorized");
    }

    private static JSONObject parse(String text) throws IOException {
        String t = text.trim();
        if (t.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(t);
        } catch (JSONException e) {
            throw new IOException("bad JSON");
        }
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            byte[] buf = new byte[2048];
            int r;
            while ((r = s.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > MAX_BODY) throw new IOException("response too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try (InputStream s = in) {
            byte[] buf = new byte[1024];
            int total = 0, r;
            while (total < MAX_BODY && (r = s.read(buf)) > 0) total += r;
        } catch (IOException ignored) {
            // the status code is what matters
        }
    }

    /** Accepts only the pinned leaf certificate; with no pin (pairing) it records the one it sees. */
    private static final class PinningTrustManager implements X509TrustManager {
        private final String pinned;
        volatile String observed;
        volatile boolean mismatch;

        PinningTrustManager(String pinned) {
            this.pinned = pinned == null || pinned.isEmpty() ? null : pinned;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
            String hash = JointSpaceAuth.sha256Hex(chain[0].getEncoded());
            if (pinned != null && !pinned.equals(hash)) {
                mismatch = true;
                throw new CertificateException("certificate does not match the paired TV");
            }
            observed = hash;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
