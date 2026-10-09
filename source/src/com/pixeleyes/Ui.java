package com.pixeleyes;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Цвета темы Telegram (рефлексией) и мелкие кирпичики интерфейса. */
final class Ui {

    private static Class<?> theme;
    private static Method getColor;
    private static boolean resolved;
    private static final Map<String, Integer> keys = new HashMap<>();

    private Ui() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            theme = Class.forName("org.telegram.ui.ActionBar.Theme", false, Ui.class.getClassLoader());
            getColor = theme.getMethod("getColor", int.class);
        } catch (Throwable t) {
            theme = null;
            getColor = null;
        }
    }

    static int color(String key, int fallback) {
        resolve();
        if (getColor == null) {
            return fallback;
        }
        try {
            Integer k;
            synchronized (keys) {
                k = keys.get(key);
                if (k == null) {
                    Field f = theme.getField(key);
                    k = f.getInt(null);
                    keys.put(key, k);
                }
            }
            return (Integer) getColor.invoke(null, k);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static int text() {
        return color("key_windowBackgroundWhiteBlackText", 0xFF222222);
    }

    static int gray() {
        return color("key_windowBackgroundWhiteGrayText2", 0xFF8A8A8A);
    }

    static int accent() {
        return color("key_featuredStickers_addButton", 0xFF3D8FEB);
    }

    static int card() {
        int bg = color("key_windowBackgroundWhite", 0xFFFFFFFF);
        return mix(bg, text(), 0.06f);
    }

    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8
                | Math.round(ab + (bb - ab) * t);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusPx, int strokeColor, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) {
            d.setStroke(strokePx, strokeColor);
        }
        return d;
    }

    static TextView label(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    /** Кнопка-чип: текст в скруглённой рамке. */
    static TextView chip(Context c, String s) {
        TextView t = label(c, s, 15, text(), false);
        t.setGravity(Gravity.CENTER);
        int p = dp(c, 8);
        t.setPadding(p, dp(c, 5), p, dp(c, 5));
        t.setBackground(round(card(), dp(c, 14), 0, 0));
        t.setClickable(true);
        return t;
    }

    /**
     * Контейнер, который видит все касания внутри себя и отдаёт их глазам, ничего
     * не забирая у детей: глаза в примере и в галерее смотрят на палец.
     */
    static final class Spy extends FrameLayout {
        final ArrayList<EyesView> eyes = new ArrayList<>();

        Spy(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            int act = a == MotionEvent.ACTION_DOWN ? 0 : a == MotionEvent.ACTION_MOVE ? 1
                    : (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) ? 2 : -1;
            if (act >= 0) {
                for (EyesView v : eyes) {
                    v.globalTouch(act, e.getRawX(), e.getRawY());
                }
            }
            boolean handled = super.dispatchTouchEvent(e);
            // иначе после ACTION_DOWN движения пальца сюда больше не придут
            return handled || a == MotionEvent.ACTION_DOWN;
        }
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static void hideFocus(View v) {
        v.setFocusable(false);
    }
}
