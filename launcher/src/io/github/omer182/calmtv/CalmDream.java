package io.github.omer182.calmtv;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Calm's screensaver: the same nature photos as home, slowly drifting, with the clock and weather. */
public class CalmDream extends DreamService {
    private static final long INTERVAL_MS = 40_000;
    private static final long FADE_MS = 2_500;

    private final Handler handler = new Handler();
    private final List<Integer> photos = new ArrayList<>();
    private final ImageView[] views = new ImageView[2];
    private int front;
    private int index;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        for (Field f : R.drawable.class.getFields()) {
            if (!f.getName().startsWith("wp_")) continue;
            try {
                photos.add(f.getInt(null));
            } catch (IllegalAccessException ignored) {
            }
        }
        Collections.shuffle(photos);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // Photos live in their own layer, so swapping them never covers the clock.
        FrameLayout photoLayer = new FrameLayout(this);
        root.addView(photoLayer, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            photoLayer.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            views[i] = iv;
        }

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, Ui.dp(this, 360), Gravity.BOTTOM));

        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.VERTICAL);
        corner.setGravity(Gravity.END);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(72);
        time.setTypeface(Ui.inter(this, 200));
        time.setShadowLayer(Ui.dp(this, 8), 0, Ui.dp(this, 1), 0x99000000);
        corner.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(Color.WHITE);
        date.setTextSize(20);
        date.setTypeface(Ui.inter(this, 400));
        date.setAlpha(0.85f);
        date.setShadowLayer(Ui.dp(this, 6), 0, Ui.dp(this, 1), 0x99000000);
        corner.addView(date);
        Weather w = Weather.load(getSharedPreferences("dock", MODE_PRIVATE));
        if (w != null) {
            TextView now = Ui.text(this, 18, 400);
            now.setAlpha(0.85f);
            now.setPadding(0, Ui.dp(this, 10), 0, 0);
            now.setText(w.nowLine(Config.get(this)));
            corner.addView(now);
        }
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        clp.setMargins(0, 0, Ui.dp(this, 64), Ui.dp(this, 56));
        root.addView(corner, clp);

        // Right-align every line in the corner block.
        for (int i = 0; i < corner.getChildCount(); i++) {
            TextView tv = (TextView) corner.getChildAt(i);
            tv.setGravity(Gravity.END);
            tv.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        }
        setContentView(root);
        show(false);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        handler.postDelayed(next, INTERVAL_MS);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            index = (index + 1) % Math.max(1, photos.size());
            show(true);
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void show(boolean fade) {
        if (photos.isEmpty()) return;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bmp = BitmapFactory.decodeResource(getResources(), photos.get(index), opts);
        final ImageView old = views[front];
        front = 1 - front;
        ImageView img = views[front];
        img.bringToFront();
        img.setImageBitmap(bmp);
        ObjectAnimator.ofPropertyValuesHolder(img,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f))
                .setDuration(INTERVAL_MS + FADE_MS)
                .start();
        if (!fade) {
            img.setAlpha(1f);
            return;
        }
        img.setAlpha(0f);
        img.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            old.setAlpha(0f);
            old.setImageDrawable(null);
        }).start();
    }
}
