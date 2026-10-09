package com.pixeleyes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Редактор глаз прямо в настройках: рисуешь пиксели трёх слоёв (глаз,
 * радужка, блик), сверху живой пример с тем, что получается. Цвета из
 * палитры; цвет, помеченный как "яблоко", это то, по чему ходит зрачок и что
 * закрывают веки. На выходе JSON того же формата, что и у встроенных глаз.
 */
final class Editor {

    static final int L_EYE = 0;
    static final int L_IRIS = 1;
    static final int L_HL = 2;
    static final String[] LAYER_NAMES = {"Глаз", "Радужка", "Блик"};

    static final int T_BRUSH = 0;
    static final int T_ERASE = 1;
    static final int T_FILL = 2;
    static final int T_PICK = 3;

    /** Палитра для пиксель-арта: серые, розовые и красные, жёлтые и кожа, зелёные, синие, фиолетовые. */
    static final int[] COLORS = {
            0xFFFFFFFF, 0xFFE6E6EE, 0xFFB4B4C8, 0xFF7A7A90, 0xFF45455A, 0xFF1B1B22, 0xFF000000,
            0xFFFFD6E0, 0xFFFF8CC6, 0xFFE0479E, 0xFFFF4F7B, 0xFFC2272D, 0xFF8C1A1F, 0xFF5C0A10,
            0xFFFFE066, 0xFFFFCC1F, 0xFFFF9A3C, 0xFFB86F2A, 0xFF8A5A36, 0xFF4A2C1A, 0xFFF9D9CF,
            0xFFD6EE5F, 0xFFA4C93C, 0xFF5F8A1A, 0xFF2E6B3A, 0xFF7FE0C6, 0xFF33E6FF, 0xFF1F7F8C,
            0xFFB8D9FF, 0xFF6FA8FF, 0xFF3D5AFE, 0xFF26327A, 0xFFE2CCFF, 0xFFB88CFF, 0xFF7A45D6,
            0xFF4A227F, 0xFF241532, 0xFF0A0F14,
    };

