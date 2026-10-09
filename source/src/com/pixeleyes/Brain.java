package com.pixeleyes;

import java.util.Iterator;
import java.util.Random;

/**
 * Характер глаз: куда смотреть и что чувствовать прямо сейчас. Получает всё,
 * что происходит (палец, набор текста, события чата, тычки по самим глазам), и
 * каждый кадр выдаёт Face. Время в секундах, источник времени снаружи, поэтому
 * поведение проверяется на ПК без Android.
 *
 * Внимание по старшинству: перетаскивание, тычок, палец на экране, набор
 * текста, "смотрит на тебя" после набора, взгляд на событие, безделье.
 */
final class Brain {

    // эмоции
    static final int NEUTRAL = 0;
    static final int HAPPY = 1;
    static final int LOVE = 2;
    static final int SURPRISED = 3;
    static final int ANGRY = 4;
    static final int SAD = 5;
    static final int DIZZY = 6;
    static final int OUCH = 7;
    static final int WINK = 8;
    static final int SUSPICIOUS = 9;
    static final int SCARED = 10;
    static final int STARE = 11;
    static final int FOCUS = 12;
    static final int CURIOUS = 13;
    static final int SLEEPY = 14;
    static final int ASLEEP = 15;

    static final String[] NAMES = {"neutral", "happy", "love", "surprised", "angry", "sad", "dizzy", "ouch",
            "wink", "suspicious", "scared", "stare", "focus", "curious", "sleepy", "asleep"};

    // настройки
    boolean followTouch = true;
    boolean followTyping = true;
    boolean stareAfterTyping = true;
    boolean sleepOn = true;
    boolean blinkOn = true;
    float sleepAfter = 90f;
    boolean night;
    boolean lowBattery;

    final Skin skin;
    final Renderer geo;
    final Face face;
    private final Random rnd;
    private final int n;

    // где глаза на экране (центры в пикселях экрана) и сколько пикселей экрана в пикселе сетки
    private final float[] ex;
    private final float[] ey;
    private float unit = 8f;
    private float reach = 300f;
    private boolean geometry;

    float now;
    private float lastNow = -1f;
    /** Как часто нужны кадры: 0 спать до события, 1 редко (частицы), 2 средне, 3 каждый кадр. */
    int pace;

    // входы
    private boolean touching;
    private float tx;
    private float ty;
    private float touchUpAt = -99f;
    private float typedAt = -99f;
    private float caretX = Float.NaN;
    private float caretY = Float.NaN;
    private float stareUntil = -99f;
    private boolean stareArmed;
    private float delAt = -99f;
    private int delRun;
    private float glanceUntil = -99f;
    private float glanceX;
    private float glanceY;
    private float lastInteraction;
    private boolean dragging;
    private float dragVX;
    private float dragVY;
    private int pokes;
    private float lastPoke = -99f;
    private boolean napping;
    private float peekUntil = -99f;
    private float curiousUntil = -99f;

    // эмоция
    int emo = NEUTRAL;
    private float emoUntil;
    private float emoStart;
    // следующая эмоция после текущей (испуг, потом слёзы)
    private int nextEmo = -1;
    private float nextEmoSec;

    // безделье
    private final float[] idleX;
    private final float[] idleY;
    private final float[] nextSaccade;
    private final float[] nextBlink;
    private final float[] blinkAt;
    private float twitchUntil = -99f;
    private float nextTwitch;
    private float twX;
    private float twY;
    private float nextParticle;
    private float nextShake;

    // сглаженное
    private final float[] gx;
    private final float[] gy;
    private final float[] top;
    private final float[] bot;
    private final float[] arc;
    private float slant;
    private float pupil = 1f;

    Brain(Skin skin, Renderer geo, long seed) {
        this.skin = skin;
        this.geo = geo;
        this.n = skin.places.size();
        this.face = new Face(n);
        this.rnd = new Random(seed);
        ex = new float[n];
        ey = new float[n];
        idleX = new float[n];
        idleY = new float[n];
        nextSaccade = new float[n];
        nextBlink = new float[n];
        blinkAt = new float[n];
        gx = new float[n];
        gy = new float[n];
        top = new float[n];
        bot = new float[n];
        arc = new float[n];
        for (int i = 0; i < n; i++) {
            top[i] = skin.baseOpen;
            bot[i] = 1f;
            blinkAt[i] = -99f;
            nextBlink[i] = rand(skin.blinkMin, skin.blinkMax);
            nextSaccade[i] = rand(0.5f, 2f);
        }
        nextTwitch = rand(0.3f, 1.2f);
    }

