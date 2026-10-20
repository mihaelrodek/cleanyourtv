package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class NetworkInfoFormatTest {

    @Test public void ipv4ReadsLittleEndianLowByteFirst() {
        // 192.168.1.50 as WifiInfo.getIpAddress() packs it: 192 in the low byte.
        int little = 192 | (168 << 8) | (1 << 16) | (50 << 24);
        assertEquals("192.168.1.50", NetworkInfoFormat.ipv4(little));
    }

    @Test public void ipv4HandlesHighOctetsWithoutSignExtension() {
        int little = 10 | (0 << 8) | (0 << 16) | (255 << 24);
        assertEquals("10.0.0.255", NetworkInfoFormat.ipv4(little));
    }

    @Test public void ipv4IsNullWhenUnset() {
        assertNull(NetworkInfoFormat.ipv4(0));
    }

    @Test public void bandNamesTheFrequency() {
        assertEquals("2.4 GHz", NetworkInfoFormat.band(2412));
        assertEquals("2.4 GHz", NetworkInfoFormat.band(2472));
        assertEquals("5 GHz", NetworkInfoFormat.band(5180));
        assertEquals("5 GHz", NetworkInfoFormat.band(5805));
    }

    @Test public void bandIsNullWhenUnknown() {
        assertNull(NetworkInfoFormat.band(0));
        assertNull(NetworkInfoFormat.band(-1));
    }
}
