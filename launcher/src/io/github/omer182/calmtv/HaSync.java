package io.github.omer182.calmtv;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tells Home Assistant (on the homeserver) when the alarm rings, so it can send a Wake-on-LAN
 * packet at that time: the TV can't wake itself from standby. See ha/calm_alarm.yaml.
 */
final class HaSync {
    private HaSync() {
    }

    static void push(Context context, final AlarmStore alarm) {
        final String webhook = Config.get(context).alarmWebhookUrl();
        if (webhook == null) return; // no Home Assistant: the alarm only rings if the TV is on
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject()
                        .put("enabled", alarm.enabled)
                        .put("time", alarm.timeText() + ":00")
                        .put("repeat", alarm.repeat == AlarmStore.REPEAT_SUN_THU ? "sun_thu"
                                : alarm.repeat == AlarmStore.REPEAT_DAILY ? "daily" : "once");
                conn = (HttpURLConnection) new URL(webhook).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream out = conn.getOutputStream();
                out.write(body.toString().getBytes("UTF-8"));
                out.close();
                Log.i(AlarmReceiver.TAG, "HA sync " + body + " -> " + conn.getResponseCode());
            } catch (Exception e) {
                Log.w(AlarmReceiver.TAG, "HA sync failed", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
