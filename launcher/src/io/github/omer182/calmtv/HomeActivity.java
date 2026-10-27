package io.github.omer182.calmtv;

import android.animation.Animator;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal Apple TV-style home screen: rotating nature wallpapers, a clock and a dock of favourite apps. */
public class HomeActivity extends Activity {

    private static final long WALLPAPER_INTERVAL_MS = 40_000;
    private static final long CROSSFADE_MS = 2_500;
    private static final long IDLE_HIDE_MS = 90_000;

    private final Handler handler = new Handler();
    private final List<Integer> wallpapers = new ArrayList<>();
    private int wallpaperIndex;

    private ImageView[] wallpaperViews;
    private int frontView;
    private ObjectAnimator zoom;
    private View chrome;
    private GridLayout dock;
    private static final int DOCK_COLUMNS = 4;
    // One row: 99dp card + 11dp margin above and below. The padding leaves room for the focus zoom.
    private static final int DOCK_ROW = 121;
    private static final int DOCK_PAD = 20;
    private int dockRestTop; // dp from the top of the screen to the grid, first-row view
    private View gridScrim;
    private View clockBox;
    private int dockRow = -1;
    private FrameLayout allAppsPanel;
    private LinearLayout allAppsGrid;
    private ScrollView allAppsScroll;
    private boolean chromeHidden;
    private TextView greeting;
    private View curtain;
    private TextView weatherNow;
    private TextView weatherTomorrow;
    private Weather weather;
    private boolean greetingPending = true;
    private boolean resumed;

    private SharedPreferences prefs;

