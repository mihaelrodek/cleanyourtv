package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The JointSpace pairing, in app-private SharedPreferences ("jointspace"): the device id, the auth key
 * the TV granted, and the SHA-256 of the TV's certificate seen while pairing. Never logged.
 */
final class JointSpaceStore {
    private static final String NAME = "jointspace";
    private static final String DEVICE_ID = "device_id";
    private static final String AUTH_KEY = "auth_key";
    private static final String CERT_SHA256 = "cert_sha256";

    private final SharedPreferences prefs;

    JointSpaceStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    boolean isPaired() {
        return !deviceId().isEmpty() && !authKey().isEmpty() && !certSha256().isEmpty();
    }

    String deviceId() {
        return prefs.getString(DEVICE_ID, "");
    }

    String authKey() {
        return prefs.getString(AUTH_KEY, "");
    }

    String certSha256() {
        return prefs.getString(CERT_SHA256, "");
    }

    void save(String deviceId, String authKey, String certSha256) {
        prefs.edit().putString(DEVICE_ID, deviceId).putString(AUTH_KEY, authKey)
                .putString(CERT_SHA256, certSha256).apply();
    }

    void clear() {
        prefs.edit().clear().apply();
    }
}
