package com.pixeleyes;

import java.util.HashMap;

/**
 * Рисует лицо в массив ARGB размером с холст в пикселях сетки. Без Android:
 * один и тот же код работает на телефоне и на ПК (там из него делаются
 * картинки для проверки и для каталога).
 *
 * Порядок для каждого глаза: глаз из сетки, радужка (только по яблоку),
 * блик, веки. Дальше частицы над глазами и тень под всем.
 */
final class Renderer {

    static final int MX = 3;      // поля холста: по бокам
    static final int MT = 7;      // сверху место для частиц
    static final int MB = 4;      // снизу место для слёз

    final Skin skin;
    final int cw;
    final int ch;
    final int[] buf;
    private final int[] tmp;
    private final int[] out;
    /** Обводка вокруг глаз: 0 нет, 1 тень вниз-вправо (светлая тема), 2 светлый ореол (тёмная тема). */
    static final int EDGE_NONE = 0;
    static final int EDGE_SHADOW = 1;
    static final int EDGE_HALO = 2;
    int edge = EDGE_SHADOW;

    private final Skin.Sprite eyeM;
    private final Skin.Sprite irisM;
    private final HashMap<String, Skin.Sprite> framesM = new HashMap<>();
    // радужка с расширенным или суженным зрачком: уровни -2..2, отдельно для отражённой
    private final Skin.Sprite[] pupilLv = new Skin.Sprite[5];
    private final Skin.Sprite[] pupilLvM = new Skin.Sprite[5];
    private final int irisMain;

    Renderer(Skin s) {
        skin = s;
        int w = 0;
        int h = 0;
        for (Skin.Place p : s.places) {
            w = Math.max(w, p.x + s.eye.w * p.scale);
            h = Math.max(h, p.y + s.eye.h * p.scale);
        }
        cw = w + 2 * MX;
        ch = h + MT + MB;
        buf = new int[cw * ch];
        out = new int[cw * ch];
        tmp = new int[s.eye.w * s.eye.h];
        eyeM = s.eye.mirrored();
        irisM = s.iris.mirrored();
        for (java.util.Map.Entry<String, Skin.Sprite> e : s.frames.entrySet()) {
            framesM.put(e.getKey(), e.getValue().mirrored());
        }
        irisMain = mainColor(s.iris, s.pupilColor);
        for (int lv = -2; lv <= 2; lv++) {
            Skin.Sprite v = pupilVariant(s.iris, lv);
            pupilLv[lv + 2] = v;
            pupilLvM[lv + 2] = v.mirrored();
        }
    }

    /** Центр глаза i на холсте (в пикселях сетки), для расчёта взгляда. */
    float eyeCenterX(int i) {
        Skin.Place p = skin.places.get(i);
        float cx = p.mirror ? skin.eye.w - 1 - skin.centerX() : skin.centerX();
        return MX + p.x + (cx + 0.5f) * p.scale;
    }

    float eyeCenterY(int i) {
        Skin.Place p = skin.places.get(i);
        return MT + p.y + (skin.centerY() + 0.5f) * p.scale;
    }

    /** Верх глаза i на холсте: над ним рождаются частицы. */
    float eyeTop(int i) {
        Skin.Place p = skin.places.get(i);
        return MT + p.y + skin.boxT * p.scale;
    }

    float eyeBottom(int i) {
        Skin.Place p = skin.places.get(i);
        return MT + p.y + (skin.boxB + 1) * p.scale;
    }

    /** Рисует кадр; результат в out(). */
    int[] render(Face f) {
        java.util.Arrays.fill(buf, 0);
        float faceCx = 0f;
        for (int i = 0; i < f.n; i++) {
            faceCx += eyeCenterX(i);
        }
        faceCx /= Math.max(1, f.n);
        for (int i = 0; i < f.n && i < skin.places.size(); i++) {
            Skin.Place p = skin.places.get(i);
            int side;
            if (f.n == 1) {
                side = 0;
            } else {
                float ex = eyeCenterX(i);
                side = ex < faceCx - 0.5f ? 1 : ex > faceCx + 0.5f ? -1 : 0;
            }
            renderEye(f, i, p.mirror, side);
            blit(tmp, skin.eye.w, skin.eye.h, MX + p.x + f.shakeX, MT + p.y + f.shakeY, p.scale);
        }
        for (Face.Particle pt : f.particles) {
            drawParticle(pt);
        }
        if (edge == EDGE_SHADOW) {
            applyShadow();
        } else if (edge == EDGE_HALO) {
            applyHalo();
        } else {
            System.arraycopy(buf, 0, out, 0, buf.length);
        }
        return out;
    }