    // Going into standby: black out now, because the panel shows the last frame before
    // SCREEN_ON arrives. Coming back: fade from black into the greeting.
    private final BroadcastReceiver screenState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            greetingPending = true;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                closeAllApps();
                greeting.animate().cancel();
                greeting.setVisibility(View.GONE);
                chrome.animate().cancel();
                chrome.setAlpha(0f);
                curtain.animate().cancel();
                curtain.setAlpha(1f);
                curtain.setVisibility(View.VISIBLE);
            } else {
                refreshWeather.run();
                if (resumed) showGreeting();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dock", MODE_PRIVATE);
        loadWallpaperIds();
        setContentView(buildRoot());
        showWallpaper(false);
        weather = Weather.load(prefs);
        applyWeather();
        refreshWeather.run();
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenState, screen);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(screenState);
        handler.removeCallbacks(refreshWeather);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ensureSleepService();
        HomeState.startServer(this);
        // Re-pick "tomorrow" in case midnight passed since the last fetch.
        applyWeather();
        rebuildDock();
        if (allAppsPanel.getVisibility() == View.VISIBLE) rebuildAllApps();
        handler.removeCallbacks(nextWallpaper);
        handler.postDelayed(nextWallpaper, WALLPAPER_INTERVAL_MS);
        // Brought to the front on standby (SleepService): stay black until SCREEN_ON greets.
        if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) return;
        if (greetingPending) showGreeting();
        if (greeting.getVisibility() != View.VISIBLE) {
            showChrome();
            liftCurtain(300);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        hideGreeting();
        handler.removeCallbacks(nextWallpaper);
        handler.removeCallbacks(hideChrome);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close the app grid and go back to the dock.
        closeAllApps();
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (allAppsPanel.getVisibility() == View.VISIBLE) closeAllApps();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (greeting.getVisibility() == View.VISIBLE) {
            // Any key skips the welcome.
            if (event.getAction() == KeyEvent.ACTION_UP) hideGreeting();
            return true;
        }
        if (chromeHidden) {
            // The first key press after idling only wakes the UI.
            if (event.getAction() == KeyEvent.ACTION_UP) showChrome();
            return true;
        }
        handler.removeCallbacks(hideChrome);
        handler.postDelayed(hideChrome, IDLE_HIDE_MS);
        if (editingKey != null) return onEditKey(event);
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------- layout

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        wallpaperViews = new ImageView[2];
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            root.addView(iv, match());
            wallpaperViews[i] = iv;
        }

        FrameLayout chromeLayer = new FrameLayout(this);
        chrome = chromeLayer;
        root.addView(chromeLayer, match());

        // Soft shading so text and tiles stay readable on bright photos.
        View topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));
        chromeLayer.addView(topShade, new FrameLayout.LayoutParams(-1, dp(200), Gravity.TOP));
        View bottomShade = new View(this);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000}));
        chromeLayer.addView(bottomShade, new FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        styleText(time, 64, inter(200));
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        styleText(date, 18, inter(400));
        date.setAlpha(0.85f);
        weatherNow = new TextView(this);
        styleText(weatherNow, 18, inter(400));
        weatherNow.setAlpha(0.85f);
        weatherNow.setPadding(0, dp(14), 0, 0);
        weatherTomorrow = new TextView(this);
        styleText(weatherTomorrow, 16, inter(400));
        weatherTomorrow.setAlpha(0.7f);
        weatherTomorrow.setPadding(0, dp(2), 0, 0);
        for (TextView tv : new TextView[]{time, date, weatherNow, weatherTomorrow}) {
            tv.setGravity(Gravity.END);
            clockBox.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        }
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(28), dp(56), 0);
        chromeLayer.addView(clockBox, clp);
        this.clockBox = clockBox;

        // Darkens the photo when you scroll down into the card grid (Apple TV style).
        gridScrim = new View(this);
        gridScrim.setBackgroundColor(0xE60B0C0E);
        gridScrim.setAlpha(0f);
        chromeLayer.addView(gridScrim, 0, match());

        // Cards in rows of 4. The first row sits where the dock always was; the other rows wait
        // just below the screen and come into view as the whole page moves up (dockFocused).
        dock = new GridLayout(this);
        dock.setColumnCount(DOCK_COLUMNS);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(0, dp(DOCK_PAD), 0, dp(DOCK_PAD));
        float screenDp = getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dockRestTop = Math.round(screenDp) - 48 - DOCK_ROW - DOCK_PAD;
        glp.topMargin = dp(dockRestTop);
        chromeLayer.addView(dock, glp);

        allAppsPanel = buildAllAppsPanel();
        root.addView(allAppsPanel, match());

        greeting = new TextView(this);
        styleText(greeting, 64, inter(200));
        greeting.setGravity(Gravity.CENTER);
        greeting.setBackgroundColor(0x66000000);
        greeting.setVisibility(View.GONE);
        root.addView(greeting, match());

        // Starts black so a cold boot also fades in instead of popping.
        curtain = new View(this);
        curtain.setBackgroundColor(Color.BLACK);
        root.addView(curtain, match());
        return root;
    }

    private void liftCurtain(long durationMs) {
        if (curtain.getVisibility() != View.VISIBLE) return;
        curtain.animate().alpha(0f).setDuration(durationMs).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                curtain.setVisibility(View.GONE);
                curtain.animate().setListener(null);
            }
        });
    }

    // ---------------------------------------------------------------- greeting

    private final Runnable greetingTimeout = new Runnable() {
        @Override
        public void run() {
            hideGreeting();
        }
    };

    // ---------------------------------------------------------------- weather

    private static final long WEATHER_REFRESH_MS = 30 * 60_000;

    private final Runnable refreshWeather = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            new Thread(() -> {
                final Weather w = Weather.fetch(Config.get(HomeActivity.this));
                handler.post(() -> {
                    if (w != null) {
                        w.save(prefs);
                        weather = w;
                        applyWeather();
                    }
                    // Retry sooner after a failure, e.g. Wi-Fi still reconnecting after standby.
                    handler.postDelayed(refreshWeather, w != null ? WEATHER_REFRESH_MS : 60_000);
                });
            }).start();
        }
    };

    private void applyWeather() {
        // No location in calm.json: no weather, even if an old forecast is cached.
        boolean on = weather != null && Config.get(this).hasWeather();
        String now = on ? weather.nowLine(Config.get(this)) : null;
        String upcoming = on ? weather.tomorrowLine() : null;
        weatherNow.setVisibility(now != null ? View.VISIBLE : View.GONE);
        weatherTomorrow.setVisibility(upcoming != null ? View.VISIBLE : View.GONE);
        weatherNow.setText(now);
        weatherTomorrow.setText(upcoming);
    }

    private void showGreeting() {
        greetingPending = false;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(Config.get(this).greet(Ui.partOfDay(hour), ",\n"));
        greeting.bringToFront();
        greeting.animate().cancel();
        greeting.setAlpha(0f);
        greeting.setVisibility(View.VISIBLE);
        curtain.bringToFront();
        chrome.animate().cancel();
        chrome.setAlpha(0f);
        // Photo comes up out of black, the words follow just behind it.
        liftCurtain(1500);
        greeting.animate().alpha(1f).setStartDelay(500).setDuration(1300).setListener(null);
        handler.removeCallbacks(greetingTimeout);
        handler.postDelayed(greetingTimeout, 5_000);
    }

    private void hideGreeting() {
        handler.removeCallbacks(greetingTimeout);
        if (greeting.getVisibility() != View.VISIBLE) return;
        greeting.animate().alpha(0f).setStartDelay(0).setDuration(900).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                greeting.setVisibility(View.GONE);
                greeting.animate().setListener(null);
            }
        });
        showChrome();
    }

    private FrameLayout buildAllAppsPanel() {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(0xE6101214);
        panel.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(36), dp(56), 0);

        TextView title = new TextView(this);
        styleText(title, 30, inter(500));
        title.setText("All Apps");
        col.addView(title);
        TextView hint = new TextView(this);
        styleText(hint, 15, inter(400));
        hint.setAlpha(0.6f);
        hint.setText("Hold OK on an app to add it to the dock or remove it");
        hint.setPadding(0, dp(4), 0, dp(8));
        col.addView(hint);

        ScrollView scroll = new ScrollView(this) {
            // Focus changes don't jump-scroll; the row focus listener animates instead.
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0;
            }
        };
        allAppsScroll = scroll;
        scroll.setClipToPadding(false);
        // Room for a focused tile to grow without being clipped at the edges.
        scroll.setPadding(dp(8), dp(12), dp(8), dp(48));
        scroll.setVerticalScrollBarEnabled(false);
        allAppsGrid = new LinearLayout(this);
        allAppsGrid.setOrientation(LinearLayout.VERTICAL);
        allAppsGrid.setClipChildren(false);
        scroll.addView(allAppsGrid);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        panel.addView(col, match());
        return panel;
    }

    // ---------------------------------------------------------------- dock

    // Built-in cards live in the same order list as apps, under these keys.
    private static final String CARD_HOME = "@home", CARD_CAMERAS = "@cameras", CARD_ALARM = "@alarm", CARD_ALL = "@all",
            CARD_SETTINGS = "@settings";
    private static final String[] CARDS = {CARD_HOME, CARD_CAMERAS, CARD_ALARM, CARD_ALL, CARD_SETTINGS};

    private void rebuildDock() {
        View focused = getCurrentFocus();
        Object focusedTag = editingKey != null ? editingKey : focused != null ? focused.getTag() : null;
        dock.removeAllViews();
        Map<String, App> installed = installedApps();
        for (final String key : dockOrder()) {
            View tile;
            if (hiddenCard(key)) continue;
            if (CARD_HOME.equals(key)) tile = homeTile();
            else if (CARD_CAMERAS.equals(key)) tile = camerasTile();
            else if (CARD_ALARM.equals(key)) tile = alarmTile();
            else if (CARD_ALL.equals(key)) {
                // Dark glass with a grid of colourful app squares, like an app folder.
                tile = iconCard(CARD_ALL, "All Apps", new int[]{0xFF3A3A3C, 0xFF1C1C1E}, R.drawable.ic_apps_color, 62, 0,
                        v -> openAllApps());
            } else if (CARD_SETTINGS.equals(key)) {
                // Apple's silver Settings icon with dark gears.
                tile = iconCard(CARD_SETTINGS, "Settings", new int[]{0xFFF2F2F7, 0xFFD1D1D6, 0xFF9C9CA1},
                        R.drawable.ic_gears, 68, 0xFF3A3A3C, v -> launch(new Intent(Settings.ACTION_SETTINGS)));
            } else {
                App app = installed.get(key);
                if (app == null) continue;
                tile = appTile(app, true);
            }
            // Hold OK on any card to move it (or remove an app).
            tile.setOnLongClickListener(v -> {
                startEditing(key);
                return true;
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(tile.getLayoutParams());
            lp.setMargins(dp(13), dp(11), dp(13), dp(11));
            final View.OnFocusChangeListener grow = tile.getOnFocusChangeListener();
            tile.setOnFocusChangeListener((v, has) -> {
                grow.onFocusChange(v, has);
                if (has) dockFocused(v);
            });
            dock.addView(tile, lp);
        }

        View toFocus = dock.getChildAt(0);
        for (int i = 0; i < dock.getChildCount(); i++) {
            if (dock.getChildAt(i).getTag() != null && dock.getChildAt(i).getTag().equals(focusedTag)) {
                toFocus = dock.getChildAt(i);
            }
        }
        // Jump straight to the focused card's row without animating (e.g. after a move in edit mode).
        dockRow = -1;
        showRow(toFocus != null ? dock.indexOfChild(toFocus) / DOCK_COLUMNS : 0, false);
        if (editingKey != null && toFocus != null) showEditOverlay((FrameLayout) toFocus);
        if (allAppsPanel.getVisibility() != View.VISIBLE && toFocus != null) toFocus.requestFocus();
    }

    private void dockFocused(View card) {
        int index = dock.indexOfChild(card);
        if (index >= 0) showRow(index / DOCK_COLUMNS, true);
    }

    private static final long PAGE_MS = 520;
    private static final int GRID_TOP = 64; // dp from the top of the screen in grid view

    /**
     * Row 0 is the normal home screen: photo, clock and one row of cards at the bottom. Any lower
     * row scrolls the whole page down like Apple TV: the clock and photo slide up and away, the
     * background darkens, and all rows are shown with the focused one kept on screen.
     */
    private void showRow(int row, boolean animate) {
        if (row == dockRow) return;
        dockRow = row;
        // The grid starts near the bottom of the screen, so give it its full height explicitly;
        // otherwise its lower rows are cut to the space left below its starting point.
        int rows = (dock.getChildCount() + DOCK_COLUMNS - 1) / DOCK_COLUMNS;
        int fullHeight = dp(2 * DOCK_PAD) + rows * dp(DOCK_ROW);
        ViewGroup.LayoutParams dlp = dock.getLayoutParams();
        if (dlp.height != fullHeight) {
            dlp.height = fullHeight;
            ((FrameLayout.LayoutParams) dlp).bottomMargin = -fullHeight;
            dock.setLayoutParams(dlp);
        }
        boolean grid = row > 0;
        float screen = getResources().getDisplayMetrics().heightPixels;
        float shift = 0;
        if (grid) {
            // Bring the grid up to the top, then further if the focused row would fall off the bottom.
            shift = dp(GRID_TOP) - dp(dockRestTop);
            float rowBottom = dp(dockRestTop) + shift + dp(DOCK_PAD) + (row + 1) * dp(DOCK_ROW);
            float limit = screen - dp(40);
            if (rowBottom > limit) shift -= rowBottom - limit;
        }
        float clockShift = grid ? -dp(320) : 0;
        float photoShift = 0; // the photo only darkens; moving it would uncover black edges
        for (int i = 0; i < dock.getChildCount(); i++) {
            View card = dock.getChildAt(i);
            float alpha = grid || i < DOCK_COLUMNS ? 1f : 0f;
            if (animate) card.animate().alpha(alpha).setDuration(PAGE_MS).start();
            else card.setAlpha(alpha);
        }
        if (!animate) {
            dock.setTranslationY(shift);
            clockBox.setTranslationY(clockShift);
            clockBox.setAlpha(grid ? 0f : 1f);
            gridScrim.setAlpha(grid ? 1f : 0f);
            for (ImageView wp : wallpaperViews) wp.setTranslationY(photoShift);
            return;
        }
        DecelerateInterpolator ease = new DecelerateInterpolator(2.2f);
        dock.animate().translationY(shift).setInterpolator(ease).setDuration(PAGE_MS).start();
        clockBox.animate().translationY(clockShift).alpha(grid ? 0f : 1f).setInterpolator(ease).setDuration(PAGE_MS).start();
        gridScrim.animate().alpha(grid ? 1f : 0f).setDuration(PAGE_MS).start();
        for (ImageView wp : wallpaperViews) {
            ObjectAnimator.ofFloat(wp, View.TRANSLATION_Y, photoShift).setDuration(PAGE_MS).start();
        }
    }

    private ObjectAnimator scrollAnim;

    /** Eased scroll (fast start, soft landing) instead of Android's instant jump. */
    private void glide(View view, String property, int to) {
        if (scrollAnim != null) scrollAnim.cancel();
        scrollAnim = ObjectAnimator.ofInt(view, property, to);
        scrollAnim.setInterpolator(new DecelerateInterpolator(2.2f));
        scrollAnim.setDuration(420);
        scrollAnim.start();
    }

    /** Dock cards in order: app packages plus the built-in card keys. */
    private List<String> dockOrder() {
        List<String> list = new ArrayList<>();
        String saved = prefs.getString("order", null);
        if (saved != null) {
            if (!saved.isEmpty()) Collections.addAll(list, saved.split(","));
        } else {
            // First run after this change: the old app list, then the built-in cards.
            String apps = prefs.getString("packages", null);
            // First run: the dock from calm.json. It may place the built-in cards too
            // ("@home", "@cameras", "@alarm", "@all", "@settings"); any it leaves out go at the end.
            if (apps == null) list.addAll(Config.get(this).dock);
            else if (!apps.isEmpty()) Collections.addAll(list, apps.split(","));
        }
        // A built-in card can't go missing (they can't be removed, only moved).
        for (String card : CARDS) if (!list.contains(card)) list.add(card);
        return list;
    }

    private void saveOrder(List<String> list) {
        prefs.edit().putString("order", TextUtils.join(",", list)).apply();
    }

    private void toggleDock(App app) {
        List<String> list = dockOrder();
        boolean removed = list.remove(app.pkg);
        if (!removed) {
            // New apps go in before the built-in cards.
            int at = list.size();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).startsWith("@")) {
                    at = i;
                    break;
                }
            }
            list.add(at, app.pkg);
        }
        saveOrder(list);
        Toast.makeText(this, (removed ? "Removed from dock: " : "Added to dock: ") + app.label,
                Toast.LENGTH_SHORT).show();
        rebuildDock();
    }

    // ---------------------------------------------------------------- dock editing

    private String editingKey;
    private boolean ignoreNextOkUp;
    private boolean removeSelected;
    private TextView editX;

    private void startEditing(String key) {
        editingKey = key;
        removeSelected = false;
        // The OK release that ends this long press must not count as "done".
        ignoreNextOkUp = true;
        rebuildDock();
    }

    private void stopEditing() {
        editingKey = null;
        editX = null;
        rebuildDock();
    }

    /** Dark scrim over the card with ‹ ✕ › (no ✕ for built-in cards, which can't be removed). */
    private void showEditOverlay(FrameLayout tile) {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0x99000000);
        TextView left = new TextView(this);
        styleText(left, 30, inter(400));
        left.setText("‹");
        overlay.addView(left);
        editX = null;
        if (!editingKey.startsWith("@")) {
            editX = new TextView(this);
            styleText(editX, 20, inter(600));
            editX.setText("✕");
            editX.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(dp(40), dp(40));
            xlp.leftMargin = dp(22);
            xlp.rightMargin = dp(22);
            overlay.addView(editX, xlp);
            styleEditX();
        } else {
            overlay.addView(new View(this), new LinearLayout.LayoutParams(dp(60), 1));
        }
        TextView right = new TextView(this);
        styleText(right, 30, inter(400));
        right.setText("›");
        overlay.addView(right);
        tile.addView(overlay, match());
        tile.animate().scaleX(1.18f).scaleY(1.18f).setDuration(120).start();
    }

    /** The ✕ is a soft circle, or solid red once selected with Up/Down. */
    private void styleEditX() {
        if (editX == null) return;
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(removeSelected ? 0xFFFF453A : 0x40FFFFFF);
        if (removeSelected) circle.setStroke(dp(2), Color.WHITE);
        editX.setBackground(circle);
        editX.animate().scaleX(removeSelected ? 1.2f : 1f).scaleY(removeSelected ? 1.2f : 1f).setDuration(100).start();
    }

    /**
     * While editing: Left/Right move the card, Up/Down select the ✕, OK removes when the ✕ is
     * selected and otherwise finishes, Back finishes.
     */
    private boolean onEditKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (ok && ignoreNextOkUp) {
                ignoreNextOkUp = false;
            } else if (ok && removeSelected && !editingKey.startsWith("@")) {
                List<String> list = dockOrder();
                list.remove(editingKey);
                saveOrder(list);
                stopEditing();
            } else if (ok || code == KeyEvent.KEYCODE_BACK) {
                stopEditing();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            List<String> list = visibleOrder();
            int i = list.indexOf(editingKey);
            int j = i + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            if (i >= 0 && j >= 0 && j < list.size()) {
                Collections.swap(list, i, j);
                saveOrder(list);
                removeSelected = false;
                rebuildDock();
            }
        } else if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
                && event.getRepeatCount() == 0 && editX != null) {
            removeSelected = !removeSelected;
            styleEditX();
        }
        return true;
    }

    /** The order list without uninstalled apps, so a move always swaps with a visible neighbour. */
    private List<String> visibleOrder() {
        Map<String, App> installed = installedApps();
        List<String> list = new ArrayList<>();
        for (String key : dockOrder()) {
            if (hiddenCard(key)) continue;
            if (key.startsWith("@") || installed.containsKey(key)) list.add(key);
        }
        return list;
    }

    /** Built-in cards whose feature isn't set up in calm.json. */
    private boolean hiddenCard(String key) {
        Config config = Config.get(this);
        return CARD_HOME.equals(key) && !config.hasHomeAssistant()
                || CARD_CAMERAS.equals(key) && !config.hasCameras();
    }

    // ---------------------------------------------------------------- all apps

    private void openAllApps() {
        rebuildAllApps();
        allAppsPanel.setAlpha(0f);
        allAppsPanel.setVisibility(View.VISIBLE);
        allAppsPanel.animate().alpha(1f).setDuration(200).setListener(null);
        chrome.animate().alpha(0f).setDuration(200);
        if (allAppsGrid.getChildCount() > 0) {
            ViewGroup firstRow = (ViewGroup) allAppsGrid.getChildAt(0);
            if (firstRow.getChildCount() > 0) ((ViewGroup) firstRow.getChildAt(0)).getChildAt(0).requestFocus();
        }
    }

    private void closeAllApps() {
        if (allAppsPanel.getVisibility() != View.VISIBLE) return;
        allAppsPanel.animate().alpha(0f).setDuration(200).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                allAppsPanel.setVisibility(View.GONE);
                allAppsPanel.animate().setListener(null);
            }
        });
        chrome.animate().alpha(1f).setDuration(200);
        View allTile = dock.findViewWithTag(CARD_ALL);
        if (allTile != null) allTile.requestFocus();
    }

    private void rebuildAllApps() {
        allAppsGrid.removeAllViews();
        List<App> apps = new ArrayList<>(installedApps().values());
        Collections.sort(apps, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setPadding(0, 0, 0, dp(18));
                allAppsGrid.addView(row);
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            // Margin inside the cell so the focused tile can grow without hitting the cell's edges.
            View tile = appTile(apps.get(i), false);
            LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            tlp.setMargins(dp(14), dp(10), dp(12), 0);
            cell.addView(tile, tlp);
            TextView name = new TextView(this);
            styleText(name, 15, inter(400));
            name.setAlpha(0.8f);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(14), dp(12), dp(12), 0);
            name.setText(apps.get(i).label);
            cell.addView(name, new LinearLayout.LayoutParams(dp(176 + 26), -2));
            row.addView(cell);
            // Scroll so the whole row, names included, is on screen when a tile in it gets focus.
            final View.OnFocusChangeListener grow = tile.getOnFocusChangeListener();
            final LinearLayout thisRow = row;
            tile.setOnFocusChangeListener((v, has) -> {
                grow.onFocusChange(v, has);
                if (has) allAppsScroll.post(() -> {
                    int top = thisRow.getTop() - dp(12);
                    int bottom = thisRow.getBottom() + dp(12);
                    int visible = allAppsScroll.getHeight() - allAppsScroll.getPaddingBottom();
                    int y = allAppsScroll.getScrollY();
                    if (bottom > y + visible) glide(allAppsScroll, "scrollY", bottom - visible);
                    else if (top < y) glide(allAppsScroll, "scrollY", Math.max(0, top));
                });
            });
        }
    }

    // ---------------------------------------------------------------- tiles

    private View appTile(final App app, boolean inDock) {
        FrameLayout tile = baseTile(app.pkg, app.label);
        Drawable banner = app.banner;
        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            tile.addView(iv, match());
        } else {
            tile.setBackground(frosted());
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            box.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView name = new TextView(this);
            styleText(name, 13, inter(400));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(8), dp(6), dp(8), 0);
            name.setText(app.label);
            box.addView(name);
            tile.addView(box, match());
        }
        tile.setOnClickListener(v -> {
            closeAllApps();
            launch(app.launchIntent);
        });
        if (!inDock) {
            // In All Apps, holding OK adds the app to the dock or takes it off.
            tile.setOnLongClickListener(v -> {
                toggleDock(app);
                rebuildAllApps();
                return true;
            });
        }
        return tile;
    }

    /**
     * Android switches the sleep-button service off when Calm is reinstalled, so turn it back on.
     * Needs WRITE_SECURE_SETTINGS, granted once over adb (see build.ps1).
     */
    private void ensureSleepService() {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        String ours = getPackageName() + "/" + SleepService.class.getName();
        // Is it actually running? After an app update the setting can still list it while
        // Android has stopped it, so check the running services, not just the setting.
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) return;
        }
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String others = enabled == null ? "" : enabled.replace(ours, "").replaceAll("::+", ":").replaceAll("^:|:$", "");
        // Writing the setting without us, then with us, makes Android (re)start the service.
        Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others);
        Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                others.isEmpty() ? ours : others + ":" + ours);
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    /** Alarm clock icon, with the wake-up time underneath when the alarm is on. */
    private View alarmTile() {
        AlarmStore alarm = AlarmStore.load(this);
        // Dawn sky: night blue at the top fading into sunrise.
        GradientDrawable dawn = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1C2541, 0xFF4A3F7A, 0xFFD9785A, 0xFFF2C47F});
        String title = alarm.enabled ? alarm.timeText() : "Alarm";
        String subtitle = alarm.enabled ? AlarmStore.REPEAT_NAMES[alarm.repeat] : "Off";
        View tile = appleTile(CARD_ALARM, dawn, R.drawable.ic_alarm, title, subtitle);
        tile.setOnClickListener(v -> launch(new Intent(this, AlarmSetupActivity.class)));
        return tile;
    }

    /** Apple Home-style tile: warm orange gradient, house icon, live AC status. */
    private View homeTile() {
        GradientDrawable warm = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF8A00, 0xFFFFB800, 0xFFFFD43B});
        HomeState home = HomeState.load(this);
        String subtitle = Config.get(this).room;
        if (home != null) {
            String mode = home.acState;
            subtitle = "off".equals(mode) ? "AC off"
                    : "AC " + home.acTemp + "°  ·  " + ("cool".equals(mode) ? "Cooling" : "heat".equals(mode) ? "Heating" : "Fan");
        }
        View tile = appleTile(CARD_HOME, warm, R.drawable.ic_home, "Home", subtitle);
        tile.setOnClickListener(v -> launch(new Intent(this, HomeControlActivity.class)));
        return tile;
    }

    /** Night-blue tile with a camera icon; opens the cameras screen. */
    private View camerasTile() {
        GradientDrawable night = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0F2C4A, 0xFF1E5A86, 0xFF3C8DBC});
        int count = Config.get(this).cameras.size();
        View tile = appleTile(CARD_CAMERAS, night, R.drawable.ic_camera, "Cameras",
                count == 1 ? "1 camera" : count + " cameras");
        tile.setOnClickListener(v -> launch(new Intent(this, CamerasActivity.class)));
        return tile;
    }

    /** Full-bleed colour tile with an icon top-left and a title/subtitle bottom-left. */
    private View appleTile(String tag, GradientDrawable bg, int iconRes, String title, String subtitle) {
        FrameLayout tile = baseTile(tag, title);
        bg.setCornerRadius(dp(14));
        tile.setBackground(bg);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.START);
        ilp.setMargins(dp(14), dp(12), 0, 0);
        tile.addView(icon, ilp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        styleText(t, 20, inter(600));
        t.setShadowLayer(dp(3), 0, dp(1), 0x40000000);
        t.setText(title);
        text.addView(t);
        TextView s = new TextView(this);
        styleText(s, 13, inter(500));
        s.setShadowLayer(dp(3), 0, dp(1), 0x40000000);
        s.setAlpha(0.9f);
        s.setText(subtitle);
        text.addView(s);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tlp.setMargins(dp(14), 0, dp(10), dp(10));
        tile.addView(text, tlp);
        return tile;
    }

    /** Icon-only gradient card, like an app icon on Apple TV. */
    private View iconCard(String tag, String label, int[] colors, int iconRes, int iconDp, int iconTint,
                          View.OnClickListener onClick) {
        FrameLayout tile = baseTile(tag, label);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        bg.setCornerRadius(dp(14));
        tile.setBackground(bg);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        if (iconTint != 0) icon.setColorFilter(iconTint);
        tile.addView(icon, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        tile.setOnClickListener(onClick);
        return tile;
    }

    private FrameLayout baseTile(Object tag, final String label) {
        final FrameLayout tile = new FrameLayout(this);
        tile.setTag(tag);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipToOutline(true);
        final float radius = dp(14);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        // Focus ring: a white border with a thin dark line inside it, so it also shows on light cards.
        GradientDrawable outer = new GradientDrawable();
        outer.setCornerRadius(radius);
        outer.setStroke(dp(3), Color.WHITE);
        GradientDrawable inner = new GradientDrawable();
        inner.setCornerRadius(radius - dp(3));
        inner.setStroke(dp(1), 0x59000000);
        final LayerDrawable ring = new LayerDrawable(new Drawable[]{outer, inner});
        ring.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
        ring.setAlpha(0);
        tile.setForeground(ring);
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.14f : 1f).scaleY(hasFocus ? 1.14f : 1f)
                    .translationZ(hasFocus ? dp(18) : 0).setDuration(160).start();
            ObjectAnimator.ofInt(ring, "alpha", hasFocus ? 255 : 0).setDuration(160).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), dp(99));
        lp.rightMargin = dp(26);
        tile.setLayoutParams(lp);
        return tile;
    }

    private GradientDrawable frosted() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x55FFFFFF);
        d.setCornerRadius(dp(14));
        return d;
    }

    // ---------------------------------------------------------------- wallpapers

    private void loadWallpaperIds() {
        for (Field f : R.drawable.class.getFields()) {
            if (!f.getName().startsWith("wp_")) continue;
            try {
                wallpapers.add(f.getInt(null));
            } catch (IllegalAccessException ignored) {
            }
        }
        Collections.shuffle(wallpapers);
    }

    private final Runnable nextWallpaper = new Runnable() {
        @Override
        public void run() {
            wallpaperIndex = (wallpaperIndex + 1) % Math.max(1, wallpapers.size());
            showWallpaper(true);
            handler.postDelayed(this, WALLPAPER_INTERVAL_MS);
        }
    };

    private void showWallpaper(boolean crossfade) {
        if (wallpapers.isEmpty()) return;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bmp = BitmapFactory.decodeResource(getResources(), wallpapers.get(wallpaperIndex), opts);

        final ImageView old = wallpaperViews[frontView];
        frontView = 1 - frontView;
        final ImageView next = wallpaperViews[frontView];
        next.bringToFront();
        chrome.bringToFront();
        allAppsPanel.bringToFront();
        greeting.bringToFront();
        curtain.bringToFront();
        next.setImageBitmap(bmp);
        next.animate().cancel();

        // Slow "Ken Burns" drift: zoom in gently while the photo is on screen.
        if (zoom != null) zoom.cancel();
        zoom = ObjectAnimator.ofPropertyValuesHolder(next,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, Math.random() < 0.5 ? -dp(30) : dp(30)));
        zoom.setInterpolator(new LinearInterpolator());
        zoom.setDuration(WALLPAPER_INTERVAL_MS + CROSSFADE_MS);
        zoom.start();

        if (!crossfade) {
            next.setAlpha(1f);
            old.setAlpha(0f);
            return;
        }
        next.setAlpha(0f);
        next.animate().alpha(1f).setDuration(CROSSFADE_MS).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                old.setAlpha(0f);
                old.setImageDrawable(null);
                next.animate().setListener(null);
            }
        });
    }

    // ---------------------------------------------------------------- idle

    private final Runnable hideChrome = new Runnable() {
        @Override
        public void run() {
            if (allAppsPanel.getVisibility() == View.VISIBLE) return;
            chromeHidden = true;
            chrome.animate().alpha(0f).setDuration(1200);
        }
    };

    private void showChrome() {
        chromeHidden = false;
        chrome.animate().alpha(allAppsPanel.getVisibility() == View.VISIBLE ? 0f : 1f).setDuration(300);
        handler.removeCallbacks(hideChrome);
        handler.postDelayed(hideChrome, IDLE_HIDE_MS);
    }

    // ---------------------------------------------------------------- apps

    private static class App {
        String pkg;
        String label;
        Drawable banner;
        Drawable icon;
        Intent launchIntent;
    }

    private Map<String, App> installedApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> apps = new HashMap<>();
        // TV apps only: phone-style LAUNCHER entries on this TV are system utilities.
        addLaunchable(pm, apps, Intent.CATEGORY_LEANBACK_LAUNCHER);
        return apps;
    }

    private void addLaunchable(PackageManager pm, Map<String, App> apps, String category) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (apps.containsKey(ai.packageName) || ai.packageName.equals(getPackageName())) continue;
            App app = new App();
            app.pkg = ai.packageName;
            app.label = String.valueOf(ri.loadLabel(pm));
            app.banner = ai.loadBanner(pm);
            if (app.banner == null) app.banner = ai.applicationInfo.loadBanner(pm);
            app.icon = ri.loadIcon(pm);
            app.launchIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            apps.put(app.pkg, app);
        }
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that app", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- helpers

    private final Map<Integer, Typeface> interCache = new HashMap<>();

    /** Inter (variable font in assets) at the given weight; thin weights use the display optical size. */
    private Typeface inter(int weight) {
        Typeface face = interCache.get(weight);
        if (face == null) {
            face = new Typeface.Builder(getAssets(), "Inter.ttf")
                    .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + (weight < 400 ? 32 : 14))
                    .build();
            if (face == null) face = Typeface.DEFAULT;
            interCache.put(weight, face);
        }
        return face;
    }

    private void styleText(TextView tv, int sizeDp, Typeface face) {
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        tv.setTypeface(face);
        tv.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