    // ---- входы ----------------------------------------------------------------------

    /** Центры глаз на экране и размер пикселя сетки в пикселях экрана. */
    void setGeometry(float[] cx, float[] cy, float unitPx, float reachPx) {
        for (int i = 0; i < n && i < cx.length; i++) {
            ex[i] = cx[i];
            ey[i] = cy[i];
        }
        unit = Math.max(1f, unitPx);
        reach = Math.max(40f, reachPx);
        geometry = true;
    }

    /** 0 палец опустился, 1 ведёт, 2 поднялся или отменён. */
    void touch(int action, float x, float y) {
        if (action == 2) {
            if (touching) {
                touchUpAt = now;
            }
            touching = false;
            return;
        }
        tx = x;
        ty = y;
        touching = true;
        interact();
    }

    /** Набор текста: точка каретки на экране (NaN, если неизвестна) и сколько символов прибавилось. */
    void typing(float x, float y, int delta) {
        typedAt = now;
        caretX = x;
        caretY = y;
        stareArmed = true;
        if (delta < 0) {
            if (now - delAt > 1.5f) {
                delRun = 0;
            }
            delAt = now;
            delRun += -delta;
            if (delRun >= 12 && emo != SUSPICIOUS) {
                feel(SUSPICIOUS, 1.6f);
                delRun = 0;
            }
        }
        interact();
    }

    /** Набор закончился без отправки (поле потеряло фокус). */
    void typingDone() {
        if (now - typedAt < 1.5f) {
            typedAt = now - 1.5f;
        }
    }

    void poke() {
        if (now - lastPoke > 2.5f) {
            pokes = 0;
        }
        pokes++;
        lastPoke = now;
        napping = false;
        if (pokes >= 3) {
            feel(ANGRY, 3f);
            pokes = 0;
        } else {
            feel(OUCH, 0.45f);
        }
        interact();
    }

    void toggleNap() {
        napping = !napping;
        if (napping) {
            emo = NEUTRAL;
        } else {
            feel(SURPRISED, 0.5f);
        }
        interact();
    }

    boolean napping() {
        return napping;
    }

    void dragStart() {
        dragging = true;
        napping = false;
        interact();
    }

    void drag(float vx, float vy) {
        dragVX = vx;
        dragVY = vy;
        interact();
    }

    /** Отпустили, не сдвинув: без головокружения. */
    void dragEndQuiet() {
        dragging = false;
    }

    void dragEnd() {
        dragging = false;
        feel(DIZZY, 1.2f);
        interact();
    }

    /**
     * События: "incoming_here x y", "incoming_other", "send", "love", "deleted",
     * "shake", "copy", "screen", "peer_typing", "emo:<имя>".
     */
    void event(String name, float x, float y) {
        if (name == null) {
            return;
        }
        if (name.startsWith("emo:")) {
            String e = name.substring(4);
            for (int k = 0; k < NAMES.length; k++) {
                if (NAMES[k].equals(e)) {
                    if (k == ASLEEP) {
                        napping = true;
                    } else {
                        napping = false;
                        feel(k, k == STARE ? 3f : 2f);
                        if (k == STARE) {
                            stareUntil = now + 3f;
                        }
                    }
                    interact();
                    return;
                }
            }
            return;
        }
        boolean asleep = isAsleep();
        switch (name) {
            case "incoming_here":
                if (asleep) {
                    peekUntil = now + 1.6f;
                    return;
                }
                glance(x, y, 1.1f);
                feel(SURPRISED, 0.35f);
                break;
            case "incoming_other":
                if (asleep) {
                    peekUntil = now + 1.2f;
                    return;
                }
                glance(x, y, 0.8f);
                break;
            case "send":
                typedAt = -99f;
                stareArmed = false;
                stareUntil = -99f;
                feel(HAPPY, 1.3f);
                interact();
                break;
            case "love":
                feel(LOVE, 2.4f);
                interact();
                break;
            case "deleted":
                feel(SAD, 1.8f);
                interact();
                break;
            case "shake":
                napping = false;
                feel(DIZZY, 2.6f);
                interact();
                break;
            case "copy":
                feel(WINK, 0.7f);
                interact();
                break;
            case "screen":
                if (!asleep) {
                    glance(x, y, 0.35f);
                }
                break;
            case "insulted":
                // гадости в наш адрес: смотрят на сообщение, пугаются, потом плачут
                napping = false;
                interact();
                glance(x, y, 1.2f);
                feel(SCARED, 1.3f);
                spawn(Face.P_EXCL, -1);
                nextEmo = SAD;
                nextEmoSec = 1.8f;
                break;
            case "rude":
                // гадости пишем мы: злятся вместе с нами
                interact();
                feel(ANGRY, 2.2f);
                break;
            case "laugh":
                interact();
                feel(HAPPY, 1.6f);
                break;
            case "sad_text":
                interact();
                feel(SAD, 2.2f);
                break;
            case "peer_typing":
                if (!asleep) {
                    curiousUntil = now + 2.5f;
                    glance(x, y, 2.5f);
                    if (emo == NEUTRAL) {
                        feel(CURIOUS, 2.5f);
                    }
                }
                break;
            default:
                break;
        }
    }

