package com.letterforge.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Phase-1 content filter: skin-tone detection via HSV thresholding.
 * Skin range: H ∈ [0°,50°], S ∈ [0.20,0.85], V ∈ [0.25,1.0].
 * Returns the fraction of opaque pixels matching this range.
 * A configurable threshold (e.g. 0.35) triggers flagging.
 */
public final class ContentFilter {

    private ContentFilter() {}

    public static float skinToneFraction(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int total = 0;
        int skin  = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb  = img.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha < 128) continue;
                total++;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >>  8) & 0xFF;
                int b =  argb        & 0xFF;
                float[] hsv = rgbToHsv(r, g, b);
                if (isSkinTone(hsv[0], hsv[1], hsv[2])) skin++;
            }
        }
        return total == 0 ? 0f : (float) skin / total;
    }

    public static float skinToneFraction(byte[] imageData) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
            return img == null ? 0f : skinToneFraction(img);
        } catch (IOException e) {
            return 0f;
        }
    }

    private static boolean isSkinTone(float h, float s, float v) {
        return h >= 0f && h <= 50f
            && s >= 0.20f && s <= 0.85f
            && v >= 0.25f;
    }

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max   = Math.max(rf, Math.max(gf, bf));
        float min   = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float v = max;
        float s = max == 0 ? 0 : delta / max;
        float h;
        if (delta == 0) {
            h = 0;
        } else if (max == rf) {
            h = 60f * (((gf - bf) / delta) % 6);
        } else if (max == gf) {
            h = 60f * (((bf - rf) / delta) + 2);
        } else {
            h = 60f * (((rf - gf) / delta) + 4);
        }
        if (h < 0) h += 360f;
        return new float[]{ h, s, v };
    }
}
