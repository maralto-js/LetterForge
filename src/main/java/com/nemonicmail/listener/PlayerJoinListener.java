package com.nemonicmail.listener;

import com.nemonicmail.manager.LetterManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final LetterManager letterManager;

    public PlayerJoinListener(LetterManager letterManager) {
        this.letterManager = letterManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        letterManager.onPlayerJoin(player);
    }
}
