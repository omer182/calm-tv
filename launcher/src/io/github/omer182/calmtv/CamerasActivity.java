package io.github.omer182.calmtv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Live view of the go2rtc cameras in calm.json, two per row. Left/Right picks a camera, OK shows
 * it full screen with sound, Back goes back. Streams only run while this screen is open.
 */
public class CamerasActivity extends Activity {
    private static final int COLUMNS = 2;
    // Nobody watching: close, so the TV doesn't keep the server transcoding all night.
    private static final long IDLE_CLOSE_MS = 15 * 60_000;

    private final Handler handler = new Handler();
    private final List<FrameLayout> tiles = new ArrayList<>();
    private final List<WebView> players = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private LinearLayout grid;
    private TextView title;
    private int selected;
    private boolean fullScreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        select(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(idleClose, IDLE_CLOSE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(idleClose);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Home, another app or standby: stop the streams rather than keep them in the background.
        finish();
    }

    @Override
    protected void onDestroy() {
        for (WebView player : players) player.destroy();
        super.onDestroy();
    }

    private final Runnable idleClose = this::finish;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        handler.removeCallbacks(idleClose);
        handler.postDelayed(idleClose, IDLE_CLOSE_MS);
        int code = event.getKeyCode();
        // The players aren't focusable, so the arrows, OK and Back are all handled here, on release.
        boolean ours = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT);
        if (!ours) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_UP) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!fullScreen) select(selected - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!fullScreen) select(selected + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!fullScreen) select(selected - COLUMNS);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!fullScreen) select(selected + COLUMNS);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                setFullScreen(!fullScreen);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (fullScreen) setFullScreen(false);
                else finish();
                return true;
            default:
                return true;
        }
    }

    // ---------------------------------------------------------------- layout

    private View buildLayout() {
        Config config = Config.get(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1013);
        root.setPadding(dp(56), dp(36), dp(56), dp(36));
        root.setClipChildren(false);

        title = Ui.text(this, 30, 500);
        title.setText("Cameras");
        title.setPadding(0, 0, 0, dp(22));
        root.addView(title);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);
        grid.setClipChildren(false);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        String template = asset("camera.html");
        LinearLayout row = null;
        for (int i = 0; i < config.cameras.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER);
                row.setClipChildren(false);
                grid.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
            }
            String[] cam = config.cameras.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);
            // Keep the tile 16:9 inside its cell whenever the cell changes size (e.g. full screen).
            cell.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) v.post(this::fitTiles);
            });
            FrameLayout tile = new FrameLayout(this);
            tile.setClipToOutline(true);
            tile.setBackground(Ui.rounded(this, Color.BLACK, 14));
            tile.setForeground(ring());
            WebView player = player(template, config.go2rtcUrl, cam[1]);
            tile.addView(player, new FrameLayout.LayoutParams(-1, -1));
            cell.addView(tile, new LinearLayout.LayoutParams(-1, 0, 1f));
            TextView label = Ui.text(this, 18, 500);
            label.setText(cam[0]);
            label.setPadding(dp(4), dp(12), 0, 0);
            cell.addView(label);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1f);
            clp.setMargins(dp(14), dp(10), dp(14), dp(10));
            row.addView(cell, clp);
            tiles.add(tile);
            players.add(player);
            labels.add(label);
        }
        return root;
    }

    private void fitTiles() {
        for (FrameLayout tile : tiles) {
            View cell = (View) tile.getParent();
            View label = labels.get(tiles.indexOf(tile));
            int labelHeight = label.getVisibility() == View.VISIBLE ? label.getHeight() : 0;
            int width = cell.getWidth();
            int height = cell.getHeight() - labelHeight;
            if (width * 9 > height * 16) width = height * 16 / 9;
            else height = width * 9 / 16;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            if (lp.width != width || lp.height != height) {
                lp.width = width;
                lp.height = height;
                lp.weight = 0;
                tile.setLayoutParams(lp);
            }
        }
    }

    private WebView player(String template, String go2rtc, String src) {
        WebView web = new WebView(this);
        web.setFocusable(false);
        web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        // Full screen turns the sound on without a tap, which a TV can't give.
        settings.setMediaPlaybackRequiresUserGesture(false);
        String html = template.replace("$GO2RTC", JSONObject.quote(go2rtc)).replace("$SRC", JSONObject.quote(src));
        // go2rtc's origin, so its WebSocket accepts the page.
        web.loadDataWithBaseURL(go2rtc + "/", html, "text/html", "utf-8", null);
        return web;
    }

    /** The same white focus ring as the home screen's cards. */
    private GradientDrawable ring() {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setStroke(dp(3), Color.WHITE);
        ring.setAlpha(0);
        return ring;
    }

    private void select(int index) {
        if (index < 0 || index >= tiles.size()) return;
        selected = index;
        for (int i = 0; i < tiles.size(); i++) {
            boolean on = i == selected;
            tiles.get(i).getForeground().setAlpha(on ? 255 : 0);
            ((View) tiles.get(i).getParent()).animate().scaleX(on ? 1.03f : 1f).scaleY(on ? 1.03f : 1f)
                    .setDuration(160).start();
            labels.get(i).setAlpha(on ? 1f : 0.6f);
        }
    }

    /** One camera filling the screen with its sound on, or back to all of them muted. */
    private void setFullScreen(boolean on) {
        if (tiles.isEmpty()) return;
        fullScreen = on;
        View selectedCell = (View) tiles.get(selected).getParent();
        View selectedRow = (View) selectedCell.getParent();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View row = grid.getChildAt(i);
            row.setVisibility(on && row != selectedRow ? View.GONE : View.VISIBLE);
        }
        for (int i = 0; i < tiles.size(); i++) {
            View cell = (View) tiles.get(i).getParent();
            cell.setVisibility(on && cell != selectedCell ? View.GONE : View.VISIBLE);
            labels.get(i).setVisibility(on ? View.GONE : View.VISIBLE);
            tiles.get(i).getForeground().setAlpha(!on && i == selected ? 255 : 0);
            players.get(i).evaluateJavascript("setMuted(" + !(on && i == selected) + ")", null);
        }
        selectedCell.setScaleX(1f);
        selectedCell.setScaleY(1f);
        title.setVisibility(on ? View.GONE : View.VISIBLE);
        View root = (View) grid.getParent();
        int pad = on ? 0 : dp(56);
        root.setPadding(pad, on ? 0 : dp(36), pad, on ? 0 : dp(36));
        ViewGroup.MarginLayoutParams clp = (ViewGroup.MarginLayoutParams) selectedCell.getLayoutParams();
        clp.setMargins(on ? 0 : dp(14), on ? 0 : dp(10), on ? 0 : dp(14), on ? 0 : dp(10));
        selectedCell.setLayoutParams(clp);
        if (!on) select(selected);
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
