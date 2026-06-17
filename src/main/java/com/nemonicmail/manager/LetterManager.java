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

    private record CachedInbox(List<Letter> letters, long expiresAt) {}

    private final NemonicMail plugin;
    private final StorageProvider storage;
    private final ExecutorService ioExecutor;
    private final Map<UUID, CachedInbox> inboxCache;
    /** UUIDs com aquecimento de cache em andamento — evita disparar várias cargas para o mesmo jogador. */
    private final Set<UUID> warming = ConcurrentHashMap.newKeySet();
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutos

    public LetterManager(NemonicMail plugin, StorageProvider storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nemonicmail-db");
            t.setDaemon(true);
            return t;
        });
        // LinkedHashMap com LRU eviction
        this.inboxCache = Collections.synchronizedMap(
            new LinkedHashMap<UUID, CachedInbox>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CachedInbox> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            }
        );
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Cache helpers ---

    private List<Letter> getCachedLetters(UUID uuid, long now) {
        CachedInbox cached = inboxCache.get(uuid);
        if (cached != null && cached.expiresAt() > now) {
            return cached.letters();
        }
        return new CopyOnWriteArrayList<>();
    }

    private void setCachedLetters(UUID uuid, List<Letter> letters) {
        long now = System.currentTimeMillis();
        inboxCache.put(uuid, new CachedInbox(
            new CopyOnWriteArrayList<>(letters),
            now + CACHE_TTL_MS
        ));
    }

    /**
     * Prepends a letter to the player's cached inbox WITHOUT discarding what's already there.
     * On a cache miss/expired entry it does NOT create a single-letter inbox (that would mask
     * every older letter for the next 5 minutes — the "cartas sumiram" bug); it just schedules
     * an async warm-up, which reloads the full inbox from the DB (the new letter is already
     * inserted at this point, so it is included).
     */
    private void addToCache(UUID uuid, Letter letter) {
        long now = System.currentTimeMillis();
        CachedInbox cached = inboxCache.get(uuid);
        if (cached != null && cached.expiresAt() > now) {
            inboxCache.compute(uuid, (k, c) -> {
                if (c == null || c.expiresAt() <= now) return c; // expirou no meio — deixa o warm cuidar
                List<Letter> letters = new CopyOnWriteArrayList<>(c.letters());
                letters.add(0, letter);
                return new CachedInbox(letters, now + CACHE_TTL_MS);
            });
        } else {
            warmInboxAsync(uuid);
        }
    }

    /**
     * Carrega o inbox direto do BANCO (inbox + broadcasts pendentes), atualiza o cache e
     * devolve a lista. Usado pelo /carta caixa — a GUI nunca mais depende do TTL do cache.
     */
    public CompletableFuture<List<Letter>> getInboxAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<Letter> broadcasts = storage.getPendingBroadcasts(uuid);
            List<Letter> inbox = new ArrayList<>(storage.getInbox(uuid));
            for (Letter bc : broadcasts) {
                if (inbox.stream().noneMatch(l -> l.id().equals(bc.id()))) inbox.add(0, bc);
            }
            setCachedLetters(uuid, inbox);
            return inbox;
        }, ioExecutor);
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

                addToCache(letter.recipientUUID(), letter.withDelivered(true));
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
            setCachedLetters(uuid, inbox);

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

    /**
     * Persists a direct letter. The returned future completes with {@code true} only after the
     * row is committed — callers MUST NOT destroy the player's draft book until then, otherwise a
     * failed INSERT (disk full, locked DB) silently loses the letter. All Bukkit access is marshalled
     * back to the main thread.
     */
    public CompletableFuture<Boolean> sendLetter(Letter letter) {
        return CompletableFuture.supplyAsync(() -> {
            if (!storage.insertLetter(letter)) return false;
            if (letter.deliverAt() <= System.currentTimeMillis()
                    && letter.recipientUUID() != null
                    && storage.tryMarkDelivered(letter.id())) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player recipient = Bukkit.getPlayer(letter.recipientUUID());
                    if (recipient == null || !recipient.isOnline()) return;
                    addToCache(letter.recipientUUID(), letter.withDelivered(true));
                    notifyNewLetter(recipient, letter);
                });
            }
            return true;
        }, ioExecutor);
    }

    public CompletableFuture<Boolean> sendBroadcast(Letter letter) {
        return CompletableFuture.supplyAsync(() -> {
            if (!storage.insertLetter(letter)) return false;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    addToCache(p.getUniqueId(), letter);
                    notifyNewLetter(p, letter);
                }
            });
            return true;
        }, ioExecutor);
    }

    // --- Cache access ---

    public List<Letter> getInbox(UUID playerUUID) {
        long now = System.currentTimeMillis();
        return getCachedLetters(playerUUID, now);
    }

    public int getUnreadCount(UUID playerUUID) {
        long now = System.currentTimeMillis();
        List<Letter> cached = getCachedLetters(playerUUID, now);
        if (!cached.isEmpty()) return (int) cached.stream().filter(l -> !l.read()).count();
        return storage.countUnread(playerUUID) + storage.countUnreadBroadcasts(playerUUID);
    }

    /**
     * Contagem de não lidas SEM nenhuma consulta síncrona ao banco — segura para a main thread
     * (ex.: PlaceholderAPI resolve placeholders no thread do servidor, em scoreboard/chat/actionbar).
     * Em cache miss retorna 0 e aquece o cache de forma assíncrona; na próxima chamada o valor real aparece.
     * O cache já é populado no onPlayerJoin, então o miss só ocorre após expirar o TTL (5 min).
     */
    public int getUnreadCountFast(UUID playerUUID) {
        long now = System.currentTimeMillis();
        CachedInbox cached = inboxCache.get(playerUUID);
        if (cached != null && cached.expiresAt() > now) {
            return (int) cached.letters().stream().filter(l -> !l.read()).count();
        }
        warmInboxAsync(playerUUID);
        return 0;
    }

    /** Carrega inbox + broadcasts no cache fora da main thread. Deduplicado por {@link #warming}. */
    private void warmInboxAsync(UUID uuid) {
        if (!warming.add(uuid)) return; // já há um aquecimento em andamento
        CompletableFuture.runAsync(() -> {
            try {
                List<Letter> broadcasts = storage.getPendingBroadcasts(uuid);
                List<Letter> inbox = new ArrayList<>(storage.getInbox(uuid));
                for (Letter bc : broadcasts) {
                    if (inbox.stream().noneMatch(l -> l.id().equals(bc.id()))) inbox.add(0, bc);
                }
                setCachedLetters(uuid, inbox);
            } finally {
                warming.remove(uuid);
            }
        }, ioExecutor);
    }

    public void markRead(UUID playerUUID, UUID letterId, LetterType type) {
        long now = System.currentTimeMillis();
        CachedInbox cached = inboxCache.get(playerUUID);
        if (cached != null && cached.expiresAt() > now) {
            List<Letter> letters = cached.letters();
            letters.replaceAll(l -> l.id().equals(letterId) ? l.withRead(true) : l);
            inboxCache.put(playerUUID, new CachedInbox(letters, now + CACHE_TTL_MS));
        }
        CompletableFuture.runAsync(() -> {
            if (type == LetterType.BROADCAST) storage.markBroadcastSeen(letterId, playerUUID);
            else storage.markRead(letterId);
        }, ioExecutor);
    }

    public void runCleanup() {
        long now = System.currentTimeMillis();
        // Clamp em tudo: getLong devolve 0 para valor invalido no YAML, e um TTL de 0 dias
        // apagaria o banco inteiro na proxima limpeza.
        long directTTL    = TimeUnit.DAYS.toMillis(clampDays(plugin.getConfig().getLong("cleanup.delete-read-after-days", 30), 30));
        long broadcastTTL = TimeUnit.DAYS.toMillis(clampDays(plugin.getConfig().getLong("cleanup.broadcast-retention-days", 15), 15));
        long auditTTL     = TimeUnit.DAYS.toMillis(clampDays(plugin.getConfig().getLong("cleanup.delete-audit-after-days", 90), 90));
        long modImageTTL  = TimeUnit.DAYS.toMillis(clampDays(plugin.getConfig().getLong("cleanup.delete-moderation-image-after-days", 4), 4));
        CompletableFuture.runAsync(() -> {
            storage.cleanup(now - directTTL, now - broadcastTTL, now - auditTTL);
            storage.cleanupModerationImages(now - modImageTTL);
        }, ioExecutor);
    }

    private static long clampDays(long value, long fallback) {
        return value <= 0 ? fallback : value;
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
            // Evict from all online caches — cached.letters() é CopyOnWriteArrayList (removeIf seguro).
            inboxCache.forEach((uuid, cached) ->
                cached.letters().removeIf(l -> l.id().equals(letterId)));
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
