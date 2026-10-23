package io.github.omer182.calmtv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Shared look for Calm's screens: Inter text, dp sizes, frosted panels. */
final class Ui {
    private static final Map<Integer, Typeface> INTER = new HashMap<>();

    private Ui() {
    }

    /** Inter (variable font in assets) at the given weight; thin weights use the display optical size. */
    static Typeface inter(Context context, int weight) {
        Typeface face = INTER.get(weight);
        if (face == null) {
            face = new Typeface.Builder(context.getAssets(), "Inter.ttf")
                    .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + (weight < 400 ? 32 : 14))
                    .build();
            if (face == null) face = Typeface.DEFAULT;
            INTER.put(weight, face);
        }
        return face;
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, int sizeDp, int weight) {
        TextView tv = new TextView(context);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        tv.setTypeface(inter(context, weight));
        tv.setShadowLayer(dp(context, 6), 0, dp(context, 1), 0x99000000);
        return tv;
    }

    static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    /** "Good morning" 05–12, "Good afternoon" 12–17, "Good evening" 17–22, else "Good night". */
    static String partOfDay(int hour) {
        return hour >= 5 && hour < 12 ? "Good morning"
                : hour >= 12 && hour < 17 ? "Good afternoon"
                : hour >= 17 && hour < 22 ? "Good evening"
                : "Good night";
    }

    static String twoDigits(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
