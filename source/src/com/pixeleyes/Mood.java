package com.pixeleyes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Настроение текста сообщения: оскорбление, любовь, смех, грусть. Только по
 * словам и эмодзи, без сети. Обычный мат как междометие ("бля", "пиздец")
 * оскорблением не считается: нужен адресат или само ругательное слово.
 *
 * Тот же разбор есть в JS на сайте (assets/eyes.js), правки переносить туда.
 */
final class Mood {

    static final int NONE = 0;
    static final int LOVE = 1;
    static final int INSULT = 2;
    static final int LAUGH = 3;
    static final int SAD = 4;

    private Mood() {
    }

    /**
     * Свои слова из настроек. Каждая запись: слово или фраза, звёздочка в конце
     * одного слова значит "любое окончание". Исключения убираются из текста до разбора.
     */
    static final class Custom {
        final List<List<String>> phrases = new ArrayList<>();
        final List<List<String>> stems = new ArrayList<>();
        final List<String> ignorePhrases = new ArrayList<>();
        final List<String> ignoreStems = new ArrayList<>();

        Custom() {
            for (int i = 0; i < 5; i++) {
                phrases.add(new ArrayList<>());
                stems.add(new ArrayList<>());
            }
        }

        boolean empty() {
            for (int i = 0; i < 5; i++) {
                if (!phrases.get(i).isEmpty() || !stems.get(i).isEmpty()) {
                    return false;
                }
            }
            return ignorePhrases.isEmpty() && ignoreStems.isEmpty();
        }

