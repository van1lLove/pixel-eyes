package com.pixeleyes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Набор глаз: пиксельные сетки и характер. Описывается JSON (формат в README
 * репозитория и в Builtins), разбирается здесь один раз, дальше Renderer только
 * читает готовые массивы.
 *
 * Сетка это массив строк, символ = пиксель: '.' и пробел прозрачны, остальные
 * берутся из палитры. Символы из "sclera" это глазное яблоко: по ним ходит
 * радужка и их закрывают веки.
 */
final class Skin {

    static final int FORMAT = 1;
    static final int MAX_EYE = 32;
    static final int MAX_EYES = 12;

    static final String[] FRAME_NAMES = {"closed", "happy", "love", "dizzy", "angry", "sad", "ouch", "surprised"};

    /** Прямоугольная картинка ARGB, 0 = прозрачно. */
    static final class Sprite {
        final int w;
        final int h;
        final int[] px;

        Sprite(int w, int h) {
            this.w = w;
            this.h = h;
            this.px = new int[w * h];
        }

        int at(int x, int y) {
            return x < 0 || y < 0 || x >= w || y >= h ? 0 : px[y * w + x];
        }

        Sprite mirrored() {
            Sprite m = new Sprite(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    m.px[y * w + x] = px[y * w + (w - 1 - x)];
                }
            }
            return m;
        }
    }

    static final class Place {
        int x;
        int y;
        boolean mirror;
        int scale = 1;
    }

    // описание
    String id = "custom";
    String name = "Без имени";
    String author = "";
    String description = "";
    final ArrayList<String> tags = new ArrayList<>();

    // картинки (для левого глаза; правый при mirror берёт отражённые)
    Sprite eye;
    boolean[] interior;           // клетки глазного яблока
    Sprite iris;
    int irisDx;
    int irisDy;
    int pupilColor;               // цвет зрачка внутри радужки: для расширения и сужения
    Sprite highlight;
    int hlDx;
    int hlDy;
    boolean hlFixed;
    int lidColor;
    int lashColor;
    int outlineColor;
    int travelX;
    int travelY;
    final HashMap<String, Sprite> frames = new HashMap<>();
    final ArrayList<Place> places = new ArrayList<>();

    // характер
    float blinkMin = 2.5f;
    float blinkMax = 6f;
    int style;                    // 0 плавно, 1 рывками, 2 дёргано
    float stare;
    float twitch;
    float speed = 1f;
    float baseOpen = 1f;
    float baseSlant;
    boolean independent;
    boolean creepy;

    // геометрия глазного яблока (по interior)
    int boxL;
    int boxT;
    int boxR;
    int boxB;
    int[] colTop;                 // по столбцам: первая и последняя строка яблока, -1 если нет
    int[] colBot;

    /** Разобранный JSON как был (для экспорта и редактора). */
    Map<String, Object> source;

    static final String STYLE_SMOOTH = "smooth";
    static final String STYLE_SNAP = "snap";
    static final String STYLE_TWITCH = "twitch";

    // ---- разбор -----------------------------------------------------------------

    static Skin parse(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("ожидался объект { ... }");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) root;
        return fromMap(m);
    }

    static Skin fromMap(Map<String, Object> m) {
        Skin s = new Skin();
        s.source = m;
        Object fmt = m.get("format");
        if (fmt instanceof Number && ((Number) fmt).intValue() > FORMAT) {
            throw new IllegalArgumentException("формат " + ((Number) fmt).intValue()
                    + " новее этой версии плагина, обновите плагин");
        }
        s.id = cleanId(str(m.get("id"), "custom"));
        s.name = limit(str(m.get("name"), s.id), 40);
        s.author = limit(str(m.get("author"), ""), 40);
        s.description = limit(str(m.get("description"), ""), 200);
        Object tags = m.get("tags");
        if (tags instanceof List) {
            for (Object t : (List<?>) tags) {
                if (t instanceof String && s.tags.size() < 8) {
                    s.tags.add(limit((String) t, 20));
                }
            }
        }

        Map<Character, Integer> pal = palette(m.get("palette"));
        String scleraChars = str(m.get("sclera"), "w");

        List<String> eyeRows = rows(m.get("eye"), "eye");
        if (eyeRows == null) {
            throw new IllegalArgumentException("нет сетки \"eye\": это главное поле, сам глаз");
        }
        s.eye = grid(eyeRows, pal, "eye", MAX_EYE, MAX_EYE);
        s.interior = new boolean[s.eye.w * s.eye.h];
        int count = 0;
        for (int y = 0; y < eyeRows.size(); y++) {
            String r = eyeRows.get(y);
            for (int x = 0; x < s.eye.w && x < r.length(); x++) {
                if (scleraChars.indexOf(r.charAt(x)) >= 0) {
                    s.interior[y * s.eye.w + x] = true;
                    count++;
                }
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("в \"eye\" нет ни одной клетки яблока (символы из \"sclera\": "
                    + scleraChars + ")");
        }
        s.measure();
        s.outlineColor = mostCommonEdge(s);

        List<String> irisRows = rows(m.get("iris"), "iris");
        if (irisRows != null) {
            s.iris = grid(irisRows, pal, "iris", s.eye.w, s.eye.h);
        } else {
            s.iris = new Sprite(2, 2);
            java.util.Arrays.fill(s.iris.px, 0xFF111111);
        }
        int[] io = pair(m.get("iris_offset"), 0, 0);
        s.irisDx = clampI(io[0], -s.eye.w, s.eye.w);
        s.irisDy = clampI(io[1], -s.eye.h, s.eye.h);
        String pupil = str(m.get("pupil"), "");
        s.pupilColor = pupil.length() == 1 && pal.containsKey(pupil.charAt(0)) ? pal.get(pupil.charAt(0)) : 0;

        List<String> hlRows = rows(m.get("highlight"), "highlight");
        if (hlRows != null) {
            s.highlight = grid(hlRows, pal, "highlight", s.eye.w, s.eye.h);
            int[] ho = pair(m.get("highlight_offset"), 0, 0);
            s.hlDx = clampI(ho[0], -s.eye.w, s.eye.w);
            s.hlDy = clampI(ho[1], -s.eye.h, s.eye.h);
            s.hlFixed = bool(m.get("highlight_fixed"), false);
        }

        s.lidColor = color(m.get("lid"), pal, s.outlineColor, "lid");
        s.lashColor = color(m.get("lash"), pal, darker(s.lidColor), "lash");

        int scW = s.boxR - s.boxL + 1;
        int scH = s.boxB - s.boxT + 1;
        int[] tr = pair(m.get("travel"), Math.max(1, (scW - s.iris.w) / 2), Math.max(0, (scH - s.iris.h) / 2));
        s.travelX = clampI(tr[0], 0, 10);
        s.travelY = clampI(tr[1], 0, 10);

        Object frames = m.get("frames");
        if (frames instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) frames).entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!known(key)) {
                    throw new IllegalArgumentException("неизвестный кадр \"" + key + "\", бывают: "
                            + String.join(", ", FRAME_NAMES));
                }
                List<String> fr = rows(e.getValue(), "frames." + key);
                if (fr != null) {
                    Sprite g = grid(fr, pal, "frames." + key, MAX_EYE, MAX_EYE);
                    s.frames.put(key, fit(g, s.eye.w, s.eye.h));
                }
            }
        }

        s.layout(m.get("layout"));
        s.behavior(m.get("behavior"));
        return s;
    }

    private static boolean known(String key) {
        for (String n : FRAME_NAMES) {
            if (n.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private void layout(Object o) {
        Map<?, ?> lm = o instanceof Map ? (Map<?, ?>) o : null;
        Object eyes = lm == null ? null : lm.get("eyes");
        if (eyes instanceof List) {
            for (Object e : (List<?>) eyes) {
                if (!(e instanceof Map)) {
                    continue;
                }
                Map<?, ?> em = (Map<?, ?>) e;
                Place p = new Place();
                p.x = clampI(num(em.get("x"), 0), 0, 120);
                p.y = clampI(num(em.get("y"), 0), 0, 60);
                p.mirror = bool(em.get("mirror"), false);
                p.scale = clampI(num(em.get("scale"), 1), 1, 3);
                places.add(p);
                if (places.size() >= MAX_EYES) {
                    break;
                }
            }
            if (places.isEmpty()) {
                throw new IllegalArgumentException("layout.eyes пустой");
            }
        } else {
            int count = lm == null ? 2 : clampI(num(lm.get("count"), 2), 1, 2);
            int gap = lm == null ? 2 : clampI(num(lm.get("gap"), 2), 0, 32);
            boolean mirror = lm == null || bool(lm.get("mirror"), true);
            Place a = new Place();
            places.add(a);
            if (count == 2) {
                Place b = new Place();
                b.x = eye.w + gap;
                b.mirror = mirror;
                places.add(b);
            }
        }
        // слишком большой холст на экране не нужен: отсекаем заранее
        int w = 0;
        int h = 0;
        for (Place p : places) {
            w = Math.max(w, p.x + eye.w * p.scale);
            h = Math.max(h, p.y + eye.h * p.scale);
        }
        if (w > 128 || h > 64) {
            throw new IllegalArgumentException("глаза не помещаются в 128x64 пикселя (" + w + "x" + h + ")");
        }
    }

    private void behavior(Object o) {
        if (!(o instanceof Map)) {
            return;
        }
        Map<?, ?> b = (Map<?, ?>) o;
        Object blink = b.get("blink");
        if (blink instanceof List && ((List<?>) blink).size() == 2) {
            blinkMin = clampF(fnum(((List<?>) blink).get(0), 2.5f), 0f, 60f);
            blinkMax = clampF(fnum(((List<?>) blink).get(1), 6f), blinkMin, 60f);
        } else if (blink instanceof Number) {
            float v = clampF(((Number) blink).floatValue(), 0f, 60f);
            blinkMin = v * 0.6f;
            blinkMax = v * 1.4f;
        }
        String st = str(b.get("style"), STYLE_SMOOTH).toLowerCase(Locale.ROOT);
        style = STYLE_SNAP.equals(st) ? 1 : STYLE_TWITCH.equals(st) ? 2 : 0;
        stare = clampF(fnum(b.get("stare"), 0f), 0f, 1f);
        twitch = clampF(fnum(b.get("twitch"), 0f), 0f, 1f);
        speed = clampF(fnum(b.get("speed"), 1f), 0.2f, 4f);
        baseOpen = clampF(fnum(b.get("open"), 1f), 0.3f, 1f);
        baseSlant = clampF(fnum(b.get("slant"), 0f), -1f, 1f);
        independent = bool(b.get("independent"), false);
        creepy = bool(b.get("creepy"), false);
    }

    private void measure() {
        int w = eye.w;
        int h = eye.h;
        colTop = new int[w];
        colBot = new int[w];
        boxL = w;
        boxT = h;
        boxR = -1;
        boxB = -1;
        for (int x = 0; x < w; x++) {
            colTop[x] = -1;
            colBot[x] = -1;
            for (int y = 0; y < h; y++) {
                if (interior[y * w + x]) {
                    if (colTop[x] < 0) {
                        colTop[x] = y;
                    }
                    colBot[x] = y;
                    boxL = Math.min(boxL, x);
                    boxR = Math.max(boxR, x);
                    boxT = Math.min(boxT, y);
                    boxB = Math.max(boxB, y);
                }
            }
        }
    }

    /** Цвет контура: самый частый непрозрачный цвет на краю яблока. */
    private static int mostCommonEdge(Skin s) {
        HashMap<Integer, Integer> count = new HashMap<>();
        int w = s.eye.w;
        int h = s.eye.h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = s.eye.px[y * w + x];
                if (c == 0 || s.interior[y * w + x]) {
                    continue;
                }
                count.merge(c, 1, Integer::sum);
            }
        }
        int best = 0xFF1A1A1A;
        int n = 0;
        for (Map.Entry<Integer, Integer> e : count.entrySet()) {
            if (e.getValue() > n) {
                n = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    // ---- помощники разбора -------------------------------------------------------

    static String cleanId(String v) {
        StringBuilder b = new StringBuilder();
        for (char c : v.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                b.append(c);
            } else if (c == ' ') {
                b.append('_');
            }
            if (b.length() >= 32) {
                break;
            }
        }
        return b.length() == 0 ? "custom" : b.toString();
    }

    private static String limit(String v, int n) {
        v = v.replace('\n', ' ').trim();
        return v.length() > n ? v.substring(0, n) : v;
    }

    private static String str(Object o, String d) {
        return o instanceof String ? (String) o : d;
    }

    private static boolean bool(Object o, boolean d) {
        return o instanceof Boolean ? (Boolean) o : d;
    }

    private static int num(Object o, int d) {
        return o instanceof Number ? (int) Math.round(((Number) o).doubleValue()) : d;
    }

    private static float fnum(Object o, float d) {
        return o instanceof Number ? ((Number) o).floatValue() : d;
    }

    static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float clampF(float v, float lo, float hi) {
        if (Float.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    private static int[] pair(Object o, int dx, int dy) {
        if (o instanceof List && ((List<?>) o).size() == 2) {
            return new int[]{num(((List<?>) o).get(0), dx), num(((List<?>) o).get(1), dy)};
        }
        return new int[]{dx, dy};
    }

    private static Map<Character, Integer> palette(Object o) {
        LinkedHashMap<Character, Integer> pal = new LinkedHashMap<>();
        if (o == null) {
            return pal;
        }
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("\"palette\" должна быть объектом {\"символ\": \"#цвет\"}");
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.length() != 1) {
                throw new IllegalArgumentException("в палитре ключ \"" + k + "\": нужен ровно один символ");
            }
            char c = k.charAt(0);
            if (c == '.' || c == ' ') {
                throw new IllegalArgumentException("символы '.' и пробел заняты под прозрачность");
            }
            Object v = e.getValue();
            if (!(v instanceof String)) {
                throw new IllegalArgumentException("цвет для '" + c + "' должен быть строкой \"#RRGGBB\"");
            }
            pal.put(c, parseColor((String) v, "palette." + c));
        }
        return pal;
    }

    static int parseColor(String v, String where) {
        String t = v.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        try {
            if (t.length() == 3) {
                int r = Integer.parseInt(t.substring(0, 1), 16);
                int g = Integer.parseInt(t.substring(1, 2), 16);
                int b = Integer.parseInt(t.substring(2, 3), 16);
                return 0xFF000000 | (r * 17) << 16 | (g * 17) << 8 | b * 17;
            }
            if (t.length() == 6) {
                return 0xFF000000 | Integer.parseInt(t, 16);
            }
            if (t.length() == 8) {
                return (int) Long.parseLong(t, 16);
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(where + ": непонятный цвет \"" + v + "\", нужен #RRGGBB");
    }

    private static int color(Object o, Map<Character, Integer> pal, int d, String where) {
        if (!(o instanceof String) || ((String) o).isEmpty()) {
            return d;
        }
        String v = (String) o;
        if (v.length() == 1 && pal.containsKey(v.charAt(0))) {
            return pal.get(v.charAt(0));
        }
        if ("none".equalsIgnoreCase(v) || "transparent".equalsIgnoreCase(v)) {
            return 0;
        }
        return parseColor(v, where);
    }

    static int darker(int c) {
        int a = c >>> 24;
        int r = ((c >> 16) & 0xFF) * 6 / 10;
        int g = ((c >> 8) & 0xFF) * 6 / 10;
        int b = (c & 0xFF) * 6 / 10;
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static List<String> rows(Object o, String where) {
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            // одной строкой через переносы тоже можно
            ArrayList<String> r = new ArrayList<>();
            for (String line : ((String) o).split("\n")) {
                r.add(line);
            }
            return r;
        }
        if (!(o instanceof List)) {
            throw new IllegalArgumentException("\"" + where + "\" должна быть массивом строк");
        }
        ArrayList<String> r = new ArrayList<>();
        for (Object x : (List<?>) o) {
            if (!(x instanceof String)) {
                throw new IllegalArgumentException("в \"" + where + "\" все строки должны быть в кавычках");
            }
            r.add((String) x);
        }
        if (r.isEmpty()) {
            throw new IllegalArgumentException("\"" + where + "\" пустая");
        }
        return r;
    }

    private static Sprite grid(List<String> rows, Map<Character, Integer> pal, String where, int maxW, int maxH) {
        int w = 0;
        for (String r : rows) {
            w = Math.max(w, r.length());
        }
        int h = rows.size();
        if (w == 0) {
            throw new IllegalArgumentException("\"" + where + "\" пустая");
        }
        if (w > maxW || h > maxH) {
            throw new IllegalArgumentException("\"" + where + "\" " + w + "x" + h + ", больше допустимого "
                    + maxW + "x" + maxH);
        }
        Sprite s = new Sprite(w, h);
        for (int y = 0; y < h; y++) {
            String r = rows.get(y);
            for (int x = 0; x < r.length(); x++) {
                char c = r.charAt(x);
                if (c == '.' || c == ' ') {
                    continue;
                }
                Integer col = pal.get(c);
                if (col == null) {
                    throw new IllegalArgumentException("в \"" + where + "\" символ '" + c + "' (строка "
                            + (y + 1) + "), а в палитре его нет");
                }
                s.px[y * w + x] = col;
            }
        }
        return s;
    }

    /** Подгоняет кадр под размер глаза: по центру, лишнее обрезается. */
    private static Sprite fit(Sprite g, int w, int h) {
        if (g.w == w && g.h == h) {
            return g;
        }
        Sprite out = new Sprite(w, h);
        int ox = (w - g.w) / 2;
        int oy = (h - g.h) / 2;
        for (int y = 0; y < g.h; y++) {
            for (int x = 0; x < g.w; x++) {
                int tx = x + ox;
                int ty = y + oy;
                if (tx >= 0 && ty >= 0 && tx < w && ty < h) {
                    out.px[ty * w + tx] = g.px[y * g.w + x];
                }
            }
        }
        return out;
    }

    /** Пиксель глазного яблока (у отражённого глаза столбец зеркальный). */
    boolean inside(int x, int y, boolean mirror) {
        if (mirror) {
            x = eye.w - 1 - x;
        }
        return x >= 0 && y >= 0 && x < eye.w && y < eye.h && interior[y * eye.w + x];
    }

    /** Центр яблока по горизонтали, в пикселях сетки (половинки допустимы). */
    float centerX() {
        return (boxL + boxR) / 2f;
    }

    float centerY() {
        return (boxT + boxB) / 2f;
    }

    /** JSON для экспорта: исходный, как пришёл. */
    String toJson() {
        return source == null ? "{}" : MiniJson.write(source);
    }
}
