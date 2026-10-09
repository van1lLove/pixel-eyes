package com.pixeleyes;

/** Настройки из Python строкой "ключ=значение;...". Неизвестные ключи пропускаются. */
final class Config {

    static final int WHERE_ALL = 0;
    static final int WHERE_CHATS = 1;
    static final int WHERE_TYPING = 2;

    static final int EDGE_AUTO = 0;

    volatile boolean enabled = true;
    volatile float pixelDp = 3f;
    volatile float alpha = 1f;
    volatile float posX = 0.82f;
    volatile float posY = 0.22f;
    volatile int where = WHERE_ALL;
    volatile boolean followTouch = true;
    volatile boolean followTyping = true;
    volatile boolean stare = true;
    volatile boolean messages = true;
    volatile boolean send = true;
    volatile boolean shake = true;
    volatile boolean clip = true;
    volatile boolean sleep = true;
    volatile float sleepSec = 90f;
    volatile boolean night = true;
    volatile boolean battery = true;
    volatile boolean peer = true;
    volatile boolean screen = true;
    volatile boolean love = true;
    volatile boolean text = true;
    volatile boolean interactive = true;
    volatile int edge = EDGE_AUTO;      // 0 авто, 1 тень, 2 светлый контур, 3 без обводки
    volatile boolean debug;

    void apply(String s) {
        if (s == null) {
            return;
        }
        for (String pair : s.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = pair.substring(0, eq).trim();
            String v = pair.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "enabled": enabled = bool(v); break;
                    case "px": pixelDp = clamp(num(v), 1f, 12f); break;
                    case "alpha": alpha = clamp(num(v), 10f, 100f) / 100f; break;
                    case "posx": posX = clamp(num(v), 0f, 1f); break;
                    case "posy": posY = clamp(num(v), 0f, 1f); break;
                    case "where": where = (int) clamp(num(v), 0f, 2f); break;
                    case "touch": followTouch = bool(v); break;
                    case "typing": followTyping = bool(v); break;
                    case "stare": stare = bool(v); break;
                    case "messages": messages = bool(v); break;
                    case "send": send = bool(v); break;
                    case "shake": shake = bool(v); break;
                    case "clip": clip = bool(v); break;
                    case "sleep": sleep = bool(v); break;
                    case "sleepsec": sleepSec = clamp(num(v), 10f, 3600f); break;
                    case "night": night = bool(v); break;
                    case "battery": battery = bool(v); break;
                    case "peer": peer = bool(v); break;
                    case "screen": screen = bool(v); break;
                    case "love": love = bool(v); break;
                    case "text": text = bool(v); break;
                    case "poke": interactive = bool(v); break;
                    case "edge": edge = (int) clamp(num(v), 0f, 3f); break;
                    case "debug": debug = bool(v); break;
                    default: break;
                }
            } catch (Throwable ignored) {
                // плохое значение оставляет прежнее
            }
        }
    }

    private static float num(String v) {
        return Float.parseFloat(v.replace(',', '.'));
    }

    private static boolean bool(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
