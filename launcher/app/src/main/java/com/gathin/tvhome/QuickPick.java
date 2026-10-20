package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Picks the apps the quick launch bar shows (see {@link QuickLaunchActivity}): the configured
 * {@code quick_launch_apps} when it has any, otherwise {@code dock_apps}. Keeps the configured order,
 * drops packages that are not installed (the {@code installed} predicate) or listed twice, and caps the
 * result at {@link #MAX_TILES}. No {@code android.*} here so it is plain-JVM testable, like
 * {@link RemoteKeys} and {@link CardFeature}.
 */
final class QuickPick {
    private QuickPick() { }

    /** At most this many tiles fit the bar. */
    static final int MAX_TILES = 4;

    static List<String> choose(List<String> quickApps, List<String> dockApps, Predicate<String> installed) {
        List<String> source = quickApps != null && !quickApps.isEmpty() ? quickApps : dockApps;
        List<String> out = new ArrayList<>();
        if (source == null) return out;
        for (String pkg : source) {
            if (pkg == null) continue;
            String p = pkg.trim();
            if (p.isEmpty() || out.contains(p)) continue;
            if (installed.test(p)) {
                out.add(p);
                if (out.size() >= MAX_TILES) break;
            }
        }
        return out;
    }
}
