package com.pixeleyes;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Проверки чистой части ядра на ПК: разбор глаз, отрисовка, поведение.
 * Запуск: bash eyes/test/run.sh
 */
public final class CoreTest {

    static int fails;
    static int checks;

    static void check(String name, boolean ok) {
        checks++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    static String error(String json) {
        try {
            Skin.parse(json);
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Мозг на поддельной геометрии: глаз i на экране там же, где на холсте, пиксель = 10 px. */
    static Brain brain(Skin s, Renderer r) {
        Brain b = new Brain(s, r, 3);
        int n = s.places.size();
        float[] cx = new float[n];
        float[] cy = new float[n];
        for (int i = 0; i < n; i++) {
            cx[i] = r.eyeCenterX(i) * 10f;
            cy[i] = r.eyeCenterY(i) * 10f;
        }
        b.setGeometry(cx, cy, 10f, 300f);
        return b;
    }

    static float run(Brain b, float t, float sec) {
        for (float k = 0; k < sec; k += 0.016f) {
            t += 0.016f;
            b.update(t);
        }
        return t;
    }

    public static void main(String[] a) {
        System.out.println("1. встроенные глаза");
        HashSet<String> ids = new HashSet<>();
        boolean allOk = true;
        for (String j : Builtins.JSON) {
            try {
                Skin s = Skin.parse(j);
                if (!ids.add(s.id)) {
                    allOk = false;
                    System.out.println("    повтор id " + s.id);
                }
                Renderer r = new Renderer(s);
                Brain b = brain(s, r);
                float t = 0f;
                for (String e : Brain.NAMES) {
                    b.event("emo:" + e, 0, 0);
                    t = run(b, t, 0.3f);
                    r.render(b.face);
                }
            } catch (Throwable t) {
                allOk = false;
                System.out.println("    " + t);
            }
        }
        check("все " + Builtins.JSON.length + " разбираются и рисуются во всех эмоциях, id не повторяются", allOk);
        check("по умолчанию мультяшные", "cartoon".equals(Skin.parse(Builtins.JSON[Builtins.DEFAULT]).id));

        System.out.println("2. понятные ошибки");
        check("нет eye", error("{\"id\":\"x\"}").contains("нет сетки \"eye\""));
        check("символ не из палитры", error("{\"palette\":{\"w\":\"#fff\"},\"eye\":[\"wq\"]}").contains("символ 'q'"));
        check("плохой цвет", error("{\"palette\":{\"w\":\"красный\"},\"eye\":[\"w\"]}").contains("непонятный цвет"));
        check("нет яблока", error("{\"palette\":{\"#\":\"#000\"},\"eye\":[\"##\"]}").contains("ни одной клетки яблока"));
        StringBuilder big = new StringBuilder("{\"palette\":{\"w\":\"#fff\"},\"eye\":[\"");
        for (int i = 0; i < 40; i++) {
            big.append('w');
        }
        big.append("\"]}");
        check("слишком большой", error(big.toString()).contains("больше допустимого"));
        check("битый JSON со строкой и символом", error("{\"eye\": [\"ww\",}").contains("строка 1"));
        check("формат из будущего", error("{\"format\":9,\"palette\":{\"w\":\"#fff\"},\"eye\":[\"w\"]}")
                .contains("обновите плагин"));
        check("неизвестный кадр", error("{\"palette\":{\"w\":\"#fff\"},\"eye\":[\"w\"],\"frames\":{\"lol\":[\"w\"]}}")
                .contains("неизвестный кадр"));
        check("запятая перед скобкой прощается", error("{\"palette\":{\"w\":\"#fff\",},\"eye\":[\"w\",],}").isEmpty());
        check("комментарии // прощаются", error("{\n// мои глаза\n\"palette\":{\"w\":\"#fff\"},\"eye\":[\"w\"]}")
                .isEmpty());

        System.out.println("3. JSON туда и обратно");
        Skin anime = null;
        for (String j : Builtins.JSON) {
            Skin s = Skin.parse(j);
            if (s.id.equals("anime")) {
                anime = s;
            }
        }
        String again = anime.toJson();
        Skin back = Skin.parse(again);
        check("запись и разбор дают те же пиксели", java.util.Arrays.equals(back.eye.px, anime.eye.px)
                && java.util.Arrays.equals(back.iris.px, anime.iris.px) && back.frames.size() == anime.frames.size());
        Object parsed = MiniJson.parse("{\"a\":[1,2.5,\"x\\n\\u0041\"],\"b\":{\"c\":true,\"d\":null}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> pm = (Map<String, Object>) parsed;
        check("разбор чисел, строк, вложенных объектов", ((List<?>) pm.get("a")).get(2).equals("x\nA")
                && Boolean.TRUE.equals(((Map<?, ?>) pm.get("b")).get("c")));

        System.out.println("4. отрисовка");
        Skin cartoon = Skin.parse(Builtins.JSON[Builtins.DEFAULT]);
        Renderer r = new Renderer(cartoon);
        Face f = new Face(cartoon.places.size());
        int[] center = r.render(f).clone();
        f.gx[0] = 1f;
        f.gx[1] = 1f;
        int[] right = r.render(f).clone();
        check("взгляд вправо меняет картинку", !java.util.Arrays.equals(center, right));
        f.gx[0] = 0f;
        f.gx[1] = 0f;
        f.openTop[0] = 0f;
        f.openTop[1] = 0f;
        int[] closed = r.render(f).clone();
        int pupil = 0;
        for (int px : closed) {
            if (px == 0xFF15151C) {
                pupil++;
            }
        }
        check("закрытые веки прячут зрачок", pupil == 0);
        r.edge = Renderer.EDGE_HALO;
        f.openTop[0] = 1f;
        f.openTop[1] = 1f;
        int[] halo = r.render(f).clone();
        int light = 0;
        for (int px : halo) {
            if (px == 0x4DFFFFFF) {
                light++;
            }
        }
        check("светлый контур на тёмной теме", light > 20);

        System.out.println("5. поведение");
        Brain b = brain(cartoon, r);
        float t = run(b, 0f, 0.5f);
        b.now = t;
        b.typing(r.eyeCenterX(0) * 10f + 2000f, r.eyeCenterY(0) * 10f, 1);
        t = run(b, t, 0.4f);
        check("печатает: глаза на каретке справа", b.face.gx[0] > 0.5f);
        check("и сосредоточены", b.emo == Brain.FOCUS);
        t = run(b, t, 1.5f);
        check("перестал печатать: смотрит прямо на вас", b.emo == Brain.STARE && Math.abs(b.face.gx[0]) < 0.1f);
        b.now = t;
        b.event("send", 0, 0);
        t = run(b, t, 0.3f);
        check("отправка: радость", b.emo == Brain.HAPPY && "happy".equals(b.face.frame));
        t = run(b, t, 2f);
        b.now = t;
        b.poke();
        check("тычок: ай", b.emo == Brain.OUCH);
        b.poke();
        b.poke();
        check("три тычка: злость", b.emo == Brain.ANGRY);
        t = run(b, t, 3.5f);
        b.sleepAfter = 5f;
        t = run(b, t, 6f);
        check("без дела клюют носом", b.face.openTop[0] < 0.5f && b.face.openTop[0] > 0.05f);
        t = run(b, t, 21f);
        check("потом спят с Z", b.isAsleep() && "closed".equals(b.face.frame)
                && b.face.particles.stream().anyMatch(p -> p.type == Face.P_Z));
        b.now = t;
        b.touch(0, 0f, 0f);
        t = run(b, t, 0.1f);
        check("касание будит: удивление", !b.isAsleep() && b.emo == Brain.SURPRISED);
        b.now = t;
        b.touch(2, 0f, 0f);
        t = run(b, t, 1f);
        b.now = t;
        b.event("incoming_here", -5000f, 9000f);
        t = run(b, t, 0.3f);
        check("новое сообщение: оглянулись вниз влево", b.face.gx[0] < -0.3f && b.face.gy[0] > 0.3f);
        t = run(b, t, 2f);
        b.now = t;
        b.toggleNap();
        t = run(b, t, 0.5f);
        check("двойной тап: дремлют", b.isAsleep());
        b.now = t;
        b.event("incoming_here", 0, 0);
        t = run(b, t, 0.4f);
        check("сообщение во сне: приоткрыли один глаз", b.face.openTop[0] > 0.2f && b.face.openTop[1] < 0.05f);
        b.now = t;
        b.toggleNap();
        b.event("shake", 0, 0);
        t = run(b, t, 0.3f);
        check("тряска: голова кружится", b.face.pupilMode == Face.PUPIL_SPIRAL);
        float wake = b.nextWake();
        check("время следующего события в будущем", wake > b.now && wake < b.now + 120f);

        Skin swarm = null;
        for (String j : Builtins.JSON) {
            Skin s = Skin.parse(j);
            if (s.id.equals("swarm")) {
                swarm = s;
            }
        }
        Renderer rs = new Renderer(swarm);
        Brain bs = brain(swarm, rs);
        float ts = 0f;
        boolean differ = false;
        for (int k = 0; k < 600 && !differ; k++) {
            ts += 0.016f;
            bs.update(ts);
            float o0 = bs.face.openTop[0];
            for (int i = 1; i < swarm.places.size(); i++) {
                if (Math.abs(bs.face.openTop[i] - o0) > 0.3f) {
                    differ = true;
                }
            }
        }
        check("рой моргает вразнобой", differ);
        bs.now = ts;
        bs.touch(0, 0f, 0f);
        ts = run(bs, ts, 0.5f);
        boolean converge = true;
        for (int i = 0; i < swarm.places.size(); i++) {
            if (bs.face.gx[i] > 0f) {
                converge = false;
            }
        }
        check("рой смотрит на палец каждым глазом со своего места", converge);

        System.out.println("6. настроение текста");
        String casesPath = a.length > 0 ? a[0] : "test/mood_cases.txt";
        String[] moodNames = {"none", "love", "insult", "laugh", "sad"};
        int moodChecked = 0;
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(casesPath),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.startsWith("#") || line.indexOf('	') < 0) {
                    continue;
                }
                String want = line.substring(0, line.indexOf('	'));
                String text = line.substring(line.indexOf('	') + 1);
                String got = moodNames[Mood.of(text)];
                moodChecked++;
                if (!got.equals(want)) {
                    check("\"" + text + "\": ждали " + want + ", вышло " + got, false);
                }
            }
        } catch (java.io.IOException e) {
            check("файл примеров настроения: " + e, false);
        }
        check("примеры настроения сходятся (" + moodChecked + ")", moodChecked > 40);

        System.out.println("6б. свои слова из настроек");
        Mood.setCustom("insult=ты бот, чепух*\nlove=зая, котёнок\nlaugh=кек\nsad=эх\nignore=сука, дурак, дебил*");
        check("своя гадость фразой", Mood.of("ну ты бот") == Mood.INSULT);
        check("своя гадость корнем", Mood.of("какая чепуховина") == Mood.INSULT);
        check("своё ласковое", Mood.of("привет, зая") == Mood.LOVE);
        check("ё в своих словах как е", Mood.of("котенок мой") == Mood.LOVE);
        check("свой смех", Mood.of("кек") == Mood.LAUGH);
        check("своя грусть", Mood.of("эх") == Mood.SAD);
        check("исключение слова", Mood.of("ты дурак") == Mood.NONE);
        check("исключение корнем", Mood.of("ты дебилушка") == Mood.NONE);
        check("исключение не мешает остальному", Mood.of("ты сука, иди нахуй") == Mood.INSULT);
        check("встроенное работает рядом со своим", Mood.of("люблю тебя") == Mood.LOVE);
        Mood.setCustom("");
        check("без своих слов всё как было", Mood.of("ты дурак") == Mood.INSULT && Mood.of("кек") == Mood.NONE);

        System.out.println("7. гадости в наш адрес: испуг, потом слёзы");
        Skin cs = Skin.parse(Builtins.JSON[Builtins.DEFAULT]);
        Brain bi = new Brain(cs, new Renderer(cs), 7L);
        float ti = run(bi, 1f, 0.2f);
        bi.now = ti;
        bi.event("insulted", 100f, 900f);
        ti = run(bi, ti, 0.5f);
        check("сначала испуг", bi.emo == Brain.SCARED);
        ti = run(bi, ti, 1.2f);
        check("потом грусть", bi.emo == Brain.SAD);
        ti = run(bi, ti, 2.5f);
        check("потом успокоились", bi.emo == Brain.NEUTRAL);
        bi.now = ti;
        bi.event("rude", 0f, 0f);
        ti = run(bi, ti, 0.2f);
        check("наши гадости: злость", bi.emo == Brain.ANGRY);
        bi.now = ti;
        bi.event("laugh", 0f, 0f);
        ti = run(bi, ti, 0.2f);
        check("смех: радость", bi.emo == Brain.HAPPY);
        bi.now = ti;
        bi.event("insulted", 0f, 0f);
        ti = run(bi, ti, 0.3f);
        bi.now = ti;
        bi.poke();
        ti = run(bi, ti, 2.5f);
        check("тычок посреди испуга отменяет слёзы", bi.emo != Brain.SAD);

        System.out.println();
        System.out.println(fails == 0 ? "ИТОГ: всё прошло (" + checks + ")" : "ИТОГ: провалов " + fails + " из " + checks);
        System.exit(fails == 0 ? 0 : 1);
    }
}
