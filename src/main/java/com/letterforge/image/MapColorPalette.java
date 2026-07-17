package com.letterforge.image;

import org.bukkit.map.MapPalette;

/**
 * Pre-computed RGB565 LUT for O(1) Minecraft map color matching.
 * Uses MapPalette.matchColor() only at class-load time (65 536 calls ≈ <50ms),
 * never in the per-pixel hot path. PAL_RGB is derived as the centroid of all
 * RGB565 inputs that map to each palette index — accurate enough for
 * Floyd-Steinberg error diffusion.
 */
public final class MapColorPalette {

    // RGB565: R5 bits 15-11, G6 bits 10-5, B5 bits 4-0 → 65 536 entries, 64 KB
    private static final byte[] LUT = new byte[1 << 16];
    // Representative RGB per palette index (centroid). -1 = unused index.
    static final int[] PAL_RGB = new int[256];

    static {
        buildLut();
        buildPaletteFromCentroids();
    }

    /** O(1) color matching — no deprecated API in hot path. */
    public static byte match(int r, int g, int b) {
        return LUT[((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)];
    }

    /**
     * Returns [r, g, b] of palette entry for Floyd-Steinberg error diffusion.
     * Based on centroid of all quantized inputs that map to this index.
     */
    public static int[] getRGB(int palIdx) {
        if (palIdx < 0 || palIdx >= PAL_RGB.length || PAL_RGB[palIdx] < 0) {
            return new int[]{0, 0, 0};
        }
        int rgb = PAL_RGB[palIdx];
        return new int[]{ (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF };
    }

    @SuppressWarnings("removal")
    private static void buildLut() {
        for (int r5 = 0; r5 < 32; r5++) {
            for (int g6 = 0; g6 < 64; g6++) {
                for (int b5 = 0; b5 < 32; b5++) {
                    int r = (r5 << 3) | (r5 >> 2);
                    int g = (g6 << 2) | (g6 >> 4);
                    int b = (b5 << 3) | (b5 >> 2);
                    LUT[(r5 << 11) | (g6 << 5) | b5] = MapPalette.matchColor(r, g, b);
                }
            }
        }
    }

    private static void buildPaletteFromCentroids() {
        long[] sumR = new long[256], sumG = new long[256], sumB = new long[256];
        int[]  count = new int[256];

        for (int r5 = 0; r5 < 32; r5++) {
            for (int g6 = 0; g6 < 64; g6++) {
                for (int b5 = 0; b5 < 32; b5++) {
                    int idx = LUT[(r5 << 11) | (g6 << 5) | b5] & 0xFF;
                    int r = (r5 << 3) | (r5 >> 2);
                    int g = (g6 << 2) | (g6 >> 4);
                    int b = (b5 << 3) | (b5 >> 2);
                    sumR[idx] += r; sumG[idx] += g; sumB[idx] += b;
                    count[idx]++;
                }
            }
        }

        for (int i = 0; i < 256; i++) {
            PAL_RGB[i] = count[i] > 0
                ? ((int)(sumR[i] / count[i]) << 16)
                  | ((int)(sumG[i] / count[i]) << 8)
                  |  (int)(sumB[i] / count[i])
                : -1;
        }
    }

    private MapColorPalette() {}
}
