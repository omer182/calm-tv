package io.github.omer182.calmtv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.List;

/**
 * Everything personal about a household, read from calm.json in the app's external files dir:
 *   adb push calm.json /sdcard/Android/data/io.github.omer182.calmtv/files/calm.json
 * See config/calm.example.json. Every field is optional; missing ones fall back to the defaults
 * here, and features that need a missing value (weather, Home card, Spotify) quietly switch off.
 */
final class Config {
    private static final String TAG = "CalmConfig";
    static final String FILE = "calm.json";

    /** Shown after "Good evening, " — empty means just "Good evening". */
    String names = "";

    /** Weather location; no location means no weather. */
    String placeName = "";
    double lat = Double.NaN;
    double lon = Double.NaN;

    /** Spotify playlist id for the random-song alarm; empty means a random YouTube morning song. */
    String spotifyPlaylist = "";
    int alarmVolume = 20;

    /** Home Assistant base URL (e.g. http://192.168.1.10:8123); empty hides the Home card. */
    String haUrl = "";
    String haAlarmWebhook = "";
    String haHomeWebhook = "";
    /** Shown under "Home" on the Home card and screen. */
    String room = "Home";

    /** Android keycode of the remote button used for the sleep timer; 0 means off. */
    int sleepKey = 0;

    /** Remote buttons that open an app, keycode -> package (e.g. 193 -> Netflix on Xiaomi remotes). */
    final Map<Integer, String> buttons = new HashMap<>();

    /** go2rtc base URL (e.g. http://192.168.1.10:1984) for the Cameras card; empty hides it. */
    String go2rtcUrl = "";
    /** Cameras in screen order: name shown on screen, go2rtc stream name. */
    final List<String[]> cameras = new ArrayList<>();

    /** Package names for the first-run dock, before the user rearranges it. */
    final List<String> dock = new ArrayList<>();

    private long loadedFrom = -1;
    private static Config current;

    static synchronized Config get(Context context) {
        File f = file(context);
        long stamp = f.exists() ? f.lastModified() : 0;
        if (current == null || current.loadedFrom != stamp) {
            current = load(f);
            current.loadedFrom = stamp;
        }
        return current;
    }

    static File file(Context context) {
        File dir = context.getApplicationContext().getExternalFilesDir(null);
        return new File(dir != null ? dir : context.getFilesDir(), FILE);
    }

    private static Config load(File f) {
        Config c = new Config();
        Collections.addAll(c.dock,"com.netflix.ninja", "com.google.android.youtube.tv", "com.plexapp.android");
        if (!f.exists()) return c;
        try {
            byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            JSONObject j = new JSONObject(new String(bytes, "UTF-8"));
            c.names = j.optString("names", c.names);
            JSONObject loc = j.optJSONObject("location");
            if (loc != null) {
                c.placeName = loc.optString("name", "");
                c.lat = loc.optDouble("lat", Double.NaN);
                c.lon = loc.optDouble("lon", Double.NaN);
            }
            JSONObject alarm = j.optJSONObject("alarm");
            if (alarm != null) {
                c.spotifyPlaylist = alarm.optString("spotifyPlaylist", "");
                c.alarmVolume = alarm.optInt("volume", c.alarmVolume);
            }
            JSONObject ha = j.optJSONObject("homeAssistant");
            if (ha != null) {
                c.haUrl = ha.optString("url", "").replaceAll("/+$", "");
                c.haAlarmWebhook = ha.optString("alarmWebhook", "");
                c.haHomeWebhook = ha.optString("homeWebhook", "");
                c.room = ha.optString("room", c.room);
            }
            c.sleepKey = j.optInt("sleepKey", c.sleepKey);
            JSONObject buttons = j.optJSONObject("buttons");
            if (buttons != null) {
                for (Iterator<String> it = buttons.keys(); it.hasNext(); ) {
                    String key = it.next();
                    try {
                        c.buttons.put(Integer.parseInt(key), buttons.getString(key));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            JSONObject cams = j.optJSONObject("cameras");
            if (cams != null) {
                c.go2rtcUrl = cams.optString("go2rtc", "").replaceAll("/+$", "");
                JSONArray streams = cams.optJSONArray("streams");
                for (int i = 0; streams != null && i < streams.length(); i++) {
                    JSONObject cam = streams.getJSONObject(i);
                    c.cameras.add(new String[]{cam.optString("name", "Camera"), cam.getString("src")});
                }
            }
            JSONArray dock = j.optJSONArray("dock");
            if (dock != null) {
                c.dock.clear();
                for (int i = 0; i < dock.length(); i++) c.dock.add(dock.getString(i));
            }
            Log.i(TAG, "loaded " + f);
        } catch (Exception e) {
            Log.w(TAG, "bad " + f + ", using defaults", e);
        }
        return c;
    }

    boolean hasWeather() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    boolean hasHomeAssistant() {
        return !haUrl.isEmpty();
    }

    boolean hasCameras() {
        return !go2rtcUrl.isEmpty() && !cameras.isEmpty();
    }

    String alarmWebhookUrl() {
        return haUrl.isEmpty() || haAlarmWebhook.isEmpty() ? null : haUrl + "/api/webhook/" + haAlarmWebhook;
    }

    String homeWebhookUrl() {
        return haUrl.isEmpty() || haHomeWebhook.isEmpty() ? null : haUrl + "/api/webhook/" + haHomeWebhook;
    }

    /** "Good evening, Alex & Sam" or just "Good evening". */
    String greet(String partOfDay, String separator) {
        return names.isEmpty() ? partOfDay : partOfDay + separator + names;
    }
}
