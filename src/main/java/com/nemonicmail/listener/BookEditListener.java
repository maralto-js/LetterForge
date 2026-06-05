package com.nemonicmail.listener;

import com.nemonicmail.Keys;
import com.nemonicmail.NemonicMail;
import com.nemonicmail.manager.LetterManager;
import com.nemonicmail.manager.SpamGuard;
import com.nemonicmail.model.Letter;
import com.nemonicmail.model.LetterPriority;
import com.nemonicmail.model.LetterType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class BookEditListener implements Listener {

    private final NemonicMail plugin;
    private final LetterManager letterManager;
    private final SpamGuard spamGuard;

    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    public BookEditListener(NemonicMail plugin, LetterManager letterManager, SpamGuard spamGuard) {
        this.plugin = plugin;
        this.letterManager = letterManager;
        this.spamGuard = spamGuard;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBookEdit(PlayerEditBookEvent event) {
        if (!event.isSigning()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!processing.add(uuid)) return;

        try {
            boolean inMainHand = player.getInventory().getItemInMainHand().getType() == Material.WRITABLE_BOOK
                    && player.getInventory().getItemInMainHand().hasItemMeta();
            boolean inOffHand  = player.getInventory().getItemInOffHand().getType() == Material.WRITABLE_BOOK
                    && player.getInventory().getItemInOffHand().hasItemMeta();

            if (!inMainHand && !inOffHand) {
                return;
            }

            ItemStack book = inMainHand
                    ? player.getInventory().getItemInMainHand()
                    : player.getInventory().getItemInOffHand();

            PersistentDataContainer pdc = book.getItemMeta().getPersistentDataContainer();
            if (!pdc.has(Keys.IS_DRAFT, PersistentDataType.BOOLEAN)) {
                return;
            }

            event.setCancelled(true);

            BookMeta newMeta = event.getNewBookMeta();
            List<String> pages = newMeta.pages().stream()
                    .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                    .filter(s -> !s.isBlank())
                    .toList();

            var msgs = plugin.getMessages();

            if (pages.isEmpty()) {
                player.sendMessage(msgs.prefixed("letter.empty"));
                return;
            }

            int maxPages = plugin.getConfig().getInt("limits.max-pages", 6);
            if (pages.size() > maxPages) {
                player.sendMessage(msgs.prefixed("letter.too-many-pages",
                        Map.of("max", String.valueOf(maxPages))));
                return;
            }

            int maxChars = plugin.getConfig().getInt("limits.max-chars-per-page", 256);
            if (pages.stream().anyMatch(p -> p.length() > maxChars)) {
                player.sendMessage(msgs.prefixed("letter.page-too-long",
                        Map.of("max", String.valueOf(maxChars))));
                return;
            }

            String typeStr      = pdc.get(Keys.LETTER_TYPE,     PersistentDataType.STRING);
            String priorityStr  = pdc.get(Keys.LETTER_PRIORITY, PersistentDataType.STRING);
            String recipUUIDStr = pdc.get(Keys.RECIPIENT_UUID,  PersistentDataType.STRING);
            String recipName    = pdc.get(Keys.RECIPIENT_NAME,  PersistentDataType.STRING);

            if (typeStr == null || priorityStr == null || recipName == null) {
                plugin.getLogger().warning("Invalid letter metadata for player " + player.getName());
                player.sendMessage(msgs.prefixed("error.invalid-metadata"));
                return;
            }

            LetterType type;
            LetterPriority priority;
            try {
                type = LetterType.valueOf(typeStr);
                priority = LetterPriority.valueOf(priorityStr);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid enum value: typeStr=" + typeStr + ", priorityStr=" + priorityStr);
                player.sendMessage(msgs.prefixed("error.invalid-metadata"));
                return;
            }

            UUID recipientUUID;
            try {
                recipientUUID = (recipUUIDStr != null && !recipUUIDStr.isEmpty())
                        ? UUID.fromString(recipUUIDStr) : null;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID format: " + recipUUIDStr);
                player.sendMessage(msgs.prefixed("error.invalid-metadata"));
                return;
            }

            String cooldownKey = type == LetterType.ANONYMOUS
                    ? "cooldowns.anonymous-seconds" : "cooldowns.default-seconds";
            long cooldownMs = spamGuard.resolveCooldownMs(player, cooldownKey);
            SpamGuard.CheckResult result = type == LetterType.BROADCAST
                    ? spamGuard.checkBroadcast(player)
                    : spamGuard.check(player, cooldownMs);

            switch (result) {
                case SpamGuard.CheckResult.OnCooldown cd -> {
                    player.sendMessage(msgs.prefixed("cooldown.letter",
                            Map.of("seconds", String.valueOf(cd.secondsLeft()))));
                    return;
                }
                case SpamGuard.CheckResult.RateLimited ignored -> {
                    player.sendMessage(msgs.prefixed("cooldown.rate-limited"));
                    return;
                }
                default -> {}
            }

            long now = System.currentTimeMillis();
            long delay = type == LetterType.ANONYMOUS
                    ? plugin.getConfig().getLong("delivery.anonymous-delay-seconds", 30) * 1000L
                    : plugin.getConfig().getLong("delivery.direct-delay-seconds", 0) * 1000L;

            long ttlDays = type == LetterType.BROADCAST
                    ? plugin.getConfig().getLong("cleanup.delete-broadcast-after-days", 7)
                    : plugin.getConfig().getLong("cleanup.delete-read-after-days", 30);
            long expiresAt = now + TimeUnit.DAYS.toMillis(ttlDays);

            Letter letter = new Letter(
                    UUID.randomUUID(),
                    player.getUniqueId(),
                    player.getName(),
                    recipientUUID,
                    recipName,
                    pages,
                    now,
                    now + delay,
                    false,
                    false,
                    type,
                    priority,
                    expiresAt
            );

            final boolean finalInMainHand = inMainHand;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    ItemStack current = finalInMainHand
                            ? player.getInventory().getItemInMainHand()
                            : player.getInventory().getItemInOffHand();
                    if (current.getType() == Material.WRITABLE_BOOK) {
                        if (finalInMainHand) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                        else                 player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                    }
                }
            });

            if (type == LetterType.BROADCAST) {
                letterManager.sendBroadcast(letter);
                spamGuard.recordBroadcast(player.getUniqueId());
                player.sendMessage(msgs.prefixed("broadcast.sent"));
            } else {
                letterManager.sendLetter(letter);
                spamGuard.recordSend(player.getUniqueId());
                player.sendMessage(msgs.prefixed("letter.sent", Map.of("player", recipName)));

                var mim = plugin.getMapImageManager();
                if (mim != null) {
                    mim.consumePending(player.getUniqueId()).ifPresent(pending -> {
                        int[] mapIds = mim.createMapViews(pending.tiles());
                        letterManager.submitIoTask(() ->
                                mim.saveMapImages(letter.id(), mapIds, pending));
                        player.sendMessage(msgs.prefixed("image.attached"));
                    });
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Letter submission error for player " + player.getName(), e);
            try {
                player.sendMessage(plugin.getMessages().prefixed("error.general"));
            } catch (Exception ignored) {}
        } finally {
            processing.remove(uuid);
        }
    }
}
