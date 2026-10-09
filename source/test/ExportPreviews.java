package com.pixeleyes;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Картинки для каталога: глаза в покое, прозрачный фон, светлый контур. java ExportPreviews dir scale files... */
public final class ExportPreviews {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        dir.mkdirs();
        int scale = Integer.parseInt(a[1]);
        for (int i = 2; i < a.length; i++) {
            String json = new String(Files.readAllBytes(new File(a[i]).toPath()), StandardCharsets.UTF_8);
            Skin s = Skin.parse(json);
            int[] px = RenderSheet.state(s, "", Renderer.EDGE_HALO);
            Renderer r = new Renderer(s);
            BufferedImage cell = new BufferedImage(r.cw, r.ch, BufferedImage.TYPE_INT_ARGB);
            cell.setRGB(0, 0, r.cw, r.ch, px, 0, r.cw);
            BufferedImage big = new BufferedImage(r.cw * scale, r.ch * scale, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = big.createGraphics();
            g.drawImage(cell, 0, 0, r.cw * scale, r.ch * scale, null);
            g.dispose();
            ImageIO.write(big, "png", new File(dir, s.id + ".png"));
        }
        System.out.println("картинок: " + (a.length - 2));
    }
}
