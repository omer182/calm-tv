package io.github.omer182.calmtv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * One camera in the top-right corner over whatever is playing, opened and closed with a spare
 * remote button (cameras.popup in calm.json). Drawn by SleepService, the only part of Calm that
 * may draw over other apps. Shows the camera's MJPEG stream if there is one, since the app
 * underneath usually holds the TV's only video decoder. No sound; closes itself after a few minutes.
 */
final class CameraPopup {
    private static final String TAG = "CalmCameras";
    // Forgotten on screen: close, so the server isn't transcoding through the whole film.
    private static final long CLOSE_AFTER_MS = 3 * 60_000;

    private final Context context;
    private final WindowManager wm;
    private final Handler handler = new Handler();
    private FrameLayout frame;
    private WebView player;

    CameraPopup(Context context, WindowManager wm) {
        this.context = context;
        this.wm = wm;
    }

    boolean isShowing() {
        return frame != null;
    }

    void toggle() {
        if (isShowing()) hide();
        else show();
    }

    void show() {
        Config config = Config.get(context);
        String[] cam = config.popupStream();
        if (cam == null || isShowing()) return;
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        // A third of the screen wide, 16:9.
        int width = screen.widthPixels / 3;
        int height = width * 9 / 16;

        frame = new FrameLayout(context);
        frame.setClipToOutline(true);
        frame.setBackground(Ui.rounded(context, Color.BLACK, 14));
        player = CameraPlayer.create(context, config.go2rtcUrl, cam[1], true, true, config.popupMjpeg);
        frame.addView(player, new FrameLayout.LayoutParams(-1, -1));
        TextView label = Ui.text(context, 15, 500);
        label.setText(cam[0]);
        int pad = Ui.dp(context, 10);
        label.setPadding(pad + Ui.dp(context, 4), pad, pad, pad);
        frame.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Ui.dp(context, 40);
        lp.y = Ui.dp(context, 32);
        wm.addView(frame, lp);
        handler.postDelayed(this::hide, CLOSE_AFTER_MS);
        Log.i(TAG, "pop-up on: " + (config.popupMjpeg.isEmpty() ? cam[1] + " live" : config.popupMjpeg + " MJPEG"));
    }

    void hide() {
        handler.removeCallbacksAndMessages(null);
        if (frame == null) return;
        wm.removeView(frame);
        player.destroy();
        frame = null;
        player = null;
        Log.i(TAG, "pop-up off");
    }
}