    int[] out() {
        return out;
    }

    // ---- один глаз ---------------------------------------------------------------

    private void renderEye(Face f, int i, boolean mirror, int side) {
        Skin s = skin;
        int w = s.eye.w;
        int h = s.eye.h;
        if (f.frame != null) {
            Skin.Sprite fr = (mirror ? framesM : s.frames).get(f.frame);
            if (fr != null) {
                System.arraycopy(fr.px, 0, tmp, 0, tmp.length);
                return;
            }
        }
        Skin.Sprite eye = mirror ? eyeM : s.eye;
        System.arraycopy(eye.px, 0, tmp, 0, tmp.length);

        float cx = mirror ? w - 1 - s.centerX() : s.centerX();
        float cy = s.centerY();
        int dx = mirror ? -s.irisDx : s.irisDx;
        float ix = cx + dx + Math.round(clamp1(f.gx[i]) * s.travelX);
        float iy = cy + s.irisDy + Math.round(clamp1(f.gy[i]) * s.travelY);

        if (f.pupilMode == Face.PUPIL_HEART) {
            drawHeartPupil(ix, iy, mirror);
        } else if (f.pupilMode == Face.PUPIL_SPIRAL) {
            drawSpiral(ix, iy, mirror, f.time * (side >= 0 ? 1 : -1));
        } else if (f.pupilMode != Face.PUPIL_OUCH) {
            int lv = Math.max(-2, Math.min(2, Math.round((f.pupil - 1f) / 0.22f)));
            Skin.Sprite iris = (mirror ? pupilLvM : pupilLv)[lv + 2];
            int x0 = Math.round(ix - (iris.w - 1) / 2f);
            int y0 = Math.round(iy - (iris.h - 1) / 2f);
            for (int y = 0; y < iris.h; y++) {
                for (int x = 0; x < iris.w; x++) {
                    int c = iris.px[y * iris.w + x];
                    if (c != 0) {
                        plotInside(x0 + x, y0 + y, c, mirror);
                    }
                }
            }
        }
        if (s.highlight != null && f.pupilMode == Face.PUPIL_NORMAL) {
            // блик не отражаем: свет падает с одной стороны на оба глаза
            float hx = (s.hlFixed ? cx : ix) + s.hlDx;
            float hy = (s.hlFixed ? cy : iy) + s.hlDy;
            int x0 = Math.round(hx - (s.highlight.w - 1) / 2f);
            int y0 = Math.round(hy - (s.highlight.h - 1) / 2f);
            for (int y = 0; y < s.highlight.h; y++) {
                for (int x = 0; x < s.highlight.w; x++) {
                    int c = s.highlight.px[y * s.highlight.w + x];
                    if (c != 0) {
                        plotInside(x0 + x, y0 + y, c, mirror);
                    }
                }
            }
        }
        float top = f.openTop[i];
        float bot = f.openBot[i];
        if (f.pupilMode == Face.PUPIL_OUCH) {
            top = 0f;
            bot = 1f;
        }
        boolean ouch = f.pupilMode == Face.PUPIL_OUCH;
        lids(top, bot, f.arcBot[i], f.slant, side, mirror, cx, !ouch);
        if (ouch) {
            chevron(side, mirror);
        }
    }

    private void plotInside(int x, int y, int c, boolean mirror) {
        if (skin.inside(x, y, mirror)) {
            tmp[y * skin.eye.w + x] = blend(c, tmp[y * skin.eye.w + x]);
        }
    }

