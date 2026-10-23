package io.github.omer182.calmtv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * Rings the wake-up alarm. The TV can't wake itself from standby, so Home Assistant sends a
 * Wake-on-LAN packet at the same time (see HaSync); this alarm then fires as soon as the TV is up.
 *
 * Test from adb: am broadcast -n io.github.omer182.calmtv/.AlarmReceiver -a io.github.omer182.calmtv.RING_NOW
 */
public class AlarmReceiver extends BroadcastReceiver {
    static final String TAG = "CalmAlarm";
    static final String ACTION_FIRE = "io.github.omer182.calmtv.FIRE";
    static final String ACTION_RING_NOW = "io.github.omer182.calmtv.RING_NOW";
    static final String ACTION_SET = "io.github.omer182.calmtv.SET_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_SET.equals(action)) {
            // am broadcast -n <pkg>/.AlarmReceiver -a <pkg>.SET_ALARM --ei hour 7 --ei minute 30
            //   --es repeat sun_thu|daily|once --ei sound 0-5 --ez enabled true
            AlarmStore alarm = AlarmStore.load(context);
            alarm.hour = intent.getIntExtra("hour", alarm.hour);
            alarm.minute = intent.getIntExtra("minute", alarm.minute);
            String repeat = intent.getStringExtra("repeat");
            if ("once".equals(repeat)) alarm.repeat = AlarmStore.REPEAT_ONCE;
            else if ("daily".equals(repeat)) alarm.repeat = AlarmStore.REPEAT_DAILY;
            else if ("sun_thu".equals(repeat)) alarm.repeat = AlarmStore.REPEAT_SUN_THU;
            alarm.sound = Math.max(0, Math.min(AlarmStore.SOUND_RANDOM_SONG, intent.getIntExtra("sound", alarm.sound)));
            alarm.enabled = intent.getBooleanExtra("enabled", true);
            alarm.save(context);
            HaSync.push(context, alarm);
            Log.i(TAG, "alarm set to " + alarm.timeText() + " enabled=" + alarm.enabled);
        }
        if (ACTION_FIRE.equals(action) || ACTION_RING_NOW.equals(action)) {
            Log.i(TAG, "ringing (" + action + ")");
            AlarmStore alarm = AlarmStore.load(context);
            if (ACTION_FIRE.equals(action) && alarm.repeat == AlarmStore.REPEAT_ONCE) {
                alarm.enabled = false;
                alarm.save(context);
                HaSync.push(context, alarm);
            }
            ring(context);
        }
        // Boot, app update, or after ringing: line up the next one.
        schedule(context);
    }

    /** (Re)schedules the next ring from the stored alarm, or cancels it when off. */
    static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent fire = new Intent(context, AlarmReceiver.class).setAction(ACTION_FIRE);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, fire, PendingIntent.FLAG_UPDATE_CURRENT);
        long at = AlarmStore.load(context).nextTrigger();
        if (at < 0) {
            am.cancel(pi);
            Log.i(TAG, "alarm off");
            return;
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        Log.i(TAG, "next ring at " + new java.util.Date(at));
    }

    @SuppressWarnings("deprecation")
    private static void ring(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "calm:alarm");
        lock.acquire(15_000);
        context.startActivity(new Intent(context, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
