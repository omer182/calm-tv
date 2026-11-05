package io.github.omer182.calmtv;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Sleep timer on a spare remote button (sleepKey in calm.json), working in any app.
 * Press: +30 min, up to 2 h. Hold 3 s: timer off. In the last minute any button cancels.
 * It also handles the other buttons that work in any app: "buttons" that open an app, and the
 * camera pop-up.
 */
public class SleepService extends AccessibilityService {
    private static final String TAG = "CalmSleep";
    private static final long STEP_MS = 30 * 60_000;
    private static final long MAX_MS = 120 * 60_000;
    private static final long HOLD_MS = 3_000;
    private static final long WARN_MS = 60_000;
    private static final long BADGE_MS = 3_500;
    // Time for the app that was showing to drop behind Home and save its state.
    private static final long CLOSE_APPS_DELAY_MS = 5_000;
    private static final long FORCE_STOP_TIMEOUT_MS = 8_000;
    // Screen-off delay plus a few apps' worth of force stops.
    private static final long STANDBY_WORK_MS = 40_000;
    private static final String SETTINGS = "com.android.tv.settings";

    private final Handler handler = new Handler();
    private long sleepAt; // uptime millis, 0 = no timer
    private boolean holdFired;
    private boolean warning;
    private TextView badge;
    private WindowManager wm;
    private CameraPopup popup;
    // Force stop, the way you would by hand: the app's page in Settings, "Force stop", "OK".
    // Only while the screen stays off; turning the TV on gives up and goes home.
    private final ArrayDeque<String> stopQueue = new ArrayDeque<>();
    private String stopping;
    // From the screen broadcasts: this TV still says "interactive" for a while after standby.
    private boolean screenOn = true;
    private PowerManager.WakeLock standbyLock;
    private long stopDeadline;
    private boolean stopConfirming;

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            screenOn = false;
            // Standby suspends the CPU within seconds; stay awake to close the apps.
            if (!standbyLock.isHeld()) standbyLock.acquire(STANDBY_WORK_MS);
            // Turned off by hand: the timer has nothing left to do.
            if (sleepAt != 0) clearTimer(false);
            popup.hide();
            // Standby keeps the last app in front, so the TV would wake up inside it. Go home now
            // so it wakes to the welcome instead. (Home can't do this itself: Android blocks
            // activity starts from the background, but not from an accessibility service.)
            startActivity(new Intent(context, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Then close the apps left open, once they're behind Home, so none wakes up stuck.
            handler.removeCallbacks(closeApps);
            handler.postDelayed(closeApps, CLOSE_APPS_DELAY_MS);
        }
    };

    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            screenOn = true;
        }
    };

    /**
     * Standby isn't off: apps stay open for days, and Plex sometimes hangs when opened again
     * after that. Closes every app with a launcher entry that Android counts as in the
     * background, so the next evening starts fresh. Music still playing (a foreground service)
     * is left alone.
     */
    private final Runnable closeApps = () -> {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        Set<String> pkgs = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                pkgs.add(ri.activityInfo.packageName);
            }
        }
        pkgs.remove(getPackageName());
        for (String pkg : pkgs) am.killBackgroundProcesses(pkg);
        Log.i(TAG, "screen off: closed " + pkgs.size() + " apps");
        stopQueue.clear();
        stopQueue.addAll(Config.get(this).forceStop);
        forceStopNext();
    };

    private void forceStopNext() {
        stopping = stopQueue.poll();
        if (stopping == null || screenOn) {
            if (stopping != null) Log.i(TAG, "screen on, not force-stopping " + stopping);
            stopping = null;
            stopQueue.clear();
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (standbyLock.isHeld()) standbyLock.release();
            return;
        }
        stopConfirming = false;
        stopDeadline = SystemClock.uptimeMillis() + FORCE_STOP_TIMEOUT_MS;
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", stopping, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no app settings screen", e);
            stopQueue.clear();
            forceStopNext();
            return;
        }
        handler.postDelayed(forceStopStep, 700);
    }

    private final Runnable forceStopStep = new Runnable() {
        @Override
        public void run() {
            if (stopping == null) return;
            if (screenOn) {
                Log.i(TAG, "screen on, stopped force-stopping " + stopping);
                stopQueue.clear();
                forceStopNext();
                return;
            }
            if (SystemClock.uptimeMillis() > stopDeadline) {
                Log.w(TAG, "couldn't force stop " + stopping);
                forceStopNext();
                return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            Log.d(TAG, "force stop " + stopping + (stopConfirming ? ", confirming" : "") + ": window "
                    + (root == null ? "none" : root.getPackageName()));
            if (root != null && SETTINGS.contentEquals(root.getPackageName())) {
                if (!stopConfirming) {
                    AccessibilityNodeInfo item = clickable(find(root, "android:id/title", "Force stop"));
                    if (item != null && !item.isEnabled()) {
                        Log.i(TAG, stopping + " already stopped");
                        forceStopNext();
                        return;
                    }
                    if (item != null && item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) stopConfirming = true;
                } else {
                    AccessibilityNodeInfo ok = clickable(find(root, SETTINGS + ":id/guidedactions_item_title", "OK"));
                    if (ok != null && ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "screen off: force-stopped " + stopping);
                        handler.postDelayed(() -> forceStopNext(), 700);
                        return;
                    }
                }
            }
            handler.postDelayed(this, 400);
        }
    };

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String viewId, String text) {
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(viewId)) {
            if (n.getText() != null && text.equalsIgnoreCase(n.getText().toString())) return n;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        while (n != null && !n.isClickable()) n = n.getParent();
        return n;
    }

    // Test from adb without waiting 30 min:
    //   am broadcast -a io.github.omer182.calmtv.SLEEP_TEST --ei seconds 70
    private final BroadcastReceiver test = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.hasExtra("seconds")) {
                // Same as one press of the sleep button.
                addStep();
                return;
            }
            if (intent.getIntExtra("seconds", 70) < 0) {
                // Same as holding the sleep button: timer off.
                clearTimer(false);
                return;
            }
            long ms = intent.getIntExtra("seconds", 70) * 1000L;
            sleepAt = SystemClock.uptimeMillis() + ms;
            warning = false;
            handler.removeCallbacks(warn);
            handler.removeCallbacks(sleep);
            handler.postAtTime(warn, sleepAt - WARN_MS);
            handler.postAtTime(sleep, sleepAt);
            showBadge("Sleep test in " + ms / 1000 + " s", BADGE_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        standbyLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "calm:standby");
        popup = new CameraPopup(this, wm);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        registerReceiver(test, new IntentFilter("io.github.omer182.calmtv.SLEEP_TEST"));
        Log.i(TAG, "sleep button ready");
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screenOff);
        unregisterReceiver(screenOnReceiver);
        unregisterReceiver(test);
        handler.removeCallbacksAndMessages(null);
        hideBadge();
        if (popup != null) popup.hide();
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        // "buttons" in calm.json: remote buttons that open an app, e.g. the Netflix button, which
        // the stock home screen used to handle.
        String app = Config.get(this).buttons.get(code);
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) openApp(app);
            return true;
        }
        // "cameras.popup.key" in calm.json: a spare button that shows a camera over whatever is on.
        if (code == Config.get(this).popupKey && Config.get(this).popupStream() != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) popup.toggle();
            return true;
        }
        // Back closes the pop-up first, rather than leaving the app underneath.
        if (code == KeyEvent.KEYCODE_BACK && popup.isShowing()) {
            if (event.getAction() == KeyEvent.ACTION_UP) popup.hide();
            return true;
        }
        // "sleepKey" in calm.json: a remote button the TV doesn't use (e.g. 194 on Xiaomi remotes).
        int sleepKey = Config.get(this).sleepKey;
        if (sleepKey != 0 && code == sleepKey) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                holdFired = false;
                handler.postDelayed(hold, HOLD_MS);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(hold);
                if (!holdFired) addStep();
            }
            return true;
        }
        if (warning) {
            // Last minute: any button means "I'm still watching".
            if (event.getAction() == KeyEvent.ACTION_UP) clearTimer(true);
            return true;
        }
        return false;
    }

    private void openApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Log.w(TAG, "button app not installed: " + pkg);
            return;
        }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final Runnable hold = () -> {
        holdFired = true;
        clearTimer(true);
    };

    private void addStep() {
        long now = SystemClock.uptimeMillis();
        long left = sleepAt == 0 ? 0 : Math.max(0, sleepAt - now);
        // Round what's left up to the next half hour, then add one: 25 min left -> 1 h.
        long steps = (left + STEP_MS - 1) / STEP_MS;
        long next = Math.min(MAX_MS, (steps + 1) * STEP_MS);
        sleepAt = now + next;
        warning = false;
        handler.removeCallbacks(warn);
        handler.removeCallbacks(sleep);
        handler.postAtTime(warn, sleepAt - WARN_MS);
        handler.postAtTime(sleep, sleepAt);
        showBadge("Sleep in " + duration(next) + (next == MAX_MS ? "  (max)" : ""), BADGE_MS);
        Log.i(TAG, "sleep in " + next / 60_000 + " min");
    }

    private void clearTimer(boolean announce) {
        sleepAt = 0;
        warning = false;
        handler.removeCallbacks(warn);
        handler.removeCallbacks(sleep);
        handler.removeCallbacks(countdown);
        if (announce) showBadge("Sleep timer off", BADGE_MS);
        else hideBadge();
        Log.i(TAG, "timer off");
    }

    private final Runnable warn = () -> {
        warning = true;
        this.countdown.run();
    };

    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            if (!warning) return;
            long secs = Math.max(0, (sleepAt - SystemClock.uptimeMillis() + 999) / 1000);
            showBadge("Sleeping in " + secs + " s  ·  any button to stay", 0);
            if (secs > 0) handler.postDelayed(this, 1000);
        }
    };

    private final Runnable sleep = () -> {
        warning = false;
        sleepAt = 0;
        hideBadge();
        Log.i(TAG, "sleeping now");
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, CalmAdmin.class);
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow();
        } else {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
    };

    private static String duration(long ms) {
        long mins = ms / 60_000;
        if (mins < 60) return mins + " min";
        return mins / 60 + (mins % 60 == 0 ? " h" : " h " + mins % 60 + " min");
    }

    private void showBadge(String text, long hideAfterMs) {
        if (badge == null) {
            badge = Ui.text(this, 20, 500);
            int p = Ui.dp(this, 14);
            badge.setPadding(Ui.dp(this, 22), p, Ui.dp(this, 22), p);
            badge.setBackground(Ui.rounded(this, 0xCC15171A, 28));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            // Top-left: the home screen's clock and weather live in the top-right corner.
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Ui.dp(this, 48);
            lp.y = Ui.dp(this, 40);
            wm.addView(badge, lp);
        }
        badge.setText(text);
        handler.removeCallbacks(hideBadgeLater);
        if (hideAfterMs > 0) handler.postDelayed(hideBadgeLater, hideAfterMs);
    }

    private final Runnable hideBadgeLater = this::hideBadge;

    private void hideBadge() {
        if (badge != null) {
            wm.removeView(badge);
            badge = null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
