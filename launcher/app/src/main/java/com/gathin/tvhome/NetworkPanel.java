package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;

/**
 * The network pill's dropdown (config {@code network_panel}): a read-only glass panel showing the
 * current connection — the Wi-Fi SSID (or "Ethernet"), signal, link speed, band, IP and gateway — and
 * one row that opens the system Wi-Fi settings for any actual change. Nothing here scans, connects or
 * asks for a password; those stay in the TV's own settings.
 *
 * The connection is read with the same {@link ConnectivityManager}/{@link WifiManager} logic as
 * {@link MainActivity#updateWifiLabel()}, so the panel agrees with the pill label. Every read is an
 * instant local call, so, unlike {@link AmbilightPanel} and {@link TvPanel}, there is no background
 * thread: everything is gathered once on the main thread when the panel opens. Mirrors the glass-popup
 * structure of those panels (glassPopup / dropIn / item_input rows).
 */
final class NetworkPanel {

    /** WifiInfo's sentinel for "no signal reading"; not a public SDK constant, so inlined here. */
    private static final int INVALID_RSSI = -127;

    private final MainActivity activity;
    private final float density;
    private PopupWindow popup;
    private LinearLayout rows;

    NetworkPanel(MainActivity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    void show(View anchor) {
        ViewGroup root = activity.findViewById(R.id.root);
        View panel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        panel.setContentDescription(activity.getString(R.string.network_title));
        rows = panel.findViewById(R.id.rows);
        popup = activity.glassPopup(panel, (int) (300 * density));
        build();
        int gravity = activity.getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        activity.dropIn(popup, panel, anchor, gravity);
        focus(0);
    }

    // ---- content ---------------------------------------------------------------------------------

    private void build() {
        rows.removeAllViews();
        boolean wifi = false, ethernet = false, online = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            // All networks, not just the active one (a VPN would otherwise be the active one), matching
            // updateWifiLabel: ignore the VPN transport, require an internet-capable network.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
        } catch (Exception ignored) {
        }

        if (!online) {
            addRow(R.string.network_not_connected, null);
        } else if (ethernet) {   // same precedence as updateWifiLabel: Ethernet before Wi-Fi
            addRow(R.string.network_ethernet, null);
            addValueRow(R.string.network_ip, activeIpv4());
        } else if (wifi) {
            buildWifi();
        } else {
            // Online some other way (cellular, USB tethering): just the address, when we can read it.
            addValueRow(R.string.network_ip, activeIpv4());
        }

        addSeparator();
        addRow(R.string.network_change, null, this::openWifiSettings);
    }

    private void buildWifi() {
        WifiManager wm = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;

        String ssid = info != null ? info.getSSID() : null;
        if (ssid != null) {
            ssid = ssid.replace("\"", "").trim();
            if (ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x")) ssid = null;
        }
        addRow(R.string.wifi, ssid);   // "Wi-Fi … <network>", or just "Wi-Fi" when the name is hidden

        if (info != null) {
            int rssi = info.getRssi();
            if (rssi != INVALID_RSSI) {
                int level = WifiManager.calculateSignalLevel(rssi, 5);   // 0..4; static form, API 1+
                addRow(R.string.network_signal, level + "/5  ·  " + rssi + " dBm");
            }
            int speed = info.getLinkSpeed();
            if (speed > 0) addRow(R.string.network_speed, speed + " Mbps");
            addValueRow(R.string.network_band, NetworkInfoFormat.band(info.getFrequency()));
            addValueRow(R.string.network_ip, NetworkInfoFormat.ipv4(info.getIpAddress()));
        }
        if (wm != null) {
            DhcpInfo dhcp = wm.getDhcpInfo();
            if (dhcp != null) addValueRow(R.string.network_gateway, NetworkInfoFormat.ipv4(dhcp.gateway));
        }
    }

    /** The active network's first IPv4 address as dotted text (for Ethernet, which has no WifiInfo). */
    private String activeIpv4() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address) {
                        return la.getAddress().getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---- rows ------------------------------------------------------------------------------------

    /** An info or action row: {@code status} in the value position, {@code action} null for info rows. */
    private void addRow(int titleRes, String status) {
        addRow(titleRes, status, null);
    }

    private void addRow(int titleRes, String status, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(titleRes);
        TextView st = row.findViewById(R.id.status);
        if (status == null) st.setVisibility(View.GONE);
        else st.setText(status);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        if (action != null) row.setOnClickListener(v -> action.run());
        rows.addView(row);
    }

    /** Like {@link #addRow(int, String)} but skips the row entirely when the value is unknown. */
    private void addValueRow(int titleRes, String status) {
        if (status != null) addRow(titleRes, status);
    }

    private void addSeparator() {
        View line = new View(activity);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void openWifiSettings() {
        if (popup != null) popup.dismiss();
        // MainActivity.open/systemIntent are private; start the same intent directly, with its toast.
        Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void focus(int index) {
        if (rows.getChildCount() == 0) return;
        rows.getChildAt(Math.max(0, Math.min(index, rows.getChildCount() - 1))).requestFocus();
    }
}