    private void glance(float x, float y, float sec) {
        glanceX = x;
        glanceY = y;
        glanceUntil = now + sec;
    }

    private void interact() {
        boolean wasAsleep = isAsleep() && !napping;
        lastInteraction = now;
        if (wasAsleep && emo != OUCH && emo != ANGRY) {
            feel(SURPRISED, 0.5f);
            for (int i = 0; i < n; i++) {
                blinkAt[i] = now;
            }
        }
    }

    void feel(int e, float sec) {
        if (e != SCARED) {
            nextEmo = -1;
        }
        emo = e;
        emoStart = now;
        emoUntil = now + sec;
        if (e == SURPRISED) {
            spawn(Face.P_EXCL, -1);
        } else if (e == ANGRY) {
            spawn(Face.P_ANGER, n - 1);
        } else if (e == OUCH) {
            spawn(Face.P_DROP, n - 1);
        } else if (e == CURIOUS) {
            spawn(Face.P_QUESTION, -1);
        }
        nextParticle = now + 0.3f;
    }

    boolean isAsleep() {
        return napping || sleepStage() == 2;
    }

    /** 0 бодрые, 1 клюют носом, 2 спят. */
    private int sleepStage() {
        if (napping) {
            return 2;
        }
        if (!sleepOn) {
            return 0;
        }
        float idle = now - lastInteraction;
        float after = night ? sleepAfter * 0.5f : sleepAfter;
        if (idle > after + 20f) {
            return 2;
        }
        if (idle > after) {
            return 1;
        }
        return 0;
    }

    // ---- кадр -----------------------------------------------------------------------

