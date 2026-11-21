package com.neoremote.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.SeekBar;
import android.widget.TextView;

/** Кольори та дрібні помічники для інтерфейсу, зібраного в коді. */
final class Ui {
    static final int BG = 0xFF14111C;
    static final int CARD = 0xFF1F1A2C;
    static final int CARD_HI = 0xFF2C2440;
    static final int ACCENT = 0xFFA070FF;
    static final int ACCENT_LIGHT = 0xFFC9AEFF;
    static final int ACCENT_DIM = 0xFF4A3A70;
    static final int TEXT = 0xFFF2EEFA;
    static final int TEXT_DIM = 0xFF9C94AE;
    static final int RED = 0xFFE5484D;
    static final int GREEN = 0xFF3DD68C;
    static final int ORANGE = 0xFFF5A524;

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable roundStroke(Context c, int color, int stroke, float radiusDp) {
        GradientDrawable g = round(c, color, radiusDp);
        g.setStroke(dp(c, 1.5f), stroke);
        return g;
    }

    static void bg(TextView v, int color, int stroke, float radiusDp) {
        GradientDrawable g = stroke == 0 ? round(v.getContext(), color, radiusDp)
                : roundStroke(v.getContext(), color, stroke, radiusDp);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), g, null));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView button(Context c, String s, int color, int stroke) {
        TextView b = text(c, s, 15, TEXT, true);
        b.setGravity(Gravity.CENTER);
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        b.setFocusable(true);
        bg(b, color, stroke, 16);
        return b;
    }

    static SeekBar seek(Context c, int max, int value) {
        SeekBar s = new SeekBar(c);
        s.setMax(max);
        s.setProgress(value);
        s.setProgressTintList(ColorStateList.valueOf(ACCENT));
        s.setThumbTintList(ColorStateList.valueOf(ACCENT_LIGHT));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A3350));
        int p = dp(c, 6);
        s.setPadding(dp(c, 16), p, dp(c, 16), p);
        return s;
    }
}
