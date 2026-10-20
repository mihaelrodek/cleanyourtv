package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Ambilight pill's dropdown (config ambilight_panel): power, the TV's Ambilight styles, a static
 * colour and brightness, through the TV's own JointSpace API (see {@link JointSpaceClient}). The first
 * use pairs with the TV: it shows a PIN on screen, which is typed here.
 *
 * All network work runs on one shared background thread; results come back to the main thread and are
 * dropped when the dropdown has been closed in the meantime.
 */
final class AmbilightPanel {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jointspace");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_MS = 250;
    /** Ambilight brightness in the TV's settings tree (menuitems/settings). */
    private static final long BRIGHTNESS_NODE = 2131230769L;

    private static final String FOLLOW_VIDEO = "FOLLOW_VIDEO";
    private static final String FOLLOW_AUDIO = "FOLLOW_AUDIO";
    private static final String FOLLOW_COLOR = "FOLLOW_COLOR";

    /** menuSetting values verified on the 55PUS6412 (JointSpace 6.1.0), with friendly labels. */
    private static final String[] VIDEO = {"STANDARD", "NATURAL", "IMMERSIVE", "VIVID", "GAME", "COMFORT", "RELAX"};
    private static final int[] VIDEO_LABELS = {R.string.ambilight_standard, R.string.ambilight_natural,
            R.string.ambilight_immersive, R.string.ambilight_vivid, R.string.ambilight_game,
            R.string.ambilight_comfort, R.string.ambilight_relax};
    private static final String[] AUDIO = {"ENERGY_ADAPTIVE_BRIGHTNESS", "ENERGY_ADAPTIVE_COLORS", "VU_METER",
            "SPECTRUM_ANALYSER", "KNIGHT_RIDER_CLOCKWISE", "KNIGHT_RIDER_ALTERNATING", "RANDOM_PIXEL_FLASH",
            "MODE_RANDOM"};
    private static final int[] AUDIO_LABELS = {R.string.ambilight_lumina, R.string.ambilight_colora,
            R.string.ambilight_retro, R.string.ambilight_spectrum, R.string.ambilight_scanner_clockwise,
            R.string.ambilight_scanner, R.string.ambilight_rhythm, R.string.ambilight_party};
    private static final String[] LOUNGE = {"HOT_LAVA", "DEEP_WATER", "FRESH_NATURE", "ISF", "PTA_LOUNGE"};
    private static final int[] LOUNGE_LABELS = {R.string.ambilight_hot_lava, R.string.ambilight_deep_water,
            R.string.ambilight_fresh_nature, R.string.ambilight_warm_white, R.string.ambilight_cool_white};

    private static final int SCREEN_PAIR = 0, SCREEN_PIN = 1, SCREEN_MAIN = 2,
            SCREEN_VIDEO = 3, SCREEN_AUDIO = 4, SCREEN_LOUNGE = 5;

    private interface Job<T> {
        T run() throws Exception;
    }

    private interface Done<T> {
        void accept(T value);
    }

    /** Between pair/request and pair/grant. */
    private static final class Pending {
        String deviceId, authKey, certSha256;
        long timestamp;
    }

    /** What the TV last reported. */
    private static final class State {
        Boolean power;
        String styleName = "", menuSetting = "", stringValue = "";
        boolean isExpert;
        int brightness = -1;
    }

    private final MainActivity activity;
    private final JointSpaceStore store;
    private final float density;
    private PopupWindow popup;
    private LinearLayout rows;
    private TextView status;
    private int screen;
    private Pending pending;
    private EditText pinField;
    private State state = new State();
    /** Colour-row hue, 0..255; not read back from the TV, which keeps it in its expert settings. */
    private int hue = 170;
    private int brightness = -1;
    /** A Left/Right change is waiting for its debounce: a read-back must not overwrite it. */
    private boolean huePending, brightnessPending;
    private final Runnable applyHue = this::applyHue;
    private final Runnable applyBrightness = this::applyBrightness;