        boolean match(int mood, String joined, List<String> words) {
            if (phrase(joined, phrases.get(mood).toArray(new String[0]))) {
                return true;
            }
            for (String w : words) {
                if (startsWithAny(w, stems.get(mood).toArray(new String[0]))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static volatile Custom custom = new Custom();

    /** Строки "insult=слово, фраза, корень*" для insult, love, laugh, sad и ignore. */
    static void setCustom(String spec) {
        Custom c = new Custom();
        if (spec != null) {
            for (String line : spec.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                int mood = "love".equals(key) ? LOVE : "insult".equals(key) ? INSULT : "laugh".equals(key) ? LAUGH
                        : "sad".equals(key) ? SAD : "ignore".equals(key) ? 0 : -1;
                if (mood < 0) {
                    continue;
                }
                for (String item : line.substring(eq + 1).split("[,;]")) {
                    String t = item.trim();
                    boolean stem = t.endsWith("*");
                    if (stem) {
                        t = t.substring(0, t.length() - 1);
                    }
                    List<String> ws = words(t);
                    if (ws.isEmpty()) {
                        continue;
                    }
                    String norm = String.join(" ", ws);
                    boolean asStem = stem && ws.size() == 1;
                    if (mood == 0) {
                        (asStem ? c.ignoreStems : c.ignorePhrases).add(norm);
                    } else {
                        (asStem ? c.stems : c.phrases).get(mood).add(norm);
                    }
                }
            }
        }
        custom = c;
    }

    /** Ругательства, которые сами по себе про кого-то: начало слова. */
    static final String[] STRONG_STEMS = {
            "дебил", "идиот", "долбоеб", "долбаеб", "далбаеб", "долбоящер", "еблан", "ебанат", "ебанько", "ебанут",
            "мудак", "мудил", "мудозвон", "мудло", "урод", "уебок", "уебан", "уебищ", "ублюд", "тварь", "твари",
            "гнид", "мразь", "мрази", "мразот", "чмош", "чмыр", "пидор", "пидр", "пидар", "гандон", "гондон",
            "шлюх", "шалав", "сучк", "сученыш", "дегенерат", "кретин", "имбецил", "олигофрен",
            "ничтожеств", "падл", "паскуд", "сволоч", "скотин", "выродок", "выблядок", "хуесос", "хуила", "хуйло",
            "говнюк", "говноед", "лошар", "придурк", "придурок", "тупорыл", "тупиц", "петушар", "чепушил",
            "пиздабол", "пиздюк", "залупа", "конченн",
    };

    /** Ругательства целым словом (короткие, иначе ловили бы чужие слова). */
    static final Set<String> STRONG_WORDS = new HashSet<>(Arrays.asList(
            "чмо", "даун", "дауна", "дауну", "дауном", "дауны", "даунов", "педик", "педика", "педики", "педиков",
            "отброс", "отбросы", "отбросов", "конченый", "конченая", "конченые", "конченого", "конченой", "мудень",
            "ебанашка", "сучка"));

    /** Мягкие: оскорбление, только если есть адресат или сообщение совсем короткое. */
    static final Set<String> MILD_WORDS = new HashSet<>(Arrays.asList(
            "дурак", "дура", "дураки", "дурачок", "дурочка", "глупый", "глупая", "глупые", "тупой", "тупая", "тупое",
            "тупые", "лох", "лоха", "лохи", "лошок", "лохушка", "клоун", "клоуны", "нуб", "лузер", "неадекват",
            "псих", "психичка", "аутист", "аутистка", "бездарь", "козел"));

    /** "Сука" чаще междометие: оскорбление, только если стоит вплотную к адресату ("ты сука", "сука ты", "он сука"). */
    static final Set<String> BITCH = new HashSet<>(Arrays.asList("сука", "суки", "сучара"));
    static final Set<String> BITCH_BEFORE = new HashSet<>(Arrays.asList(
            "ты", "вы", "он", "она", "они", "это", "такая", "такой", "какая", "тупая", "тупой"));
    static final Set<String> BITCH_AFTER = new HashSet<>(Arrays.asList("ты", "вы", "такая", "такой"));
    static final Set<String> LINKS = new HashSet<>(Arrays.asList("и", "же", "ещё", "еще", "прям", "реально"));

    /** Кому адресовано. */
    static final Set<String> PRONOUNS = new HashSet<>(Arrays.asList(
            "ты", "тебя", "тебе", "тобой", "вы", "вас", "вам", "вами", "твой", "твоя", "твое", "твои", "ваш", "ваша",
            "он", "она", "они", "его", "ее", "их", "этот", "эта", "такой", "такая"));

    /** Грубые фразы целиком (по словам). */
    static final String[] RUDE_PHRASES = {
            "иди нахуй", "иди на хуй", "идите нахуй", "пошел нахуй", "пошла нахуй", "пошли нахуй", "пошел на хуй",
            "иди нахер", "пошел нахер", "иди в жопу", "пошел в жопу", "иди в пизду", "пошел в пизду", "пошел ты",
            "пошла ты", "иди ты", "катись", "отъебись", "отьебись", "съебись", "завали ебало", "завали хлебало",
            "закрой рот", "закрой ебало", "закрой пасть", "ебало завали", "заткнись", "заткнитесь", "сдохни",
            "чтоб ты сдох", "чтоб ты сдохла", "чтобы ты сдох", "убью тебя", "ненавижу", "ты никто", "ебал тебя",
            "ебал твою", "мамку твою", "твою мамку", "мамку ебал", "иди лесом", "отвали", "отстань", "бесишь",
            "ты достал", "ты достала", "ты меня достал", "ты меня достала",
    };

    static final String[] LOVE_PHRASES = {
            "люблю", "лю тебя", "люблб", "обожаю тебя", "обожаю вас", "любимый", "любимая", "любимой", "любимому",
            "любимка", "любимочка", "любовь моя", "моя любовь", "скучаю", "соскучился", "соскучилась", "целую",
            "чмоки", "чмок", "обнимаю", "обнимашки", "love you", "lov u", "ily", "i love",
    };

    static final String[] SAD_PHRASES = {
            "грустно", "грусть", "печально", "печаль", "тоскливо", "одиноко", "плачу", "мне плохо", "мне хуево",
            "мне хреново", "депрессия", "обидно", "не люблю", "разлюбил", "разлюбила", "расстались",
    };

    static final String[] LAUGH_WORDS = {"лол", "ору", "ржу", "ржака", "угар", "орнул", "lol", "lmao", "haha",
            "хах", "хех", "хехе", "бгг"};

    private static final int[] LOVE_CODES = {
            0x2764, 0x2665, 0x1F48B, 0x1F48C, 0x1F493, 0x1F495, 0x1F496, 0x1F497, 0x1F498, 0x1F499,
            0x1F49A, 0x1F49B, 0x1F49C, 0x1F49D, 0x1F49E, 0x1F5A4, 0x1F60D, 0x1F618, 0x1F63B, 0x1F90D,
            0x1F90E, 0x1F970, 0x1F9E1, 0x1FA75, 0x1FA76, 0x1FA77, 0x1FAF6,
    };
    private static final int[] LAUGH_CODES = {0x1F602, 0x1F923, 0x1F606, 0x1F639, 0x1F480};
    private static final int[] SAD_CODES = {0x1F622, 0x1F61E, 0x1F614, 0x2639, 0x1F641, 0x1F494, 0x1F63F, 0x1F625};
    private static final int[] ANGRY_CODES = {0x1F92C, 0x1F595};

    /** Настроение текста. */
    static int of(CharSequence text) {
        if (text == null || text.length() == 0) {
            return NONE;
        }
        String raw = text.length() > 1000 ? text.subSequence(0, 1000).toString() : text.toString();
        List<String> words = words(raw);
        Custom cu = custom;
        if (!cu.ignorePhrases.isEmpty() || !cu.ignoreStems.isEmpty()) {
            words = withoutIgnored(words, cu);
        }
        String joined = " " + String.join(" ", words) + " ";
        if (!cu.empty()) {
            // свои слова важнее встроенных, порядок тот же: гадости, любовь, грусть, смех
            if (cu.match(INSULT, joined, words)) {
                return INSULT;
            }
            if (cu.match(LOVE, joined, words)) {
                return LOVE;
            }
        }
        boolean loveEmoji = hasCode(raw, LOVE_CODES);
        boolean strong = hasCode(raw, ANGRY_CODES) || phrase(joined, RUDE_PHRASES);
        boolean mild = false;
        boolean directed = words.size() <= 3;
        for (String w : words) {
            if (PRONOUNS.contains(w)) {
                directed = true;
            }
        }
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (i > 0 && "не".equals(words.get(i - 1))) {
                continue;
            }
            if (BITCH.contains(w)) {
                // "ты сука", а также "ну ты и сука", "она же сука"
                boolean before = i > 0 && BITCH_BEFORE.contains(words.get(i - 1))
                        || i > 1 && LINKS.contains(words.get(i - 1)) && BITCH_BEFORE.contains(words.get(i - 2));
                boolean after = i + 1 < words.size() && BITCH_AFTER.contains(words.get(i + 1));
                if (before || after) {
                    strong = true;
                }
            } else if (STRONG_WORDS.contains(w) || startsWithAny(w, STRONG_STEMS)) {
                strong = true;
            } else if (MILD_WORDS.contains(w)) {
                mild = true;
            }
        }
        boolean notLove = joined.contains(" не люблю ");
        boolean love = !notLove && (loveEmoji || phrase(joined, LOVE_PHRASES));
        if (strong) {
            return INSULT;
        }
        if (love) {
            return LOVE;
        }
        if (mild && directed) {
            return INSULT;
        }
        if (hasCode(raw, SAD_CODES) || phrase(joined, SAD_PHRASES) || cu.match(SAD, joined, words)) {
            return SAD;
        }
        if (hasCode(raw, LAUGH_CODES) || laughWord(words) || cu.match(LAUGH, joined, words)) {
            return LAUGH;
        }
        return NONE;
    }

    /** Текст без слов и фраз из исключений. */
    private static List<String> withoutIgnored(List<String> words, Custom cu) {
        String j = " " + String.join(" ", words) + " ";
        for (String p : cu.ignorePhrases) {
            String needle = " " + p + " ";
            while (j.contains(needle)) {
                j = j.replace(needle, " ");
            }
        }
        ArrayList<String> out = new ArrayList<>();
        for (String w : j.trim().split(" ")) {
            if (!w.isEmpty() && !startsWithAny(w, cu.ignoreStems.toArray(new String[0]))) {
                out.add(w);
            }
        }
        return out;
    }

    /** Слова в нижнем регистре: ё как е, латиница-двойник внутри русского слова как кириллица, "дееебил" как "дебил". */
    static List<String> words(String s) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        String low = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i <= low.length(); i++) {
            char c = i < low.length() ? low.charAt(i) : ' ';
            if (Character.isLetter(c)) {
                w.append(c);
            } else if (w.length() > 0) {
                out.add(normalize(w.toString()));
                w.setLength(0);
            }
        }
        return out;
    }

