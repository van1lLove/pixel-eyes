package com.pixeleyes;

import java.util.ArrayList;

/**
 * Состояние лица на один кадр: куда смотрит каждый глаз, насколько открыты
 * веки, выражение и частицы над глазами. Его заполняет Brain, рисует Renderer.
 */
final class Face {

    static final int PUPIL_NORMAL = 0;
    static final int PUPIL_HEART = 1;
    static final int PUPIL_SPIRAL = 2;
    static final int PUPIL_OUCH = 3;   // глаза "><"

    // частицы
    static final int P_Z = 0;
    static final int P_HEART = 1;
    static final int P_ANGER = 2;
    static final int P_DROP = 3;
    static final int P_EXCL = 4;
    static final int P_NOTE = 5;
    static final int P_STAR = 6;
    static final int P_QUESTION = 7;

    static final class Particle {
        int type;
        float x;          // в пикселях холста
        float y;
        float vx;
        float vy;
        float age;        // секунды
        float life;       // сколько живёт
    }

    final int n;
    final float[] gx;
    final float[] gy;
    final float[] openTop;
    final float[] openBot;
    final float[] arcBot;
    float slant;
    float pupil = 1f;
    int pupilMode = PUPIL_NORMAL;
    String frame;                 // кадр скина вместо отрисовки, если он есть
    float time;
    int shakeX;
    int shakeY;
    final ArrayList<Particle> particles = new ArrayList<>();

    Face(int n) {
        this.n = n;
        gx = new float[n];
        gy = new float[n];
        openTop = new float[n];
        openBot = new float[n];
        arcBot = new float[n];
        for (int i = 0; i < n; i++) {
            openTop[i] = 1f;
            openBot[i] = 1f;
        }
    }

    /** Сравнение для решения, перерисовывать ли (по тому, что реально видно). */
    long signature(Skin s) {
        long h = 1469598103934665603L;
        for (int i = 0; i < n; i++) {
            h = mix(h, Math.round(gx[i] * s.travelX * 2));
            h = mix(h, Math.round(gy[i] * s.travelY * 2));
            h = mix(h, Math.round(openTop[i] * 32));
            h = mix(h, Math.round(openBot[i] * 32));
            h = mix(h, Math.round(arcBot[i] * 16));
        }
        h = mix(h, Math.round(slant * 16));
        h = mix(h, Math.round(pupil * 8));
        h = mix(h, pupilMode);
        h = mix(h, frame == null ? 0 : frame.hashCode());
        h = mix(h, shakeX * 31 + shakeY);
        if (pupilMode == PUPIL_SPIRAL) {
            h = mix(h, Math.round(time * 12));
        }
        for (Particle p : particles) {
            h = mix(h, Math.round(p.x * 2) * 131 + Math.round(p.y * 2));
            h = mix(h, p.type);
            h = mix(h, Math.round(p.age / p.life * 4));
        }
        return h;
    }

    private static long mix(long h, long v) {
        return (h ^ v) * 1099511628211L;
    }
}
