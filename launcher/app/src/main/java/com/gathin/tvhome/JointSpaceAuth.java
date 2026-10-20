package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure-Java helpers for the TV's JointSpace API: HTTP Digest (RFC 2617, MD5, qop=auth), the pairing
 * signature, and the certificate fingerprint. No android.* and no org.json, so the JVM tests in src/test
 * can run them. java.util.Base64 is API 26, the launcher's minSdk.
 */
final class JointSpaceAuth {
    /** The pairing HMAC key every JointSpace client uses (pylips, the Philips apps): not a secret of this TV. */
    private static final String PAIR_SECRET =
            "JCqdN5AcnAHgJYseUn7ER5k3qgtemfUvMRghQpTfTZq7Cvv8EPQPqfz6dDxPQPSu4gKFPWkJGw32zyASgJkHwCjU";
    private static final char[] ALNUM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JointSpaceAuth() {
    }

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = HEX[(bytes[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    static String md5Hex(String s) {
        return hex(digest("MD5", s.getBytes(StandardCharsets.UTF_8)));
    }

    static String sha256Hex(byte[] data) {
        return hex(digest("SHA-256", data));
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The nonce count as RFC 2617 writes it: 8 lowercase hex digits. */
    static String nc(int count) {
        return String.format(Locale.ROOT, "%08x", count);
    }

    /** RFC 2617 request-digest for qop=auth. */
    static String digestResponse(String user, String realm, String password, String method, String uri,
                                 String nonce, String nc, String cnonce, String qop) {
        String ha1 = md5Hex(user + ":" + realm + ":" + password);
        String ha2 = md5Hex(method + ":" + uri);
        return md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
    }

    /**
     * Parses a {@code WWW-Authenticate: Digest ...} header into lowercase keys and unquoted values;
     * null when it is not a Digest challenge. Quoted values may contain commas and escaped quotes.
     */
    static Map<String, String> parseChallenge(String header) {
        if (header == null) return null;
        String h = header.trim();
        if (!h.regionMatches(true, 0, "Digest", 0, 6)) return null;
        Map<String, String> out = new HashMap<>();
        int i = 6, n = h.length();
        while (i < n) {
            while (i < n && (h.charAt(i) == ',' || Character.isWhitespace(h.charAt(i)))) i++;
            int keyStart = i;
            while (i < n && h.charAt(i) != '=' && h.charAt(i) != ',' && !Character.isWhitespace(h.charAt(i))) i++;
            String key = h.substring(keyStart, i).toLowerCase(Locale.ROOT);
            while (i < n && Character.isWhitespace(h.charAt(i))) i++;
            if (i >= n || h.charAt(i) != '=') {
                if (!key.isEmpty()) out.put(key, "");
                continue;
            }
            i++;   // '='
            while (i < n && Character.isWhitespace(h.charAt(i))) i++;
            StringBuilder value = new StringBuilder();
            if (i < n && h.charAt(i) == '"') {
                i++;
                while (i < n && h.charAt(i) != '"') {
                    if (h.charAt(i) == '\\' && i + 1 < n) i++;
                    value.append(h.charAt(i++));
                }
                i++;   // closing quote
            } else {
                while (i < n && h.charAt(i) != ',') value.append(h.charAt(i++));
            }
            if (!key.isEmpty()) out.put(key, value.toString().trim());
        }
        return out.containsKey("nonce") ? out : null;
    }

    /** Picks "auth" out of a qop list such as {@code "auth,auth-int"}; null if the server does not offer it. */
    static String pickQop(String qop) {
        if (qop == null || qop.isEmpty()) return null;
        for (String q : qop.split(",")) {
            if (q.trim().equalsIgnoreCase("auth")) return "auth";
        }
        return null;
    }

    /** The Authorization header for one request. With no qop (RFC 2069 servers) nc and cnonce are left out. */
    static String authorization(String user, String password, String method, String uri,
                                Map<String, String> challenge, int count, String cnonce) {
        String realm = valueOr(challenge.get("realm"), "");
        String nonce = valueOr(challenge.get("nonce"), "");
        String qop = pickQop(challenge.get("qop"));
        StringBuilder sb = new StringBuilder("Digest username=\"").append(quote(user))
                .append("\", realm=\"").append(quote(realm))
                .append("\", nonce=\"").append(quote(nonce))
                .append("\", uri=\"").append(quote(uri)).append('"');
        if (qop != null) {
            String nc = nc(count);
            sb.append(", qop=").append(qop).append(", nc=").append(nc)
                    .append(", cnonce=\"").append(cnonce).append("\", response=\"")
                    .append(digestResponse(user, realm, password, method, uri, nonce, nc, cnonce, qop))
                    .append('"');
        } else {
            String ha1 = md5Hex(user + ":" + realm + ":" + password);
            sb.append(", response=\"").append(md5Hex(ha1 + ":" + nonce + ":" + md5Hex(method + ":" + uri)))
                    .append('"');
        }
        String opaque = challenge.get("opaque");
        if (opaque != null) sb.append(", opaque=\"").append(quote(opaque)).append('"');
        String algorithm = challenge.get("algorithm");
        if (algorithm != null && !algorithm.isEmpty()) sb.append(", algorithm=").append(algorithm);
        return sb.toString();
    }

    /**
     * The pair/grant signature: Base64 (no line breaks) of the lowercase hex HMAC-SHA1 of
     * {@code timestamp + pin}, keyed with the shared JointSpace secret. Verified on the 55PUS6412.
     */
    static String pairingSignature(long timestamp, String pin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(PAIR_SECRET), "HmacSHA1"));
            byte[] sig = mac.doFinal((timestamp + pin).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hex(sig).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A pairing device id: {@code length} characters from [A-Za-z0-9]. */
    static String randomId(SecureRandom random, int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) out[i] = ALNUM[random.nextInt(ALNUM.length)];
        return new String(out);
    }

    /** A client nonce: 16 hex characters. */
    static String cnonce(SecureRandom random) {
        byte[] b = new byte[8];
        random.nextBytes(b);
        return hex(b);
    }

    private static String quote(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String valueOr(String v, String fallback) {
        return v == null ? fallback : v;
    }
}