    private static final String LAT = "aeopcxykmtb";
    private static final String CYR = "аеорсхукмтв";

    static String normalize(String w) {
        boolean cyr = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c >= 'а' && c <= 'я' || c == 'ё') {
                cyr = true;
                break;
            }
        }
        StringBuilder b = new StringBuilder(w.length());
        char prev = 0;
        int run = 0;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c == 'ё') {
                c = 'е';
            }
            if (cyr) {
                int k = LAT.indexOf(c);
                if (k >= 0) {
                    c = CYR.charAt(k);
                }
            }
            run = c == prev ? run + 1 : 1;
            prev = c;
            // три и больше одинаковых подряд сводим к одной: "дееебил", "люблююю"
            if (run == 3) {
                b.setLength(b.length() - 1);
                continue;
            }
            if (run > 3) {
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    private static boolean startsWithAny(String w, String[] stems) {
        for (String s : stems) {
            if (w.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean phrase(String joined, String[] list) {
        for (String p : list) {
            if (joined.contains(" " + p + " ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean laughWord(List<String> words) {
        for (String w : words) {
            for (String l : LAUGH_WORDS) {
                if (w.equals(l)) {
                    return true;
                }
            }
            // ахаха, хахах, азазаз, пхпх: чередование с "х" или "з"
            if (w.length() >= 4 && isLaugh(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLaugh(String w) {
        int pairs = 0;
        for (int i = 0; i + 1 < w.length(); i++) {
            char a = w.charAt(i);
            char b = w.charAt(i + 1);
            if ((a == 'а' && (b == 'х' || b == 'з')) || ((a == 'х' || a == 'з') && b == 'а')) {
                pairs++;
            } else if (!(a == 'п' && b == 'х') && !(a == 'х' && b == 'п')) {
                return false;
            }
        }
        return pairs >= 3;
    }

    private static boolean hasCode(String s, int[] codes) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            for (int c : codes) {
                if (cp == c) {
                    return true;
                }
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    static String name(int mood) {
        switch (mood) {
            case LOVE: return "любовь";
            case INSULT: return "оскорбление";
            case LAUGH: return "смех";
            case SAD: return "грусть";
            default: return "нет";
        }
    }
}
