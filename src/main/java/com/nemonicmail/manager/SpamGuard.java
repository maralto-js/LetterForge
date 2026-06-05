package com.nemonicmail.manager;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SpamGuard {

    public sealed interface CheckResult permits CheckResult.Allowed, CheckResult.OnCooldown, CheckResult.RateLimited {
        record Allowed() implements CheckResult {}
        record OnCooldown(long secondsLeft) implements CheckResult {}
        record RateLimited() implements CheckResult {}
    }

    private final Plugin plugin;
    private final Map<UUID, Long>          lastSend          = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> rateCounter       = new ConcurrentHashMap<>();
    private final Map<UUID, Long>          rateWindowStart   = new ConcurrentHashMap<>();
    private final Map<UUID, Long>          broadcastCooldown = new ConcurrentHashMap<>();
    // Separate image cooldowns — independent of letter cooldowns
    private final Map<UUID, Long>          imageUrlCooldown    = new ConcurrentHashMap<>();
    private final Map<UUID, Long>          imageUploadCooldown = new ConcurrentHashMap<>();

    public SpamGuard(Plugin plugin) {
        this.plugin = plugin;
    }

    public CheckResult check(Player player, long cooldownMs) {
        if (player.hasPermission("nemonicmail.bypass.cooldown")) return new CheckResult.Allowed();

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        long last = lastSend.getOrDefault(uuid, 0L);
        long diff = now - last;
        if (diff < cooldownMs) {
            return new CheckResult.OnCooldown(TimeUnit.MILLISECONDS.toSeconds(cooldownMs - diff) + 1);
        }

        int maxPerMin = plugin.getConfig().getInt("limits.max-per-minute", 5);
        long windowStart = rateWindowStart.getOrDefault(uuid, 0L);
        if (now - windowStart > 60_000L) {
            rateWindowStart.put(uuid, now);
            rateCounter.put(uuid, new AtomicInteger(0));
        }

        AtomicInteger counter = rateCounter.computeIfAbsent(uuid, k -> new AtomicInteger(0));
        int currentCount = counter.getAndIncrement();
        if (currentCount >= maxPerMin) {
            counter.decrementAndGet();
            return new CheckResult.RateLimited();
        }
        return new CheckResult.Allowed();
    }

    public void recordSend(UUID uuid) {
        lastSend.put(uuid, System.currentTimeMillis());
    }

    public CheckResult checkBroadcast(Player player) {
        if (player.hasPermission("nemonicmail.bypass.cooldown")) return new CheckResult.Allowed();
        long cooldownMs = TimeUnit.MINUTES.toMillis(
                plugin.getConfig().getLong("cooldowns.broadcast-minutes", 30));
        UUID uuid = player.getUniqueId();
        long diff = System.currentTimeMillis() - broadcastCooldown.getOrDefault(uuid, 0L);
        if (diff < cooldownMs)
            return new CheckResult.OnCooldown(TimeUnit.MILLISECONDS.toSeconds(cooldownMs - diff) + 1);
        return new CheckResult.Allowed();
    }

    public void recordBroadcast(UUID uuid) {
        broadcastCooldown.put(uuid, System.currentTimeMillis());
    }

    // --- Image rate limits (separate from letter cooldowns) ---

    public CheckResult checkImageUrl(Player player) {
        if (player.hasPermission("nemonicmail.bypass.cooldown")) return new CheckResult.Allowed();
        long cooldownMs = TimeUnit.SECONDS.toMillis(
                plugin.getConfig().getLong("image-cooldowns.url-seconds", 30));
        UUID uuid = player.getUniqueId();
        long diff = System.currentTimeMillis() - imageUrlCooldown.getOrDefault(uuid, 0L);
        if (diff < cooldownMs)
            return new CheckResult.OnCooldown(TimeUnit.MILLISECONDS.toSeconds(cooldownMs - diff) + 1);
        return new CheckResult.Allowed();
    }

    public void recordImageUrl(UUID uuid) {
        imageUrlCooldown.put(uuid, System.currentTimeMillis());
    }

    public CheckResult checkImageUpload(Player player) {
        if (player.hasPermission("nemonicmail.bypass.cooldown")) return new CheckResult.Allowed();
        long cooldownMs = TimeUnit.SECONDS.toMillis(
                plugin.getConfig().getLong("image-cooldowns.upload-seconds", 60));
        UUID uuid = player.getUniqueId();
        long diff = System.currentTimeMillis() - imageUploadCooldown.getOrDefault(uuid, 0L);
        if (diff < cooldownMs)
            return new CheckResult.OnCooldown(TimeUnit.MILLISECONDS.toSeconds(cooldownMs - diff) + 1);
        return new CheckResult.Allowed();
    }

    public void recordImageUpload(UUID uuid) {
        imageUploadCooldown.put(uuid, System.currentTimeMillis());
    }

    public void remove(UUID uuid) {
        lastSend.remove(uuid);
        rateCounter.remove(uuid);
        rateWindowStart.remove(uuid);
        broadcastCooldown.remove(uuid);
        imageUrlCooldown.remove(uuid);
        imageUploadCooldown.remove(uuid);
    }

    /** Reads cooldown from LuckPerms meta `mail.cooldown`, falls back to config. */
    public long resolveCooldownMs(Player player, String configKey) {
        long defaultSecs = plugin.getConfig().getLong(configKey, 30);

        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return TimeUnit.SECONDS.toMillis(defaultSecs);
        }

        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String meta = user.getCachedData()
                        .getMetaData(QueryOptions.defaultContextualOptions())
                        .getMetaValue("mail.cooldown");
                if (meta != null) return TimeUnit.SECONDS.toMillis(Long.parseLong(meta));
            }
        } catch (Exception ignored) {}

        return TimeUnit.SECONDS.toMillis(defaultSecs);
    }
}
