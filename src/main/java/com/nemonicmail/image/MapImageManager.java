package com.nemonicmail.image;

import com.nemonicmail.NemonicMail;
import com.nemonicmail.storage.SQLiteStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gerencia o ciclo de vida completo de imagens em mapas:
 *  - Processamento de URLs/bytes em tiles (async)
 *  - Criação e restauração de MapViews no Bukkit (main thread)
 *  - Persistência de tiles via SQLiteStorage (via ioExecutor de LetterManager)
 *  - Entrega de itens de mapa ao jogador na primeira leitura
 *
 * Thread safety:
 *  - processUrl() / consumePending() são async-safe via ConcurrentHashMap
 *  - createMapViews() DEVE ser chamado do main thread
 *  - saveMapImages() não usa mais ForkJoinPool — delegar ao ioExecutor via letterManager.submitIoTask()
 */
public class MapImageManager {

    public record PendingImage(byte[][] tiles, int gridW, int gridH) {}

    private final NemonicMail    plugin;
    private final SQLiteStorage  storage;
    private final ImageCache     cache = new ImageCache();
    private final Map<UUID, PendingImage> pendingImages = new ConcurrentHashMap<>();

    public MapImageManager(NemonicMail plugin, SQLiteStorage storage) {
        this.plugin  = plugin;
        this.storage = storage;
    }

    // -----------------------------------------------------------------------
    // Startup
    // -----------------------------------------------------------------------

