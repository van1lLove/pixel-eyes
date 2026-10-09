package com.pixeleyes;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Вырезка куска картинки с увеличением: java Crop in out x y w h scale. */
public final class Crop {
    public static void main(String[] a) throws Exception {
        BufferedImage in = ImageIO.read(new File(a[0]));
        int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]), w = Integer.parseInt(a[4]), h = Integer.parseInt(a[5]);
        int s = Integer.parseInt(a[6]);
        BufferedImage out = new BufferedImage(w * s, h * s, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(in.getSubimage(x, y, w, h), 0, 0, w * s, h * s, null);
        g.dispose();
        ImageIO.write(out, "png", new File(a[1]));
    }
}