    /** Шаг поведения. true, если что-то ещё движется и нужен следующий кадр. */
    boolean update(float t) {
        float dt = lastNow < 0 ? 0.016f : Math.max(0f, Math.min(0.1f, t - lastNow));
        lastNow = t;
        now = t;
        if (emo != NEUTRAL && now >= emoUntil) {
            emo = NEUTRAL;
            if (nextEmo >= 0) {
                int e = nextEmo;
                nextEmo = -1;
                feel(e, nextEmoSec);
            }
        }
        int stage = sleepStage();
        boolean asleep = stage == 2;

        // ---- куда смотреть
        float[] tgx = new float[n];
        float[] tgy = new float[n];
        boolean fast = false;
        boolean typingNow = followTyping && now - typedAt < 1.3f;
        if (stareArmed && !typingNow && typedAt > 0 && now - typedAt >= 1.3f) {
            stareArmed = false;
            if (stareAfterTyping) {
                stareUntil = now + (skin.creepy ? 6f : 3f);
                if (emo == NEUTRAL || emo == FOCUS) {
                    feel(STARE, skin.creepy ? 6f : 3f);
                }
            }
        }
        if (dragging) {
            // глаза отстают от движения, как будто их тащат
            float len = (float) Math.hypot(dragVX, dragVY);
            float k = len < 1f ? 0f : Math.min(1f, len / 1500f);
            for (int i = 0; i < n; i++) {
                tgx[i] = len < 1f ? 0f : -dragVX / len * k;
                tgy[i] = len < 1f ? 0f : -dragVY / len * k;
            }
            fast = true;
        } else if (followTouch && (touching || now - touchUpAt < 0.7f) && geometry) {
            lookAt(tx, ty, tgx, tgy);
            fast = true;
        } else if (typingNow && geometry && !Float.isNaN(caretX)) {
            lookAt(caretX, caretY, tgx, tgy);
            fast = true;
            if (emo == NEUTRAL) {
                feel(FOCUS, 1.3f);
            }
        } else if (now < stareUntil) {
            // прямо на тебя
            for (int i = 0; i < n; i++) {
                tgx[i] = 0f;
                tgy[i] = 0f;
            }
        } else if (now < glanceUntil && geometry) {
            lookAt(glanceX, glanceY, tgx, tgy);
            fast = true;
        } else {
            idle(tgx, tgy);
        }
        if (skin.twitch > 0f && !asleep) {
            if (now >= nextTwitch) {
                twX = (rnd.nextFloat() - 0.5f) * 0.5f;
                twY = (rnd.nextFloat() - 0.5f) * 0.4f;
                twitchUntil = now + 0.12f;
                nextTwitch = now + rand(0.25f, 1.6f) / skin.twitch;
            }
            if (now < twitchUntil) {
                for (int i = 0; i < n; i++) {
                    tgx[i] += twX;
                    tgy[i] += twY;
                }
            }
        }

        // ---- веки и лицо по эмоции
        float baseOpen = skin.baseOpen;
        float baseSlant = skin.baseSlant;
        if (night) {
            baseOpen *= 0.82f;
        }
        if (lowBattery) {
            baseOpen *= 0.75f;
            baseSlant -= 0.3f;
        }
        float wantTop = baseOpen;
        float wantBot = 1f;
        float wantArc = 0f;
        float wantSlant = baseSlant;
        float wantPupil = 1f;
        int pupilMode = Face.PUPIL_NORMAL;
        String frame = null;
        boolean blinking = blinkOn && skin.blinkMax > 0f;
        int winkEye = -1;
        int e = asleep ? ASLEEP : emo;
        if (e == NEUTRAL && stage == 1) {
            e = SLEEPY;
        }
        switch (e) {
            case HAPPY:
                wantTop = 0f;
                wantBot = 1f;
                wantArc = 1f;
                frame = "happy";
                blinking = false;
                break;
            case LOVE:
                wantTop = 1f;
                wantBot = 0.9f;
                wantArc = 0.3f;
                pupilMode = Face.PUPIL_HEART;
                frame = "love";
                break;
            case SURPRISED:
                wantTop = 1f;
                wantPupil = 0.55f;
                frame = "surprised";
                blinking = false;
                break;
            case ANGRY:
                wantTop = 0.62f;
                wantBot = 0.95f;
                wantSlant = 0.85f;
                wantPupil = 0.75f;
                frame = "angry";
                break;
            case SAD:
                wantTop = 0.72f;
                wantBot = 0.92f;
                wantSlant = -0.75f;
                wantPupil = 1.15f;
                frame = "sad";
                break;
            case DIZZY:
                wantTop = 1f;
                pupilMode = Face.PUPIL_SPIRAL;
                frame = "dizzy";
                blinking = false;
                break;
            case OUCH:
                pupilMode = Face.PUPIL_OUCH;
                frame = "ouch";
                blinking = false;
                break;
            case WINK:
                winkEye = n - 1;
                wantTop = 1f;
                break;
            case SUSPICIOUS:
                wantTop = 0.55f;
                wantBot = 0.85f;
                wantSlant = 0.15f;
                wantPupil = 0.9f;
                break;
            case SCARED:
                wantTop = 1f;
                wantPupil = 0.5f;
                blinking = false;
                break;
            case STARE:
                wantTop = 1f;
                wantPupil = 1.3f;
                if (skin.creepy) {
                    blinking = false;
                }
                break;
            case FOCUS:
                wantTop = Math.min(wantTop, 0.82f);
                wantPupil = 0.95f;
                break;
            case CURIOUS:
                wantTop = 1f;
                wantPupil = 1.15f;
                break;
            case SLEEPY:
                wantTop = Math.min(wantTop, 0.35f);
                break;
            case ASLEEP:
                wantTop = 0f;
                wantBot = 1f;
                frame = "closed";
                blinking = false;
                break;
            default:
                break;
        }
        if (dragging) {
            wantTop = 1f;
            wantPupil = 0.6f;
        }

        // ---- сглаживание
        float tauG = (skin.style == 0 ? 0.075f : 0.03f) / skin.speed;
        if (fast) {
            tauG *= 0.7f;
        }
        float ag = 1f - (float) Math.exp(-dt / tauG);
        float al = 1f - (float) Math.exp(-dt / 0.07f);
        boolean moving = false;
        for (int i = 0; i < n; i++) {
            float nx = clampUnit(tgx[i], tgy[i], true);
            float ny = clampUnit(tgx[i], tgy[i], false);
            float ox = gx[i];
            float oy = gy[i];
            gx[i] += (nx - gx[i]) * ag;
            gy[i] += (ny - gy[i]) * ag;
            // хвост экспоненты на экране не виден: как только пиксель совпал с целью, приехали
            if (px(gx[i], skin.travelX) == px(nx, skin.travelX)) {
                gx[i] = nx;
            }
            if (px(gy[i], skin.travelY) == px(ny, skin.travelY)) {
                gy[i] = ny;
            }
            if (gx[i] != nx || gy[i] != ny || Math.abs(gx[i] - ox) > 0.001f || Math.abs(gy[i] - oy) > 0.001f) {
                moving = true;
            }
            float t0 = wantTop;
            float b0 = wantBot;
            float a0 = wantArc;
            if (i == winkEye) {
                t0 = 0f;
                b0 = 1f;
                a0 = 0.9f;
            }
            if (asleep && now < peekUntil && i == 0) {
                // приоткрыл один глаз посмотреть, что пришло
                t0 = 0.4f;
            }
            top[i] = settle(top[i] + (t0 - top[i]) * al, t0, 0.02f);
            bot[i] = settle(bot[i] + (b0 - bot[i]) * al, b0, 0.02f);
            arc[i] = settle(arc[i] + (a0 - arc[i]) * al, a0, 0.03f);
            if (top[i] != t0 || bot[i] != b0 || arc[i] != a0) {
                moving = true;
            }
        }
        slant = settle(slant + (wantSlant - slant) * al, wantSlant, 0.03f);
        pupil = settle(pupil + (wantPupil - pupil) * al, wantPupil, 0.03f);
        if (slant != wantSlant || pupil != wantPupil) {
            moving = true;
        }

        // ---- моргание
        boolean anyBlink = false;
        for (int i = 0; i < n; i++) {
            int src = skin.independent ? i : 0;
            if (i == src && blinking && now >= nextBlink[i]) {
                blinkAt[i] = now;
                float gap = rand(skin.blinkMin, skin.blinkMax);
                if (stage == 1) {
                    gap *= 0.6f;
                }
                // иногда двойное
                nextBlink[i] = now + (rnd.nextFloat() < 0.18f ? 0.28f : gap);
            }
            float since = now - blinkAt[src];
            float closeK = blinkCurve(since, stage == 1 ? 1.8f : 1f);
            if (closeK > 0f) {
                anyBlink = true;
            }
            face.openTop[i] = top[i] * (1f - closeK);
            face.openBot[i] = bot[i] * (1f - 0.12f * closeK);
            face.arcBot[i] = arc[i];
            face.gx[i] = gx[i];
            face.gy[i] = gy[i];
        }
        face.slant = slant;
        face.pupil = pupil;
        face.pupilMode = pupilMode;
        face.frame = frame;
        face.time = now;

        // ---- дрожь
        if ((e == SCARED || dragging || (skin.style == 2 && !asleep)) && now >= nextShake) {
            nextShake = now + 0.07f;
            float amp = skin.style == 2 && e != SCARED && !dragging ? 0.25f : 1f;
            face.shakeX = rnd.nextFloat() < amp ? rnd.nextInt(3) - 1 : 0;
            face.shakeY = rnd.nextFloat() < amp * 0.5f ? rnd.nextInt(3) - 1 : 0;
        } else if (e != SCARED && !dragging && skin.style != 2) {
            face.shakeX = 0;
            face.shakeY = 0;
        }

        // ---- частицы
        if (now >= nextParticle) {
            switch (e) {
                case ASLEEP:
                    spawn(Face.P_Z, n - 1);
                    nextParticle = now + 1.3f;
                    break;
                case LOVE:
                    spawn(Face.P_HEART, rnd.nextInt(n));
                    nextParticle = now + 0.55f;
                    break;
                case SAD:
                    spawn(Face.P_DROP, rnd.nextInt(n) | 0x100);
                    nextParticle = now + 1.0f;
                    break;
                case DIZZY:
                    spawn(Face.P_STAR, -1);
                    nextParticle = now + 0.4f;
                    break;
                case ANGRY:
                    spawn(Face.P_ANGER, n - 1);
                    nextParticle = now + 1.3f;
                    break;
                default:
                    nextParticle = now + 0.5f;
                    break;
            }
        }
        // таймеры, которые не сработали из-за другого занятия, сдвигаем вперёд:
        // иначе вью будила бы себя каждый кадр ради события из прошлого
        // у глаз без своего характера (не independent) таймеры только у первого
        int timers = skin.independent ? n : 1;
        for (int i = 0; i < timers; i++) {
            if (nextSaccade[i] < now) {
                nextSaccade[i] = now + 0.3f;
            }
            if (nextBlink[i] < now) {
                nextBlink[i] = now + (blinking ? 0.05f : 0.5f);
            }
        }
        if (nextTwitch < now) {
            nextTwitch = now + 0.3f;
        }
        if (nextParticle < now) {
            nextParticle = now + 0.5f;
        }
        Iterator<Face.Particle> it = face.particles.iterator();
        while (it.hasNext()) {
            Face.Particle p = it.next();
            p.age += dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            if (p.age >= p.life) {
                it.remove();
            }
        }
        if (moving || anyBlink || touching || dragging) {
            pace = 3;
        } else if (pupilMode == Face.PUPIL_SPIRAL || face.shakeX != 0 || face.shakeY != 0
                || (skin.style == 2 && !asleep)) {
            pace = 2;
        } else if (!face.particles.isEmpty()) {
            pace = 1;
        } else {
            pace = 0;
        }
        return pace > 0;
    }

