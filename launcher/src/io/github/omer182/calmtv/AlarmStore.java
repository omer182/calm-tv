package io.github.omer182.calmtv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/** The single wake-up alarm, kept in shared prefs. */
final class AlarmStore {
    static final int REPEAT_ONCE = 0;
    static final int REPEAT_DAILY = 1;
    static final int REPEAT_SUN_THU = 2;
    static final String[] REPEAT_NAMES = {"Once", "Every day", "Sun – Thu"};

    /** Sounds 0..4 are bundled tunes; the last one is a random morning song on YouTube. */
    static final String[] SOUND_NAMES = {
            "Morning Chimes", "Sunrise", "Marimba", "Music Box", "Classic Beep", "Random morning song"};
    static final int[] TUNES = {
            R.raw.tune_chimes, R.raw.tune_sunrise, R.raw.tune_marimba, R.raw.tune_musicbox, R.raw.tune_beep};
    static final int SOUND_RANDOM_SONG = 5;


    boolean enabled;
    int hour = 7;
    int minute = 30;
    int repeat = REPEAT_SUN_THU;
    int sound = SOUND_RANDOM_SONG;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("alarm", Context.MODE_PRIVATE);
    }

    static AlarmStore load(Context context) {
        SharedPreferences p = prefs(context);
        AlarmStore a = new AlarmStore();
        a.enabled = p.getBoolean("enabled", false);
        a.hour = p.getInt("hour", a.hour);
        a.minute = p.getInt("minute", a.minute);
        a.repeat = p.getInt("repeat", a.repeat);
        a.sound = p.getInt("sound", a.sound);
        return a;
    }

    void save(Context context) {
        prefs(context).edit()
                .putBoolean("enabled", enabled).putInt("hour", hour).putInt("minute", minute)
                .putInt("repeat", repeat).putInt("sound", sound)
                .apply();
    }

    String timeText() {
        return Ui.twoDigits(hour) + ":" + Ui.twoDigits(minute);
    }

    /** Next ring time after now, or -1 when off. */
    long nextTrigger() {
        if (!enabled) return -1;
        Calendar c = Calendar.getInstance();
        long now = c.getTimeInMillis();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (c.getTimeInMillis() > now && ringsOn(c.get(Calendar.DAY_OF_WEEK))) return c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return -1;
    }

    private boolean ringsOn(int dayOfWeek) {
        if (repeat != REPEAT_SUN_THU) return true;
        return dayOfWeek != Calendar.FRIDAY && dayOfWeek != Calendar.SATURDAY;
    }

    /** e.g. "Rings in 7 h 25 min". */
    String statusText() {
        long at = nextTrigger();
        if (at < 0) return "Alarm is off";
        long mins = (at - System.currentTimeMillis() + 59_999) / 60_000;
        long h = mins / 60;
        long m = mins % 60;
        if (h >= 24) return "Rings in " + (h / 24) + " d " + (h % 24) + " h";
        return "Rings in " + (h > 0 ? h + " h " : "") + m + " min";
    }
}