    public void restoreRenderers() {
        CompletableFuture.runAsync(() -> {
            List<MapImageEntry> all = storage.getAllMapImages();
            if (all.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                int ok = 0, missing = 0;
                for (MapImageEntry entry : all) {
                    MapView view = Bukkit.getMap(entry.mapId());
                    if (view == null) { missing++; continue; }
                    view.getRenderers().forEach(view::removeRenderer);
                    view.addRenderer(new StaticMapRenderer(entry.pixels()));
                    ok++;
                }
                if (ok > 0)
                    plugin.getLogger().info("[NemonicMail] " + ok + " renderer(s) de mapa restaurado(s).");
                if (missing > 0)
                    plugin.getLogger().warning("[NemonicMail] " + missing + " MapView(s) não encontrado(s) — tiles órfãos no banco.");
            });
        });
    }

    // -----------------------------------------------------------------------
    // Estado pendente (jogador prepara imagem antes de escrever carta)
    // -----------------------------------------------------------------------

    public void storePending(UUID playerUuid, byte[][] tiles, int gridW, int gridH) {
        pendingImages.put(playerUuid, new PendingImage(tiles, gridW, gridH));
    }

    public Optional<PendingImage> consumePending(UUID playerUuid) {
        return Optional.ofNullable(pendingImages.remove(playerUuid));
    }

    public boolean hasPending(UUID playerUuid) {
        return pendingImages.containsKey(playerUuid);
    }

    public void cancelPending(UUID playerUuid) {
        pendingImages.remove(playerUuid);
    }

    // -----------------------------------------------------------------------
    // Processamento async de URL
    // -----------------------------------------------------------------------

    public CompletableFuture<Void> processUrl(UUID playerUuid, String url, int gridW, int gridH) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                byte[][] tiles = cache.get(url).orElseGet(() -> {
                    try {
                        BufferedImage img = ImageProcessor.fetchImage(url);
                        if (plugin.getConfig().getBoolean("content-filter.enabled", false)) {
                            float threshold = (float) plugin.getConfig().getDouble("content-filter.skin-threshold", 0.35);
                            float fraction  = ContentFilter.skinToneFraction(img);
                            if (fraction > threshold) {
                                plugin.auditLog("CONTENT_FLAGGED_URL", playerUuid.toString(), null, url,
                                        String.format("url skin=%.2f > threshold=%.2f", fraction, threshold));
                                throw new RuntimeException("Imagem bloqueada pelo filtro de conteúdo");
                            }
                        }
                        // Filtro NSFW (modelo) — só roda se habilitado E se um addon registrou um scorer.
                        if (plugin.getConfig().getBoolean("nsfw-filter.enabled", false)
                                && NsfwFilter.isAvailable()) {
                            try {
                                float nsfwThreshold = (float) plugin.getConfig().getDouble("nsfw-filter.threshold", 0.75);
                                float nsfwScore = NsfwFilter.score(img);
                                if (nsfwScore > nsfwThreshold) {
                                    if (plugin.getConfig().getBoolean("nsfw-filter.audit-log", true)) {
                                        plugin.auditLog("NSFW_FLAGGED_URL", playerUuid.toString(), null, url,
                                                String.format("url nsfw=%.2f > threshold=%.2f", nsfwScore, nsfwThreshold));
                                    }
                                    if (plugin.getConfig().getBoolean("nsfw-filter.notify-admin", true)) {
                                        plugin.getLogger().warning(String.format(
                                                "[NemonicMail] [NSFW] URL bloqueada — player=%s score=%.2f", playerUuid, nsfwScore));
                                    }
                                    throw new RuntimeException("Imagem bloqueada pelo filtro NSFW");
                                }
                            } catch (RuntimeException re) {
                                throw re;
                            } catch (Exception nsfwErr) {
                                // Falha de inferência não deve derrubar o envio — apenas registra.
                                plugin.getLogger().warning("[NemonicMail] [NSFW] Erro na inferência: " + nsfwErr.getMessage());
                            }
                        }
                        byte[][] t = ImageProcessor.fromImage(img, gridW, gridH);
                        cache.put(url, t);
                        return t;
                    } catch (RuntimeException re) {
                        throw re;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                storePending(playerUuid, tiles, gridW, gridH);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    // -----------------------------------------------------------------------
    // Criação de MapViews + persistência (chamar APÓS processUrl / storePending)
    // -----------------------------------------------------------------------

    /** DEVE ser chamado no main thread. Retorna IDs dos mapas criados. */
    public int[] createMapViews(byte[][] tiles) {
        World world = Bukkit.getWorlds().get(0);
        int[] mapIds = new int[tiles.length];
        for (int i = 0; i < tiles.length; i++) {
            MapView view = Bukkit.createMap(world);
            view.getRenderers().forEach(view::removeRenderer);
            view.addRenderer(new StaticMapRenderer(tiles[i]));
            view.setTrackingPosition(false);
            view.setScale(MapView.Scale.CLOSEST);
            mapIds[i] = (int) view.getId();
        }
        return mapIds;
    }

    /**
     * Salva tiles no banco. Deve ser chamado via letterManager.submitIoTask()
     * para usar o mesmo executor single-threaded das demais operações de storage,
     * evitando concorrência com SQLiteStorage.
     */
    public void saveMapImages(UUID letterId, int[] mapIds, PendingImage pending) {
        for (int i = 0; i < mapIds.length; i++) {
            storage.insertMapImage(letterId, i, mapIds[i],
                    pending.tiles()[i], pending.gridW(), pending.gridH());
        }
    }

    /**
     * Saves a moderation copy of the attached image, tagged with the REAL sender (even for
     * anonymous letters). Independent of the letter lifecycle and auto-purged after a few days
     * (see cleanup.delete-moderation-image-after-days). Call via letterManager.submitIoTask().
     */
    public void saveModerationImage(UUID letterId, int[] mapIds, PendingImage pending,
                                    String senderUuid, String senderName,
                                    String recipient, String letterType) {
        storage.insertModerationImageBatch(letterId, mapIds, pending.tiles(),
                pending.gridW(), pending.gridH(), senderUuid, senderName, recipient, letterType);
    }

    /** True if a moderation copy still exists for this letter (within the retention window). */
    public boolean hasModerationImage(UUID letterId) {
        return storage.hasModerationImage(letterId);
    }

    /**
     * Hands the moderation copy of a letter's image to a staff member as map item(s) so they can
     * inspect exactly what was sent. Reuses the original MapView when it still exists; otherwise
     * recreates a one-off view from the stored pixels.
     */
    public void giveModerationImage(Player staff, UUID letterId) {
        CompletableFuture.runAsync(() -> {
            List<MapImageEntry> entries = storage.getModerationImages(letterId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!staff.isOnline()) return;
                var msgs = plugin.getMessages();
                if (entries.isEmpty()) {
                    staff.sendMessage(msgs.prefixed("admin.no-mod-image"));
                    return;
                }
                World world = Bukkit.getWorlds().get(0);
                for (MapImageEntry entry : entries) {
                    MapView view = entry.mapId() >= 0 ? Bukkit.getMap(entry.mapId()) : null;
                    if (view == null) {
                        view = Bukkit.createMap(world);
                        view.getRenderers().forEach(view::removeRenderer);
                        view.addRenderer(new StaticMapRenderer(entry.pixels()));
                        view.setTrackingPosition(false);
                        view.setScale(MapView.Scale.CLOSEST);
                    }
                    ItemStack item = new ItemStack(Material.FILLED_MAP);
                    MapMeta meta = (MapMeta) item.getItemMeta();
                    meta.setMapView(view);
                    meta.displayName(Component.text(
                            "[MODERACAO] Imagem " + (entry.tileIndex() + 1) + "/" + entries.size(),
                            NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                    item.setItemMeta(meta);
                    giveOrDrop(staff, item);
                }
                staff.sendMessage(msgs.prefixed("admin.mod-image-given",
                        Map.of("count", String.valueOf(entries.size()))));
            });
        });
    }

    private void giveOrDrop(Player p, ItemStack item) {
        var overflow = p.getInventory().addItem(item);
        overflow.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
    }

    // -----------------------------------------------------------------------
    // Entrega ao jogador
    // -----------------------------------------------------------------------

    public void giveAttachmentToPlayer(Player player, UUID letterId) {
        CompletableFuture.runAsync(() -> {
            List<MapImageEntry> entries = storage.getMapImagesForLetter(letterId);
            if (entries.isEmpty()) return;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                var msgs = plugin.getMessages();
                boolean anyDropped = false;
                for (MapImageEntry entry : entries) {
                    MapView view = Bukkit.getMap(entry.mapId());
                    if (view == null) continue;

                    ItemStack item = new ItemStack(Material.FILLED_MAP);
                    MapMeta meta = (MapMeta) item.getItemMeta();
                    meta.setMapView(view);
                    if (entries.size() > 1) {
                        meta.displayName(Component.text(
                            "Carta — Mapa " + (entry.tileIndex() + 1) + "/" + entries.size(),
                            NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                    }
                    item.setItemMeta(meta);

                    var overflow = player.getInventory().addItem(item);
                    if (!overflow.isEmpty()) {
                        overflow.values().forEach(i ->
                            player.getWorld().dropItemNaturally(player.getLocation(), i));
                        anyDropped = true;
                    }
                }

                String msgKey = entries.size() == 1 ? "gui.map-single" : "gui.map-multi";
                Component msg = msgs.prefixed(msgKey,
                        Map.of("count", String.valueOf(entries.size())));
                if (anyDropped) {
                    msg = msg.append(msgs.component("gui.map-dropped"));
                }
                player.sendMessage(msg);
            });
        });
    }

    // -----------------------------------------------------------------------
    // Cache
    // -----------------------------------------------------------------------

    /** Limpa o cache de imagens processadas — chamado por /carta reload. */
    public void clearCache() {
        cache.clear();
    }
}
