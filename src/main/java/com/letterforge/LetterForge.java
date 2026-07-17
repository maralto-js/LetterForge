package com.letterforge;

import com.letterforge.command.CartaCommand;
import com.letterforge.config.Messages;
import com.letterforge.image.ImageProcessor;
import com.letterforge.image.ImageUploadServer;
import com.letterforge.image.MapImageManager;
import com.letterforge.image.NsfwFilter;
import com.letterforge.listener.BookEditListener;
import com.letterforge.listener.GUIListener;
import com.letterforge.listener.PlayerJoinListener;
import com.letterforge.listener.PlayerQuitListener;
import com.letterforge.manager.LetterManager;
import com.letterforge.manager.SpamGuard;
import com.letterforge.placeholder.MailPlaceholder;
import com.letterforge.storage.SQLiteStorage;
import com.letterforge.storage.StorageProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

public final class LetterForge extends JavaPlugin {

    private StorageProvider   storage;
    private LetterManager     letterManager;
    private SpamGuard         spamGuard;
    private MapImageManager   mapImageManager;
    private ImageUploadServer imageUploadServer;
    private Messages          messages;

    private volatile boolean debugMode = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Keys.init(this);

        messages = new Messages(this);

        storage = new SQLiteStorage(getDataFolder(), getLogger());
        try {
            storage.init();
        } catch (Exception e) {
            getLogger().severe("[LetterForge] Falha ao inicializar banco de dados: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Apply configurable image dimension limit on startup
        ImageProcessor.setMaxImageDimension(getConfig().getInt("security.max-image-dimension", 4096));

        // NSFW: o core é leve e não embute ONNX. Um scorer real é registrado pelo addon
        // premium (LetterForge-NSFW-Model) via NsfwFilter.register(). Sem o addon, a
        // moderação usa apenas o ContentFilter (HSV).
        NsfwFilter.init(getLogger());

        if (storage instanceof SQLiteStorage sqStorage
                && getConfig().getBoolean("image-rendering.enabled", true)) {
            mapImageManager = new MapImageManager(this, sqStorage);
            mapImageManager.restoreRenderers();

            if (getConfig().getBoolean("image-upload.enabled", true)) {
                imageUploadServer = new ImageUploadServer(this);
                try {
                    imageUploadServer.start();
                } catch (Exception e) {
                    getLogger().warning("[LetterForge] Falha ao iniciar upload server: " + e.getMessage());
                    imageUploadServer = null;
                }
            }
        }

        spamGuard     = new SpamGuard(this);
        letterManager = new LetterManager(this, storage);
        letterManager.startDeliveryScheduler();

        CartaCommand cartaCmd = new CartaCommand(this, letterManager, spamGuard);
        var cmd = getCommand("carta");
        if (cmd != null) {
            cmd.setExecutor(cartaCmd);
            cmd.setTabCompleter(cartaCmd);
        }

        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinListener(letterManager), this);
        pm.registerEvents(new PlayerQuitListener(letterManager, spamGuard), this);
        pm.registerEvents(new BookEditListener(this, letterManager, spamGuard), this);
        pm.registerEvents(new GUIListener(this, letterManager, mapImageManager), this);

        if (pm.isPluginEnabled("PlaceholderAPI")) {
            new MailPlaceholder(letterManager).register();
            getLogger().info("[LetterForge] PlaceholderAPI integrado.");
        }

        long intervalHours = getConfig().getLong("cleanup.run-every-hours", 24);
        getServer().getAsyncScheduler().runAtFixedRate(this,
                task -> {
                    // Purges expired letters, audit rows AND moderation image copies (4-day TTL),
                    // then clears the in-memory image cache so nothing lingers in RAM.
                    letterManager.runCleanup();
                    if (mapImageManager != null) mapImageManager.clearCache();
                },
                intervalHours, intervalHours, TimeUnit.HOURS);

        getLogger().info("[LetterForge] Plugin carregado com sucesso.");
    }

    @Override
    public void onDisable() {
        if (imageUploadServer != null) imageUploadServer.stop();
        if (letterManager != null)     letterManager.shutdown();
        if (storage != null)           storage.close();
        NsfwFilter.close();
        getLogger().info("[LetterForge] Plugin descarregado.");
    }

    public void reload() {
        reloadConfig();
        messages.reload();
        ImageProcessor.setMaxImageDimension(getConfig().getInt("security.max-image-dimension", 4096));
        if (mapImageManager != null) mapImageManager.clearCache();
        getLogger().info("[LetterForge] Configuracoes e mensagens recarregadas.");
    }

    /** Convenience method — routes to SQLiteStorage.logAudit via the IO executor. */
    public void auditLog(String eventType, String actorUuid, String actorName,
                         String targetId, String detail) {
        if (letterManager != null) {
            letterManager.submitIoTask(() ->
                storage.logAudit(eventType, actorUuid, actorName, targetId, detail));
        }
    }

    // --- Getters ---

    public LetterManager     getLetterManager()    { return letterManager; }
    public MapImageManager   getMapImageManager()  { return mapImageManager; }
    public ImageUploadServer getImageUploadServer(){ return imageUploadServer; }
    public Messages          getMessages()         { return messages; }
    public boolean           isDebugMode()         { return debugMode; }
    public void              setDebugMode(boolean v){ this.debugMode = v; }
}