    /**
     * Веки. Верхнее опускается к открытию top (1 открыто, 0 до низа), нижнее
     * поднимается. slant наклоняет верхнее (+ злые: внутренний угол ниже),
     * arc выгибает нижнее дугой вверх (улыбка "^").
     */
    private void lids(float top, float bot, float arc, float slant, int side, boolean mirror, float cx,
                      boolean line) {
        Skin s = skin;
        int w = s.eye.w;
        int T = s.boxT;
        int B = s.boxB;
        float H = B - T + 1;
        float half = Math.max(1f, (s.boxR - s.boxL + 1) / 2f);
        if (top >= 0.999f && bot >= 0.999f && Math.abs(slant) < 0.01f && arc < 0.01f) {
            return;
        }
        for (int x = 0; x < w; x++) {
            int sx = mirror ? w - 1 - x : x;
            int ct = s.colTop[sx];
            int cb = s.colBot[sx];
            if (ct < 0) {
                continue;
            }
            float nx = Math.max(-1f, Math.min(1f, (x - cx) / half));
            float tilt;
            if (side != 0) {
                tilt = slant * side * nx;
            } else {
                tilt = slant * (0.5f - Math.abs(nx));
            }
            float yT = T + (1f - top) * H + tilt * H * 0.4f;
            float yB = B + 1 - (1f - bot) * H - arc * H * 0.45f * (1f - nx * nx);
            boolean closed = yT >= yB - 0.5f;
            int lashRow = -1;
            int lashRow2 = -1;
            if (closed) {
                // закрытый глаз: линия века дугой. arc > 0 улыбка "∩" (радость,
                // подмигивание), иначе спокойная "‿"
                float bend = arc > 0.05f ? -arc * H * 0.4f : H * 0.12f;
                float row = T + H * 0.6f + bend * (1f - nx * nx);
                lashRow = Math.max(ct, Math.min(cb, Math.round(row)));
                if (H >= 11 && arc > 0.05f) {
                    lashRow2 = Math.max(ct, Math.min(cb, lashRow + 1));
                }
            }
            int lastTop = -1;
            int firstBot = -1;
            for (int y = ct; y <= cb; y++) {
                if (!s.interior[y * w + sx]) {
                    continue;
                }
                boolean cover;
                if (closed) {
                    cover = true;
                } else if (y + 0.5f < yT) {
                    cover = true;
                    lastTop = y;
                } else if (y + 0.5f > yB) {
                    cover = true;
                    if (firstBot < 0) {
                        firstBot = y;
                    }
                } else {
                    cover = false;
                }
                if (cover) {
                    tmp[y * w + x] = s.lidColor;
                }
            }
            if (closed) {
                if (line && s.lashColor != 0 && s.interior[lashRow * w + sx]) {
                    tmp[lashRow * w + x] = s.lashColor;
                    if (lashRow2 >= 0 && s.interior[lashRow2 * w + sx]) {
                        tmp[lashRow2 * w + x] = s.lashColor;
                    }
                }
            } else {
                if (lastTop >= 0 && s.lashColor != 0) {
                    tmp[lastTop * w + x] = s.lashColor;
                }
                if (firstBot >= 0 && s.lashColor != 0 && arc > 0.3f) {
                    tmp[firstBot * w + x] = s.lashColor;
                }
            }
        }
    }

    /** "><": глаза зажмурены от тычка. Плечи под 45 градусов, остриё к переносице. */
    private void chevron(int side, boolean mirror) {
        Skin s = skin;
        int w = s.eye.w;
        int boxW = s.boxR - s.boxL + 1;
        int boxH = s.boxB - s.boxT + 1;
        int arm = Math.max(1, Math.min(boxW - 2, boxH - 2) / 2);
        int cx = Math.round((s.boxL + s.boxR) / 2f);
        if (mirror) {
            cx = w - 1 - cx;
        }
        int cy = Math.round((s.boxT + s.boxB) / 2f);
        int dir = side == 0 ? 1 : side;
        int c = s.lashColor != 0 ? s.lashColor : 0xFF1A1A1A;
        int tipX = cx + dir * arm / 2;
        for (int k = 0; k <= arm; k++) {
            int x = tipX - dir * k;
            for (int y : new int[]{cy - k, cy + k}) {
                if (x >= 0 && x < w && y >= 0 && y < s.eye.h && s.inside(x, y, mirror)) {
                    tmp[y * w + x] = c;
                }
            }
        }
    }

    private static final String[] HEART5 = {".#.#.", "#####", "#####", ".###.", "..#.."};
    private static final String[] HEART3 = {"#.#", "###", ".#."};

    private void drawHeartPupil(float ix, float iy, boolean mirror) {
        String[] g = Math.min(skin.iris.w, skin.iris.h) >= 5 ? HEART5 : HEART3;
        int x0 = Math.round(ix - (g[0].length() - 1) / 2f);
        int y0 = Math.round(iy - (g.length - 1) / 2f);
        for (int y = 0; y < g.length; y++) {
            for (int x = 0; x < g[y].length(); x++) {
                if (g[y].charAt(x) == '#') {
                    plotInside(x0 + x, y0 + y, 0xFFFF4F7B, mirror);
                }
            }
        }
        plotInside(x0 + 1, y0 + (g.length >= 5 ? 1 : 0), 0xFFFFD6E0, mirror);
    }

