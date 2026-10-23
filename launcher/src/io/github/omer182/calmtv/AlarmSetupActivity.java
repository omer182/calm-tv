package io.github.omer182.calmtv;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Remote-friendly alarm settings: Up/Down picks a row, Left/Right changes it, OK toggles or
 * previews the sound. Every change is saved, rescheduled and sent to Home Assistant.
 */
public class AlarmSetupActivity extends Activity {
    private static final int ROW_ON = 0, ROW_HOUR = 1, ROW_MINUTE = 2, ROW_REPEAT = 3, ROW_SOUND = 4;
    private static final String[] LABELS = {"Alarm", "Hour", "Minute", "Repeat", "Sound"};

    private final Handler handler = new Handler();
    private final TextView[] values = new TextView[LABELS.length];
    private final LinearLayout[] rows = new LinearLayout[LABELS.length];
    private AlarmStore alarm;
    private TextView bigTime;
    private TextView status;
    private MediaPlayer preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        alarm = AlarmStore.load(this);
        setContentView(buildLayout());
        refresh();
        rows[alarm.enabled ? ROW_HOUR : ROW_ON].requestFocus();
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF0E1013);
        int pad = Ui.dp(this, 64);
        root.setPadding(pad, pad, pad, pad);

        // Left: the big time and when it rings.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, 22, 500);
        title.setText("Wake-up alarm");
        title.setAlpha(0.7f);
        left.addView(title);
        bigTime = Ui.text(this, 120, 200);
        left.addView(bigTime);
        status = Ui.text(this, 20, 400);
        status.setAlpha(0.8f);
        left.addView(status);
        TextView hint = Ui.text(this, 15, 400);
        hint.setAlpha(0.5f);
        hint.setPadding(0, Ui.dp(this, 40), 0, 0);
        hint.setText("◀ ▶ change   ·   OK on / off, hear sound   ·   Back done");
        left.addView(hint);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        // Right: the settings rows.
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < LABELS.length; i++) {
            final int row = i;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setFocusable(true);
            r.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
            r.setBackground(Ui.rounded(this, 0x00000000, 14));
            TextView label = Ui.text(this, 20, 400);
            label.setText(LABELS[i]);
            label.setAlpha(0.7f);
            r.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView value = Ui.text(this, 22, 500);
            value.setGravity(Gravity.END);
            r.addView(value, new LinearLayout.LayoutParams(-2, -2));
            values[i] = value;
            r.setOnFocusChangeListener((v, has) -> v.setBackground(
                    Ui.rounded(this, has ? 0x33FFFFFF : 0x00000000, 14)));
            r.setOnKeyListener((v, keyCode, event) -> onRowKey(row, keyCode, event));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 64));
            lp.bottomMargin = Ui.dp(this, 8);
            list.addView(r, lp);
            rows[i] = r;
        }
        root.addView(list, new LinearLayout.LayoutParams(Ui.dp(this, 420), -1));
        return root;
    }

    private boolean onRowKey(int row, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int step;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) step = -1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) step = 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() > 0) return true;
            if (row == ROW_SOUND) {
                previewSound();
            } else {
                alarm.enabled = !alarm.enabled;
                changed();
            }
            return true;
        } else {
            return false;
        }
        // Holding the button moves faster through hours and minutes.
        int fast = event.getRepeatCount() > 6 ? 5 : 1;
        switch (row) {
            case ROW_ON:
                alarm.enabled = !alarm.enabled;
                break;
            case ROW_HOUR:
                alarm.hour = (alarm.hour + step + 24) % 24;
                alarm.enabled = true;
                break;
            case ROW_MINUTE:
                alarm.minute = (alarm.minute + step * fast + 60) % 60;
                alarm.enabled = true;
                break;
            case ROW_REPEAT:
                alarm.repeat = (alarm.repeat + step + AlarmStore.REPEAT_NAMES.length) % AlarmStore.REPEAT_NAMES.length;
                break;
            case ROW_SOUND:
                alarm.sound = (alarm.sound + step + AlarmStore.SOUND_NAMES.length) % AlarmStore.SOUND_NAMES.length;
                stopPreview();
                break;
        }
        changed();
        return true;
    }

    private void changed() {
        alarm.save(this);
        AlarmReceiver.schedule(this);
        refresh();
        // Send to Home Assistant once the buttons settle, not on every step.
        handler.removeCallbacks(sync);
        handler.postDelayed(sync, 1500);
        syncPending = true;
    }

    private boolean syncPending;
    private final Runnable sync = () -> {
        syncPending = false;
        HaSync.push(this, alarm);
    };

    private void refresh() {
        bigTime.setText(alarm.timeText());
        bigTime.setAlpha(alarm.enabled ? 1f : 0.35f);
        status.setText(alarm.statusText());
        values[ROW_ON].setText(alarm.enabled ? "On" : "Off");
        values[ROW_ON].setTextColor(alarm.enabled ? 0xFF7CE38B : Color.WHITE);
        values[ROW_HOUR].setText("‹  " + Ui.twoDigits(alarm.hour) + "  ›");
        values[ROW_MINUTE].setText("‹  " + Ui.twoDigits(alarm.minute) + "  ›");
        values[ROW_REPEAT].setText("‹  " + AlarmStore.REPEAT_NAMES[alarm.repeat] + "  ›");
        values[ROW_SOUND].setText("‹  " + AlarmStore.SOUND_NAMES[alarm.sound] + "  ›");
    }

    private void previewSound() {
        stopPreview();
        if (alarm.sound == AlarmStore.SOUND_RANDOM_SONG) {
            status.setText("Plays a random song from your Spotify morning playlist");
            handler.postDelayed(this::refresh, 3000);
            return;
        }
        preview = MediaPlayer.create(this, AlarmStore.TUNES[alarm.sound]);
        if (preview == null) return;
        preview.start();
        handler.postDelayed(this::stopPreview, 6000);
    }

    private void stopPreview() {
        if (preview != null) {
            preview.release();
            preview = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
        // Make sure the last change reaches Home Assistant even if Back came quickly.
        if (syncPending) {
            handler.removeCallbacks(sync);
            sync.run();
        }
    }
}