    /** Когда следующий раз что-то случится само (для сна между кадрами). */
    float nextWake() {
        float t = Float.MAX_VALUE;
        int timers = skin.independent ? n : 1;
        for (int i = 0; i < timers; i++) {
            if (skin.blinkMax > 0f && blinkOn) {
                t = Math.min(t, nextBlink[i]);
            }
            t = Math.min(t, nextSaccade[i]);
        }
        if (skin.twitch > 0f) {
            t = Math.min(t, nextTwitch);
        }
        if (emo != NEUTRAL) {
            t = Math.min(t, emoUntil);
        }
        if (now < stareUntil) {
            t = Math.min(t, stareUntil);
        }
        if (stareArmed) {
            t = Math.min(t, typedAt + 1.3f);
        }
        if (now < glanceUntil) {
            t = Math.min(t, glanceUntil);
        }
        if (now < touchUpAt + 0.7f) {
            t = Math.min(t, touchUpAt + 0.7f);
        }
        if (sleepOn && !napping) {
            float after = night ? sleepAfter * 0.5f : sleepAfter;
            float idle = now - lastInteraction;
            if (idle <= after) {
                t = Math.min(t, lastInteraction + after + 0.01f);
            } else if (idle <= after + 20f) {
                t = Math.min(t, lastInteraction + after + 20.01f);
            }
        }
        if (isAsleep()) {
            t = Math.min(t, nextParticle);
        }
        return t;
    }

