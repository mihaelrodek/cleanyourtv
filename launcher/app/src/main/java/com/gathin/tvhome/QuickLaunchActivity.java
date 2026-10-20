package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The quick launch bar: a translucent glass bar of up to 4 app tiles that slides up from the bottom,
 * opened by a quick_launch_keycodes button from any app (see {@link InputKeyService}). DPAD left/right
 * moves the highlight, OK launches the app and closes the bar; Back, the same key (routed back through
 * the service as a fresh start, so {@link #onNewIntent} closes it), or losing focus ({@link #onPause})
 * close it without launching. Translucent and in a task of its own, like {@link QuickSettingsActivity},
 * so the app behind keeps running.
 */
public final class QuickLaunchActivity extends Activity {

    private static final int TILE_W_DP = 160, TILE_H_DP = 90, TILE_GAP_DP = 12, TILE_RADIUS_DP = 16;
    private static final float FOCUS_SCALE = 1.06f;

    /** A tile's label and artwork, loaded once and kept for the process so repeat opens are instant:
     *  the synchronous loadBanner/loadIcon per app was the whole of the open cost on this slow TV. */
    private static final class TileData {
        final String label;
        final Drawable banner;
        final Drawable icon;

        TileData(String label, Drawable banner, Drawable icon) {
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }
    }

    private static final Map<String, TileData> CACHE = new HashMap<>();

    private int[] quickLaunchKeycodes = new int[0];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config cfg = Config.load(this);
        quickLaunchKeycodes = cfg.quickLaunchKeycodes;
        PackageManager pm = getPackageManager();
        // Only 4 apps, so a synchronous query on open is cheap; keep allocations small.
        List<String> packages = QuickPick.choose(cfg.quickLaunchApps, cfg.dockApps,
                pkg -> pm.getLeanbackLaunchIntentForPackage(pkg) != null
                        || pm.getLaunchIntentForPackage(pkg) != null);
        if (packages.isEmpty()) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }

        setContentView(R.layout.activity_quick_launch);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout tiles = findViewById(R.id.tiles);
        TextView name = findViewById(R.id.name);

        boolean motion = cfg.animations && !cfg.reducedMotion;
        float density = getResources().getDisplayMetrics().density;
        int radius = Math.round(TILE_RADIUS_DP * density);
        ViewOutlineProvider rounded = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };

        for (int i = 0; i < packages.size(); i++) {
            String pkg = packages.get(i);
            TileData data = CACHE.get(pkg);
            if (data == null) {
                String label = pkg;
                Drawable banner = null, icon = null;
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    label = String.valueOf(pm.getApplicationLabel(info));
                    Drawable b = info.loadBanner(pm);
                    if (b != null && AppAdapter.isBannerShaped(b.getIntrinsicWidth(), b.getIntrinsicHeight())) {
                        banner = b;
                    } else {
                        icon = info.loadIcon(pm);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    icon = pm.getDefaultActivityIcon();
                }
                data = new TileData(label, banner, icon);
                CACHE.put(pkg, data);
            }
            tiles.addView(tile(tiles, pkg, data.label, data.banner, data.icon, rounded, name, motion, density, i > 0));
        }

        View first = tiles.getChildAt(0);
        name.setText((CharSequence) first.getTag());
        first.requestFocus();

        if (motion) {
            View panel = findViewById(R.id.panel);
            panel.setTranslationY(200 * density);   // set before the first draw, so there is no flash at rest
            panel.post(() -> panel.animate().translationY(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start());
        }
    }

    private View tile(ViewGroup parent, String pkg, String label, Drawable banner, Drawable icon,
                      ViewOutlineProvider rounded, TextView name, boolean motion, float density, boolean gap) {
        FrameLayout card = new FrameLayout(this);
        int w = Math.round(TILE_W_DP * density), h = Math.round(TILE_H_DP * density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        if (gap) lp.leftMargin = Math.round(TILE_GAP_DP * density);
        card.setLayoutParams(lp);
        card.setBackgroundResource(R.drawable.tile_bg);
        // A focus ring drawn as the foreground: it costs one cheap redraw of the ring when the highlight
        // moves, where the old scale-on-focus re-composited the whole banner bitmap and was slow here.
        card.setForeground(getDrawable(R.drawable.quick_tile_focus));
        card.setFocusable(true);
        card.setClickable(true);
        card.setOutlineProvider(rounded);
        card.setClipToOutline(true);
        card.setContentDescription(label);
        card.setTag(label);

        ImageView image = new ImageView(this);
        if (banner != null) {
            image.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageDrawable(banner);
        } else {
            int s = Math.round(48 * density);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(s, s, Gravity.CENTER);
            image.setLayoutParams(ilp);
            image.setImageDrawable(icon);
        }
        card.addView(image);

        card.setOnClickListener(v -> {
            RemoteKeys.launch(this, pkg);
            finish();
        });
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) name.setText(label);
            // The focus ring (foreground selector) marks the highlight. Add the lift only when motion is
            // on; on a reduced-motion TV the ring alone keeps moving between tiles snappy.
            if (motion) {
                float scale = focused ? FOCUS_SCALE : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(focused ? 160 : 120)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        });
        return card;
    }

    private boolean isQuickLaunchKey(int keyCode) {
        for (int code : quickLaunchKeycodes) if (keyCode == code) return true;
        return false;
    }

    /** The quick-launch button pressed again, routed back through the service as a fresh start: close. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the bar. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    /** Defensive: close on the quick-launch key or Menu if they ever reach the bar directly (Back already
     *  finishes). Normally the accessibility service consumes those keys before they get here. */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || isQuickLaunchKey(keyCode)) {
            finish();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
