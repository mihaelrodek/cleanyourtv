package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV pill's dropdown (config tv_panel): volume, mute and the picture mode, through the TV's own
 * JointSpace API (see {@link JointSpaceClient}). It reuses the pairing the Ambilight panel stores
 * ({@link JointSpaceStore}); when nothing is paired yet it points the user at the Ambilight panel
 * rather than duplicating the pairing flow.
 *
 * All network work runs on one shared background thread; results come back to the main thread and are
 * dropped when the dropdown has been closed in the meantime. Mirrors {@link AmbilightPanel}.
 */
final class TvPanel {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jointspace-tv");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_MS = 250;
    private static final int VOLUME_STEP = 2;

    private static final int SCREEN_MESSAGE = 0, SCREEN_MAIN = 1, SCREEN_PICTURE = 2;

    private interface Job<T> {
        T run() throws Exception;
    }

    private interface Done<T> {
        void accept(T value);
    }

    private interface Write {
        void apply(JointSpaceClient c) throws Exception;
    }

    /** One selectable picture mode the TV reports as controllable and available. */
    private static final class PicMode {
        final int enumId;
        final String stringId;

        PicMode(int enumId, String stringId) {
            this.enumId = enumId;
            this.stringId = stringId;
        }
    }

    /** What the TV last reported. */
    private static final class State {
        int volCurrent = -1, volMin = 0, volMax = -1;
        boolean muted;
        int pictureSelected = -1;
        List<PicMode> modes = new ArrayList<>();
    }

    private final MainActivity activity;
    private final JointSpaceStore store;
    private final float density;
    /** JointSpace picture-style node id for this TV (from the theme; differs by platform). */
    private final int pictureNode;
    private PopupWindow popup;
    private LinearLayout rows;
    private TextView status;
    private int screen;
    private State state = new State();
    /** Optimistic volume shown while a Left/Right change is still being debounced. */
    private int volume = -1;
    private boolean volumePending;
    private final Runnable applyVolume = this::applyVolume;

