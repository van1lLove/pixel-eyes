package com.pixeleyes;

import android.app.Activity;
import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Глаза поверх окна приложения и всё, на что они реагируют. Работает без хуков:
 * вью лежит в DecorView главной активности, касания видны через обёртку
 * Window.Callback, набор текста через слушатель фокуса и TextWatcher,
 * события чата через наблюдателя NotificationCenter (Proxy на интерфейс
 * клиента), тряска через акселерометр, копирование через буфер обмена.
 *
 * Всё только на потоке интерфейса.
 */
final class Overlay implements Application.ActivityLifecycleCallbacks, EyesView.Host, CallbackWrapper.Sink {

    private static final String LAUNCH = "org.telegram.ui.LaunchActivity";

    private final EyesCore core;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Application app;
    private WeakReference<Activity> activity = new WeakReference<>(null);
    EyesView view;
    private ViewGroup decor;
    private CallbackWrapper wrapper;
    private Window wrappedWindow;
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusL = this::onFocus;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutL = this::onLayout;
    private final WeakHashMap<EditText, Watcher> watched = new WeakHashMap<>();
    private WeakReference<EditText> focused = new WeakReference<>(null);
    private long typingSeenMs;
    private boolean resumed;
    private boolean installed;

    // экран
    private WeakReference<Object> lastFragment = new WeakReference<>(null);
    String fragmentName = "";
    long openDialog;
    private Method mLastFragmentStatic;
    private Method mLayoutGetter;
    private boolean fragResolved;
    private int lastW;
    private int lastH;
    private long themeCheckedMs;
    private boolean dark;
    private boolean darkKnown;

    // перетаскивание
    private boolean inDrag;
    private float baseTX;
    private float baseTY;

    // события клиента
    private Object ncProxy;
    private final List<Object[]> ncSubs = new ArrayList<>();
    private int idNew = -1;
    private int idDel = -1;
    private int idUpd = -1;
    private int printMask = 64;
    private Method mPrinting;
    private Method mMcInstance;
    private long lastGlanceMs;
    private int ncCount;
    private long lastOtherMs;
    private long lastMoodMs;
    // "печатает..." в списке чатов: куда смотрим и повтор, пока печатают
    private final Runnable dialogsTyping = this::scanDialogsTyping;
    private Class<?> dialogCellClass;
    private boolean dialogCellResolved;
    private Field fMessageLeft;
    private Field fMessageTop;
    private Field fSelectedAccount;
    private long typingDialog;

    // датчики и окружение
    private SensorManager sensors;
    private SensorEventListener shakeL;
    private int shakeHits;
    private long shakeWindowMs;
    private long lastShakeMs;
    private ClipboardManager clipboard;
    private ClipboardManager.OnPrimaryClipChangedListener clipL;
    private long lastClipMs;
    private final Runnable envTick = this::envTick;

    Overlay(EyesCore core) {
        this.core = core;
    }

    // ---- установка ---------------------------------------------------------------------

    void install(Context ctx) {
        if (installed) {
            return;
        }
        installed = true;
        Context ac = ctx.getApplicationContext();
        if (ac instanceof Application) {
            app = (Application) ac;
            app.registerActivityLifecycleCallbacks(this);
        }
        registerNotifications();
        Activity a = currentActivity();
        if (a != null && !a.isFinishing()) {
            attach(a);
            resumed = true;
            startSensors();
        }
        core.log("оверлей стоит" + (a == null ? ", ждём активность" : ""));
    }

    void uninstall() {
        installed = false;
        if (app != null) {
            try {
                app.unregisterActivityLifecycleCallbacks(this);
            } catch (Throwable ignored) {
            }
        }
        stopSensors();
        unregisterNotifications();
        detach();
        for (Map.Entry<EditText, Watcher> e : new ArrayList<>(watched.entrySet())) {
            try {
                e.getKey().removeTextChangedListener(e.getValue());
            } catch (Throwable ignored) {
            }
        }
        watched.clear();
        handler.removeCallbacksAndMessages(null);
        view = null;
    }

