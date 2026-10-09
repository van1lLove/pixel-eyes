package com.pixeleyes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Сами глаза на экране: картинка из Renderer, увеличенная без сглаживания, и
 * свой цикл кадров. Пока что-то движется, кадр каждый vsync; когда всё
 * замерло, вью спит до следующего события по расписанию мозга (моргание,
 * взгляд в сторону), так что в покое батарея не тратится.
 *
 * Нажатия по самим глазам: тап это тычок (три подряд злят), зажать и тащить
 * переносит глаза, зажать и отпустить на месте усыпляет или будит.
 */
final class EyesView extends View implements Choreographer.FrameCallback {

    interface Host {
        /** Глаза перетащили: центр в долях экрана. */
        void onMoved(EyesView v, float fx, float fy);

        /** Перетаскивание вью: смещение от исходного места. */
        void onDrag(EyesView v, float dx, float dy);
    }

    Skin skin;
    Renderer renderer;
    Brain brain;
    private Bitmap bmp;
    private final Paint paint = new Paint();
    private final Rect dst = new Rect();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable wakeRunnable = this::wake;
    private final Runnable slowFrame = () -> {
        slowPosted = false;
        doFrame(System.nanoTime());
    };
    private boolean slowPosted;
    private boolean paused;
    private int lastState = -1;
    private final long t0 = System.nanoTime();
    private final int[] loc = new int[2];

    final float density;
    private int unitPx = 8;
    private float pixelDp = 3f;
    private boolean posted;
    private boolean attached;
    private long lastSig = Long.MIN_VALUE;
    private boolean dirty = true;
    boolean preview;              // в настройках: смотрит на палец внутри примера
    boolean interactive = true;   // тычки и перетаскивание
    Host host;
    int edge = Renderer.EDGE_SHADOW;
    int frames;                   // для статистики
    int renders;

    // жесты
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private long lastMoveMs;
    private long downMs;
    private boolean dragging;
    private boolean movedFar;
    private float velX;
    private float velY;
    private final int slop;
    private final Runnable longPress = this::startDrag;

