package com.pixeleyes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Маленький разборщик JSON без зависимостей: на телефоне есть org.json, а на ПК,
 * где гоняются проверки, его нет. Объекты разбираются в LinkedHashMap (порядок
 * ключей сохраняется), массивы в ArrayList, числа в Double, null в null.
 */
final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("пусто");
        }
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) {
            throw p.err("лишние символы после JSON");
        }
        return v;
    }

    private IllegalArgumentException err(String what) {
        int line = 1;
        int col = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) {
            if (s.charAt(k) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new IllegalArgumentException(what + " (строка " + line + ", символ " + col + ")");
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') {
                i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                // комментарии // до конца строки: в самодельных глазах их удобно оставлять
                while (i < s.length() && s.charAt(i) != '\n') {
                    i++;
                }
            } else {
                break;
            }
        }
    }

    private Object value(int depth) {
        if (depth > 32) {
            throw err("слишком глубокая вложенность");
        }
        if (i >= s.length()) {
            throw err("JSON оборвался");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                word("true");
                return Boolean.TRUE;
            case 'f':
                word("false");
                return Boolean.FALSE;
            case 'n':
                word("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw err("непонятный символ '" + c + "'");
        }
    }

    private void word(String w) {
        if (!s.startsWith(w, i)) {
            throw err("ожидалось " + w);
        }
        i += w.length();
    }

    private Map<String, Object> object(int depth) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw err("ожидался ключ в кавычках");
            }
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') {
                throw err("ожидалось ':'");
            }
            i++;
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (i >= s.length()) {
                throw err("объект не закрыт");
            }
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("ожидалась ',' или '}'");
            }
            ws();
            // запятая перед закрывающей скобкой: прощаем
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
        }
    }

    private List<Object> array(int depth) {
        ArrayList<Object> a = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            if (i >= s.length()) {
                throw err("массив не закрыт");
            }
            char c = s.charAt(i++);
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw err("ожидалась ',' или ']'");
            }
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return a;
            }
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) {
                throw err("строка не закрыта");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw err("строка не закрыта");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw err("плохой \\u");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw err("плохой \\u");
                    }
                    i += 4;
                    break;
                default:
                    throw err("плохая экранировка \\" + e);
            }
        }
    }

    private Double number() {
        int start = i;
        if (s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                i++;
            } else {
                break;
            }
        }
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException ex) {
            throw err("плохое число");
        }
    }

    // ---- запись ---------------------------------------------------------------

    static String quote(String v) {
        StringBuilder b = new StringBuilder(v.length() + 2);
        b.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
        return b.toString();
    }

    /** Запись в JSON. Массивы строк (сетки пикселей) идут по строке на элемент. */
    static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, 0);
        return b.toString();
    }

    private static void indent(StringBuilder b, int n) {
        for (int k = 0; k < n; k++) {
            b.append("  ");
        }
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object o, int level) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String) {
            b.append(quote((String) o));
        } else if (o instanceof Boolean) {
            b.append(o.toString());
        } else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                b.append((long) d);
            } else {
                b.append(d);
            }
        } else if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int k = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                indent(b, level + 1);
                b.append(quote(e.getKey())).append(": ");
                write(b, e.getValue(), level + 1);
                if (++k < m.size()) {
                    b.append(',');
                }
                b.append('\n');
            }
            indent(b, level);
            b.append('}');
        } else if (o instanceof List) {
            List<Object> a = (List<Object>) o;
            if (a.isEmpty()) {
                b.append("[]");
                return;
            }
            boolean simple = true;
            boolean strings = true;
            for (Object x : a) {
                if (x instanceof Map || x instanceof List) {
                    simple = false;
                }
                if (!(x instanceof String)) {
                    strings = false;
                }
            }
            if (simple && !strings) {
                b.append('[');
                for (int k = 0; k < a.size(); k++) {
                    if (k > 0) {
                        b.append(", ");
                    }
                    write(b, a.get(k), level + 1);
                }
                b.append(']');
                return;
            }
            b.append("[\n");
            for (int k = 0; k < a.size(); k++) {
                indent(b, level + 1);
                write(b, a.get(k), level + 1);
                if (k + 1 < a.size()) {
                    b.append(',');
                }
                b.append('\n');
            }
            indent(b, level);
            b.append(']');
        } else {
            b.append(quote(o.toString()));
        }
    }
}
