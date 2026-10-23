package io.github.omer182.calmtv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Apple Home-style control panel: the bedroom AC (power, mode, temperature, fan, off-timer)
 * and a few Home Assistant devices. State comes from Home Assistant pushes (HomeState).
 */
public class HomeControlActivity extends Activity implements HomeState.Listener {
    private static final String[] MODES = {"off", "cool", "heat", "fan_only"};
    private static final String[] MODE_NAMES = {"Off", "Cool", "Heat", "Fan"};
    private static final String[] FANS = {"low", "mid", "high"};
    private static final String[] FAN_NAMES = {"Low", "Mid", "High"};
    private static final int[] TIMER_MINUTES = {30, 60, 120, 0};
    private static final String[] TIMER_NAMES = {"30 min", "1 h", "2 h", "Cancel"};
    private static final int MIN_TEMP = 16, MAX_TEMP = 30;

    private final Handler handler = new Handler();
    private HomeState state;
    private String lastOnMode = "cool";

    private LinearLayout acCard;
    private TextView acStatus;
    private TextView tempView;
    private TextView timerStatus;
    private final TextView[] modeChips = new TextView[MODES.length];
    private final TextView[] fanChips = new TextView[FANS.length];
    private LinearLayout deviceColumn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HomeState.startServer(this);
        state = HomeState.load(this);
        if (state == null) state = new HomeState();
        setContentView(buildLayout());
        render();
        tempView.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeState.setListener(this);
        // Ask Home Assistant for the current state; it answers with a push.
        HomeState.command("refresh", null, null);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        HomeState.setListener(null);
        handler.removeCallbacks(tick);
    }

    @Override
    public void onHomeState(HomeState s) {
        // A temperature change still waiting to be sent wins over an older push.
        if (pendingTemp) s.acTemp = state.acTemp;
        state = s;
        render();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderTimer();
            handler.postDelayed(this, 30_000);
        }
    };

    // ---------------------------------------------------------------- layout

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1013);
        root.setPadding(dp(56), dp(36), dp(56), dp(36));
        root.setClipChildren(false);

        TextView title = Ui.text(this, 30, 500);
        title.setText("Home");
        root.addView(title);
        TextView sub = Ui.text(this, 16, 400);
        sub.setAlpha(0.55f);
        sub.setText(Config.get(this).room);
        sub.setPadding(0, 0, 0, dp(18));
        root.addView(sub);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setClipChildren(false);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        acCard = buildAcCard();
        content.addView(acCard, new LinearLayout.LayoutParams(dp(540), -1));

        deviceColumn = new LinearLayout(this);
        deviceColumn.setOrientation(LinearLayout.VERTICAL);
        deviceColumn.setClipChildren(false);
        deviceColumn.setClipToPadding(false);
        deviceColumn.setPadding(dp(28), 0, 0, 0);
        content.addView(deviceColumn, new LinearLayout.LayoutParams(0, -1, 1f));
        return root;
    }

    private LinearLayout buildAcCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(22), dp(28), dp(22));
        card.setClipChildren(false);
        card.setClipToPadding(false);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_ac);
        header.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView name = Ui.text(this, 20, 500);
        name.setText("Air conditioner");
        name.setPadding(dp(12), 0, 0, 0);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        acStatus = Ui.text(this, 16, 400);
        acStatus.setAlpha(0.8f);
        header.addView(acStatus);
        card.addView(header);

        // Big temperature: Left/Right change it, OK switches the AC on or off.
        tempView = Ui.text(this, 64, 200);
        tempView.setGravity(Gravity.CENTER);
        tempView.setFocusable(true);
        tempView.setBackground(Ui.rounded(this, 0x00000000, 20));
        tempView.setOnFocusChangeListener((v, has) ->
                v.setBackground(Ui.rounded(this, has ? 0x26FFFFFF : 0x00000000, 20)));
        tempView.setOnKeyListener((v, keyCode, event) -> onTempKey(keyCode, event));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(6);
        tlp.bottomMargin = dp(4);
        card.addView(tempView, tlp);

        card.addView(chipRow("Mode", MODE_NAMES, modeChips, i -> setMode(MODES[i])));
        card.addView(chipRow("Fan", FAN_NAMES, fanChips, i -> {
            state.acFan = FANS[i];
            HomeState.command("ac_fan", "fan", FANS[i]);
            render();
        }));
        TextView[] timerChips = new TextView[TIMER_NAMES.length];
        card.addView(chipRow("Off in", TIMER_NAMES, timerChips, i -> setOffTimer(TIMER_MINUTES[i])));

        timerStatus = Ui.text(this, 15, 400);
        timerStatus.setAlpha(0.75f);
        timerStatus.setPadding(dp(96), dp(2), 0, 0);
        card.addView(timerStatus);
        return card;
    }

    private interface OnChip {
        void onChip(int index);
    }

    private LinearLayout chipRow(String label, String[] names, TextView[] chips, final OnChip onChip) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        // A focused chip grows past the row's padding; don't cut it there.
        row.setClipToPadding(false);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView l = Ui.text(this, 15, 400);
        l.setAlpha(0.6f);
        l.setText(label);
        row.addView(l, new LinearLayout.LayoutParams(dp(96), -2));
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView chip = Ui.text(this, 16, 500);
            chip.setShadowLayer(0, 0, 0, 0);
            chip.setText(names[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true);
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            chip.setOnClickListener(v -> onChip.onChip(index));
            chip.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(120).start();
                styleChip((TextView) v, v.isSelected(), has);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(10);
            row.addView(chip, lp);
            chips[i] = chip;
            styleChip(chip, false, false);
        }
        return row;
    }

    private void styleChip(TextView chip, boolean selected, boolean focused) {
        int bg = selected ? 0xFFFFFFFF : focused ? 0x40FFFFFF : 0x1FFFFFFF;
        chip.setBackground(Ui.rounded(this, bg, 20));
        chip.setTextColor(selected ? 0xFF15171A : Color.WHITE);
    }

    // ---------------------------------------------------------------- actions

    private boolean pendingTemp;
    private final Runnable sendTemp = () -> {
        pendingTemp = false;
        HomeState.command("ac_temp", "temp", state.acTemp);
    };

    private boolean onTempKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            state.acTemp = Math.max(MIN_TEMP, Math.min(MAX_TEMP, state.acTemp + step));
            // Send once the presses settle, so the AC doesn't beep for every degree.
            pendingTemp = true;
            handler.removeCallbacks(sendTemp);
            handler.postDelayed(sendTemp, 900);
            render();
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.getRepeatCount() == 0) {
            setMode("off".equals(state.acState) ? lastOnMode : "off");
            return true;
        }
        return false;
    }

    private void setMode(String mode) {
        if (!"off".equals(mode)) lastOnMode = mode;
        state.acState = mode;
        if ("off".equals(mode)) state.acOffAt = 0;
        HomeState.command("ac_mode", "mode", mode);
        render();
    }

    private void setOffTimer(int minutes) {
        state.acOffAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        HomeState.command("ac_off_in", "minutes", minutes);
        render();
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        String mode = state.acState;
        boolean on = !"off".equals(mode);
        if (on) lastOnMode = mode;
        int tint = "cool".equals(mode) ? 0xFF1E5AA8 : "heat".equals(mode) ? 0xFFB4561E
                : "fan_only".equals(mode) ? 0xFF2E6B5C : 0x1FFFFFFF;
        acCard.setBackground(Ui.rounded(this, tint, 28));

        acStatus.setText(!on ? "Off" : "cool".equals(mode) ? "Cooling to " + state.acTemp + "°"
                : "heat".equals(mode) ? "Heating to " + state.acTemp + "°" : "Fan only");
        tempView.setText("‹  " + state.acTemp + "°  ›");
        tempView.setAlpha(on ? 1f : 0.45f);

        for (int i = 0; i < MODES.length; i++) select(modeChips[i], MODES[i].equals(mode));
        for (int i = 0; i < FANS.length; i++) select(fanChips[i], FANS[i].equals(state.acFan));
        renderTimer();
        renderDevices();
    }

    private void select(TextView chip, boolean selected) {
        chip.setSelected(selected);
        styleChip(chip, selected, chip.isFocused());
    }

    private void renderTimer() {
        if (timerStatus == null) return;
        long left = state.acOffAt - System.currentTimeMillis();
        if (state.acOffAt == 0 || left <= 0 || "off".equals(state.acState)) {
            timerStatus.setText("off".equals(state.acState) ? "" : "No off-timer");
            return;
        }
        long mins = (left + 30_000) / 60_000;
        String at = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(state.acOffAt));
        String in = mins >= 60 ? mins / 60 + " h" + (mins % 60 > 0 ? " " + mins % 60 + " min" : "") : mins + " min";
        timerStatus.setText("Turns off at " + at + "  ·  in " + in);
    }

    private void renderDevices() {
        View focused = getCurrentFocus();
        Object focusedId = focused != null ? focused.getTag() : null;
        deviceColumn.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < state.devices.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = dp(16);
                deviceColumn.addView(row, rlp);
            }
            View tile = deviceTile(state.devices.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(116), 1f);
            if (i % 2 == 0) lp.rightMargin = dp(16);
            row.addView(tile, lp);
            if (tile.getTag().equals(focusedId)) tile.requestFocus();
        }
        // Keep a lone last tile the same size as the others.
        if (state.devices.size() % 2 == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(116), 1f));
        if (state.devices.isEmpty()) {
            TextView wait = Ui.text(this, 16, 400);
            wait.setAlpha(0.6f);
            wait.setText("Waiting for Home Assistant…");
            deviceColumn.addView(wait);
        }
    }

    /** Apple Home-style tile: white with dark text when on, translucent when off. */
    private View deviceTile(final HomeState.Device d) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setTag(d.id);
        tile.setFocusable(true);
        tile.setPadding(dp(18), dp(14), dp(18), dp(14));
        tile.setBackground(Ui.rounded(this, d.on ? 0xF2FFFFFF : 0x1FFFFFFF, 22));

        ImageView icon = new ImageView(this);
        icon.setImageResource(d.id.startsWith("switch.boiler") ? R.drawable.ic_flame : R.drawable.ic_bulb);
        icon.setColorFilter(d.on ? 0xFFFF9F0A : 0xB3FFFFFF, PorterDuff.Mode.SRC_IN);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView name = Ui.text(this, 17, 500);
        name.setShadowLayer(0, 0, 0, 0);
        name.setText(d.name);
        name.setTextColor(d.on ? 0xFF15171A : Color.WHITE);
        name.setPadding(0, dp(10), 0, 0);
        tile.addView(name);
        TextView st = Ui.text(this, 15, 400);
        st.setShadowLayer(0, 0, 0, 0);
        st.setText(d.on ? "On" : "Off");
        st.setTextColor(d.on ? 0xFF5B5F66 : 0x99FFFFFF);
        tile.addView(st);

        tile.setOnFocusChangeListener((v, has) ->
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start());
        tile.setOnClickListener(v -> {
            d.on = !d.on;
            HomeState.command("toggle", "entity_id", d.id);
            renderDevices();
        });
        return tile;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
