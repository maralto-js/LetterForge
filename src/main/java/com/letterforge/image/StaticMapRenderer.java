package com.letterforge.image;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Renders a pre-computed 128×128 byte array onto a MapView.
 * Non-contextual (contextual=false): same output for every viewer.
 * Draws only once thanks to the rendered flag — the canvas state
 * is preserved by Bukkit between render cycles for non-contextual renderers.
 */
public class StaticMapRenderer extends MapRenderer {

    private static final int MAP_SIZE = 128;

    private final byte[] pixels;
    private volatile boolean rendered = false;

    public StaticMapRenderer(byte[] pixels) {
        super(false);
        this.pixels = pixels;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (rendered) return;
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                canvas.setPixel(x, y, pixels[y * MAP_SIZE + x]);
            }
        }
        rendered = true;
    }
}
