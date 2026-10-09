package com.pixeleyes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Ядро Pixel Eyes: всё, что зовёт Python. Только статические методы, наружу
 * строки, числа и объекты классов системы (Chaquopy не оборачивает классы из
 * чужого загрузчика DEX).
 *
 * Скин и настройки можно менять с любого потока: вью трогаются только на
 * потоке интерфейса.
 */
public final class EyesCore {

    public static final String VERSION = "pe-core-3";
    private static final String REGISTRY = "pixel_eyes.cleanup";
    private static final int LOG_LIMIT = 40_000;

    private static volatile EyesCore instance;

    static EyesCore get() {
        EyesCore c = instance;
        if (c == null) {
            synchronized (EyesCore.class) {
                c = instance;
                if (c == null) {
                    c = new EyesCore();
                    instance = c;
                }
            }
        }
        return c;
    }

    final Config config = new Config();
    private volatile Skin skin;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Overlay overlay;
    private volatile Object listener;
    private final StringBuilder logBuf = new StringBuilder();
    private SimpleDateFormat clock;
    private volatile String lastError = "";
    /** Живые примеры в настройках: меняются вместе с выбором глаз. */
    final java.util.WeakHashMap<EyesView, Boolean> previews = new java.util.WeakHashMap<>();

    private EyesCore() {
        skin = Skin.parse(Builtins.JSON[Builtins.DEFAULT]);
    }

    Skin skin() {
        return skin;
    }

    // ---- для Python ---------------------------------------------------------------------

    public static String version() {
        return VERSION;
    }

    /** Ставит глаза поверх приложения. Пустая строка или причина отказа. */
    public static String install(Context ctx) {
        EyesCore c = get();
        // глаза прошлого поколения плагина (другой загрузчик DEX) убираем сами
        try {
            Properties p = System.getProperties();
            Object prev;
            synchronized (p) {
                prev = p.remove(REGISTRY);
            }
            if (prev instanceof Runnable && prev != c.cleanup) {
                c.main.post((Runnable) prev);
            }
            synchronized (p) {
                p.put(REGISTRY, c.cleanup);
            }
        } catch (Throwable ignored) {
        }
        c.main.post(() -> {
            try {
                if (c.overlay == null) {
                    c.overlay = new Overlay(c);
                }
                c.overlay.install(ctx);
            } catch (Throwable t) {
                c.lastError = "install: " + t;
                c.log("не встало: " + t);
            }
        });
        return "";
    }

    private final Runnable cleanup = () -> {
        Overlay o = get().overlay;
        if (o != null) {
            o.uninstall();
        }
        get().overlay = null;
    };

