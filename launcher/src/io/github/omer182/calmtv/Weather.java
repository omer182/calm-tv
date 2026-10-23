package io.github.omer182.calmtv;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Current conditions and the next days' forecast for the configured place, from Open-Meteo (free, no API key). */
class Weather {
    private static String url(Config c) {
        return "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + c.lat + "&longitude=" + c.lon
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=3";
    }
    private static final int DAYS = 3;
    // Until this hour, "tomorrow" means the day that has already started: we're still up from last night.
    private static final int NIGHT_ENDS_HOUR = 5;

    int nowTemp;
    int nowCode;
    boolean isDay;
    final String[] dates = new String[DAYS];
    final int[] max = new int[DAYS];
    final int[] min = new int[DAYS];
    final int[] codes = new int[DAYS];
    long fetchedAt;

    /** Blocking network call; run it off the main thread. Returns null on any failure. */
    static Weather fetch(Config config) {
        if (!config.hasWeather()) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url(config)).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            StringBuilder body = new StringBuilder();
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            for (String line; (line = in.readLine()) != null; ) body.append(line);
            in.close();

            JSONObject json = new JSONObject(body.toString());
            JSONObject current = json.getJSONObject("current");
            JSONObject daily = json.getJSONObject("daily");
            Weather w = new Weather();
            w.nowTemp = (int) Math.round(current.getDouble("temperature_2m"));
            w.nowCode = current.getInt("weather_code");
            w.isDay = current.getInt("is_day") == 1;
            JSONArray time = daily.getJSONArray("time");
            JSONArray maxes = daily.getJSONArray("temperature_2m_max");
            JSONArray mins = daily.getJSONArray("temperature_2m_min");
            JSONArray dayCodes = daily.getJSONArray("weather_code");
            for (int i = 0; i < DAYS && i < time.length(); i++) {
                w.dates[i] = time.getString(i);
                w.max[i] = (int) Math.round(maxes.getDouble(i));
                w.min[i] = (int) Math.round(mins.getDouble(i));
                w.codes[i] = dayCodes.getInt(i);
            }
            w.fetchedAt = System.currentTimeMillis();
            return w;
        } catch (Exception e) {
            return null;
        }
        finally {
            if (conn != null) conn.disconnect();
        }
    }

    void save(SharedPreferences prefs) {
        SharedPreferences.Editor e = prefs.edit()
                .putInt("w_nowTemp", nowTemp).putInt("w_nowCode", nowCode).putBoolean("w_isDay", isDay)
                .putLong("w_at", fetchedAt);
        for (int i = 0; i < DAYS; i++) {
            e.putString("w_date" + i, dates[i]).putInt("w_max" + i, max[i])
                    .putInt("w_min" + i, min[i]).putInt("w_code" + i, codes[i]);
        }
        e.apply();
    }

    static Weather load(SharedPreferences prefs) {
        if (!prefs.contains("w_date0")) return null;
        Weather w = new Weather();
        w.nowTemp = prefs.getInt("w_nowTemp", 0);
        w.nowCode = prefs.getInt("w_nowCode", 0);
        w.isDay = prefs.getBoolean("w_isDay", true);
        w.fetchedAt = prefs.getLong("w_at", 0);
        for (int i = 0; i < DAYS; i++) {
            w.dates[i] = prefs.getString("w_date" + i, null);
            w.max[i] = prefs.getInt("w_max" + i, 0);
            w.min[i] = prefs.getInt("w_min" + i, 0);
            w.codes[i] = prefs.getInt("w_code" + i, 0);
        }
        return w;
    }

    /**
     * Index of the day we call "tomorrow": the next calendar day in the evening, but the
     * current one after midnight (until 05:00). Matched by date so a forecast cached before
     * midnight still picks the right day. -1 if the forecast doesn't cover it.
     */
    int upcomingDay() {
        Calendar day = Calendar.getInstance();
        if (day.get(Calendar.HOUR_OF_DAY) >= NIGHT_ENDS_HOUR) day.add(Calendar.DAY_OF_MONTH, 1);
        String target = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day.getTime());
        for (int i = 0; i < DAYS; i++) {
            if (target.equals(dates[i])) return i;
        }
        return -1;
    }

    /** For the alarm screen in the morning, e.g. "Today: 26° and sunny", or null. */
    String todayPhrase() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
        for (int i = 0; i < DAYS; i++) {
            if (today.equals(dates[i])) return "Today: " + max[i] + "° and " + describe(codes[i], true).toLowerCase();
        }
        return null;
    }

    /** e.g. "Tomorrow  25° / 17°  Cloudy", or null if there's no forecast for that day. */
    String tomorrowLine() {
        int d = upcomingDay();
        if (d < 0) return null;
        return "Tomorrow  " + max[d] + "° / " + min[d] + "°  " + describe(codes[d], true);
    }

    /** e.g. "Now  18°  Partly cloudy  ·  Lisbon". */
    String nowLine(Config config) {
        String line = "Now  " + nowTemp + "°  " + describe(nowCode, isDay);
        return config.placeName.isEmpty() ? line : line + "  ·  " + config.placeName;
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    static String describe(int code, boolean day) {
        if (code == 0) return day ? "Sunny" : "Clear";
        if (code == 1) return day ? "Mostly sunny" : "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Foggy";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rainy";
        if (code >= 71 && code <= 77) return "Snowy";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Stormy";
        return "Mixed";
    }
}
