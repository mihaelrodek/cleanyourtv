package com.gathin.tvhome;

import java.util.Locale;

/**
 * Pure-Java helpers for the TV pill (see {@link TvPanel}): clamping and stepping the volume within the
 * range the TV reports, and reducing a picture-mode {@code string_id} ("MAIN_MOVIE") to its suffix
 * ("MOVIE"). No android.* and no org.json, so the JVM tests in src/test can exercise them.
 */
final class TvControl {

    private TvControl() {
    }

    /** Keeps {@code value} inside [{@code min}, {@code max}]; if the range is backwards, returns {@code min}. */
    static int clampVolume(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    /** {@code current} moved by {@code step}, clamped to the TV's [{@code min}, {@code max}] range. */
    static int stepVolume(int current, int step, int min, int max) {
        return clampVolume(current + step, min, max);
    }

    /**
     * The picture-mode key the friendly labels map on: the part of a {@code string_id} after a leading
     * {@code MAIN_}, upper-cased ("MAIN_HDR_PERSONAL" to "HDR_PERSONAL"). Anything without that prefix,
     * or null/blank, comes back trimmed and upper-cased unchanged.
     */
    static String pictureModeKey(String stringId) {
        if (stringId == null) return "";
        String s = stringId.trim();
        // The TV sends a fully-qualified id, e.g. "org.droidtv.ui.strings.R.string.MAIN_MOVIE"; take the
        // last dotted segment first, then drop the MAIN_ prefix, so the label map sees "MOVIE".
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        s = s.toUpperCase(Locale.ROOT);
        return s.startsWith("MAIN_") ? s.substring(5) : s;
    }
}
