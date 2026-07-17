package com.letterforge.listener;

import com.letterforge.manager.LetterManager;
import com.letterforge.manager.SpamGuard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerQuitListener implements Listener {

    private final LetterManager letterManager;
    private final SpamGuard spamGuard;

    public PlayerQuitListener(LetterManager letterManager, SpamGuard spamGuard) {
        this.letterManager = letterManager;
        this.spamGuard = spamGuard;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        letterManager.onPlayerQuit(event.getPlayer().getUniqueId());
        spamGuard.remove(event.getPlayer().getUniqueId());
    }
}