    private void drawSpiral(float ix, float iy, boolean mirror, float t) {
        int r = Math.max(2, Math.max(skin.iris.w, skin.iris.h) / 2 + 1);
        int c1 = skin.pupilColor != 0 ? skin.pupilColor : 0xFF1A1A1A;
        int c2 = irisMain != 0 ? irisMain : 0xFF8F6BFF;
        for (int y = -r; y <= r; y++) {
            for (int x = -r; x <= r; x++) {
                float d = (float) Math.sqrt(x * x + y * y);
                if (d > r + 0.3f) {
                    continue;
                }
                double a = Math.atan2(y, x) + d * 1.7 - t * 7.0;
                double m = ((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
                plotInside(Math.round(ix) + x, Math.round(iy) + y, m < Math.PI ? c1 : c2, mirror);
            }
        }
    }

    // ---- холст ----------------------------------------------------------------------

    private void blit(int[] src, int w, int h, int ox, int oy, int scale) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = src[y * w + x];
                if (c == 0) {
                    continue;
                }
                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) {
                        put(ox + x * scale + sx, oy + y * scale + sy, c);
                    }
                }
            }
        }
    }

    private void put(int x, int y, int c) {
        if (x < 0 || y < 0 || x >= cw || y >= ch) {
            return;
        }
        int k = y * cw + x;
        buf[k] = blend(c, buf[k]);
    }

    /** Тень на пиксель вниз-вправо: глаза видно и на светлом, и на тёмном фоне. */
    private void applyShadow() {
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int k = y * cw + x;
                int c = buf[k];
                if ((c >>> 24) != 0) {
                    out[k] = c;
                    continue;
                }
                int src = x > 0 && y > 0 ? buf[k - cw - 1] : 0;
                out[k] = (src >>> 24) > 0x40 ? 0x55000000 : 0;
            }
        }
    }

    /** Светлый контур в пиксель: тёмные линии видно и на тёмном фоне. */
    private void applyHalo() {
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int k = y * cw + x;
                int c = buf[k];
                if ((c >>> 24) != 0) {
                    out[k] = c;
                    continue;
                }
                boolean near = (x > 0 && (buf[k - 1] >>> 24) > 0x40)
                        || (x + 1 < cw && (buf[k + 1] >>> 24) > 0x40)
                        || (y > 0 && (buf[k - cw] >>> 24) > 0x40)
                        || (y + 1 < ch && (buf[k + cw] >>> 24) > 0x40);
                out[k] = near ? 0x4DFFFFFF : 0;
            }
        }
    }

    // ---- частицы --------------------------------------------------------------------

    private static final String[][] GLYPHS = {
            {"####", "..#.", ".#..", "####"},                              // Z
            {".#.#.", "#####", ".###.", "..#.."},                         // сердце
            {"##.##", "#...#", ".....", "#...#", "##.##"},                // злость
            {".#.", "###", "###", ".#."},                                 // капля
            {"#", "#", "#", ".", "#"},                                    // !
            {".##", ".#.", ".#.", "##.", "##."},                          // нота
            {".#.", "###", ".#."},                                        // искра
            {"###", "..#", ".#.", "...", ".#."},                          // ?
    };
    private static final int[] GLYPH_COLORS = {
            0xFFFFFFFF, 0xFFFF4F7B, 0xFFFF3B3B, 0xFF7FD4FF, 0xFFFFD23F, 0xFFFFFFFF, 0xFFFFE066, 0xFFFFFFFF,
    };

    private void drawParticle(Face.Particle p) {
        if (p.type < 0 || p.type >= GLYPHS.length) {
            return;
        }
        String[] g = GLYPHS[p.type];
        float fade = p.life <= 0 ? 1f : 1f - p.age / p.life;
        if (fade <= 0.05f) {
            return;
        }
        int alpha = Math.round(Math.min(1f, fade * 1.6f) * 255);
        int color = (alpha << 24) | (GLYPH_COLORS[p.type] & 0xFFFFFF);
        int edge = (alpha * 3 / 4 << 24) | 0x1A1A1A;
        int x0 = Math.round(p.x - (g[0].length() - 1) / 2f);
        int y0 = Math.round(p.y - (g.length - 1) / 2f);
        // контур сначала, потом заливка
        for (int y = 0; y < g.length; y++) {
            for (int x = 0; x < g[y].length(); x++) {
                if (g[y].charAt(x) != '#') {
                    continue;
                }
                for (int[] d : NEIGHBOURS) {
                    int nx = x + d[0];
                    int ny = y + d[1];
                    boolean solid = ny >= 0 && ny < g.length && nx >= 0 && nx < g[ny].length()
                            && g[ny].charAt(nx) == '#';
                    if (!solid) {
                        putIfEmpty(x0 + nx, y0 + ny, edge);
                    }
                }
            }
        }
        for (int y = 0; y < g.length; y++) {
            for (int x = 0; x < g[y].length(); x++) {
                if (g[y].charAt(x) == '#') {
                    put(x0 + x, y0 + y, color);
                }
            }
        }
    }

    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private void putIfEmpty(int x, int y, int c) {
        if (x < 0 || y < 0 || x >= cw || y >= ch) {
            return;
        }
        int k = y * cw + x;
        if ((buf[k] >>> 24) == 0) {
            buf[k] = c;
        }
    }

    // ---- зрачок ---------------------------------------------------------------------

    /** Самый частый цвет радужки, не считая зрачка. */
    private static int mainColor(Skin.Sprite iris, int pupil) {
        HashMap<Integer, Integer> n = new HashMap<>();
        for (int c : iris.px) {
            if (c != 0 && c != pupil) {
                n.merge(c, 1, Integer::sum);
            }
        }
        int best = 0;
        int cnt = 0;
        for (java.util.Map.Entry<Integer, Integer> e : n.entrySet()) {
            if (e.getValue() > cnt) {
                cnt = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** Радужка с зрачком шире (lv > 0) или уже (lv < 0). */
    private Skin.Sprite pupilVariant(Skin.Sprite iris, int lv) {
        Skin.Sprite cur = new Skin.Sprite(iris.w, iris.h);
        System.arraycopy(iris.px, 0, cur.px, 0, iris.px.length);
        int pc = skin.pupilColor;
        if (pc == 0 || lv == 0) {
            return cur;
        }
        for (int step = 0; step < Math.abs(lv); step++) {
            Skin.Sprite next = new Skin.Sprite(cur.w, cur.h);
            System.arraycopy(cur.px, 0, next.px, 0, cur.px.length);
            int kept = 0;
            for (int y = 0; y < cur.h; y++) {
                for (int x = 0; x < cur.w; x++) {
                    int c = cur.at(x, y);
                    boolean isPupil = c == pc;
                    boolean nearPupil = false;
                    boolean allPupil = true;
                    for (int[] d : NEIGHBOURS) {
                        int nc = cur.at(x + d[0], y + d[1]);
                        if (nc == pc) {
                            nearPupil = true;
                        } else {
                            allPupil = false;
                        }
                    }
                    if (lv > 0 && !isPupil && c != 0 && nearPupil) {
                        next.px[y * cur.w + x] = pc;
                    } else if (lv < 0 && isPupil && !allPupil) {
                        next.px[y * cur.w + x] = irisMain != 0 ? irisMain : c;
                    }
                    if (next.px[y * cur.w + x] == pc) {
                        kept++;
                    }
                }
            }
            if (lv < 0 && kept == 0) {
                // сузили в ноль: оставляем точку в середине
                int bx = -1;
                int by = -1;
                float bd = Float.MAX_VALUE;
                for (int y = 0; y < cur.h; y++) {
                    for (int x = 0; x < cur.w; x++) {
                        if (cur.at(x, y) == pc) {
                            float d = Math.abs(x - (cur.w - 1) / 2f) + Math.abs(y - (cur.h - 1) / 2f);
                            if (d < bd) {
                                bd = d;
                                bx = x;
                                by = y;
                            }
                        }
                    }
                }
                if (bx >= 0) {
                    next.px[by * cur.w + bx] = pc;
                }
                return next;
            }
            cur = next;
        }
        return cur;
    }

    // ---- цвет -----------------------------------------------------------------------

    static float clamp1(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    static int blend(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 255 || dst == 0) {
            return src;
        }
        if (sa == 0) {
            return dst;
        }
        int da = dst >>> 24;
        float a = sa / 255f;
        int oa = Math.min(255, sa + Math.round(da * (1 - a)));
        int r = Math.round(((src >> 16) & 0xFF) * a + ((dst >> 16) & 0xFF) * (1 - a));
        int g = Math.round(((src >> 8) & 0xFF) * a + ((dst >> 8) & 0xFF) * (1 - a));
        int b = Math.round((src & 0xFF) * a + (dst & 0xFF) * (1 - a));
        return oa << 24 | r << 16 | g << 8 | b;
    }
}
