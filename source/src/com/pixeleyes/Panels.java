package com.pixeleyes;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Вью для экрана настроек: живой пример (глаза смотрят на палец, ряд кнопок
 * с эмоциями) и галерея всех наборов, где каждая карточка тоже живая.
 */
final class Panels {

    private Panels() {
    }

    static final String[][] EMOTIONS = {
            {"😊", "emo:happy"}, {"😍", "emo:love"}, {"😮", "emo:surprised"}, {"😠", "emo:angry"},
            {"😢", "emo:sad"}, {"😵", "emo:dizzy"}, {"😉", "emo:wink"}, {"🤨", "emo:suspicious"},
            {"👀", "emo:stare"}, {"😴", "emo:asleep"},
    };

    /** Большой пример с кнопками эмоций. */
    static View preview(Context c, Skin skin, Config cfg) {
        Ui.Spy root = new Ui.Spy(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(c, 12);
        col.setPadding(pad, pad, pad, Ui.dp(c, 8));

        FrameLayout stage = new FrameLayout(c);
        stage.setBackground(Ui.round(Ui.card(), Ui.dp(c, 16), 0, 0));
        EyesView eyes = new EyesView(c, skin);
        eyes.preview = true;
        eyes.setEdge(Overlay.isDarkTheme() ? Renderer.EDGE_HALO : Renderer.EDGE_SHADOW);
        eyes.brain.sleepOn = false;
        fitBig(eyes);
        EyesCore.get().previews.put(eyes, Boolean.TRUE);
        if (cfg != null) {
            eyes.brain.followTyping = cfg.followTyping;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        int sp = Ui.dp(c, 18);
        stage.setPadding(sp, sp, sp, sp);
        stage.addView(eyes, lp);
        stage.setMinimumHeight(Ui.dp(c, 120));
        col.addView(stage, Ui.fill());
        root.eyes.add(eyes);

        TextView hint = Ui.label(c, "Водите пальцем: глаза следят. Нажмите на них, чтобы ткнуть", 13,
                Ui.gray(), false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 6));
        col.addView(hint, Ui.fill());

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] e : EMOTIONS) {
            TextView chip = Ui.chip(c, e[0]);
            String ev = e[1];
            chip.setOnClickListener(v -> eyes.event(ev, 0, 0));
            LinearLayout.LayoutParams clp = Ui.wrap();
            clp.rightMargin = Ui.dp(c, 6);
            row.addView(chip, clp);
        }
        scroll.addView(row);
        col.addView(scroll, Ui.wrap());
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    /** Галерея: по две карточки в ряд, выбранная в рамке цвета акцента. */
    static View gallery(Context c, ArrayList<Skin> skins, String selected, EyesCore core, boolean catalog) {
        Ui.Spy root = new Ui.Spy(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 10);
        col.setPadding(pad, pad, pad, pad);
        ArrayList<View> cards = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        LinearLayout row = null;
        boolean dark = Overlay.isDarkTheme();
        for (int i = 0; i < skins.size(); i++) {
            Skin s = skins.get(i);
            if (i % 2 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(row, Ui.fill());
            }
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int cp = Ui.dp(c, 8);
            card.setPadding(cp, cp, cp, cp);
            FrameLayout box = new FrameLayout(c);
            box.setMinimumHeight(Ui.dp(c, 72));
            EyesView eyes = new EyesView(c, s);
            eyes.preview = true;
            eyes.brain.sleepOn = false;
            eyes.setEdge(dark ? Renderer.EDGE_HALO : Renderer.EDGE_SHADOW);
            float maxW = c.getResources().getDisplayMetrics().widthPixels * 0.36f;
            float px = Math.max(1.5f, Math.min(3f, maxW / (eyes.renderer.cw * eyes.density)));
            eyes.setPixelDp(px);
            box.addView(eyes, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            card.addView(box, Ui.fill());
            root.eyes.add(eyes);
            TextView name = Ui.label(c, s.name, 15, Ui.text(), true);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name, Ui.fill());
            String sub = s.tags.isEmpty() ? s.author : String.join(", ", s.tags);
            TextView meta = Ui.label(c, sub, 12, Ui.gray(), false);
            meta.setGravity(Gravity.CENTER);
            meta.setMaxLines(1);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(meta, Ui.fill());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            int m = Ui.dp(c, 4);
            clp.setMargins(m, m, m, m);
            row.addView(card, clp);
            cards.add(card);
            ids.add(s.id);
            String id = s.id;
            card.setOnClickListener(v -> {
                for (int k = 0; k < cards.size(); k++) {
                    paint(c, cards.get(k), ids.get(k).equals(id));
                }
                eyes.event("emo:happy", 0, 0);
                core.notify((catalog ? "pick:" : "select:") + id);
            });
            card.setOnLongClickListener(v -> {
                core.notify((catalog ? "pick:" : "long:") + id);
                return true;
            });
            paint(c, card, id.equals(selected));
        }
        if (skins.size() % 2 == 1 && row != null) {
            View filler = new View(c);
            row.addView(filler, new LinearLayout.LayoutParams(0, 1, 1f));
        }
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    /** Крупно, но так, чтобы рой и циклоп влезали. */
    static void fitBig(EyesView eyes) {
        float maxW = eyes.getResources().getDisplayMetrics().widthPixels * 0.62f;
        eyes.setPixelDp(Math.max(2f, Math.min(7f, maxW / (eyes.renderer.cw * eyes.density))));
    }

    private static void paint(Context c, View card, boolean on) {
        card.setBackground(Ui.round(Ui.card(), Ui.dp(c, 14), Ui.accent(), on ? Ui.dp(c, 2) : 0));
    }
}
