package com.gathin.tvhome;

import java.util.Locale;

/**
 * Pure-Java helpers for the network info panel (see {@link NetworkPanel}): turning the little-endian
 * {@code int} addresses the Wi-Fi APIs hand back ({@code WifiInfo.getIpAddress()},
 * {@code DhcpInfo.gateway}) into dotted IPv4 text, and naming the Wi-Fi band from its frequency.
 * No {@code android.*} here, so the JVM tests in src/test can exercise them, like {@link TvControl}
 * and {@link QuickPick}.
 */
final class NetworkInfoFormat {

    private NetworkInfoFormat() {
    }

    /**
     * Dotted IPv4 for a little-endian address int (the order {@code WifiInfo.getIpAddress()} and
     * {@code DhcpInfo} use): the low byte is the first octet. Returns {@code null} for 0 (unset),
     * so the caller can omit the row.
     */
    static String ipv4(int little) {
        if (little == 0) return null;
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                little & 0xFF, (little >> 8) & 0xFF, (little >> 16) & 0xFF, (little >> 24) & 0xFF);
    }

    /**
     * Friendly Wi-Fi band for a channel frequency in MHz ({@code WifiInfo.getFrequency()}): "2.4 GHz"
     * below 3000 MHz, "5 GHz" at or above it. Returns {@code null} for a non-positive frequency, so
     * the caller can omit the row. The unit text is not localised.
     */
    static String band(int frequencyMhz) {
        if (frequencyMhz <= 0) return null;
        return frequencyMhz < 3000 ? "2.4 GHz" : "5 GHz";
    }
}