    private static final String CHARS = "#wabcdefghijklmnopqrstuvxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    static final class Pal {
        char c;
        int color;
        boolean sclera;
    }

    static final class Layer {
        int w;
        int h;
        char[] px;

        Layer(int w, int h) {
            this.w = w;
            this.h = h;
            px = new char[w * h];
            Arrays.fill(px, '.');
        }

        Layer copy() {
            Layer l = new Layer(w, h);
            l.px = px.clone();
            return l;
        }

        char at(int x, int y) {
            return x < 0 || y < 0 || x >= w || y >= h ? '.' : px[y * w + x];
        }

        void resize(int nw, int nh) {
            char[] n = new char[nw * nh];
            Arrays.fill(n, '.');
            int ox = (nw - w) / 2;
            int oy = (nh - h) / 2;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int tx = x + ox;
                    int ty = y + oy;
                    if (tx >= 0 && ty >= 0 && tx < nw && ty < nh) {
                        n[ty * nw + tx] = px[y * w + x];
                    }
                }
            }
            w = nw;
            h = nh;
            px = n;
        }

        List<String> rows() {
            ArrayList<String> r = new ArrayList<>();
            for (int y = 0; y < h; y++) {
                r.add(new String(px, y * w, w));
            }
            return r;
        }
    }

    // модель
    final ArrayList<Pal> palette = new ArrayList<>();
    final Layer[] layers = new Layer[3];
    int lid = 0xFFE6D2C4;
    int lash = 0xFF1B1B22;
    boolean two = true;
    boolean mirror = true;
    int gap = 3;
    int style;
    boolean creepy;
    String id = "my_eyes";
    Map<String, Object> keep;     // кадры и поведение из исходника, которых редактор не касается

    // состояние
    int layer = L_EYE;
    int tool = T_BRUSH;
    int current = 1;              // номер цвета в палитре
    boolean symmetry = true;
    private final ArrayList<Object[]> undo = new ArrayList<>();
    private String pickTarget;    // null цвет палитры, "lid", "lash", "new"

    // вью
    private Context ctx;
    private Grid grid;
    private EyesView eyes;
    private LinearLayout paletteRow;
    private LinearLayout colorGrid;
    private final ArrayList<TextView> layerChips = new ArrayList<>();
    private final ArrayList<TextView> toolChips = new ArrayList<>();
    private TextView sizeLabel;
    private TextView scleraChip;
    private TextView lidChip;
    private TextView lashChip;
    private TextView symChip;
    private TextView twoChip;
    private TextView styleChip;
    private TextView creepyChip;
    private TextView status;
    private EditText nameField;
    String startName = "Мои глаза";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rebuild = this::rebuildPreview;

    // ---- создание ---------------------------------------------------------------------------

    static View create(Context c, Skin from) {
        Editor e = new Editor();
        e.load(from);
        if (from != null) {
            e.startName = from.name;
        }
        ScrollView sv = new ScrollView(c);
        sv.addView(e.build(c));
        sv.setTag(e);
        return sv;
    }

    static Editor of(View root) {
        Object tag = root.getTag();
        if (tag instanceof Editor) {
            return (Editor) tag;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                Editor e = of(g.getChildAt(i));
                if (e != null) {
                    return e;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    void load(Skin s) {
        palette.clear();
        Map<String, Object> src = s == null ? null : s.source;
        if (src == null) {
            // чистый лист: круглый глаз с контуром и чёрный зрачок
            src = (Map<String, Object>) MiniJson.parse(Builtins.JSON[Builtins.DEFAULT]);
            id = "my_eyes";
        } else {
            id = Skin.cleanId(String.valueOf(src.get("id")));
        }
        Map<String, Object> pal = src.get("palette") instanceof Map ? (Map<String, Object>) src.get("palette")
                : new LinkedHashMap<>();
        String sclera = src.get("sclera") instanceof String ? (String) src.get("sclera") : "w";
        for (Map.Entry<String, Object> e : pal.entrySet()) {
            if (e.getKey().length() != 1 || !(e.getValue() instanceof String)) {
                continue;
            }
            Pal p = new Pal();
            p.c = e.getKey().charAt(0);
            try {
                p.color = Skin.parseColor((String) e.getValue(), "palette");
            } catch (Throwable t) {
                continue;
            }
            p.sclera = sclera.indexOf(p.c) >= 0;
            palette.add(p);
        }
        layers[L_EYE] = layerFrom(src.get("eye"), 12, 12);
        layers[L_IRIS] = layerFrom(src.get("iris"), 4, 4);
        layers[L_HL] = layerFrom(src.get("highlight"), 2, 2);
        Skin ref = s;
        if (ref == null) {
            ref = Skin.fromMap(src);
        }
        lid = ref.lidColor;
        lash = ref.lashColor;
        two = ref.places.size() >= 2;
        mirror = ref.places.size() < 2 || ref.places.get(1).mirror;
        style = ref.style;
        creepy = ref.creepy;
        keep = new LinkedHashMap<>();
        for (String k : new String[]{"frames", "behavior", "iris_offset", "highlight_offset", "highlight_fixed",
                "travel", "description", "tags"}) {
            if (src.containsKey(k)) {
                keep.put(k, src.get(k));
            }
        }
        if (src.get("layout") instanceof Map) {
            Map<?, ?> lay = (Map<?, ?>) src.get("layout");
            if (lay.get("gap") instanceof Number) {
                gap = ((Number) lay.get("gap")).intValue();
            }
            if (lay.get("eyes") instanceof List) {
                keep.put("layout", lay);
            }
        }
        current = Math.min(1, Math.max(0, palette.size() - 1));
    }

    private static Layer layerFrom(Object rows, int dw, int dh) {
        if (!(rows instanceof List) || ((List<?>) rows).isEmpty()) {
            return new Layer(dw, dh);
        }
        List<?> r = (List<?>) rows;
        int w = 0;
        for (Object o : r) {
            w = Math.max(w, String.valueOf(o).length());
        }
        Layer l = new Layer(Math.max(1, Math.min(Skin.MAX_EYE, w)), Math.max(1, Math.min(Skin.MAX_EYE, r.size())));
        for (int y = 0; y < l.h; y++) {
            String row = String.valueOf(r.get(y));
            for (int x = 0; x < l.w && x < row.length(); x++) {
                char c = row.charAt(x);
                l.px[y * l.w + x] = c == ' ' ? '.' : c;
            }
        }
        return l;
    }

    // ---- JSON -----------------------------------------------------------------------------------

    String toJson(String name) {
        if ((name == null || name.trim().isEmpty()) && nameField != null) {
            name = nameField.getText().toString();
        }
        boolean any = false;
        for (char ch : layers[L_EYE].px) {
            if (ch != '.' && scleraChar(ch)) {
                any = true;
                break;
            }
        }
        if (!any) {
            throw new IllegalArgumentException("Отметьте хотя бы один цвет глаза как яблоко (кнопка 👁)");
        }
        Map<String, Object> m = buildMap(name);
        String json = MiniJson.write(m);
        Skin.parse(json);   // проверка: ошибку увидит пользователь
        return json;
    }

    Map<String, Object> buildMap(String name) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("format", (double) Skin.FORMAT);
        m.put("id", id);
        m.put("name", name == null || name.trim().isEmpty() ? "Мои глаза" : name.trim());
        m.put("author", "");
        if (keep.containsKey("description")) {
            m.put("description", keep.get("description"));
        }
        if (keep.containsKey("tags")) {
            m.put("tags", keep.get("tags"));
        }
        LinkedHashMap<String, Object> pal = new LinkedHashMap<>();
        StringBuilder sclera = new StringBuilder();
        for (Pal p : palette) {
            if (!used(p.c)) {
                continue;
            }
            pal.put(String.valueOf(p.c), hex(p.color));
            if (p.sclera) {
                sclera.append(p.c);
            }
        }
        m.put("palette", pal);
        m.put("eye", layers[L_EYE].rows());
        m.put("sclera", sclera.length() == 0 ? "w" : sclera.toString());
        if (!empty(layers[L_IRIS])) {
            m.put("iris", trimmed(layers[L_IRIS]).rows());
            char pupil = darkest(layers[L_IRIS]);
            if (pupil != 0) {
                m.put("pupil", String.valueOf(pupil));
            }
        }
        if (!empty(layers[L_HL])) {
            m.put("highlight", trimmed(layers[L_HL]).rows());
        }
        for (String k : new String[]{"iris_offset", "highlight_offset", "highlight_fixed", "travel"}) {
            if (keep.containsKey(k)) {
                m.put(k, keep.get(k));
            }
        }
        m.put("lid", lid == 0 ? "none" : hex(lid));
        m.put("lash", hex(lash));
        if (keep.containsKey("layout")) {
            m.put("layout", keep.get("layout"));
        } else {
            LinkedHashMap<String, Object> lay = new LinkedHashMap<>();
            lay.put("count", (double) (two ? 2 : 1));
            lay.put("gap", (double) gap);
            lay.put("mirror", mirror);
            m.put("layout", lay);
        }
        if (keep.containsKey("frames")) {
            m.put("frames", keep.get("frames"));
        }
        LinkedHashMap<String, Object> beh = new LinkedHashMap<>();
        if (keep.get("behavior") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) keep.get("behavior")).entrySet()) {
                beh.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        beh.put("style", style == 1 ? Skin.STYLE_SNAP : style == 2 ? Skin.STYLE_TWITCH : Skin.STYLE_SMOOTH);
        beh.put("creepy", creepy);
        if (style == 2 && !beh.containsKey("twitch")) {
            beh.put("twitch", 0.5);
        }
        m.put("behavior", beh);
        return m;
    }

    private boolean used(char c) {
        for (Layer l : layers) {
            for (char x : l.px) {
                if (x == c) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean empty(Layer l) {
        for (char c : l.px) {
            if (c != '.') {
                return false;
            }
        }
        return true;
    }

    /** Без пустых полей по краям: радужка и блик центруются по своей середине. */
    static Layer trimmed(Layer l) {
        int x0 = l.w;
        int y0 = l.h;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < l.h; y++) {
            for (int x = 0; x < l.w; x++) {
                if (l.px[y * l.w + x] != '.') {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (x1 < 0) {
            return l;
        }
        Layer t = new Layer(x1 - x0 + 1, y1 - y0 + 1);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                t.px[(y - y0) * t.w + (x - x0)] = l.px[y * l.w + x];
            }
        }
        return t;
    }

    private char darkest(Layer l) {
        char best = 0;
        float lum = 2f;
        for (Pal p : palette) {
            boolean in = false;
            for (char c : l.px) {
                if (c == p.c) {
                    in = true;
                    break;
                }
            }
            if (!in) {
                continue;
            }
            float v = luma(p.color);
            if (v < lum) {
                lum = v;
                best = p.c;
            }
        }
        return lum < 0.35f ? best : 0;
    }

    static float luma(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    static String hex(int c) {
        if ((c >>> 24) == 0xFF) {
            return String.format(Locale.ROOT, "#%06x", c & 0xFFFFFF);
        }
        return String.format(Locale.ROOT, "#%08x", c);
    }

    // ---- правки ---------------------------------------------------------------------------------

    private void snapshot() {
        Layer[] copy = new Layer[3];
        for (int i = 0; i < 3; i++) {
            copy[i] = layers[i].copy();
        }
        ArrayList<Pal> pc = new ArrayList<>();
        for (Pal p : palette) {
            Pal q = new Pal();
            q.c = p.c;
            q.color = p.color;
            q.sclera = p.sclera;
            pc.add(q);
        }
        undo.add(new Object[]{copy, pc, lid, lash});
        if (undo.size() > 40) {
            undo.remove(0);
        }
    }

    @SuppressWarnings("unchecked")
    void undoStep() {
        if (undo.isEmpty()) {
            say("Отменять нечего");
            return;
        }
        Object[] s = undo.remove(undo.size() - 1);
        Layer[] l = (Layer[]) s[0];
        System.arraycopy(l, 0, layers, 0, 3);
        palette.clear();
        palette.addAll((ArrayList<Pal>) s[1]);
        lid = (Integer) s[2];
        lash = (Integer) s[3];
        current = Math.max(0, Math.min(current, palette.size() - 1));
        changed();
        if (grid != null) {
            grid.requestLayout();
        }
    }

    void paint(int x, int y, boolean first) {
        Layer l = layers[layer];
        if (x < 0 || y < 0 || x >= l.w || y >= l.h) {
            return;
        }
        if (tool == T_PICK) {
            char c = l.at(x, y);
            for (int i = 0; i < palette.size(); i++) {
                if (palette.get(i).c == c) {
                    current = i;
                    tool = T_BRUSH;
                    refresh();
                    return;
                }
            }
            return;
        }
        char want = tool == T_ERASE || palette.isEmpty() ? '.' : palette.get(current).c;
        if (tool == T_FILL) {
            if (!first) {
                return;
            }
            snapshot();
            fill(l, x, y, want);
            if (symmetry) {
                fill(l, l.w - 1 - x, y, want);
            }
            changed();
            return;
        }
        if (first) {
            snapshot();
        }
        boolean ch = set(l, x, y, want);
        if (symmetry) {
            ch |= set(l, l.w - 1 - x, y, want);
        }
        if (ch) {
            changed();
        }
    }

    private static boolean set(Layer l, int x, int y, char c) {
        int k = y * l.w + x;
        if (l.px[k] == c) {
            return false;
        }
        l.px[k] = c;
        return true;
    }

    static void fill(Layer l, int x, int y, char c) {
        char from = l.at(x, y);
        if (from == c || x < 0 || y < 0 || x >= l.w || y >= l.h) {
            return;
        }
        ArrayList<int[]> stack = new ArrayList<>();
        stack.add(new int[]{x, y});
        while (!stack.isEmpty()) {
            int[] p = stack.remove(stack.size() - 1);
            int px = p[0];
            int py = p[1];
            if (px < 0 || py < 0 || px >= l.w || py >= l.h || l.px[py * l.w + px] != from) {
                continue;
            }
            l.px[py * l.w + px] = c;
            stack.add(new int[]{px + 1, py});
            stack.add(new int[]{px - 1, py});
            stack.add(new int[]{px, py + 1});
            stack.add(new int[]{px, py - 1});
        }
    }

    void resize(int dw, int dh) {
        Layer l = layers[layer];
        int nw = Math.max(1, Math.min(Skin.MAX_EYE, l.w + dw));
        int nh = Math.max(1, Math.min(Skin.MAX_EYE, l.h + dh));
        if (nw == l.w && nh == l.h) {
            return;
        }
        snapshot();
        l.resize(nw, nh);
        changed();
        if (grid != null) {
            grid.requestLayout();
        }
    }

    void addColor(int color) {
        for (int i = 0; i < palette.size(); i++) {
            if (palette.get(i).color == color) {
                current = i;
                refresh();
                return;
            }
        }
        char c = 0;
        for (char k : CHARS.toCharArray()) {
            boolean taken = false;
            for (Pal p : palette) {
                if (p.c == k) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                c = k;
                break;
            }
        }
        if (c == 0) {
            say("В палитре нет места");
            return;
        }
        snapshot();
        Pal p = new Pal();
        p.c = c;
        p.color = color;
        palette.add(p);
        current = palette.size() - 1;
        refresh();
    }

    private void changed() {
        refresh();
        handler.removeCallbacks(rebuild);
        handler.postDelayed(rebuild, 250);
    }

    private void rebuildPreview() {
        if (eyes == null) {
            return;
        }
        try {
            Skin s = Skin.parse(MiniJson.write(buildMap("Пример")));
            eyes.setSkin(s);
            float maxW = ctx.getResources().getDisplayMetrics().widthPixels * 0.5f;
            eyes.setPixelDp(Math.max(2f, Math.min(6f, maxW / (eyes.renderer.cw * eyes.density))));
            say("");
        } catch (Throwable t) {
            say(EyesCore.message(t));
        }
    }

    private void say(String s) {
        if (status != null) {
            status.setText(s);
            status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ---- интерфейс ----------------------------------------------------------------------------

    View build(Context c) {
        ctx = c;
        Ui.Spy root = new Ui.Spy(c);
        root.setTag(this);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 12);
        col.setPadding(pad, Ui.dp(c, 4), pad, pad);

        FrameLayout stage = new FrameLayout(c);
        stage.setBackground(Ui.round(Ui.card(), Ui.dp(c, 14), 0, 0));
        stage.setMinimumHeight(Ui.dp(c, 96));
        int sp = Ui.dp(c, 12);
        stage.setPadding(sp, sp, sp, sp);
        eyes = new EyesView(c, Skin.parse(Builtins.JSON[Builtins.DEFAULT]));
        eyes.preview = true;
        eyes.brain.sleepOn = false;
        eyes.setEdge(Overlay.isDarkTheme() ? Renderer.EDGE_HALO : Renderer.EDGE_SHADOW);
        stage.addView(eyes, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.eyes.add(eyes);
        col.addView(stage, Ui.fill());

        nameField = new EditText(c);
        nameField.setText(startName);
        nameField.setHint("Название");
        nameField.setSingleLine(true);
        nameField.setTextColor(Ui.text());
        nameField.setHintTextColor(Ui.gray());
        LinearLayout.LayoutParams nlp = Ui.fill();
        nlp.topMargin = Ui.dp(c, 6);
        col.addView(nameField, nlp);

        status = Ui.label(c, "", 13, 0xFFFF5A5F, false);
        status.setPadding(0, Ui.dp(c, 6), 0, 0);
        status.setVisibility(View.GONE);
        col.addView(status, Ui.fill());

        LinearLayout layersRow = row(c);
        for (int i = 0; i < LAYER_NAMES.length; i++) {
            TextView chip = Ui.chip(c, LAYER_NAMES[i]);
            int li = i;
            chip.setOnClickListener(v -> {
                layer = li;
                refresh();
                grid.requestLayout();
            });
            layersRow.addView(chip, chipLp(c));
            layerChips.add(chip);
        }
        col.addView(layersRow, Ui.fill());

        grid = new Grid(c);
        LinearLayout.LayoutParams glp = Ui.fill();
        glp.topMargin = Ui.dp(c, 8);
        col.addView(grid, glp);

        LinearLayout sizeRow = row(c);
        sizeRow.addView(small(c, "Ш −", v -> resize(-1, 0)), chipLp(c));
        sizeRow.addView(small(c, "Ш +", v -> resize(1, 0)), chipLp(c));
        sizeLabel = Ui.label(c, "", 14, Ui.gray(), false);
        sizeLabel.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        sizeRow.addView(sizeLabel, Ui.wrap());
        sizeRow.addView(small(c, "В −", v -> resize(0, -1)), chipLp(c));
        sizeRow.addView(small(c, "В +", v -> resize(0, 1)), chipLp(c));
        col.addView(scroll(c, sizeRow), Ui.fill());

        LinearLayout tools = row(c);
        String[] tn = {"✏️ Кисть", "🧽 Ластик", "🔲 Заливка", "💧 Пипетка"};
        for (int i = 0; i < tn.length; i++) {
            TextView chip = Ui.chip(c, tn[i]);
            int ti = i;
            chip.setOnClickListener(v -> {
                tool = ti;
                refresh();
            });
            tools.addView(chip, chipLp(c));
            toolChips.add(chip);
        }
        symChip = Ui.chip(c, "");
        symChip.setOnClickListener(v -> {
            symmetry = !symmetry;
            refresh();
        });
        tools.addView(symChip, chipLp(c));
        tools.addView(small(c, "↩️ Отменить", v -> undoStep()), chipLp(c));
        tools.addView(small(c, "🗑 Очистить", v -> {
            snapshot();
            Arrays.fill(layers[layer].px, '.');
            changed();
        }), chipLp(c));
        col.addView(scroll(c, tools), Ui.fill());

        paletteRow = row(c);
        col.addView(scroll(c, paletteRow), Ui.fill());
        LinearLayout palOpts = row(c);
        scleraChip = Ui.chip(c, "");
        scleraChip.setOnClickListener(v -> {
            if (palette.isEmpty()) {
                return;
            }
            snapshot();
            Pal p = palette.get(current);
            p.sclera = !p.sclera;
            changed();
        });
        palOpts.addView(scleraChip, chipLp(c));
        TextView recolor = Ui.chip(c, "🎨 Сменить цвет");
        recolor.setOnClickListener(v -> openColors(null));
        palOpts.addView(recolor, chipLp(c));
        lidChip = Ui.chip(c, "");
        lidChip.setOnClickListener(v -> openColors("lid"));
        palOpts.addView(lidChip, chipLp(c));
        lashChip = Ui.chip(c, "");
        lashChip.setOnClickListener(v -> openColors("lash"));
        palOpts.addView(lashChip, chipLp(c));
        col.addView(scroll(c, palOpts), Ui.fill());

        colorGrid = new LinearLayout(c);
        colorGrid.setOrientation(LinearLayout.VERTICAL);
        colorGrid.setVisibility(View.GONE);
        col.addView(colorGrid, Ui.fill());

        LinearLayout opts = row(c);
        twoChip = Ui.chip(c, "");
        twoChip.setOnClickListener(v -> {
            keep.remove("layout");
            two = !two;
            changed();
        });
        opts.addView(twoChip, chipLp(c));
        styleChip = Ui.chip(c, "");
        styleChip.setOnClickListener(v -> {
            style = (style + 1) % 3;
            changed();
        });
        opts.addView(styleChip, chipLp(c));
        creepyChip = Ui.chip(c, "");
        creepyChip.setOnClickListener(v -> {
            creepy = !creepy;
            changed();
        });
        opts.addView(creepyChip, chipLp(c));
        col.addView(scroll(c, opts), Ui.fill());

        TextView help = Ui.label(c, "Яблоко (точка на цвете): по нему ходит зрачок, его закрывают веки. "
                + "Радужка и блик рисуются отдельно и двигаются по яблоку. Зеркало рисует сразу с двух сторон.",
                12, Ui.gray(), false);
        help.setPadding(0, Ui.dp(c, 8), 0, 0);
        col.addView(help, Ui.fill());

        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        refresh();
        rebuildPreview();
        return root;
    }

    private static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(c, 6), 0, 0);
        return r;
    }

    private static HorizontalScrollView scroll(Context c, View content) {
        HorizontalScrollView s = new HorizontalScrollView(c);
        s.setHorizontalScrollBarEnabled(false);
        s.addView(content);
        return s;
    }

    private static LinearLayout.LayoutParams chipLp(Context c) {
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.rightMargin = Ui.dp(c, 6);
        return lp;
    }

    private static TextView small(Context c, String s, View.OnClickListener l) {
        TextView t = Ui.chip(c, s);
        t.setOnClickListener(l);
        return t;
    }

    private void openColors(String target) {
        if (colorGrid.getVisibility() == View.VISIBLE && String.valueOf(colorGrid.getTag())
                .equals(String.valueOf(target))) {
            colorGrid.setVisibility(View.GONE);
            return;
        }
        pickTarget = target;
        colorGrid.setTag(String.valueOf(target));
        colorGrid.removeAllViews();
        TextView title = Ui.label(ctx, target == null ? "Цвет для выбранной ячейки палитры"
                : "lid".equals(target) ? "Цвет века (закрытый глаз). Последний: прозрачное" : "lash".equals(target)
                ? "Цвет ресниц (кромка века)" : "Новый цвет в палитру", 13, Ui.gray(), false);
        title.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 4));
        colorGrid.addView(title);
        LinearLayout r = null;
        int per = 7;
        int size = Ui.dp(ctx, 34);
        int count = COLORS.length + ("lid".equals(target) ? 1 : 0);
        for (int i = 0; i < count; i++) {
            if (i % per == 0) {
                r = new LinearLayout(ctx);
                r.setOrientation(LinearLayout.HORIZONTAL);
                colorGrid.addView(r);
            }
            boolean none = i == COLORS.length;
            int color = none ? 0 : COLORS[i];
            View sw = new View(ctx);
            sw.setBackground(Ui.round(color, Ui.dp(ctx, 8), Ui.gray(), Ui.dp(ctx, 1)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(Ui.dp(ctx, 3), Ui.dp(ctx, 3), Ui.dp(ctx, 3), Ui.dp(ctx, 3));
            r.addView(sw, lp);
            sw.setOnClickListener(v -> {
                pick(color);
                colorGrid.setVisibility(View.GONE);
            });
        }
        colorGrid.setVisibility(View.VISIBLE);
    }

    private void pick(int color) {
        if ("lid".equals(pickTarget)) {
            snapshot();
            lid = color;
        } else if ("lash".equals(pickTarget)) {
            snapshot();
            lash = color == 0 ? 0xFF1B1B22 : color;
        } else if ("new".equals(pickTarget) || palette.isEmpty()) {
            addColor(color);
            changed();
            return;
        } else {
            snapshot();
            palette.get(current).color = color;
        }
        changed();
    }

    /** Подсветка выбранного, подписи, палитра. */
    void refresh() {
        if (ctx == null) {
            return;
        }
        for (int i = 0; i < layerChips.size(); i++) {
            mark(layerChips.get(i), i == layer);
        }
        for (int i = 0; i < toolChips.size(); i++) {
            mark(toolChips.get(i), i == tool);
        }
        symChip.setText(symmetry ? "↔️ Зеркало: вкл" : "↔️ Зеркало: выкл");
        mark(symChip, symmetry);
        Layer l = layers[layer];
        sizeLabel.setText(l.w + " × " + l.h);
        boolean onEye = layer == L_EYE;
        scleraChip.setVisibility(onEye ? View.VISIBLE : View.GONE);
        if (!palette.isEmpty()) {
            Pal p = palette.get(Math.max(0, Math.min(current, palette.size() - 1)));
            scleraChip.setText(p.sclera ? "👁 Это яблоко" : "👁 Не яблоко");
            mark(scleraChip, p.sclera);
        }
        lidChip.setText(lid == 0 ? "Веко: прозрачное" : "Веко");
        swatchIcon(lidChip, lid);
        lashChip.setText("Ресницы");
        swatchIcon(lashChip, lash);
        twoChip.setText(two ? "👀 Два глаза" : "👁 Один глаз");
        String[] sn = {"Взгляд плавный", "Взгляд резкий", "Взгляд дёрганый"};
        styleChip.setText(sn[style]);
        creepyChip.setText(creepy ? "💀 Жуткие: вкл" : "💀 Жуткие: выкл");
        mark(creepyChip, creepy);

        paletteRow.removeAllViews();
        int size = Ui.dp(ctx, 34);
        for (int i = 0; i < palette.size(); i++) {
            Pal p = palette.get(i);
            Swatch sw = new Swatch(ctx, p);
            boolean sel = i == current;
            sw.setBackground(Ui.round(p.color, Ui.dp(ctx, 8), sel ? Ui.accent() : Ui.gray(),
                    Ui.dp(ctx, sel ? 3 : 1)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = Ui.dp(ctx, 6);
            int idx = i;
            sw.setOnClickListener(v -> {
                current = idx;
                if (tool == T_ERASE || tool == T_PICK) {
                    tool = T_BRUSH;
                }
                refresh();
            });
            paletteRow.addView(sw, lp);
        }
        TextView add = Ui.chip(ctx, "＋");
        add.setOnClickListener(v -> openColors("new"));
        paletteRow.addView(add, new LinearLayout.LayoutParams(size, size));
        grid.invalidate();
    }

    private final class Swatch extends View {
        private final Pal p;
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        Swatch(Context c, Pal p) {
            super(c);
            this.p = p;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (layer == L_EYE && p.sclera) {
                dot.setColor(luma(p.color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF);
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, Ui.dp(getContext(), 3), dot);
            }
        }
    }

    /** Квадратик цвета справа от подписи кнопки. */
    private void swatchIcon(TextView chip, int color) {
        if (color == 0) {
            chip.setCompoundDrawables(null, null, null, null);
            return;
        }
        int s = Ui.dp(ctx, 14);
        android.graphics.drawable.GradientDrawable d = Ui.round(color, Ui.dp(ctx, 3), Ui.gray(), Ui.dp(ctx, 1));
        d.setBounds(0, 0, s, s);
        chip.setCompoundDrawables(null, null, d, null);
        chip.setCompoundDrawablePadding(Ui.dp(ctx, 6));
    }

    private void mark(TextView chip, boolean on) {
        chip.setBackground(Ui.round(Ui.card(), Ui.dp(ctx, 14), Ui.accent(), on ? Ui.dp(ctx, 2) : 0));
    }

    int colorOf(char c) {
        for (Pal p : palette) {
            if (p.c == c) {
                return p.color;
            }
        }
        return 0;
    }

    boolean scleraChar(char c) {
        for (Pal p : palette) {
            if (p.c == c) {
                return p.sclera;
            }
        }
        return false;
    }

    /** Сетка пикселей текущего слоя: клетки, шахматный фон под прозрачным, линии. */
    final class Grid extends View {
        private final Paint fill = new Paint();
        private final Paint line = new Paint();
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int cell = 20;
        private int lastX = -1;
        private int lastY = -1;

        Grid(Context c) {
            super(c);
            line.setColor(0x33808080);
            line.setStrokeWidth(1f);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            Layer l = layers[layer];
            int avail = MeasureSpec.getSize(ws);
            if (avail <= 0) {
                avail = getResources().getDisplayMetrics().widthPixels - Ui.dp(getContext(), 48);
            }
            cell = Math.max(Ui.dp(getContext(), 8), Math.min(Ui.dp(getContext(), 28), avail / Math.max(1, l.w)));
            setMeasuredDimension(avail, cell * l.h);
        }

        private int ox() {
            return (getWidth() - cell * layers[layer].w) / 2;
        }

        @Override
        protected void onDraw(Canvas c) {
            Layer l = layers[layer];
            int ox = ox();
            for (int y = 0; y < l.h; y++) {
                for (int x = 0; x < l.w; x++) {
                    char ch = l.px[y * l.w + x];
                    int left = ox + x * cell;
                    int top = y * cell;
                    int col = ch == '.' ? 0 : colorOf(ch);
                    fill.setColor(col == 0 ? (((x + y) & 1) == 0 ? 0x22808080 : 0x44808080) : col);
                    c.drawRect(left, top, left + cell, top + cell, fill);
                    if (layer == L_EYE && col != 0 && scleraChar(ch)) {
                        dot.setColor(luma(col) > 0.5f ? 0x40000000 : 0x50FFFFFF);
                        c.drawCircle(left + cell / 2f, top + cell / 2f, cell / 7f, dot);
                    }
                }
            }
            for (int x = 0; x <= l.w; x++) {
                c.drawLine(ox + x * cell, 0, ox + x * cell, l.h * cell, line);
            }
            for (int y = 0; y <= l.h; y++) {
                c.drawLine(ox, y * cell, ox + l.w * cell, y * cell, line);
            }
            if (symmetry) {
                line.setColor(0x88FF4F7B);
                float mid = ox + l.w * cell / 2f;
                c.drawLine(mid, 0, mid, l.h * cell, line);
                line.setColor(0x33808080);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN && getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            int x = (int) Math.floor((e.getX() - ox()) / cell);
            int y = (int) Math.floor(e.getY() / cell);
            if (a == MotionEvent.ACTION_DOWN) {
                lastX = x;
                lastY = y;
                paint(x, y, true);
                return true;
            }
            if (a == MotionEvent.ACTION_MOVE) {
                if (x != lastX || y != lastY) {
                    lastX = x;
                    lastY = y;
                    paint(x, y, false);
                }
                return true;
            }
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && getParent() != null) {
                lastX = -1;
                lastY = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }
    }
}
