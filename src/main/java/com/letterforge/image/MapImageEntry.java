package com.letterforge.image;

import java.util.UUID;

/**
 * Represents one map tile attached to a letter, as stored in letter_map_images.
 * A single-map attachment has one entry (tileIndex=0, gridW=1, gridH=1).
 * A 2×2 grid has four entries (tileIndex 0-3, gridW=2, gridH=2).
 */
public record MapImageEntry(
    UUID   letterId,
    int    tileIndex,  // 0-based, left→right top→bottom
    int    mapId,      // Bukkit MapView ID
    byte[] pixels,     // 128×128 = 16 384 bytes
    int    gridW,
    int    gridH
) {}
