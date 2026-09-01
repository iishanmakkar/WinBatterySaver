package com.batterysaver.util;

import javafx.scene.image.Image;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

public class IconUtil {

    public static BufferedImage createAwtBatteryIcon(int size, int pct, boolean charging) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Clear transparent
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, size, size);
        g.setComposite(AlphaComposite.SrcOver);

        int margin = Math.max(1, size / 8);
        int w = size - margin * 3;
        int h = size - margin * 4;
        int x = margin;
        int y = margin * 2;

        // Battery body outline
        Color outline = new Color(0x71, 0x71, 0x7a);
        g.setColor(outline);
        g.setStroke(new BasicStroke(Math.max(1.5f, size / 16.0f)));
        g.drawRoundRect(x, y, w, h, size / 6, size / 6);

        // Battery positive terminal / nub
        g.fillRect(x + w + 1, y + h / 4, Math.max(2, margin), h / 2);

        // Fill based on percentage
        if (pct >= 0) {
            int fillW = (int) Math.round((w - 3) * (pct / 100.0));
            Color fill;
            if (charging) fill = new Color(0x22, 0xc5, 0x5e); // Green
            else if (pct < 20) fill = new Color(0xef, 0x44, 0x44); // Red
            else if (pct < 50) fill = new Color(0xf5, 0x9e, 0x0b); // Amber
            else fill = new Color(0x22, 0xc5, 0x5e); // Green

            g.setColor(fill);
            g.fillRoundRect(x + 2, y + 2, Math.max(1, fillW), h - 3, size / 8, size / 8);

            // Charging bolt
            if (charging && pct < 98) {
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, Math.max(8, size / 2)));
                g.drawString("⚡", x + w / 4, y + h - 1);
            }
        } else {
            g.setColor(new Color(0xa1, 0xa1, 0xaa));
            g.fillRect(x + 2, y + 2, w - 3, h - 3);
        }

        g.dispose();
        return img;
    }

    public static Image createFxBatteryIcon(int size) {
        try {
            BufferedImage img = createAwtBatteryIcon(size, 80, false);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return new Image(new ByteArrayInputStream(baos.toByteArray()));
        } catch (Exception e) {
            return null;
        }
    }
}
