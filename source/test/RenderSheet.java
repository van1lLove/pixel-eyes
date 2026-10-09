package com.pixeleyes;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Лист состояний для глаз: каждая строка это один набор, по столбцам взгляды и
 * эмоции, всё настоящим кодом ядра (Brain + Renderer). Чтобы смотреть рисунок
 * глазами на ПК и делать картинки для каталога.
 *
 * java -cp out com.pixeleyes.RenderSheet out.png scale skin1.json skin2.json ...
 */
public final class RenderSheet {

    static String[][] STATES = {
            {"взгляд", ""}, {"влево", "look:-1:0"}, {"вправо", "look:1:0"}, {"вверх", "look:0:-1"},
            {"вниз", "look:0.7:0.8"}, {"полуморг", "blink"}, {"спит", "emo:asleep"}, {"радость", "emo:happy"},
            {"любовь", "emo:love"}, {"удивление", "emo:surprised"}, {"злость", "emo:angry"},
            {"грусть", "emo:sad"}, {"кружится", "emo:dizzy"}, {"ай", "emo:ouch"}, {"подмиг", "emo:wink"},
            {"на тебя", "emo:stare"}, {"хмм", "emo:suspicious"},
    };

    static final int WRAP = 9;

    public static void main(String[] a) throws Exception {
        String only = System.getProperty("states");
        if (only != null) {
            java.util.ArrayList<String[]> keep = new java.util.ArrayList<>();
            for (String[] st : STATES) {
                for (String w : only.split(",")) {
                    if (st[0].equals(w) || st[1].equals(w)) {
                        keep.add(st);
                    }
                }
            }
            STATES = keep.toArray(new String[0][]);
        }
        String out = a[0];
        int scale = Integer.parseInt(a[1]);
        List<Skin> skins = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 2; i < a.length; i++) {
            String json = new String(Files.readAllBytes(new File(a[i]).toPath()), StandardCharsets.UTF_8);
            try {
                skins.add(Skin.parse(json));
                names.add(new File(a[i]).getName());
            } catch (IllegalArgumentException e) {
                System.out.println(a[i] + ": " + e.getMessage());
                throw e;
            }
        }
        int cellW = 0;
        int cellH = 0;
        for (Skin s : skins) {
            Renderer r = new Renderer(s);
            cellW = Math.max(cellW, r.cw * scale);
            cellH = Math.max(cellH, r.ch * scale);
        }
        int labelH = 16;
        int colW = Math.max(cellW, 70) + 8;
        int rowH = cellH + labelH + 10;
        int nameW = 130;
        int perLine = Math.min(WRAP, STATES.length + 1);
        int lines = (STATES.length + 1 + perLine - 1) / perLine;
        int skinH = rowH * lines + 6;
        BufferedImage img = new BufferedImage(nameW + colW * perLine, skinH * skins.size() + 4,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x26, 0x28, 0x2e));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (int row = 0; row < skins.size(); row++) {
            Skin s = skins.get(row);
            int base = row * skinH + 4;
            g.setColor(Color.WHITE);
            g.drawString(s.name, 6, base + 16);
            g.setColor(new Color(0xa0a0a0));
            g.drawString(names.get(row), 6, base + 32);
            for (int k = 0; k <= STATES.length; k++) {
                int col = k % perLine;
                int y0 = base + (k / perLine) * rowH;
                boolean light = k == STATES.length;
                String[] st = light ? STATES[0] : STATES[k];
                int[] px = state(s, st[1], light ? Renderer.EDGE_SHADOW : Renderer.EDGE_HALO);
                Renderer r = new Renderer(s);
                int x0 = nameW + col * colW;
                g.setColor(light ? new Color(0xf2, 0xf2, 0xf5) : new Color(0x17, 0x21, 0x2b));
                g.fillRect(x0, y0, colW - 6, cellH + 4);
                BufferedImage cell = new BufferedImage(r.cw, r.ch, BufferedImage.TYPE_INT_ARGB);
                cell.setRGB(0, 0, r.cw, r.ch, px, 0, r.cw);
                int dx = x0 + (colW - 6 - r.cw * scale) / 2;
                int dy = y0 + 2 + (cellH - r.ch * scale) / 2;
                g.drawImage(cell, dx, dy, r.cw * scale, r.ch * scale, null);
                g.setColor(new Color(0xc8c8c8));
                g.drawString(light ? "светлый фон" : st[0], x0 + 2, y0 + cellH + labelH);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("лист: " + out + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    /** Кадр в нужном состоянии: прогоняем мозг секунду, чтобы всё доехало. */
    static int[] state(Skin s, String what, int edge) {
        Renderer r = new Renderer(s);
        r.edge = edge;
        Brain b = new Brain(s, r, 7);
        b.sleepOn = false;
        b.blinkOn = false;
        int n = s.places.size();
        float unit = 10f;
        float[] cx = new float[n];
        float[] cy = new float[n];
        for (int i = 0; i < n; i++) {
            cx[i] = r.eyeCenterX(i) * unit;
            cy[i] = r.eyeCenterY(i) * unit;
        }
        b.setGeometry(cx, cy, unit, 300f);
        float t = 1f;
        b.update(t);
        float faceX = r.cw * unit / 2f;
        float faceY = r.ch * unit / 2f;
        if (what.startsWith("look:")) {
            String[] p = what.split(":");
            float lx = Float.parseFloat(p[1]);
            float ly = Float.parseFloat(p[2]);
            b.touch(0, faceX + lx * 3000f, faceY + ly * 3000f);
        } else if (what.startsWith("emo:")) {
            b.event(what, 0, 0);
        }
        int steps = what.equals("emo:asleep") ? 200 : 60;
        for (int k = 0; k < steps; k++) {
            t += 0.016f;
            b.update(t);
        }
        if (what.equals("blink")) {
            for (int i = 0; i < n; i++) {
                b.face.openTop[i] = 0.45f;
            }
        }
        return r.render(b.face).clone();
    }
}
