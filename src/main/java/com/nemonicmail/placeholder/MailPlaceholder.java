package com.nemonicmail.placeholder;

import com.nemonicmail.manager.LetterManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MailPlaceholder extends PlaceholderExpansion {

    private final LetterManager letterManager;

    public MailPlaceholder(LetterManager letterManager) {
        this.letterManager = letterManager;
    }

    @Override
    public @NotNull String getIdentifier() { return "nemonicmail"; }

    @Override
    public @NotNull String getAuthor() { return "NemonicRP"; }

    @Override
    public @NotNull String getVersion() { return "1.0.0"; }

    @Override
    public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "0";
        // getUnreadCountFast() nunca consulta o banco na main thread (PlaceholderAPI
        // resolve placeholders no thread do servidor em scoreboard/chat/actionbar).
        return switch (params) {
            case "unread"       -> String.valueOf(letterManager.getUnreadCountFast(player.getUniqueId()));
            case "unread_color" -> {
                int count = letterManager.getUnreadCountFast(player.getUniqueId());
                yield count > 0 ? "§e" + count : "§70";
            }
            default -> null;
        };
    }
}
