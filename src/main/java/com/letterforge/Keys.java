package com.letterforge;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

public final class Keys {

    public static NamespacedKey IS_DRAFT;
    public static NamespacedKey RECIPIENT_UUID;
    public static NamespacedKey RECIPIENT_NAME;
    public static NamespacedKey LETTER_TYPE;
    public static NamespacedKey LETTER_PRIORITY;

    public static void init(Plugin plugin) {
        IS_DRAFT       = new NamespacedKey(plugin, "is_draft");
        RECIPIENT_UUID = new NamespacedKey(plugin, "recipient_uuid");
        RECIPIENT_NAME = new NamespacedKey(plugin, "recipient_name");
        LETTER_TYPE    = new NamespacedKey(plugin, "letter_type");
        LETTER_PRIORITY = new NamespacedKey(plugin, "letter_priority");
    }

    private Keys() {}
}
