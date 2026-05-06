package com.nemonicmail.image;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-safe LRU cache for processed map tile arrays.
 * Key: truncated SHA-256 of the image URL (collision-resistant, 16 chars).
 * Value: byte[][] tiles (one 16 KB array per tile).
 * Capacity: 64 entries ≈ 1 MB for 1×1 images.
 */
public class ImageCache {

    private static final int MAX_ENTRIES = 64;

    private final Map<String, byte[][]> store = Collections.synchronizedMap(
        new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75f, true /* access-order for LRU */) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[][]> eldest) {
                return size() > MAX_ENTRIES;
            }
        }
    );

    public Optional<byte[][]> get(String url) {
        return Optional.ofNullable(store.get(key(url)));
    }

    public void put(String url, byte[][] tiles) {
        store.put(key(url), tiles);
    }

    public void invalidate(String url) {
        store.remove(key(url));
    }

    public void clear() {
        store.clear();
    }

    private String key(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(url.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec — never thrown in practice
            return String.valueOf(url.hashCode());
        }
    }
}