    // ---- помощники --------------------------------------------------------------------

    /** Сдвиг в пикселях сетки, который реально нарисуется. */
    private static int px(float g, int travel) {
        return Math.round(Math.max(-1f, Math.min(1f, g)) * travel);
    }

    /** Близко к цели: считаем, что доехали. */
    private static float settle(float v, float target, float eps) {
        return Math.abs(v - target) < eps ? target : v;
    }

    /** Закрытие при моргании: 0 открыто, 1 закрыто. */
    private static float blinkCurve(float since, float slow) {
        float close = 0.06f * slow;
        float open = 0.1f * slow;
        if (since < 0f || since > close + open) {
            return 0f;
        }
        if (since < close) {
            return since / close;
        }
        return 1f - (since - close) / open;
    }

    private void lookAt(float px, float py, float[] tgx, float[] tgy) {
        for (int i = 0; i < n; i++) {
            float dx = px - ex[i];
            float dy = py - ey[i];
            float d = (float) Math.hypot(dx, dy);
            float k = 1.25f / (float) Math.sqrt(d * d + reach * reach);
            tgx[i] = dx * k;
            tgy[i] = dy * k;
        }
    }

    private void idle(float[] tgx, float[] tgy) {
        for (int i = 0; i < n; i++) {
            int src = skin.independent ? i : 0;
            if (i == src && now >= nextSaccade[i]) {
                if (rnd.nextFloat() < skin.stare) {
                    idleX[i] = 0f;
                    idleY[i] = 0f;
                    nextSaccade[i] = now + rand(1.5f, 4f);
                } else {
                    double a = rnd.nextDouble() * Math.PI * 2;
                    float r = 0.25f + rnd.nextFloat() * 0.65f;
                    idleX[i] = (float) Math.cos(a) * r;
                    idleY[i] = (float) Math.sin(a) * r * 0.7f;
                    nextSaccade[i] = now + rand(0.7f, 3.2f);
                }
            }
            tgx[i] = idleX[src];
            tgy[i] = idleY[src];
        }
    }

