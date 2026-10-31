package io.github.omer182.calmtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** A WebView playing one go2rtc camera with assets/camera.html, for the Cameras screen and the pop-up. */
final class CameraPlayer {
    private static final String TAG = "CalmCameras";

    private CameraPlayer() {
    }

    static WebView create(Context context, String go2rtc, String src, boolean live, boolean popup) {
        WebView web = new WebView(context);
        web.setFocusable(false);
        web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        // Full screen turns the sound on without a tap, which a TV can't give.
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            // Instead of the grey play button WebView shows before the first frame.
            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.i(TAG, message.message());
                return true;
            }
        });
        String html = asset(context, "camera.html").replace("$GO2RTC", JSONObject.quote(go2rtc))
                .replace("$SRC", JSONObject.quote(src)).replace("$LIVE", String.valueOf(live))
                .replace("$POPUP", String.valueOf(popup));
        // go2rtc's origin, so its WebSocket accepts the page.
        web.loadDataWithBaseURL(go2rtc + "/", html, "text/html", "utf-8", null);
        return web;
    }

    private static String asset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
