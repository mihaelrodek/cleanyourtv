package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.Map;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it, others (KTC and Kalley Realtek Google TV remotes) KEYCODE_TV_INPUT; on Google TV
 * the stock launcher handles both, so with the stock launcher disabled the button does nothing. Buttons listed in
 * input_key_keycodes (config.xml or a theme) open the input switcher the same way, app_keys start an app (a
 * YouTube button that only the stock launcher answered), and quick_launch_keycodes open the quick launch bar
 * ({@link QuickLaunchActivity}). Every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private int[] inputKeycodes = new int[0];
    private int[] quickLaunchKeycodes = new int[0];
    private Map<Integer, String> appKeys = Collections.emptyMap();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // From config.xml or a theme's config.json; theme.sh rebinds this service after a push.
        Config config = Config.load(this);
        inputKeycodes = config.inputKeycodes;
        quickLaunchKeycodes = config.quickLaunchKeycodes;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
    }

    private boolean isInputKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_TV || keyCode == KeyEvent.KEYCODE_TV_INPUT) return true;
        for (int code : inputKeycodes) if (keyCode == code) return true;
        return false;
    }

    private boolean isQuickLaunchKey(int keyCode) {
        for (int code : quickLaunchKeycodes) if (keyCode == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        // Priority: an app button wins, then the quick launch bar, then the input switcher.
        String app = appKeys.get(keyCode);
        boolean quickLaunch = app == null && isQuickLaunchKey(keyCode);
        boolean input = app == null && !quickLaunch && isInputKey(keyCode);
        if (app == null && !quickLaunch && !input) return false;
        // One place to fire these buttons. A future "long press only" variant (keeping the Philips OPTIONS
        // short press for the system menu, opening the bar only on a long press) would track the ACTION_DOWN
        // time per keyCode here and act on ACTION_UP only past the threshold. Today every matching key is a
        // short press acted on at ACTION_UP, and both DOWN and UP are consumed so the app behind never sees it.
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            if (app != null) {
                RemoteKeys.launch(this, app);
            } else if (quickLaunch) {
                openQuickLaunch();
            } else {
                try {
                    startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        }
        return true;
    }

    private void openQuickLaunch() {
        try {
            startActivity(new Intent(this, QuickLaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
