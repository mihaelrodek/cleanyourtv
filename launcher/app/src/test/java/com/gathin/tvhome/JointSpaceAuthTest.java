package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class JointSpaceAuthTest {
    // RFC 2617, section 3.5.
    private static final String RFC_HEADER = "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", "
            + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"";

    @Test
    public void digestResponseMatchesRfc2617Example() {
        assertEquals("6629fae49393a05397450978507c4ef1", JointSpaceAuth.digestResponse("Mufasa",
                "testrealm@host.com", "Circle Of Life", "GET", "/dir/index.html",
                "dcd98b7102dd2f0e8b11d0f600bfb0c093", "00000001", "0a4f113b", "auth"));
    }

    @Test
    public void parsesRfc2617Challenge() {
        Map<String, String> c = JointSpaceAuth.parseChallenge(RFC_HEADER);
        assertEquals("testrealm@host.com", c.get("realm"));
        assertEquals("auth,auth-int", c.get("qop"));
        assertEquals("dcd98b7102dd2f0e8b11d0f600bfb0c093", c.get("nonce"));
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41", c.get("opaque"));
        assertEquals("auth", JointSpaceAuth.pickQop(c.get("qop")));
    }

    @Test
    public void parsesUnquotedValuesAndCommasInQuotes() {
        Map<String, String> c = JointSpaceAuth.parseChallenge(
                "digest realm=\"a, b\",nonce=abc123, qop=auth, algorithm=MD5, stale=TRUE");
        assertEquals("a, b", c.get("realm"));
        assertEquals("abc123", c.get("nonce"));
        assertEquals("auth", c.get("qop"));
        assertEquals("MD5", c.get("algorithm"));
        assertEquals("TRUE", c.get("stale"));
    }

    @Test
    public void rejectsNonDigestChallenges() {
        assertNull(JointSpaceAuth.parseChallenge(null));
        assertNull(JointSpaceAuth.parseChallenge("Basic realm=\"x\""));
        assertNull(JointSpaceAuth.parseChallenge("Digest realm=\"x\""));   // no nonce
    }

    @Test
    public void authorizationHeaderCarriesRfcResponse() {
        Map<String, String> c = JointSpaceAuth.parseChallenge(RFC_HEADER);
        String h = JointSpaceAuth.authorization("Mufasa", "Circle Of Life", "GET", "/dir/index.html",
                c, 1, "0a4f113b");
        assertTrue(h, h.startsWith("Digest username=\"Mufasa\""));
        assertTrue(h, h.contains("uri=\"/dir/index.html\""));
        assertTrue(h, h.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""));
        assertTrue(h, h.contains("response=\"6629fae49393a05397450978507c4ef1\""));
        assertTrue(h, h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));
    }

    @Test
    public void nonceCountIsEightHexDigits() {
        assertEquals("00000001", JointSpaceAuth.nc(1));
        assertEquals("0000001a", JointSpaceAuth.nc(26));
    }

    @Test
    public void pairingSignatureIsBase64OfFortyHexChars() {
        String sig = JointSpaceAuth.pairingSignature(1700000000L, "1234");
        assertEquals(sig, JointSpaceAuth.pairingSignature(1700000000L, "1234"));
        assertNotEquals(sig, JointSpaceAuth.pairingSignature(1700000000L, "1235"));
        String decoded = new String(Base64.getDecoder().decode(sig), StandardCharsets.US_ASCII);
        assertTrue(decoded, decoded.matches("[0-9a-f]{40}"));
        assertTrue(sig, sig.matches("[A-Za-z0-9+/]+=*"));
    }

    @Test
    public void randomIdIsSixteenAlphanumerics() {
        String id = JointSpaceAuth.randomId(new SecureRandom(), 16);
        assertTrue(id, id.matches("[A-Za-z0-9]{16}"));
    }

    @Test
    public void sha256HexIsLowercaseAndFull() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                JointSpaceAuth.sha256Hex(new byte[0]));
    }

    @Test
    public void noQopFallsBackToRfc2069() {
        Map<String, String> c = new HashMap<>();
        c.put("realm", "r");
        c.put("nonce", "n");
        String h = JointSpaceAuth.authorization("u", "p", "GET", "/x", c, 1, "c");
        String ha1 = JointSpaceAuth.md5Hex("u:r:p");
        String expected = JointSpaceAuth.md5Hex(ha1 + ":n:" + JointSpaceAuth.md5Hex("GET:/x"));
        assertTrue(h, h.contains("response=\"" + expected + "\""));
        assertTrue(h, !h.contains("nc="));
    }
}
