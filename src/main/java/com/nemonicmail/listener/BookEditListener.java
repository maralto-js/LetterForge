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

        // Detecta o rascunho ANTES do lock de processamento: assim um lock residual nunca
        // interfere com a assinatura de livros comuns (não-carta).
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

        // É um rascunho de carta — daqui em diante o evento é sempre cancelado (o envio real
        // é assíncrono; o livro só some após o commit confirmado).
        event.setCancelled(true);

        // Lock anti-duplicação: fica retido até o callback do envio concluir (não até o fim
        // deste handler) — re-assinar na janela assíncrona era a brecha de carta duplicada.
        if (!processing.add(uuid)) {
            return;
        }

        boolean dispatched = false;
        try {

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

            // Re-validate sensitive permissions at SIGNING time. The draft's PDC (type/priority)
            // could have been forged in creative mode, so trusting what was written at
            // /carta escrever is not enough (audit P2: PDC forge).
            if (priority == LetterPriority.HIGH && !player.hasPermission("nemonicmail.priority.high")) {
                player.sendMessage(msgs.prefixed("write.no-perm-type"));
                return;
            }
            if (type == LetterType.BROADCAST && !player.hasPermission("nemonicmail.broadcast")) {
                player.sendMessage(msgs.prefixed("broadcast.no-permission"));
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

            // Retenção da carta — INDEPENDENTE das chaves de limpeza de lidas. Conta a partir
            // do envio e vale para lidas e não-lidas. Clamp obrigatório: getLong() devolve 0
            // para valor inválido/não-numérico no YAML, e expires_at = agora+0 faria a carta
            // "sumir" imediatamente (o bug das cartas desaparecendo em horas).
            long ttlDays = type == LetterType.BROADCAST
                    ? plugin.getConfig().getLong("cleanup.broadcast-retention-days", 15)
                    : plugin.getConfig().getLong("cleanup.letter-retention-days", 15);
            if (ttlDays <= 0) {
                plugin.getLogger().warning("[NemonicMail] Retencao invalida na config (" + ttlDays
                        + ") — usando 15 dias.");
                ttlDays = 15;
            }
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

            // IMPORTANT (audit P1): do NOT destroy the draft book up front. The INSERT runs async
            // and can fail (disk full, locked DB). Only remove the book AFTER the row is confirmed
            // committed. On failure the player keeps the draft (and the pending image) and can retry.
            final boolean finalInMainHand = inMainHand;
            final String  finalRecipName  = recipName;
            final LetterType finalType    = type;

            // Consome a imagem pendente JÁ na assinatura (main thread): se ficasse para o
            // callback, uma segunda carta assinada na janela assíncrona "roubaria" a imagem
            // desta, e um logout deixaria a pendência órfã na memória.
            final var mim = plugin.getMapImageManager();
            final var pendingImage = (mim != null && type != LetterType.BROADCAST)
                    ? mim.consumePending(uuid).orElse(null)
                    : null;

            var future = (type == LetterType.BROADCAST)
                    ? letterManager.sendBroadcast(letter)
                    : letterManager.sendLetter(letter);

            dispatched = true; // a partir daqui o lock `processing` é liberado pelo callback

            future.whenComplete((ok, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (err != null || !Boolean.TRUE.equals(ok)) {
                            if (err != null) {
                                plugin.getLogger().log(Level.SEVERE,
                                    "Falha ao enviar carta de " + player.getName(), err);
                            }
                            // Devolve a imagem pendente para o jogador tentar de novo.
                            if (pendingImage != null && mim != null) {
                                mim.storePending(uuid, pendingImage.tiles(),
                                        pendingImage.gridW(), pendingImage.gridH());
                            }
                            if (player.isOnline()) player.sendMessage(msgs.prefixed("error.send-failed"));
                            return;
                        }

                        // Sucesso confirmado. Cooldown vale MESMO se o jogador deslogou —
                        // senão relogar dentro da janela permitiria reenviar o mesmo rascunho.
                        if (finalType == LetterType.BROADCAST) spamGuard.recordBroadcast(uuid);
                        else                                    spamGuard.recordSend(uuid);

                        if (player.isOnline()) {
                            removeDraftBook(player, finalInMainHand);
                            player.sendMessage(msgs.prefixed(
                                    finalType == LetterType.BROADCAST ? "broadcast.sent" : "letter.sent",
                                    Map.of("player", finalRecipName)));
                        }

                        // Anexo de imagem: salvo SEMPRE que a carta foi gravada (o destinatário
                        // precisa dele), independente de o remetente continuar online.
                        if (pendingImage != null && mim != null) {
                            int[] mapIds = mim.createMapViews(pendingImage.tiles()); // main thread
                            letterManager.submitIoTask(() -> {
                                mim.saveMapImages(letter.id(), mapIds, pendingImage);
                                // Cópia de moderação com o remetente REAL (inclui anônimas).
                                mim.saveModerationImage(letter.id(), mapIds, pendingImage,
                                        uuid.toString(), player.getName(),
                                        finalRecipName, finalType.name());
                            });
                            if (player.isOnline()) player.sendMessage(msgs.prefixed("image.attached"));
                        }
                    } finally {
                        processing.remove(uuid);
                    }
                }));
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Letter submission error for player " + player.getName(), e);
            try {
                player.sendMessage(plugin.getMessages().prefixed("error.general"));
            } catch (Exception ignored) {}
        } finally {
            // Envio despachado → o lock é liberado pelo callback do whenComplete;
            // caminhos de validação/erro liberam aqui.
            if (!dispatched) processing.remove(uuid);
        }
    }

    /**
     * Removes the signed draft from the given hand, but only if it is still the draft book.
     * Verifying the IS_DRAFT tag (not just the material) avoids destroying a different writable
     * book the player may have swapped in during the async gap (audit §4: 1-tick swap).
     */
    private void removeDraftBook(Player player, boolean mainHand) {
        var inv = player.getInventory();
        ItemStack current = mainHand ? inv.getItemInMainHand() : inv.getItemInOffHand();
        // Assinar transmuta WRITABLE_BOOK -> WRITTEN_BOOK preservando o PDC; identifica pelo
        // IS_DRAFT, não pelo material (issue #78).
        if (current.hasItemMeta()
                && (current.getType() == Material.WRITABLE_BOOK || current.getType() == Material.WRITTEN_BOOK)
                && current.getItemMeta().getPersistentDataContainer()
                          .has(Keys.IS_DRAFT, PersistentDataType.BOOLEAN)) {
            if (mainHand) inv.setItemInMainHand(new ItemStack(Material.AIR));
            else          inv.setItemInOffHand(new ItemStack(Material.AIR));
            player.updateInventory();
        }
    }
}