    AmbilightPanel(MainActivity activity) {
        this.activity = activity;
        this.store = new JointSpaceStore(activity);
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    void show(View anchor) {
        ViewGroup root = activity.findViewById(R.id.root);
        View panel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        rows = panel.findViewById(R.id.rows);
        status = new TextView(activity);
        status.setTextColor(0xB3FFFFFF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setMaxLines(3);
        int p = (int) (12 * density);
        status.setPadding(p, (int) (6 * density), p, (int) (4 * density));
        ((LinearLayout) panel).addView(status);
        popup = activity.glassPopup(panel, (int) (300 * density));
        if (store.isPaired()) {
            showMain(0);
            setStatus(activity.getString(R.string.ambilight_loading));
            refresh();
        } else {
            showPair(false);
        }
        int gravity = activity.getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        activity.dropIn(popup, panel, anchor, gravity);
        focus(0);
    }

    // ---- screens -------------------------------------------------------------------------------

    private void showPair(boolean again) {
        screen = SCREEN_PAIR;
        rows.removeAllViews();
        addRow(again ? R.string.ambilight_pair_again : R.string.ambilight_pair, null, false, this::startPairing);
    }

    private void showPin() {
        screen = SCREEN_PIN;
        rows.removeAllViews();
        pinField = new EditText(activity);
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        pinField.setHint(R.string.ambilight_pin_hint);
        pinField.setSingleLine(true);
        pinField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pinField.setBackgroundResource(R.drawable.input_row_bg);
        // Same colours as pill_text and pill_text_dim: dark on the white focused row, white otherwise.
        int[][] states = {{android.R.attr.state_focused}, {}};
        pinField.setTextColor(new ColorStateList(states, new int[]{0xFF111111, 0xFFFFFFFF}));
        pinField.setHintTextColor(new ColorStateList(states, new int[]{0x99111111, 0x73FFFFFF}));
        pinField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        int p = (int) (12 * density);
        pinField.setPadding(p, 0, p, 0);
        pinField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            confirmPin();
            return true;
        });
        rows.addView(pinField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * density)));
        addRow(R.string.ambilight_confirm, null, false, this::confirmPin);
    }

    private void showMain(int focusIndex) {
        screen = SCREEN_MAIN;
        rows.removeAllViews();
        String power = state.power == null ? null
                : activity.getString(state.power ? R.string.ambilight_on : R.string.ambilight_off);
        addRow(R.string.ambilight_power, power, false, this::togglePower);
        addRow(R.string.ambilight_follow_video, activeLabel(FOLLOW_VIDEO, VIDEO, VIDEO_LABELS),
                isStyle(FOLLOW_VIDEO), () -> showOptions(SCREEN_VIDEO, 0));
        addRow(R.string.ambilight_follow_audio, activeLabel(FOLLOW_AUDIO, AUDIO, AUDIO_LABELS),
                isStyle(FOLLOW_AUDIO), () -> showOptions(SCREEN_AUDIO, 0));
        addRow(R.string.ambilight_lounge, activeLabel(FOLLOW_COLOR, LOUNGE, LOUNGE_LABELS),
                isStyle(FOLLOW_COLOR) && !state.isExpert, () -> showOptions(SCREEN_LOUNGE, 0));
        // The dot is the colour swatch here, so a static colour in use is marked with a bullet instead.
        boolean staticColour = isStyle(FOLLOW_COLOR) && state.isExpert;
        View colour = addRow(R.string.ambilight_colour, staticColour ? "\u2022  \u2039 \u203A" : "\u2039 \u203A",
                false, this::applyHue);
        View swatch = colour.findViewById(R.id.dot);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setStroke(Math.max(1, (int) density), 0x66FFFFFF);
        dot.setColor(hueColor(hue));
        swatch.setBackground(dot);
        ViewGroup.LayoutParams lp = swatch.getLayoutParams();
        lp.width = lp.height = (int) (16 * density);
        swatch.setVisibility(View.VISIBLE);
        colour.setOnKeyListener((v, keyCode, event) -> {
            int step = horizontalStep(v, keyCode);
            if (step == 0) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                hue = (hue + 16 * step + 256) % 256;
                dot.setColor(hueColor(hue));
                huePending = true;
                MAIN.removeCallbacks(applyHue);
                MAIN.postDelayed(applyHue, DEBOUNCE_MS);
            }
            return true;
        });
        View bright = addRow(R.string.ambilight_brightness, brightnessText(), false, null);
        TextView brightStatus = bright.findViewById(R.id.status);
        bright.setOnKeyListener((v, keyCode, event) -> {
            int step = horizontalStep(v, keyCode);
            if (step == 0) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && brightness >= 0) {
                brightness = Math.max(0, Math.min(10, brightness + step));
                brightStatus.setText(brightnessText());
                brightnessPending = true;
                MAIN.removeCallbacks(applyBrightness);
                MAIN.postDelayed(applyBrightness, DEBOUNCE_MS);
            }
            return true;
        });
        setStatus(state.stringValue.isEmpty() ? ""
                : activity.getString(R.string.ambilight_current, state.stringValue));
        focus(focusIndex);
    }

    private void showOptions(int which, int focusIndex) {
        screen = which;
        rows.removeAllViews();
        final String style;
        final String[] values;
        final int[] labels;
        if (which == SCREEN_VIDEO) {
            style = FOLLOW_VIDEO;
            values = VIDEO;
            labels = VIDEO_LABELS;
        } else if (which == SCREEN_AUDIO) {
            style = FOLLOW_AUDIO;
            values = AUDIO;
            labels = AUDIO_LABELS;
        } else {
            style = FOLLOW_COLOR;
            values = LOUNGE;
            labels = LOUNGE_LABELS;
        }
        final int mainIndex = which - SCREEN_VIDEO + 1;
        addRow(R.string.ambilight_back, null, false, () -> showMain(mainIndex));
        for (int i = 0; i < values.length; i++) {
            final String setting = values[i];
            final int index = i + 1;
            boolean active = isStyle(style) && !state.isExpert && setting.equals(state.menuSetting);
            addRow(labels[i], null, active, () -> setStyle(style, setting, index));
        }
        focus(focusIndex);
    }

    // ---- actions -------------------------------------------------------------------------------

    private void startPairing() {
        setStatus(activity.getString(R.string.ambilight_requesting_pin));
        run(() -> {
            Pending p = new Pending();
            p.deviceId = JointSpaceAuth.randomId(new SecureRandom(), 16);
            JointSpaceClient c = new JointSpaceClient(null, null, null);
            JSONObject req = new JSONObject();
            JSONArray scope = new JSONArray().put("read").put("write").put("control");
            req.put("scope", scope);
            req.put("device", device(p.deviceId));
            JSONObject res = c.postNoAuth("pair/request", req);
            String error = res.optString("error_id");
            if (!"SUCCESS".equals(error)) throw new PairingError(error);
            p.authKey = res.getString("auth_key");
            p.timestamp = res.getLong("timestamp");
            p.certSha256 = c.observedSha256();
            if (p.certSha256 == null) throw new IOException("no certificate");
            return p;
        }, p -> {
            pending = p;
            showPin();
            setStatus(activity.getString(R.string.ambilight_enter_pin));
            focus(0);
        }, this::pairingFailed);
    }

    private void confirmPin() {
        final Pending p = pending;
        String pin = pinField == null ? "" : pinField.getText().toString().trim();
        if (p == null || pin.isEmpty()) return;
        setStatus(activity.getString(R.string.ambilight_loading));
        run(() -> {
            JointSpaceClient c = new JointSpaceClient(p.deviceId, p.authKey, p.certSha256);
            JSONObject auth = new JSONObject();
            auth.put("auth_AppId", "1");
            auth.put("pin", pin);
            auth.put("auth_timestamp", p.timestamp);
            auth.put("auth_signature", JointSpaceAuth.pairingSignature(p.timestamp, pin));
            JSONObject req = new JSONObject().put("auth", auth).put("device", device(p.deviceId));
            JSONObject res = c.post("pair/grant", req);
            String error = res.optString("error_id");
            if (!"SUCCESS".equals(error)) throw new PairingError(error);
            store.save(p.deviceId, p.authKey, p.certSha256);
            return readState(client());
        }, s -> {
            pending = null;
            pinField = null;
            state = s;
            brightness = s.brightness;
            showMain(0);
        }, this::pairingFailed);
    }

    private void togglePower() {
        boolean on = state.power == null || !state.power;
        write(c -> c.post("ambilight/power", new JSONObject().put("power", on ? "On" : "Off")), 0);
    }

    private void setStyle(String style, String setting, int focusIndex) {
        write(c -> c.post("ambilight/currentconfiguration", new JSONObject().put("styleName", style)
                .put("isExpert", false).put("menuSetting", setting)), focusIndex);
    }

    private void applyHue() {
        huePending = false;
        if (popup == null || !popup.isShowing()) return;
        final int h = hue;
        write(c -> {
            JSONObject zero = new JSONObject().put("hue", 0).put("saturation", 0).put("brightness", 0);
            JSONObject color = new JSONObject().put("hue", h).put("saturation", 255).put("brightness", 255);
            c.post("ambilight/currentconfiguration", new JSONObject().put("styleName", FOLLOW_COLOR)
                    .put("isExpert", true).put("algorithm", "MANUAL_HUE")
                    .put("colorSettings", new JSONObject().put("color", color).put("colorDelta", zero)
                            .put("speed", 255)));
        }, 4);
    }

    private void applyBrightness() {
        brightnessPending = false;
        if (popup == null || !popup.isShowing() || brightness < 0) return;
        final int b = brightness;
        write(c -> {
            JSONObject value = new JSONObject().put("Nodeid", BRIGHTNESS_NODE).put("Controllable", true)
                    .put("Available", true).put("data", new JSONObject().put("value", b));
            c.post("menuitems/settings/update", new JSONObject()
                    .put("values", new JSONArray().put(new JSONObject().put("value", value))));
        }, 5);
    }

    private interface Write {
        void apply(JointSpaceClient c) throws Exception;
    }

    /** One write, then the TV's own view of the result: unknown names are accepted with 200 and ignored. */
    private void write(Write w, int focusIndex) {
        final int onScreen = screen;
        run(() -> {
            JointSpaceClient c = client();
            w.apply(c);
            return readState(c);
        }, s -> {
            state = s;
            if (!brightnessPending) brightness = s.brightness;
            if (screen != onScreen) return;
            // Rebuilding the rows would drop a Left/Right change still waiting to be sent.
            if (onScreen == SCREEN_MAIN && (huePending || brightnessPending)) return;
            if (onScreen == SCREEN_MAIN) {
                showMain(focusIndex);
            } else {
                showOptions(onScreen, focusIndex);
                setStatus(s.stringValue.isEmpty() ? "" : activity.getString(R.string.ambilight_current, s.stringValue));
            }
        }, this::failed);
    }

    private void refresh() {
        run(() -> readState(client()), s -> {
            state = s;
            brightness = s.brightness;
            if (screen == SCREEN_MAIN) showMain(focusedIndex());
        }, this::failed);
    }

    private JointSpaceClient client() throws IOException {
        if (!store.isPaired()) throw new JointSpaceClient.AuthException("not paired");
        return new JointSpaceClient(store.deviceId(), store.authKey(), store.certSha256());
    }

    private static State readState(JointSpaceClient c) throws IOException {
        State s = new State();
        String power = c.get("ambilight/power").optString("power");
        s.power = power.isEmpty() ? null : "On".equalsIgnoreCase(power);
        JSONObject cfg = c.get("ambilight/currentconfiguration");
        s.styleName = cfg.optString("styleName");
        s.menuSetting = cfg.optString("menuSetting");
        s.stringValue = cfg.optString("stringValue");
        s.isExpert = cfg.optBoolean("isExpert");
        try {
            JSONObject req = new JSONObject().put("nodes",
                    new JSONArray().put(new JSONObject().put("nodeid", BRIGHTNESS_NODE)));
            JSONArray values = c.post("menuitems/settings/current", req).optJSONArray("values");
            JSONObject first = values == null ? null : values.optJSONObject(0);
            JSONObject value = first == null ? null : first.optJSONObject("value");
            JSONObject data = value == null ? null : value.optJSONObject("data");
            if (data != null && data.has("value")) s.brightness = Math.max(0, Math.min(10, data.getInt("value")));
        } catch (JSONException | IOException e) {
            if (e instanceof JointSpaceClient.AuthException) throw (IOException) e;
            s.brightness = -1;   // brightness row stays inert; the rest of the panel still works
        }
        return s;
    }

    private static JSONObject device(String id) throws JSONException {
        return new JSONObject().put("device_name", "CleanYourTV").put("device_os", "Android")
                .put("app_name", "CleanYourTV Home").put("type", "native")
                .put("app_id", "com.gathin.tvhome").put("id", id);
    }

    private static final class PairingError extends Exception {
        final String errorId;

        PairingError(String errorId) {
            super("pairing: " + errorId);
            this.errorId = errorId;
        }
    }

    private void pairingFailed(Exception e) {
        if (e instanceof JointSpaceClient.AuthException) store.clear();
        pending = null;
        pinField = null;
        showPair(false);
        if (e instanceof PairingError && "CONCURRENT_PAIRING".equals(((PairingError) e).errorId)) {
            setStatus(activity.getString(R.string.ambilight_pair_busy));
        } else if (e instanceof PairingError || e instanceof JointSpaceClient.AuthException) {
            setStatus(activity.getString(R.string.ambilight_pair_failed));
        } else {
            setStatus(activity.getString(R.string.ambilight_unreachable));
        }
        focus(0);
    }

    private void failed(Exception e) {
        if (e instanceof JointSpaceClient.AuthException) {
            store.clear();
            showPair(true);
            setStatus(activity.getString(R.string.ambilight_paired_lost));
            focus(0);
        } else {
            setStatus(activity.getString(R.string.ambilight_unreachable));
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------

    /** Runs {@code job} on the shared thread; the result reaches the UI only while this dropdown is showing. */
    private <T> void run(Job<T> job, Done<T> ok, Done<Exception> error) {
        final PopupWindow owner = popup;
        EXEC.execute(() -> {
            T value = null;
            Exception failure = null;
            try {
                value = job.run();
            } catch (Exception e) {
                failure = e;
            }
            final T v = value;
            final Exception f = failure;
            MAIN.post(() -> {
                if (owner != popup || owner == null || !owner.isShowing()) return;
                if (f == null) ok.accept(v);
                else error.accept(f);
            });
        });
    }

    private View addRow(int title, String statusText, boolean active, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView st = row.findViewById(R.id.status);
        if (statusText == null) st.setVisibility(View.GONE);
        else st.setText(statusText);
        // The green dot marks the option the TV reports as active.
        row.findViewById(R.id.dot).setVisibility(active ? View.VISIBLE : View.GONE);
        if (action != null) row.setOnClickListener(v -> action.run());
        rows.addView(row);
        return row;
    }

    private String activeLabel(String style, String[] values, int[] labels) {
        if (!isStyle(style) || state.isExpert) return null;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(state.menuSetting)) return activity.getString(labels[i]);
        }
        return null;
    }

    private boolean isStyle(String style) {
        return style.equals(state.styleName);
    }

    private String brightnessText() {
        return brightness < 0 ? "\u2013" : "\u2039 " + brightness + " \u203A";
    }

    /** +1 for Right, -1 for Left (mirrored in right-to-left layouts), 0 for any other key. */
    private static int horizontalStep(View v, int keyCode) {
        int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        return v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -step : step;
    }

    private static int hueColor(int h) {
        return Color.HSVToColor(new float[]{h * 360f / 256f, 1f, 1f});
    }

    private void setStatus(String text) {
        status.setText(text);
        status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void focus(int index) {
        if (rows.getChildCount() == 0) return;
        rows.getChildAt(Math.max(0, Math.min(index, rows.getChildCount() - 1))).requestFocus();
    }

    private int focusedIndex() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).hasFocus()) return i;
        }
        return 0;
    }
}