    EyesView(Context ctx, Skin skin) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
        setSkin(skin);
    }

    void setSkin(Skin s) {
        Brain old = brain;
        skin = s;
        renderer = new Renderer(s);
        renderer.edge = edge;
        brain = new Brain(s, renderer, SystemClock.uptimeMillis());
        if (old != null) {
            brain.followTouch = old.followTouch;
            brain.followTyping = old.followTyping;
            brain.stareAfterTyping = old.stareAfterTyping;
            brain.sleepOn = old.sleepOn;
            brain.sleepAfter = old.sleepAfter;
            brain.night = old.night;
            brain.lowBattery = old.lowBattery;
        }
        if (bmp != null) {
            bmp.recycle();
        }
        bmp = Bitmap.createBitmap(renderer.cw, renderer.ch, Bitmap.Config.ARGB_8888);
        lastSig = Long.MIN_VALUE;
        dirty = true;
        requestLayout();
        wake();
    }

    void setEdge(int e) {
        edge = e;
        if (renderer != null) {
            renderer.edge = e;
        }
        lastSig = Long.MIN_VALUE;
        dirty = true;
        wake();
    }

    void setPixelDp(float dp) {
        pixelDp = Math.max(1f, Math.min(16f, dp));
        int u = Math.max(1, Math.round(pixelDp * density));
        if (u != unitPx) {
            unitPx = u;
            requestLayout();
        }
        dirty = true;
        wake();
    }

    void setOpacity(float a) {
        paint.setAlpha(Math.round(Math.max(0.1f, Math.min(1f, a)) * 255));
        invalidate();
    }

    int unitPx() {
        return unitPx;
    }

    /** Секунды с создания вью: время мозга. */
    float now() {
        return (System.nanoTime() - t0) / 1e9f;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(renderer.cw * unitPx, renderer.ch * unitPx);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        dirty = true;
        wake();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        posted = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(wakeRunnable);
        handler.removeCallbacks(slowFrame);
        slowPosted = false;
        handler.removeCallbacks(longPress);
    }

    /** Разбудить цикл кадров: что-то произошло. */
    void wake() {
        if (!attached || paused) {
            return;
        }
        handler.removeCallbacks(wakeRunnable);
        if (slowPosted) {
            // редкий кадр меняем на ближайший
            handler.removeCallbacks(slowFrame);
            slowPosted = false;
        }
        if (!posted) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** Приложение свёрнуто: кадры не нужны совсем. */
    void setPaused(boolean p) {
        paused = p;
        if (p) {
            Choreographer.getInstance().removeFrameCallback(this);
            handler.removeCallbacks(wakeRunnable);
            handler.removeCallbacks(slowFrame);
            posted = false;
            slowPosted = false;
        } else {
            wake();
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        // за раз заказан только один кадр: либо от vsync, либо редкий от handler
        posted = false;
        if (!attached || paused) {
            return;
        }
        frames++;
        float t = (frameTimeNanos - t0) / 1e9f;
        updateGeometry();
        boolean animating = brain.update(t);
        int state = brain.isAsleep() ? Brain.ASLEEP : brain.emo;
        if (state != lastState && !preview) {
            lastState = state;
            EyesCore.get().log("глаза: " + Brain.NAMES[state]);
        }
        long sig = brain.face.signature(skin);
        if (sig != lastSig || dirty) {
            lastSig = sig;
            dirty = false;
            render();
            invalidate();
        }
        if (getVisibility() != VISIBLE) {
            return;
        }
        if (animating && brain.pace >= 3) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        } else if (animating) {
            // частицам хватает 8 кадров в секунду, дрожи 30
            slowPosted = true;
            handler.postDelayed(slowFrame, brain.pace == 2 ? 33 : 120);
        } else {
            float next = brain.nextWake();
            long delay = next == Float.MAX_VALUE ? 60_000L : (long) Math.max(16f, (next - t) * 1000f);
            handler.postDelayed(wakeRunnable, Math.min(delay, 60_000L));
        }
    }

    private void render() {
        int[] px = renderer.render(brain.face);
        bmp.setPixels(px, 0, renderer.cw, 0, 0, renderer.cw, renderer.ch);
        renders++;
    }

    /** Где глаза на экране: мозгу для расчёта взгляда. */
    void updateGeometry() {
        getLocationOnScreen(loc);
        int n = skin.places.size();
        float[] cx = new float[n];
        float[] cy = new float[n];
        for (int i = 0; i < n; i++) {
            cx[i] = loc[0] + renderer.eyeCenterX(i) * unitPx;
            cy[i] = loc[1] + renderer.eyeCenterY(i) * unitPx;
        }
        float reach = 110f * density;
        brain.setGeometry(cx, cy, unitPx, reach);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp == null) {
            return;
        }
        if (dirty) {
            dirty = false;
            render();
        }
        dst.set(0, 0, renderer.cw * unitPx, renderer.ch * unitPx);
        c.drawBitmap(bmp, null, dst, paint);
    }

    // ---- входы снаружи ------------------------------------------------------------

    void globalTouch(int action, float rawX, float rawY) {
        brain.now = now();
        brain.touch(action, rawX, rawY);
        wake();
    }

    void typing(float x, float y, int delta) {
        brain.now = now();
        brain.typing(x, y, delta);
        wake();
    }

    void typingDone() {
        brain.now = now();
        brain.typingDone();
        wake();
    }

    void event(String name, float x, float y) {
        brain.now = now();
        brain.event(name, x, y);
        wake();
    }

    // ---- жесты по самим глазам -------------------------------------------------------

    /** Попадание по непрозрачному пикселю (вокруг глаз нажатия проходят мимо). */
    boolean hit(float x, float y) {
        int px = (int) (x / unitPx);
        int py = (int) (y / unitPx);
        int r = 1;
        int[] out = renderer.out();
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int xx = px + dx;
                int yy = py + dy;
                if (xx >= 0 && yy >= 0 && xx < renderer.cw && yy < renderer.ch
                        && (out[yy * renderer.cw + xx] >>> 24) > 0x80) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (preview) {
            // в настройках: глаза смотрят на палец, тап по ним это тычок
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN && hit(e.getX(), e.getY())) {
                brain.now = now();
                brain.poke();
                wake();
            }
            return false;
        }
        if (!interactive) {
            return false;
        }
        long ms = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hit(e.getX(), e.getY())) {
                    EyesCore.get().log("касание мимо глаз " + (int) e.getX() + "," + (int) e.getY());
                    return false;
                }
                EyesCore.get().log("касание по глазам " + (int) e.getX() + "," + (int) e.getY());
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                lastRawX = downRawX;
                lastRawY = downRawY;
                lastMoveMs = ms;
                downMs = ms;
                movedFar = false;
                dragging = false;
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float rx = e.getRawX();
                float ry = e.getRawY();
                if (!dragging && Math.hypot(rx - downRawX, ry - downRawY) > slop) {
                    movedFar = true;
                    handler.removeCallbacks(longPress);
                    // сразу тащим, если пальцем повели после короткой задержки
                    if (ms - downMs > 120) {
                        startDrag();
                    }
                }
                if (dragging) {
                    float dt = Math.max(1, ms - lastMoveMs) / 1000f;
                    velX = velX * 0.6f + (rx - lastRawX) / dt * 0.4f;
                    velY = velY * 0.6f + (ry - lastRawY) / dt * 0.4f;
                    lastMoveMs = ms;
                    brain.now = now();
                    brain.drag(velX, velY);
                    if (host != null) {
                        host.onDrag(this, rx - downRawX, ry - downRawY);
                    }
                    wake();
                }
                lastRawX = rx;
                lastRawY = ry;
                return true;
            }
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (dragging) {
                    dragging = false;
                    brain.now = now();
                    if (!movedFar) {
                        // зажали и отпустили на месте: уснуть или проснуться
                        brain.dragEndQuiet();
                        brain.toggleNap();
                    } else {
                        brain.dragEnd();
                        if (host != null) {
                            host.onDrag(this, e.getRawX() - downRawX, e.getRawY() - downRawY);
                            host.onMoved(this, -1f, -1f);
                        }
                    }
                    wake();
                    return true;
                }
                if (!movedFar) {
                    brain.now = now();
                    brain.poke();
                    wake();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (dragging) {
                    dragging = false;
                    brain.now = now();
                    brain.dragEnd();
                    if (host != null) {
                        host.onMoved(this, -1f, -1f);
                    }
                }
                return true;
            default:
                return true;
        }
    }

    private void startDrag() {
        if (dragging) {
            return;
        }
        dragging = true;
        velX = 0f;
        velY = 0f;
        try {
            performHapticFeedback(0);
        } catch (Throwable ignored) {
        }
        brain.now = now();
        brain.dragStart();
        wake();
    }

    boolean dragging() {
        return dragging;
    }
}
