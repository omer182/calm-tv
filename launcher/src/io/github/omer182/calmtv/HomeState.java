package io.github.omer182.calmtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The AC and smart-home devices as Home Assistant last reported them. Home Assistant pushes the
 * state to a tiny HTTP listener here (rest_command.calm_tv_push, see ha/calm_home.yaml), and
 * commands go back through a webhook, so the TV needs no Home Assistant login.
 */
final class HomeState {
    static final String TAG = "CalmHome";
    private static final int PORT = 8765;
    private static Context appContext;

    static final class Device {
        String id;
        String name;
        boolean on;
    }

    String acState = "off"; // off, cool, heat, fan_only
    int acTemp = 24;
    String acFan = "high";
    long acOffAt; // epoch millis, 0 = no timer
    final List<Device> devices = new ArrayList<>();
    long receivedAt;

    interface Listener {
        void onHomeState(HomeState state);
    }

    private static Listener listener;
    private static boolean serverStarted;

    static void setListener(Listener l) {
        listener = l;
    }

    // ---------------------------------------------------------------- parsing and cache

    static HomeState parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        HomeState s = new HomeState();
        JSONObject ac = root.getJSONObject("ac");
        s.acState = ac.optString("state", "off");
        s.acTemp = (int) Math.round(ac.optDouble("temp", 24));
        s.acFan = ac.optString("fan", "high");
        String offAt = ac.optString("off_at", "");
        if (offAt.length() >= 19) {
            // HA sends e.g. 2026-10-21T01:15:00+00:00
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            s.acOffAt = iso.parse(offAt.replaceAll("\\.\\d+", "")).getTime();
        }
        JSONArray devs = root.optJSONArray("devices");
        for (int i = 0; devs != null && i < devs.length(); i++) {
            JSONObject d = devs.getJSONObject(i);
            Device dev = new Device();
            dev.id = d.getString("id");
            dev.name = d.optString("name", dev.id);
            dev.on = d.optBoolean("on");
            s.devices.add(dev);
        }
        s.receivedAt = System.currentTimeMillis();
        return s;
    }

    static HomeState load(Context context) {
        String json = prefs(context).getString("state", null);
        if (json != null) {
            try {
                return parse(json);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- listener for HA pushes

    /** Starts the LAN listener once per process; safe to call repeatedly. */
    static synchronized void startServer(Context context) {
        appContext = context.getApplicationContext();
        if (serverStarted || !Config.get(context).hasHomeAssistant()) return;
        serverStarted = true;
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getByName("0.0.0.0"))) {
                Log.i(TAG, "listening on " + PORT);
                while (true) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        String body = readBody(socket);
                        OutputStream out = socket.getOutputStream();
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes("UTF-8"));
                        out.flush();
                        if (body == null) continue;
                        final HomeState state = parse(body);
                        prefs(app).edit().putString("state", body).apply();
                        main.post(() -> {
                            if (listener != null) listener.onHomeState(state);
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "bad push", e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "listener stopped", e);
                serverStarted = false;
            }
        }, "calm-home-listener");
        t.setDaemon(true);
        t.start();
    }

    private static String readBody(Socket socket) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        String line = in.readLine();
        if (line == null || !line.startsWith("POST")) return null;
        int length = 0;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        char[] buf = new char[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buf, read, length - read);
            if (n < 0) break;
            read += n;
        }
        return new String(buf, 0, read);
    }

    // ---------------------------------------------------------------- commands to HA

    static void send(final JSONObject command) {
        if (appContext == null) return;
        final String webhook = Config.get(appContext).homeWebhookUrl();
        if (webhook == null) return;
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(webhook).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream out = conn.getOutputStream();
                out.write(command.toString().getBytes("UTF-8"));
                out.close();
                Log.i(TAG, "sent " + command + " -> " + conn.getResponseCode());
            } catch (Exception e) {
                Log.w(TAG, "send failed " + command, e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    static void command(String cmd, String key, Object value) {
        try {
            JSONObject c = new JSONObject().put("cmd", cmd);
            if (key != null) c.put(key, value);
            send(c);
        } catch (Exception ignored) {
        }
    }
}