    public static void uninstall() {
        EyesCore c = get();
        c.main.post(c.cleanup);
        try {
            Properties p = System.getProperties();
            synchronized (p) {
                if (p.get(REGISTRY) == c.cleanup) {
                    p.remove(REGISTRY);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void configure(String settings) {
        EyesCore c = get();
        c.config.apply(settings);
        c.main.post(() -> {
            if (c.overlay != null) {
                c.overlay.applyConfig();
            }
        });
    }

    /** Новый набор глаз. Пустая строка или понятная ошибка в описании. */
    public static String setSkin(String json) {
        Skin s;
        try {
            s = Skin.parse(json);
        } catch (Throwable t) {
            return message(t);
        }
        EyesCore c = get();
        c.skin = s;
        c.main.post(() -> {
            if (c.overlay != null) {
                c.overlay.applyConfig();
            }
            for (EyesView v : new ArrayList<>(c.previews.keySet())) {
                if (v != null && v.skin != s) {
                    v.setSkin(s);
                    Panels.fitBig(v);
                }
            }
        });
        c.log("глаза: " + s.name + " (" + s.id + ")");
        return "";
    }

    /** Проверка описания: пустая строка, если всё хорошо. */
    public static String check(String json) {
        try {
            Skin.parse(json);
            return "";
        } catch (Throwable t) {
            return message(t);
        }
    }

    /** Кратко о наборе: id, имя, автор, описание, теги через запятую. Или "!ошибка". */
    public static String info(String json) {
        try {
            Skin s = Skin.parse(json);
            return s.id + "\n" + s.name + "\n" + s.author + "\n" + s.description + "\n" + String.join(", ", s.tags);
        } catch (Throwable t) {
            return "!" + message(t);
        }
    }

    public static int builtinCount() {
        return Builtins.JSON.length;
    }

    public static String builtinJson(int i) {
        return Builtins.JSON[i];
    }

    public static String defaultId() {
        return Skin.parse(Builtins.JSON[Builtins.DEFAULT]).id;
    }

    /** Событие от плагина, например "send" или "emo:love". */
    public static void event(String name) {
        EyesCore c = get();
        c.main.post(() -> {
            if (c.overlay != null && c.overlay.view != null) {
                c.overlay.view.event(name, 0, 0);
            }
        });
    }

    /** Свои слова для настроения: строки "insult=...", "love=...", "laugh=...", "sad=...", "ignore=...". */
    public static void setWords(String spec) {
        Mood.setCustom(spec);
    }

    /** Настроение текста: "none", "love", "insult", "laugh", "sad". */
    public static String mood(String text) {
        return new String[]{"none", "love", "insult", "laugh", "sad"}[Mood.of(text)];
    }

    /**
     * Проверка без настоящих сообщений: как отреагируют глаза на текст (свой или
     * чужой в открытом чате). Возвращает событие или "".
     */
    public static String testText(String text, boolean out) {
        return onMain(() -> {
            Overlay o = get().overlay;
            if (o == null) {
                return "нет оверлея";
            }
            java.util.List<CharSequence> one = java.util.Collections.singletonList(text);
            java.util.List<CharSequence> none = java.util.Collections.emptyList();
            String ev = out ? o.onTexts(one, none, true) : o.onTexts(none, one, true);
            return ev == null ? "" : ev;
        });
    }

    /** Видимые чаты списка "id@x,y" (точка, куда смотреть на "печатает..."). */
    public static String dialogsInfo() {
        return onMain(() -> {
            Overlay o = get().overlay;
            return o == null ? "" : o.fragmentName + " | " + o.visibleDialogs();
        });
    }

    /** Посмотреть на строку чата в списке, как будто там печатают. */
    public static boolean lookAtDialog(long dialog) {
        return "1".equals(onMain(() -> {
            Overlay o = get().overlay;
            return o != null && o.lookAtDialog(dialog) ? "1" : "0";
        }));
    }

    private static String onMain(java.util.concurrent.Callable<String> task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return task.call();
            } catch (Throwable t) {
                return "ошибка: " + t;
            }
        }
        final String[] box = {""};
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        get().main.post(() -> {
            try {
                box[0] = task.call();
            } catch (Throwable t) {
                box[0] = "ошибка: " + t;
            }
            done.countDown();
        });
        try {
            done.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return box[0];
    }

    /** java.util.function.Consumer<String>: сюда уходят "pos:x,y", "select:id", "long:id". */
    public static void setListener(Object consumer) {
        get().listener = consumer;
    }

    public static View createPreview(Context ctx, String json) {
        Skin s;
        try {
            s = json == null || json.isEmpty() ? get().skin : Skin.parse(json);
        } catch (Throwable t) {
            s = get().skin;
        }
        return Panels.preview(ctx, s, get().config);
    }

    /**
     * Галерея: описания через символ с кодом 1, плохие пропускаются. mode "catalog":
     * нажатие шлёт "pick:id" (скачать из каталога), иначе "select:id" и "long:id".
     */
    public static View createGallery(Context ctx, String joined, String selected, String mode) {
        ArrayList<Skin> list = new ArrayList<>();
        for (String j : joined.split("\u0001")) {
            if (j.trim().isEmpty()) {
                continue;
            }
            try {
                list.add(Skin.parse(j));
            } catch (Throwable ignored) {
            }
        }
        return Panels.gallery(ctx, list, selected, get(), "catalog".equals(mode));
    }

    /** Редактор: собранный FrameLayout, его же потом отдают в editorJson. */
    public static View createEditor(Context ctx, String json) {
        Skin s;
        try {
            s = json == null || json.isEmpty() ? null : Skin.parse(json);
        } catch (Throwable t) {
            s = null;
        }
        return Editor.create(ctx, s);
    }

    /** JSON из редактора или "!ошибка". */
    public static String editorJson(View root, String name) {
        try {
            Editor e = Editor.of(root);
            if (e == null) {
                return "!редактор не найден";
            }
            return e.toJson(name);
        } catch (Throwable t) {
            return "!" + message(t);
        }
    }

    /**
     * Рисует вью в PNG шириной widthPx (для проверки вёрстки без скриншотов
     * экрана); 0: уже размещённое вью как есть. Пустая строка или ошибка.
     */
    public static String snapshot(View v, int widthPx, String path) {
        try {
            int h;
            if (widthPx > 0) {
                v.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                h = Math.max(1, v.getMeasuredHeight());
                v.layout(0, 0, widthPx, h);
            } else {
                // живое окно: рисуем как есть, без перемера
                widthPx = Math.max(1, v.getWidth());
                h = Math.max(1, v.getHeight());
            }
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(widthPx, h,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            c.drawColor(Ui.color("key_windowBackgroundWhite", 0xFFFFFFFF));
            v.draw(c);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(path)) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            b.recycle();
            return "";
        } catch (Throwable t) {
            return t.toString();
        }
    }

    public static String stats() {
        EyesCore c = get();
        Overlay o = c.overlay;
        return "глаза " + c.skin.name + "; " + (o == null ? "оверлея нет" : o.stats())
                + (c.lastError.isEmpty() ? "" : "; ошибка: " + c.lastError);
    }

    public static String drainLog() {
        EyesCore c = get();
        synchronized (c.logBuf) {
            String s = c.logBuf.toString();
            c.logBuf.setLength(0);
            return s;
        }
    }

    // ---- внутреннее ----------------------------------------------------------------------

    void notify(String msg) {
        Object l = listener;
        if (l == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Consumer<String> cons = (Consumer<String>) l;
            cons.accept(msg);
        } catch (Throwable t) {
            lastError = "listener: " + t;
        }
    }

    void log(String line) {
        if (!config.debug) {
            return;
        }
        synchronized (logBuf) {
            if (clock == null) {
                clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
            }
            if (logBuf.length() > LOG_LIMIT) {
                logBuf.setLength(0);
                logBuf.append("... журнал ядра переполнился\n");
            }
            logBuf.append(clock.format(new Date())).append(' ').append(line).append('\n');
        }
    }

    static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.toString() : m;
    }

    /** Для Python: вью-обёртка, если понадобится вставить что-то своё. */
    static FrameLayout box(Context c, View v) {
        FrameLayout f = new FrameLayout(c);
        f.addView(v);
        return f;
    }
}