    TvPanel(MainActivity activity) {
        this.activity = activity;
        this.store = new JointSpaceStore(activity);
        this.density = activity.getResources().getDisplayMetrics().density;
        this.pictureNode = Config.load(activity).pictureStyleNode;
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
            showMessage();
        }
        int gravity = activity.getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        activity.dropIn(popup, panel, anchor, gravity);
        focus(0);
    }

    // ---- screens -------------------------------------------------------------------------------

    /** Not paired yet: send the user to the Ambilight panel, which owns the pairing flow. */
    private void showMessage() {
        screen = SCREEN_MESSAGE;
        rows.removeAllViews();
        addRow(R.string.tv_pair_hint, null, false, () -> {
            popup.dismiss();
            View amb = activity.findViewById(R.id.btnAmbilight);
            if (amb != null && amb.getVisibility() == View.VISIBLE) amb.performClick();
        });
        setStatus("");
    }

    private void showMain(int focusIndex) {
        screen = SCREEN_MAIN;
        rows.removeAllViews();
        // The panel is the picture-mode picker: one row per mode the TV reports as usable, the current
        // one marked. (Volume and mute were removed at the user's request.)
        if (state.modes.isEmpty()) {
            addRow(R.string.tv_picture_mode, "–", false, null);
        } else {
            for (PicMode mode : state.modes) {
                final int enumId = mode.enumId;
                boolean active = enumId == state.pictureSelected;
                addRowText(pictureLabel(mode), active, () -> setPicture(enumId));
            }
        }
        setStatusLine();
        focus(focusIndex);
    }

    private void showPicture(int focusIndex) {
        screen = SCREEN_PICTURE;
        rows.removeAllViews();
        addRow(R.string.tv_back, null, false, () -> showMain(2));
        for (PicMode mode : state.modes) {
            final int enumId = mode.enumId;
            boolean active = enumId == state.pictureSelected;
            addRowText(pictureLabel(enumId), active, () -> setPicture(enumId));
        }
        setStatusLine();
        focus(focusIndex);
    }

    // ---- actions -------------------------------------------------------------------------------

    private void applyVolume() {
        volumePending = false;
        if (popup == null || !popup.isShowing() || volume < 0) return;
        final int v = volume;
        final boolean muted = state.muted;
        write(c -> c.post("audio/volume", new JSONObject().put("muted", muted).put("current", v)), 0);
    }

    private void toggleMute() {
        final boolean muted = !state.muted;
        final int cur = state.volCurrent;
        write(c -> c.post("audio/volume", new JSONObject().put("muted", muted).put("current", cur)), 1);
    }

    private void setPicture(int enumId) {
        write(c -> {
            JSONObject value = new JSONObject().put("Nodeid", pictureNode).put("Controllable", true)
                    .put("Available", true).put("data", new JSONObject().put("selected_item", enumId));
            c.post("menuitems/settings/update", new JSONObject()
                    .put("values", new JSONArray().put(new JSONObject().put("value", value))));
        }, 0);
    }

    /** One write, then the TV's own view of the result. */
    private void write(Write w, int focusIndex) {
        final int onScreen = screen;
        run(() -> {
            JointSpaceClient c = client();
            w.apply(c);
            return readState(c, pictureNode);
        }, s -> {
            state = s;
            if (!volumePending) volume = s.volCurrent;
            if (screen != onScreen) return;
            // Rebuilding the rows would drop a Left/Right change still waiting to be sent.
            if (onScreen == SCREEN_MAIN && volumePending) return;
            if (onScreen == SCREEN_MAIN) showMain(focusIndex);
            else showPicture(focusIndex);
        }, this::failed);
    }

    private void refresh() {
        run(() -> readState(client(), pictureNode), s -> {
            state = s;
            volume = s.volCurrent;
            if (screen == SCREEN_MAIN) showMain(focusedIndex());
        }, this::failed);
    }

    private JointSpaceClient client() throws IOException {
        if (!store.isPaired()) throw new JointSpaceClient.AuthException("not paired");
        return new JointSpaceClient(store.deviceId(), store.authKey(), store.certSha256());
    }

    private static State readState(JointSpaceClient c, int node) throws IOException {
        State s = new State();
        try {
            JSONObject req = new JSONObject().put("nodes",
                    new JSONArray().put(new JSONObject().put("nodeid", node)));
            JSONArray values = c.post("menuitems/settings/current", req).optJSONArray("values");
            JSONObject first = values == null ? null : values.optJSONObject(0);
            JSONObject value = first == null ? null : first.optJSONObject("value");
            JSONObject data = value == null ? null : value.optJSONObject("data");
            if (data != null) {
                s.pictureSelected = data.optInt("selected_item", -1);
                JSONArray enums = data.optJSONArray("enum_values");
                if (enums != null) {
                    for (int i = 0; i < enums.length(); i++) {
                        JSONObject e = enums.optJSONObject(i);
                        if (e == null) continue;
                        if (!e.optBoolean("controllable") || !e.optBoolean("available")) continue;
                        s.modes.add(new PicMode(e.optInt("enum_id", -1), e.optString("string_id")));
                    }
                }
            }
        } catch (JSONException | IOException e) {
            if (e instanceof JointSpaceClient.AuthException) throw (IOException) e;
            // The picture row stays inert; volume and mute still work.
            s.pictureSelected = -1;
            s.modes = new ArrayList<>();
        }
        return s;
    }

    private void failed(Exception e) {
        if (e instanceof JointSpaceClient.AuthException) {
            store.clear();
            showMessage();
            setStatus(activity.getString(R.string.ambilight_paired_lost));
            focus(0);
        } else {
            setStatus(activity.getString(R.string.tv_unreachable));
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
        row.findViewById(R.id.dot).setVisibility(active ? View.VISIBLE : View.GONE);
        if (action != null) row.setOnClickListener(v -> action.run());
        rows.addView(row);
        return row;
    }

    private void addRowText(String title, boolean active, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(active ? View.VISIBLE : View.GONE);
        if (action != null) row.setOnClickListener(v -> action.run());
        rows.addView(row);
    }

    private String volumeText() {
        if (state.volMax < 0 || volume < 0) return "–";
        return "‹ " + volume + " / " + state.volMax + " ›";
    }

    /** Friendly label for a picture mode enum id; the raw suffix when the id is unknown. */
    private String pictureLabel(int enumId) {
        if (enumId < 0) return null;
        for (PicMode mode : state.modes) {
            if (mode.enumId == enumId) return pictureLabel(mode);
        }
        return null;
    }

    private String pictureLabel(PicMode mode) {
        int res = labelRes(TvControl.pictureModeKey(mode.stringId));
        return res == 0 ? TvControl.pictureModeKey(mode.stringId) : activity.getString(res);
    }

    private static int labelRes(String key) {
        switch (key) {
            case "PERSONAL": return R.string.tv_pic_personal;
            case "VIVID": return R.string.tv_pic_vivid;
            case "NATURAL": return R.string.tv_pic_natural;
            case "STANDARD": return R.string.tv_pic_standard;
            case "MOVIE": return R.string.tv_pic_movie;
            case "GAME": return R.string.tv_pic_game;
            case "MONITOR": return R.string.tv_pic_monitor;
            case "HDR_PERSONAL": return R.string.tv_pic_hdr_personal;
            case "HDR_VIVID": return R.string.tv_pic_hdr_vivid;
            case "HDR_NATURAL": return R.string.tv_pic_hdr_natural;
            case "HDR_MOVIE": return R.string.tv_pic_hdr_movie;
            case "HDR_GAME": return R.string.tv_pic_hdr_game;
            case "ISF_DAY": return R.string.tv_pic_isf_day;
            case "ISF_NIGHT": return R.string.tv_pic_isf_night;
            case "AI": return R.string.tv_pic_ai;
            case "HDR_AI": return R.string.tv_pic_hdr_ai;
            case "HDR_DOLBY_VISION_BRIGHT": return R.string.tv_pic_dv_bright;
            case "HDR_DOLBY_VISION_DARK": return R.string.tv_pic_dv_dark;
            default: return 0;
        }
    }

    /** The status line: the current picture mode, when known. */
    private void setStatusLine() {
        String mode = pictureLabel(state.pictureSelected);
        setStatus(mode == null ? "" : activity.getString(R.string.tv_picture_mode) + "  ·  " + mode);
    }

    /** +1 for Right, -1 for Left (mirrored in right-to-left layouts), 0 for any other key. */
    private static int horizontalStep(View v, int keyCode) {
        int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        return v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -step : step;
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