    /** LaunchActivity.instance, если клиент уже открыт. */
    private static Activity currentActivity() {
        try {
            Class<?> la = Class.forName(LAUNCH, false, Overlay.class.getClassLoader());
            Field f = la.getField("instance");
            Object o = f.get(null);
            return o instanceof Activity ? (Activity) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isMain(Activity a) {
        return a != null && LAUNCH.equals(a.getClass().getName());
    }

    // ---- активность --------------------------------------------------------------------

    private void attach(Activity a) {
        if (a == null || a.getWindow() == null) {
            return;
        }
        if (activity.get() == a && view != null && view.getParent() == decor) {
            return;
        }
        detach();
        activity = new WeakReference<>(a);
        Window w = a.getWindow();
        View d = w.getDecorView();
        if (!(d instanceof ViewGroup)) {
            return;
        }
        decor = (ViewGroup) d;
        if (view == null) {
            view = new EyesView(a, core.skin());
            view.host = this;
        }
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        decor.addView(view, lp);
        view.setOutlineProvider(null);
        view.setTranslationZ(64f * view.density);
        Window.Callback cb = w.getCallback();
        if (cb != null && !(cb instanceof CallbackWrapper)) {
            wrapper = new CallbackWrapper(cb, this);
            w.setCallback(wrapper);
            wrappedWindow = w;
        }
        observer = decor.getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener(focusL);
        observer.addOnGlobalLayoutListener(layoutL);
        View f = decor.findFocus();
        if (f != null) {
            onFocus(null, f);
        }
        applyConfig();
        decor.post(this::applyPosition);
        core.log("глаза в окне " + a.getClass().getSimpleName());
    }

    private void detach() {
        if (observer != null) {
            try {
                if (observer.isAlive()) {
                    observer.removeOnGlobalFocusChangeListener(focusL);
                    observer.removeOnGlobalLayoutListener(layoutL);
                }
            } catch (Throwable ignored) {
            }
            observer = null;
        }
        if (decor != null) {
            try {
                ViewTreeObserver o = decor.getViewTreeObserver();
                o.removeOnGlobalFocusChangeListener(focusL);
                o.removeOnGlobalLayoutListener(layoutL);
            } catch (Throwable ignored) {
            }
        }
        if (wrapper != null) {
            wrapper.sink = null;
            try {
                if (wrappedWindow != null && wrappedWindow.getCallback() == wrapper) {
                    wrappedWindow.setCallback(wrapper.base);
                }
            } catch (Throwable ignored) {
            }
            wrapper = null;
            wrappedWindow = null;
        }
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        decor = null;
        activity = new WeakReference<>(null);
    }

    @Override
    public void onActivityResumed(Activity a) {
        if (!isMain(a)) {
            return;
        }
        attach(a);
        resumed = true;
        startSensors();
        if (view != null) {
            view.setPaused(false);
        }
    }

    @Override
    public void onActivityPaused(Activity a) {
        if (a == activity.get()) {
            resumed = false;
            stopSensors();
            if (view != null) {
                view.setPaused(true);
            }
        }
    }

    @Override
    public void onActivityDestroyed(Activity a) {
        if (a == activity.get()) {
            detach();
        }
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityStarted(Activity a) {
    }

    @Override
    public void onActivityStopped(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    // ---- настройки -----------------------------------------------------------------------

    /** Настройки и набор глаз из ядра в живую вью. */
    void applyConfig() {
        if (view == null) {
            return;
        }
        Config c = core.config;
        if (view.skin != core.skin()) {
            view.setSkin(core.skin());
        }
        view.setPixelDp(c.pixelDp);
        view.setOpacity(c.alpha);
        view.interactive = c.interactive;
        view.setClickable(false);
        Brain b = view.brain;
        b.followTouch = c.followTouch;
        b.followTyping = c.followTyping;
        b.stareAfterTyping = c.stare;
        b.sleepOn = c.sleep;
        b.sleepAfter = c.sleepSec;
        updateEdge(true);
        envTick();
        updateVisibility();
        if (decor != null) {
            decor.post(this::applyPosition);
        }
        if (resumed) {
            stopSensors();
            startSensors();
        }
        view.wake();
    }

    private void updateEdge(boolean force) {
        long ms = SystemClock.uptimeMillis();
        if (!force && ms - themeCheckedMs < 1500) {
            return;
        }
        themeCheckedMs = ms;
        boolean d = isDarkTheme();
        if (!force && darkKnown && d == dark) {
            return;
        }
        dark = d;
        darkKnown = true;
        int e;
        switch (core.config.edge) {
            case 1: e = Renderer.EDGE_SHADOW; break;
            case 2: e = Renderer.EDGE_HALO; break;
            case 3: e = Renderer.EDGE_NONE; break;
            default: e = dark ? Renderer.EDGE_HALO : Renderer.EDGE_SHADOW; break;
        }
        if (view != null && view.edge != e) {
            view.setEdge(e);
        }
    }

    static boolean isDarkTheme() {
        try {
            Class<?> th = Class.forName("org.telegram.ui.ActionBar.Theme", false, Overlay.class.getClassLoader());
            Object v = th.getMethod("isCurrentThemeDark").invoke(null);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return true;
        }
    }

    private void updateVisibility() {
        if (view == null) {
            return;
        }
        Config c = core.config;
        boolean show = c.enabled;
        if (show && c.where == Config.WHERE_CHATS) {
            show = fragmentName.endsWith(".ChatActivity");
        } else if (show && c.where == Config.WHERE_TYPING) {
            EditText e = focused.get();
            show = (e != null && e.hasFocus()) || SystemClock.uptimeMillis() - typingSeenMs < 3000;
            if (!show && typingSeenMs > 0) {
                handler.postDelayed(this::updateVisibility, 600);
            }
        }
        int want = show ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != want) {
            view.setVisibility(want);
            if (show) {
                view.wake();
            }
        }
    }

    private void applyPosition() {
        if (view == null || decor == null || inDrag) {
            return;
        }
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int vw = view.getMeasuredWidth();
        int vh = view.getMeasuredHeight();
        if (vw <= 0) {
            view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            vw = view.getMeasuredWidth();
            vh = view.getMeasuredHeight();
        }
        float tx = core.config.posX * w - vw / 2f;
        float ty = core.config.posY * h - vh / 2f;
        view.setTranslationX(Math.max(0f, Math.min(w - vw, tx)));
        view.setTranslationY(Math.max(0f, Math.min(h - vh, ty)));
        view.wake();
    }

    // ---- перетаскивание (EyesView.Host) ----------------------------------------------------

    @Override
    public void onDrag(EyesView v, float dx, float dy) {
        if (decor == null) {
            return;
        }
        if (!inDrag) {
            inDrag = true;
            baseTX = v.getTranslationX() - dx;
            baseTY = v.getTranslationY() - dy;
        }
        int w = decor.getWidth();
        int h = decor.getHeight();
        v.setTranslationX(Math.max(0f, Math.min(w - v.getWidth(), baseTX + dx)));
        v.setTranslationY(Math.max(0f, Math.min(h - v.getHeight(), baseTY + dy)));
    }

    @Override
    public void onMoved(EyesView v, float fx, float fy) {
        inDrag = false;
        if (decor == null || decor.getWidth() <= 0) {
            return;
        }
        float x = (v.getTranslationX() + v.getWidth() / 2f) / decor.getWidth();
        float y = (v.getTranslationY() + v.getHeight() / 2f) / decor.getHeight();
        core.config.posX = x;
        core.config.posY = y;
        core.notify(String.format(java.util.Locale.US, "pos:%.4f,%.4f", x, y));
    }

    // ---- касания и набор -------------------------------------------------------------------

    @Override
    public void onWindowTouch(MotionEvent e) {
        if (view == null || view.getVisibility() != View.VISIBLE || !core.config.enabled) {
            return;
        }
        int a = e.getActionMasked();
        int act;
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            act = 0;
        } else if (a == MotionEvent.ACTION_MOVE) {
            act = 1;
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            act = 2;
        } else {
            return;
        }
        view.globalTouch(act, e.getRawX(), e.getRawY());
    }

    private void onFocus(View old, View now) {
        if (now instanceof EditText) {
            EditText e = (EditText) now;
            if (!watched.containsKey(e)) {
                Watcher w = new Watcher(e);
                e.addTextChangedListener(w);
                watched.put(e, w);
            }
            focused = new WeakReference<>(e);
            typingSeenMs = SystemClock.uptimeMillis();
        }
        if (old instanceof EditText && old != now && view != null) {
            view.typingDone();
        }
        if (core.config.where == Config.WHERE_TYPING) {
            updateVisibility();
        }
    }

    private final class Watcher implements TextWatcher {
        private final WeakReference<EditText> edit;
        private int before;

        Watcher(EditText e) {
            edit = new WeakReference<>(e);
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            before = s.length();
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int b, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            EditText e = edit.get();
            if (e == null || view == null || !core.config.enabled || !e.hasFocus()) {
                return;
            }
            int delta = s.length() - before;
            typingSeenMs = SystemClock.uptimeMillis();
            if (core.config.where == Config.WHERE_TYPING) {
                updateVisibility();
            }
            if (s.length() == 0 && delta < -1) {
                // поле очистили целиком: отправили или стёрли всё разом
                view.typingDone();
                return;
            }
            if (!core.config.followTyping) {
                return;
            }
            // раскладка текста обновится только к следующему кадру
            e.post(() -> {
                float[] p = caret(e);
                if (view != null) {
                    view.typing(p[0], p[1], delta);
                }
            });
        }
    }

    /** Каретка поля на экране. */
    private static float[] caret(EditText e) {
        int[] loc = new int[2];
        e.getLocationOnScreen(loc);
        Layout l = e.getLayout();
        if (l == null) {
            return new float[]{loc[0] + e.getWidth() / 2f, loc[1] + e.getHeight() / 2f};
        }
        int sel = Math.max(0, Math.min(e.length(), e.getSelectionEnd()));
        int line = l.getLineForOffset(sel);
        float x = l.getPrimaryHorizontal(sel) + e.getTotalPaddingLeft() - e.getScrollX();
        float y = (l.getLineTop(line) + l.getLineBottom(line)) / 2f + e.getTotalPaddingTop() - e.getScrollY();
        x = Math.max(0, Math.min(e.getWidth(), x));
        y = Math.max(0, Math.min(e.getHeight(), y));
        return new float[]{loc[0] + x, loc[1] + y};
    }

    // ---- экран --------------------------------------------------------------------------------

    private void onLayout() {
        if (decor == null || view == null) {
            return;
        }
        if (decor.getWidth() != lastW || decor.getHeight() != lastH) {
            lastW = decor.getWidth();
            lastH = decor.getHeight();
            applyPosition();
        }
        updateEdge(false);
        Object frag = topFragment();
        if (frag != lastFragment.get()) {
            lastFragment = new WeakReference<>(frag);
            String name = frag == null ? "" : frag.getClass().getName();
            long dlg = dialogOf(frag);
            boolean changed = !name.equals(fragmentName) || dlg != openDialog;
            fragmentName = name;
            openDialog = dlg;
            if (changed) {
                updateVisibility();
                if (core.config.screen && view.getVisibility() == View.VISIBLE) {
                    view.event("screen", decor.getWidth() * 0.97f, decor.getHeight() * 0.45f);
                }
                core.log("экран " + (frag == null ? "?" : frag.getClass().getSimpleName())
                        + (dlg != 0 ? " чат " + dlg : ""));
                // вернулись в список чатов: вдруг там уже кто-то печатает
                handler.removeCallbacks(dialogsTyping);
                if (onDialogs()) {
                    handler.postDelayed(dialogsTyping, 700);
                }
            }
        }
    }

    private Object topFragment() {
        Activity a = activity.get();
        if (a == null) {
            return null;
        }
        try {
            if (!fragResolved) {
                fragResolved = true;
                try {
                    Method m = a.getClass().getMethod("getLastFragment");
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        mLastFragmentStatic = m;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    mLayoutGetter = a.getClass().getMethod("getActionBarLayout");
                } catch (Throwable ignored) {
                }
            }
            if (mLastFragmentStatic != null) {
                return mLastFragmentStatic.invoke(null);
            }
            if (mLayoutGetter != null) {
                Object layout = mLayoutGetter.invoke(a);
                if (layout != null) {
                    return layout.getClass().getMethod("getLastFragment").invoke(layout);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static long dialogOf(Object frag) {
        if (frag == null || !frag.getClass().getName().endsWith(".ChatActivity")) {
            return 0L;
        }
        try {
            Object v = frag.getClass().getMethod("getDialogId").invoke(frag);
            return v instanceof Number ? ((Number) v).longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ---- события клиента ------------------------------------------------------------------------

    private void registerNotifications() {
        ClassLoader cl = Overlay.class.getClassLoader();
        try {
            Class<?> nc = Class.forName("org.telegram.messenger.NotificationCenter", true, cl);
            Class<?> del = Class.forName("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate", true, cl);
            idNew = intField(nc, "didReceiveNewMessages");
            idDel = intField(nc, "messagesDeleted");
            idUpd = intField(nc, "updateInterfaces");
            InvocationHandler h = (proxy, m, args) -> {
                String n = m.getName();
                if ("didReceivedNotification".equals(n) && args != null && args.length >= 2) {
                    Object[] extra = args.length >= 3 && args[2] instanceof Object[] ? (Object[]) args[2] : new Object[0];
                    try {
                        onNotification(((Number) args[0]).intValue(), ((Number) args[1]).intValue(), extra);
                    } catch (Throwable t) {
                        core.log("событие: " + t);
                    }
                    return null;
                }
                if ("equals".equals(n)) {
                    return args != null && args.length == 1 && args[0] == proxy;
                }
                if ("hashCode".equals(n)) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(n)) {
                    return "PixelEyes";
                }
                return null;
            };
            ncProxy = Proxy.newProxyInstance(del.getClassLoader(), new Class<?>[]{del}, h);
            int max = 4;
            try {
                Class<?> uc = Class.forName("org.telegram.messenger.UserConfig", true, cl);
                max = Math.max(1, Math.min(32, uc.getField("MAX_ACCOUNT_COUNT").getInt(null)));
            } catch (Throwable ignored) {
            }
            try {
                Class<?> mc = Class.forName("org.telegram.messenger.MessagesController", true, cl);
                printMask = intField(mc, "UPDATE_MASK_USER_PRINT");
                mMcInstance = mc.getMethod("getInstance", int.class);
                for (Method m : mc.getMethods()) {
                    if ("getPrintingString".equals(m.getName()) && m.getParameterTypes().length == 3) {
                        mPrinting = m;
                    }
                }
            } catch (Throwable ignored) {
            }
            Method get = nc.getMethod("getInstance", int.class);
            Method add = nc.getMethod("addObserver", del, int.class);
            for (int acc = 0; acc < max; acc++) {
                Object inst = get.invoke(null, acc);
                for (int id : new int[]{idNew, idDel, idUpd}) {
                    if (id >= 0) {
                        add.invoke(inst, ncProxy, id);
                        ncSubs.add(new Object[]{inst, id});
                    }
                }
            }
            core.log("события чата: подписка на " + max + " аккаунтов");
        } catch (Throwable t) {
            core.log("события чата недоступны: " + t);
        }
    }

    private void unregisterNotifications() {
        if (ncProxy == null) {
            return;
        }
        try {
            ClassLoader cl = Overlay.class.getClassLoader();
            Class<?> nc = Class.forName("org.telegram.messenger.NotificationCenter", true, cl);
            Class<?> del = Class.forName("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate", true, cl);
            Method remove = nc.getMethod("removeObserver", del, int.class);
            for (Object[] s : ncSubs) {
                try {
                    remove.invoke(s[0], ncProxy, (Integer) s[1]);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        ncSubs.clear();
        ncProxy = null;
    }

    private static int intField(Class<?> c, String name) {
        try {
            return c.getField(name).getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    void onNotification(int id, int account, Object[] args) {
        ncCount++;
        if (view == null || !core.config.enabled || view.getVisibility() != View.VISIBLE || decor == null) {
            return;
        }
        Config c = core.config;
        float w = decor.getWidth();
        float h = decor.getHeight();
        long ms = SystemClock.uptimeMillis();
        if (id == idNew && args.length >= 2 && args[1] instanceof List) {
            if (args.length >= 3 && Boolean.TRUE.equals(args[2])) {
                return;   // отложенные
            }
            long dialog = args[0] instanceof Number ? ((Number) args[0]).longValue() : 0L;
            boolean out = false;
            boolean in = false;
            List<CharSequence> outTexts = new ArrayList<>();
            List<CharSequence> inTexts = new ArrayList<>();
            for (Object mo : (List<?>) args[1]) {
                if (mo == null) {
                    continue;
                }
                CharSequence t = textOf(mo);
                if (Boolean.TRUE.equals(call(mo, "isOutOwner"))) {
                    out = true;
                    if (t != null) {
                        outTexts.add(t);
                    }
                } else {
                    in = true;
                    if (t != null) {
                        inTexts.add(t);
                    }
                }
            }
            boolean here = dialog != 0 && dialog == openDialog;
            String ev = onTexts(outTexts, inTexts, here);
            if (ev == null && out && c.send) {
                ev = "send";
                view.event(ev, w * 0.8f, h * 0.85f);
            } else if (ev == null && in && c.messages && ms - lastGlanceMs > 600) {
                if (here) {
                    lastGlanceMs = ms;
                    ev = "incoming_here";
                    view.event(ev, w * 0.25f, h * 0.82f);
                } else if (ms - lastOtherMs > 3000) {
                    lastGlanceMs = ms;
                    lastOtherMs = ms;
                    ev = "incoming_other";
                    view.event(ev, w * 0.5f, 0f);
                }
            }
            if (ev != null) {
                core.log("событие " + ev + (here ? " в открытом чате" : ""));
            }
        } else if (id == idDel && c.messages && openDialog != 0) {
            long ch = args.length >= 2 && args[1] instanceof Number ? ((Number) args[1]).longValue() : 0L;
            if (ch == 0 || ch == openDialog || -ch == openDialog) {
                view.event("deleted", 0, 0);
                core.log("событие deleted");
            }
        } else if (id == idUpd && c.peer && args.length >= 1 && args[0] instanceof Number) {
            int mask = ((Number) args[0]).intValue();
            if ((mask & printMask) == 0) {
                return;
            }
            if (openDialog != 0) {
                if (peerTyping(account, openDialog)) {
                    view.event("peer_typing", w * 0.3f, h * 0.05f);
                    core.log("событие peer_typing");
                }
            } else if (onDialogs()) {
                scanDialogsTyping();
            }
        }
    }

    /**
     * Настроение текста: свои сообщения всегда, чужие только в открытом чате (их видно).
     * Возвращает имя события или null, если текст ничего особого не значит.
     */
    String onTexts(List<CharSequence> outTexts, List<CharSequence> inTexts, boolean here) {
        Config c = core.config;
        if (view == null || decor == null) {
            return null;
        }
        float w = decor.getWidth();
        float h = decor.getHeight();
        int outMood = Mood.NONE;
        int inMood = Mood.NONE;
        for (CharSequence t : outTexts) {
            outMood = stronger(outMood, Mood.of(t));
        }
        if (here) {
            for (CharSequence t : inTexts) {
                inMood = stronger(inMood, Mood.of(t));
            }
        }
        String ev = null;
        float x = 0f;
        float y = 0f;
        if (inMood == Mood.INSULT && c.text) {
            ev = "insulted";
            x = w * 0.25f;
            y = h * 0.82f;
        } else if (outMood == Mood.INSULT && c.text) {
            ev = "rude";
        } else if ((outMood == Mood.LOVE || inMood == Mood.LOVE) && c.love) {
            ev = "love";
        } else if ((outMood == Mood.SAD || inMood == Mood.SAD) && c.text) {
            ev = "sad_text";
        } else if ((outMood == Mood.LAUGH || inMood == Mood.LAUGH) && c.text) {
            ev = "laugh";
        }
        if (ev == null) {
            return null;
        }
        long ms = SystemClock.uptimeMillis();
        // поток сообщений в группе не должен дёргать глаза каждую секунду; гадости показываем всегда
        if (ms - lastMoodMs < 1200 && !"insulted".equals(ev) && !"rude".equals(ev)) {
            return null;
        }
        lastMoodMs = ms;
        view.event(ev, x, y);
        core.log("текст:" + (outMood != Mood.NONE ? " наш " + Mood.name(outMood) : "")
                + (inMood != Mood.NONE ? " чужой " + Mood.name(inMood) : "") + " -> " + ev);
        return ev;
    }

    /** Что важнее показать: оскорбление, потом любовь, грусть, смех. */
    private static int stronger(int a, int b) {
        int[] rank = {0, 3, 4, 1, 2};
        return rank[b] > rank[a] ? b : a;
    }

    /** Список чатов: сам по себе или вкладкой нижней панели (MainTabsActivity в новых клиентах). */
    private boolean onDialogs() {
        return fragmentName.endsWith(".DialogsActivity") || fragmentName.endsWith(".MainTabsActivity");
    }

    /**
     * Список чатов: если у видимого чата "печатает...", смотрим на эту строку и
     * повторяем раз в 2 секунды, пока там печатают и чат виден.
     */
    void scanDialogsTyping() {
        handler.removeCallbacks(dialogsTyping);
        if (view == null || !core.config.peer || !onDialogs() || view.getVisibility() != View.VISIBLE) {
            typingDialog = 0;
            return;
        }
        int account = selectedAccount();
        float[] best = null;
        long bestDialog = 0;
        for (View cell : visibleDialogCells()) {
            long did = cellDialog(cell);
            if (did == 0 || !peerTyping(account, did)) {
                continue;
            }
            float[] p = typingPoint(cell);
            if (best == null || p[1] < best[1]) {
                best = p;
                bestDialog = did;
            }
        }
        if (best == null) {
            if (typingDialog != 0) {
                core.log("в списке больше не печатают");
            }
            typingDialog = 0;
            return;
        }
        view.event("peer_typing", best[0], best[1]);
        if (bestDialog != typingDialog) {
            core.log("печатают в списке: чат " + bestDialog + ", смотрим в " + (int) best[0] + "," + (int) best[1]);
        }
        typingDialog = bestDialog;
        handler.postDelayed(dialogsTyping, 2000);
    }

    /** Для проверки: видимые чаты списка "id@x,y" через пробел, точка там, где "печатает...". */
    String visibleDialogs() {
        StringBuilder b = new StringBuilder();
        for (View cell : visibleDialogCells()) {
            float[] p = typingPoint(cell);
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(cellDialog(cell)).append('@').append((int) p[0]).append(',').append((int) p[1]);
        }
        return b.toString();
    }

    /** Для проверки: посмотреть на строку чата, как будто там печатают. */
    boolean lookAtDialog(long dialog) {
        for (View cell : visibleDialogCells()) {
            if (cellDialog(cell) == dialog && view != null) {
                float[] p = typingPoint(cell);
                view.event("peer_typing", p[0], p[1]);
                return true;
            }
        }
        return false;
    }

    private List<View> visibleDialogCells() {
        ArrayList<View> out = new ArrayList<>();
        if (!dialogCellResolved) {
            dialogCellResolved = true;
            try {
                dialogCellClass = Class.forName("org.telegram.ui.Cells.DialogCell", true, Overlay.class.getClassLoader());
            } catch (Throwable t) {
                core.log("DialogCell не найден: " + t);
            }
        }
        if (dialogCellClass == null || decor == null) {
            return out;
        }
        // от окна целиком: список чатов бывает вложен в экран вкладок, а спрятанные экраны отсекает isShown
        collectCells(decor, out, 0);
        return out;
    }

    private final Rect tmpRect = new Rect();

    private void collectCells(View v, List<View> out, int depth) {
        if (depth > 40 || v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (dialogCellClass.isInstance(v)) {
            if (v.isShown() && v.getGlobalVisibleRect(tmpRect) && tmpRect.height() * 2 >= v.getHeight()) {
                out.add(v);
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectCells(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    private static long cellDialog(View cell) {
        Object v = call(cell, "getDialogId");
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** Где в строке чата пишется "печатает...": начало второй строки, после аватарки. */
    private float[] typingPoint(View cell) {
        int[] loc = new int[2];
        cell.getLocationOnScreen(loc);
        float dp = cell.getResources().getDisplayMetrics().density;
        float left = -1f;
        float top = -1f;
        try {
            if (fMessageLeft == null) {
                fMessageLeft = field(dialogCellClass, "messageLeft");
                fMessageTop = field(dialogCellClass, "messageTop");
            }
            if (fMessageLeft != null && fMessageTop != null) {
                left = fMessageLeft.getInt(cell);
                top = fMessageTop.getInt(cell);
            }
        } catch (Throwable ignored) {
        }
        if (left <= 0 || top <= 0 || top >= cell.getHeight()) {
            left = 76 * dp;
            top = cell.getHeight() * 0.55f;
        }
        return new float[]{loc[0] + left + 28 * dp, loc[1] + top + 9 * dp};
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private int selectedAccount() {
        try {
            if (fSelectedAccount == null) {
                Class<?> uc = Class.forName("org.telegram.messenger.UserConfig", true, Overlay.class.getClassLoader());
                fSelectedAccount = uc.getField("selectedAccount");
            }
            return fSelectedAccount.getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean peerTyping(int account, long dialog) {
        if (mPrinting == null || mMcInstance == null) {
            return false;
        }
        try {
            Object mc = mMcInstance.invoke(null, account);
            Class<?>[] p = mPrinting.getParameterTypes();
            Object r = p[1] == long.class
                    ? mPrinting.invoke(mc, dialog, 0L, true)
                    : mPrinting.invoke(mc, dialog, 0, true);
            return r != null && r.toString().length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object call(Object o, String name) {
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Текст сообщения как его написали (с подписью к медиа), без подстановок вроде "Фото". */
    private static CharSequence textOf(Object mo) {
        try {
            Object owner = mo.getClass().getField("messageOwner").get(mo);
            Object m = owner == null ? null : owner.getClass().getField("message").get(owner);
            if (m instanceof CharSequence && ((CharSequence) m).length() > 0) {
                return (CharSequence) m;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---- датчики, буфер, время суток, батарея ------------------------------------------------------

    private void startSensors() {
        Activity a = activity.get();
        if (a == null || !core.config.enabled) {
            return;
        }
        if (core.config.shake && shakeL == null) {
            try {
                sensors = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
                Sensor acc = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (acc != null) {
                    shakeL = new SensorEventListener() {
                        @Override
                        public void onSensorChanged(SensorEvent ev) {
                            onAccel(ev.values[0], ev.values[1], ev.values[2]);
                        }

                        @Override
                        public void onAccuracyChanged(Sensor s, int accuracy) {
                        }
                    };
                    sensors.registerListener(shakeL, acc, SensorManager.SENSOR_DELAY_UI, handler);
                }
            } catch (Throwable t) {
                shakeL = null;
            }
        }
        if (core.config.clip && clipL == null) {
            try {
                clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                clipL = () -> {
                    long ms = SystemClock.uptimeMillis();
                    if (view != null && ms - lastClipMs > 1000 && view.getVisibility() == View.VISIBLE) {
                        lastClipMs = ms;
                        view.event("copy", 0, 0);
                        core.log("событие copy");
                    }
                };
                clipboard.addPrimaryClipChangedListener(clipL);
            } catch (Throwable t) {
                clipL = null;
            }
        }
        handler.removeCallbacks(envTick);
        handler.postDelayed(envTick, 60_000L);
    }

    private void stopSensors() {
        if (shakeL != null && sensors != null) {
            try {
                sensors.unregisterListener(shakeL);
            } catch (Throwable ignored) {
            }
        }
        shakeL = null;
        if (clipL != null && clipboard != null) {
            try {
                clipboard.removePrimaryClipChangedListener(clipL);
            } catch (Throwable ignored) {
            }
        }
        clipL = null;
        handler.removeCallbacks(envTick);
    }

    void onAccel(float x, float y, float z) {
        float g = (float) Math.sqrt(x * x + y * y + z * z);
        long ms = SystemClock.uptimeMillis();
        if (Math.abs(g - SensorManager.GRAVITY_EARTH) > 11f) {
            if (ms - shakeWindowMs > 900) {
                shakeWindowMs = ms;
                shakeHits = 0;
            }
            shakeHits++;
            if (shakeHits >= 3 && ms - lastShakeMs > 3000 && view != null
                    && view.getVisibility() == View.VISIBLE) {
                lastShakeMs = ms;
                shakeHits = 0;
                view.event("shake", 0, 0);
                core.log("событие shake");
            }
        }
    }

    private void envTick() {
        if (view == null) {
            return;
        }
        Config c = core.config;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        view.brain.night = c.night && hour < 6;
        boolean low = false;
        if (c.battery) {
            try {
                Activity a = activity.get();
                Context ctx = a != null ? a : app;
                Intent bi = ctx == null ? null
                        : ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                    low = level >= 0 && scale > 0 && level * 100 / scale <= 15 && plugged == 0;
                }
            } catch (Throwable ignored) {
            }
        }
        view.brain.lowBattery = low;
        if (resumed) {
            handler.removeCallbacks(envTick);
            handler.postDelayed(envTick, 60_000L);
        }
    }

    String stats() {
        return "окно " + (decor != null ? "есть" : "нет") + ", касания " + (wrapper != null ? "видны" : "нет")
                + ", полей с набором " + watched.size() + ", экран " + (fragmentName.isEmpty() ? "?"
                : fragmentName.substring(fragmentName.lastIndexOf('.') + 1))
                + (view == null ? "" : ", кадров " + view.frames + ", отрисовок " + view.renders)
                + ", событий клиента " + ncCount;
    }
}
