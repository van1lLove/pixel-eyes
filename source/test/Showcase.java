package com.pixeleyes;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Витрина для README: все глаза сеткой с подписями. java Showcase out.png cols files... */
public final class Showcase {
    public static void main(String[] a) throws Exception {
        int cols = Integer.parseInt(a[1]);
        int n = a.length - 2;
        int cellW = 300;
        int cellH = 230;
        int rows = (n + cols - 1) / cols;
        BufferedImage img = new BufferedImage(cols * cellW, rows * cellH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x17212b));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int i = 0; i < n; i++) {
            String json = new String(Files.readAllBytes(new File(a[i + 2]).toPath()), StandardCharsets.UTF_8);
            Skin s = Skin.parse(json);
            int[] px = RenderSheet.state(s, "", Renderer.EDGE_HALO);
            Renderer r = new Renderer(s);
            BufferedImage cell = new BufferedImage(r.cw, r.ch, BufferedImage.TYPE_INT_ARGB);
            cell.setRGB(0, 0, r.cw, r.ch, px, 0, r.cw);
            int scale = Math.max(2, Math.min(6, Math.min((cellW - 40) / r.cw, 150 / r.ch)));
            int x0 = (i % cols) * cellW;
            int y0 = (i / cols) * cellH;
            g.setColor(new Color(0x1f2c3a));
            g.fillRoundRect(x0 + 8, y0 + 8, cellW - 16, cellH - 16, 24, 24);
            int w = r.cw * scale;
            int h = r.ch * scale;
            g.drawImage(cell, x0 + (cellW - w) / 2, y0 + 20 + (150 - h) / 2, w, h, null);
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Color.WHITE);
            g.drawString(s.name, x0 + (cellW - fm.stringWidth(s.name)) / 2, y0 + cellH - 40);
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            fm = g.getFontMetrics();
            String sub = String.join(", ", s.tags);
            g.setColor(new Color(0x8fa3b8));
            g.drawString(sub, x0 + (cellW - fm.stringWidth(sub)) / 2, y0 + cellH - 20);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
        System.out.println("витрина: " + a[0]);
    }
}
