package com.pixeleyes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Отпечатки кадров для сверки с переносом на JS (eyes/repo/assets/eyes.js):
 * одинаковые лица рисуются здесь и в parity.js, суммы должны совпасть.
 *
 * java -cp out com.pixeleyes.JsParity skin1.json skin2.json ...
 */
public final class JsParity {

    static final String[] STATES = {"rest", "look", "half", "closed", "happy", "wide", "narrow", "heart", "spiral",
            "ouch", "angry", "sad", "frame_happy", "frame_closed", "particles", "shake"};

    static void setup(Face f, String st, int n) {
        for (int i = 0; i < n; i++) {
            switch (st) {
                case "look":
                    f.gx[i] = 0.7f;
                    f.gy[i] = -0.45f;
                    break;
                case "half":
                    f.openTop[i] = 0.5f;
                    f.gx[i] = -0.3f;
                    break;
                case "closed":
                    f.openTop[i] = 0f;
                    break;
                case "happy":
                    f.openTop[i] = 0f;
                    f.arcBot[i] = 1f;
                    break;
                case "angry":
                    f.openTop[i] = 0.62f;
                    f.openBot[i] = 0.95f;
                    break;
                case "sad":
                    f.openTop[i] = 0.72f;
                    f.openBot[i] = 0.92f;
                    f.gy[i] = 0.5f;
                    break;
                default:
                    break;
            }
        }
        switch (st) {
            case "wide":
                f.pupil = 1.3f;
                break;
            case "narrow":
                f.pupil = 0.55f;
                break;
            case "heart":
                f.pupilMode = Face.PUPIL_HEART;
                break;
            case "spiral":
                f.pupilMode = Face.PUPIL_SPIRAL;
                f.time = 0.37f;
                break;
            case "ouch":
                f.pupilMode = Face.PUPIL_OUCH;
                break;
            case "angry":
                f.slant = 0.85f;
                f.pupil = 0.75f;
                break;
            case "sad":
                f.slant = -0.75f;
                f.pupil = 1.15f;
                break;
            case "frame_happy":
                f.frame = "happy";
                break;
            case "frame_closed":
                f.frame = "closed";
                break;
            case "shake":
                f.shakeX = 1;
                f.shakeY = -1;
                break;
            case "particles":
                for (int t = 0; t < 8; t++) {
                    Face.Particle p = new Face.Particle();
                    p.type = t;
                    p.x = 4 + t * 3.3f;
                    p.y = 3.6f;
                    p.age = t * 0.1f;
                    p.life = 1.2f;
                    f.particles.add(p);
                }
                break;
            default:
                break;
        }
    }

    static long hash(int[] px) {
        long h = 1469598103934665603L;
        for (int c : px) {
            h = (h ^ (c & 0xFFFFFFFFL)) * 1099511628211L;
        }
        return h;
    }

    public static void main(String[] args) throws Exception {
        for (String file : args) {
            String json = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            Skin s = Skin.parse(json);
            Renderer r = new Renderer(s);
            String name = Paths.get(file).getFileName().toString();
            for (String st : STATES) {
                for (int edge = 0; edge <= 2; edge++) {
                    Face f = new Face(s.places.size());
                    setup(f, st, s.places.size());
                    r.edge = edge;
                    int[] out = r.render(f);
                    System.out.println(name + " " + st + " " + edge + " " + r.cw + "x" + r.ch + " "
                            + Long.toUnsignedString(hash(out), 16));
                }
            }
        }
    }
}