    /** Вектор взгляда не длиннее 1. */
    private static float clampUnit(float x, float y, boolean wantX) {
        float len = (float) Math.hypot(x, y);
        if (len > 1f) {
            x /= len;
            y /= len;
        }
        return wantX ? x : y;
    }

    private float rand(float lo, float hi) {
        if (hi <= lo) {
            return lo <= 0f ? 9999f : lo;
        }
        return lo + rnd.nextFloat() * (hi - lo);
    }

    /** Частица над глазом i (-1 над серединой). Флаг 0x100: из-под глаза (слёзы). */
    private void spawn(int type, int eye) {
        if (face.particles.size() > 12) {
            return;
        }
        boolean below = (eye & 0x100) != 0;
        int i = eye < 0 ? -1 : (eye & 0xFF);
        Face.Particle p = new Face.Particle();
        p.type = type;
        float cx;
        if (i < 0 || i >= n) {
            cx = 0f;
            for (int k = 0; k < n; k++) {
                cx += geo.eyeCenterX(k);
            }
            cx /= Math.max(1, n);
            i = n - 1;
        } else {
            cx = geo.eyeCenterX(i);
        }
        switch (type) {
            case Face.P_Z:
                p.x = cx + 3f;
                p.y = geo.eyeTop(i) - 2f;
                p.vx = 2.2f;
                p.vy = -2.8f;
                p.life = 1.8f;
                break;
            case Face.P_HEART:
                p.x = cx + (rnd.nextFloat() - 0.5f) * 4f;
                p.y = geo.eyeTop(i) - 2f;
                p.vx = (rnd.nextFloat() - 0.5f) * 2f;
                p.vy = -4f;
                p.life = 1.3f;
                break;
            case Face.P_ANGER:
                p.x = cx + 3f;
                p.y = geo.eyeTop(i) - 3f;
                p.life = 1.1f;
                break;
            case Face.P_DROP:
                if (below) {
                    boolean leftEye = geo.eyeCenterX(i) < geo.cw / 2f;
                    p.x = cx + (leftEye ? -2f : 2f);
                    p.y = geo.eyeBottom(i) - 1f;
                    p.vy = 5f;
                    p.life = 0.9f;
                } else {
                    p.x = cx + 3f;
                    p.y = geo.eyeTop(i) - 1f;
                    p.vy = 2f;
                    p.life = 0.7f;
                }
                break;
            case Face.P_EXCL:
                p.x = cx;
                p.y = geo.eyeTop(i) - 4f;
                p.life = 0.7f;
                break;
            case Face.P_QUESTION:
                p.x = cx;
                p.y = geo.eyeTop(i) - 4f;
                p.life = 1.4f;
                break;
            case Face.P_STAR:
                p.x = cx + (rnd.nextFloat() - 0.5f) * geo.cw * 0.6f;
                p.y = geo.eyeTop(i) - 2f - rnd.nextFloat() * 2f;
                p.life = 0.5f;
                break;
            default:
                p.x = cx;
                p.y = geo.eyeTop(i) - 3f;
                p.life = 1f;
                break;
        }
        face.particles.add(p);
    }
}
