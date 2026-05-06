package com.nemonicmail.manager;

import com.nemonicmail.NemonicMail;
import com.nemonicmail.model.AuditEntry;
import com.nemonicmail.model.Letter;
import com.nemonicmail.model.LetterPriority;
import com.nemonicmail.model.LetterType;
import com.nemonicmail.storage.StorageProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class LetterManager {

    private final NemonicMail plugin;
    private final StorageProvider storage;
    private final ExecutorService ioExecutor;
    private final ConcurrentHashMap<UUID, List<Letter>> inboxCache = new ConcurrentHashMap<>();

    public LetterManager(NemonicMail plugin, StorageProvider storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nemonicmail-db");
            t.setDaemon(true);
            return t;
        });
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Scheduler de entrega ---

    public void startDeliveryScheduler() {
        long intervalSecs = plugin.getConfig().getLong("delivery.check-interval-seconds", 15);
        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                task -> CompletableFuture.runAsync(this::deliverPendingLetters, ioExecutor),
                intervalSecs, intervalSecs, TimeUnit.SECONDS);
    }

    private void deliverPendingLetters() {
        long now = System.currentTimeMillis();
        List<Letter> ready = storage.getPendingLettersReadyToDeliver(now);
        if (ready.isEmpty()) return;

        for (Letter letter : ready) {
            if (letter.recipientUUID() == null) continue;
            if (!storage.tryMarkDelivered(letter.id())) continue;

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player recipient = Bukkit.getPlayer(letter.recipientUUID());
                if (recipient == null || !recipient.isOnline()) return;

                Letter delivered = letter.withDelivered(true);
                inboxCache.computeIfAbsent(letter.recipientUUID(),
                        k -> new CopyOnWriteArrayList<>()).add(0, delivered);
                notifyNewLetter(recipient, letter);
            });
        }
    }

    // --- Ciclo de vida do jogador ---

    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        CompletableFuture.runAsync(() -> {
            long now = System.currentTimeMillis();

            List<Letter> pending = storage.getPendingDirectLetters(uuid, now);
            for (Letter l : pending) storage.tryMarkDelivered(l.id());

            List<Letter> broadcasts = storage.getPendingBroadcasts(uuid);
            List<Letter> inbox = new ArrayList<>(storage.getInbox(uuid));
            for (Letter bc : broadcasts) {
                if (inbox.stream().noneMatch(l -> l.id().equals(bc.id()))) inbox.add(0, bc);
            }
            inboxCache.put(uuid, new CopyOnWriteArrayList<>(inbox));

            long unreadDirect = inbox.stream().filter(l -> !l.read() && l.type() != LetterType.BROADCAST).count();
            long total = unreadDirect + broadcasts.size();
            boolean hasUrgent = inbox.stream().anyMatch(l -> !l.read() && l.priority() != LetterPriority.NORMAL);

            if (total > 0) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) notifyOnJoin(player, (int) total, hasUrgent);
                });
            }
        }, ioExecutor);
    }

    public void onPlayerQuit(UUID uuid) {
        inboxCache.remove(uuid);
    }

    // --- Envio ---

    public void sendLetter(Letter letter) {
        CompletableFuture.runAsync(() -> {
            storage.insertLetter(letter);
            if (letter.deliverAt() <= System.currentTimeMillis()) {
                if (!storage.tryMarkDelivered(letter.id())) return;
                Player recipient = Bukkit.getPlayer(letter.recipientUUID());
                if (recipient != null) {
                    Letter delivered = letter.withDelivered(true);
                    inboxCache.computeIfAbsent(letter.recipientUUID(),
                            k -> new CopyOnWriteArrayList<>()).add(0, delivered);
                    Bukkit.getScheduler().runTask(plugin, () -> notifyNewLetter(recipient, letter));
                }
            }
        }, ioExecutor);
    }

    public void sendBroadcast(Letter letter) {
        CompletableFuture.runAsync(() -> {
            storage.insertLetter(letter);
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    inboxCache.computeIfAbsent(p.getUniqueId(),
                            k -> new CopyOnWriteArrayList<>()).add(0, letter);
                    notifyNewLetter(p, letter);
                }
            });
        }, ioExecutor);
    }

    // --- Cache access ---

    public List<Letter> getInbox(UUID playerUUID) {
        return inboxCache.getOrDefault(playerUUID, Collections.emptyList());
    }

    public int getUnreadCount(UUID playerUUID) {
        List<Letter> cached = inboxCache.get(playerUUID);
        if (cached != null) return (int) cached.stream().filter(l -> !l.read()).count();
        return storage.countUnread(playerUUID) + storage.countUnreadBroadcasts(playerUUID);
    }

    public void markRead(UUID playerUUID, UUID letterId, LetterType type) {
        List<Letter> cache = inboxCache.get(playerUUID);
        if (cache != null) cache.replaceAll(l -> l.id().equals(letterId) ? l.withRead(true) : l);
        CompletableFuture.runAsync(() -> {
            if (type == LetterType.BROADCAST) storage.markBroadcastSeen(letterId, playerUUID);
            else storage.markRead(letterId);
        }, ioExecutor);
    }

    public void runCleanup() {
        long now = System.currentTimeMillis();
        long directTTL    = TimeUnit.DAYS.toMillis(plugin.getConfig().getLong("cleanup.delete-read-after-days", 30));
        long broadcastTTL = TimeUnit.DAYS.toMillis(plugin.getConfig().getLong("cleanup.delete-broadcast-after-days", 7));
        CompletableFuture.runAsync(() -> storage.cleanup(now - directTTL, now - broadcastTTL), ioExecutor);
    }

    public void submitIoTask(Runnable task) {
        CompletableFuture.runAsync(task, ioExecutor);
    }

    // --- Admin ---

    public CompletableFuture<Optional<Letter>> getLetter(UUID letterId) {
        return CompletableFuture.supplyAsync(() -> storage.getLetterById(letterId), ioExecutor);
    }

    public CompletableFuture<List<Letter>> getPlayerLettersAdmin(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> storage.getInboxAdmin(playerUuid), ioExecutor);
    }

    public CompletableFuture<Void> revokeLetter(UUID letterId, String adminUuid, String adminName) {
        return CompletableFuture.runAsync(() -> {
            storage.revokeLetterById(letterId);
            storage.logAudit("LETTER_REVOKED", adminUuid, adminName, letterId.toString(), null);
            // Evict from all online caches
            inboxCache.forEach((uuid, letters) ->
                letters.removeIf(l -> l.id().equals(letterId)));
        }, ioExecutor);
    }

    public CompletableFuture<List<AuditEntry>> getRecentAuditLog(int limit) {
        return CompletableFuture.supplyAsync(() -> storage.getRecentAuditLog(limit), ioExecutor);
    }

    // --- Notificações ---

    private void notifyOnJoin(Player player, int total, boolean hasUrgent) {
        var msgs = plugin.getMessages();
        if (hasUrgent) {
            player.showTitle(Title.title(
                    msgs.component("notify.join-title-urgent"),
                    msgs.component("notify.join-subtitle", Map.of("count", String.valueOf(total))),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))
            ));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.8f);
        } else {
            player.sendActionBar(msgs.component("notify.join-actionbar",
                    Map.of("count", String.valueOf(total))));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
        }

        player.sendMessage(msgs.prefix().append(
                msgs.component("notify.join-chat", Map.of("count", String.valueOf(total)))
                        .append(Component.text(" "))
                        .append(Component.text("[Abrir]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.runCommand("/carta caixa"))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Clique para abrir sua caixa postal"))))));
    }

    private void notifyNewLetter(Player player, Letter letter) {
        var msgs = plugin.getMessages();
        String tag = switch (letter.type()) {
            case URGENT    -> msgs.raw("tags.urgent");
            case OFFICIAL  -> msgs.raw("tags.official");
            case BROADCAST -> msgs.raw("tags.broadcast");
            case ANONYMOUS -> msgs.raw("tags.anonymous");
            default        -> "";
        };
        NamedTextColor tagColor = switch (letter.type()) {
            case URGENT    -> NamedTextColor.RED;
            case OFFICIAL  -> NamedTextColor.GOLD;
            case BROADCAST -> NamedTextColor.YELLOW;
            case ANONYMOUS -> NamedTextColor.GRAY;
            default        -> NamedTextColor.GOLD;
        };
        NamedTextColor barColor = letter.priority() != LetterPriority.NORMAL
                ? NamedTextColor.RED : NamedTextColor.GOLD;

        String sender = letter.displaySender();
        player.sendActionBar(msgs.component("notify.new-actionbar",
                Map.of("tag", tag, "sender", sender)).color(barColor));

        player.sendMessage(msgs.prefix()
                .append(Component.text(tag, tagColor))
                .append(msgs.component("notify.new-chat", Map.of("sender", sender)))
                .append(Component.text(" "))
                .append(Component.text("[Ver]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.runCommand("/carta caixa"))
                        .hoverEvent(HoverEvent.showText(Component.text("Abrir caixa postal")))));

        Sound sound = letter.priority() != LetterPriority.NORMAL
                ? Sound.BLOCK_NOTE_BLOCK_BELL : Sound.ENTITY_VILLAGER_AMBIENT;
        player.playSound(player.getLocation(), sound, 0.4f, 1.5f);
    }
}
