package com.letterforge.manager;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.LocalDate;
import java.time.ZoneId;
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
    // long[]{ epochDay, count } — não limpo em remove() (relogar zeraria a cota)
    private final Map<UUID, long[]>        imageDaily          = new ConcurrentHashMap<>();

    public SpamGuard(Plugin plugin) {
        this.plugin = plugin;
    }

    public CheckResult check(Player player, long cooldownMs) {
        if (player.hasPermission("letterforge.bypass.cooldown")) return new CheckResult.Allowed();

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        long last = lastSend.getOrDefault(uuid, 0L);
        long diff = now - last;
        if (diff < cooldownMs) {
            return new CheckResult.OnCooldown(TimeUnit.MILLISECONDS.toSeconds(cooldownMs - diff) + 1);
        }

        // Só LÊ o contador — o incremento acontece em recordSend(), quando o envio é
        // confirmado. Incrementar aqui queimava o limite com envios que falhavam.
        int maxPerMin = plugin.getConfig().getInt("limits.max-per-minute", 5);
        long windowStart = rateWindowStart.getOrDefault(uuid, 0L);
        AtomicInteger counter = rateCounter.get(uuid);
        int currentCount = (counter == null || now - windowStart > 60_000L) ? 0 : counter.get();
        if (currentCount >= maxPerMin) {
            return new CheckResult.RateLimited();
        }
        return new CheckResult.Allowed();
    }

    public void recordSend(UUID uuid) {
        long now = System.currentTimeMillis();
        lastSend.put(uuid, now);
        long windowStart = rateWindowStart.getOrDefault(uuid, 0L);
        if (now - windowStart > 60_000L) {
            rateWindowStart.put(uuid, now);
            rateCounter.put(uuid, new AtomicInteger(1));
        } else {
            rateCounter.computeIfAbsent(uuid, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    public CheckResult checkBroadcast(Player player) {
        if (player.hasPermission("letterforge.bypass.cooldown")) return new CheckResult.Allowed();
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
        if (player.hasPermission("letterforge.bypass.cooldown")) return new CheckResult.Allowed();
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
        if (player.hasPermission("letterforge.bypass.cooldown")) return new CheckResult.Allowed();
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

    // --- Daily image quota ---

    private boolean isImageUnlimited(Player player) {
        return player.hasPermission("letterforge.image.unlimited")
                || player.hasPermission("letterforge.admin");
    }

    public int imageDailyLimit() {
        return plugin.getConfig().getInt("image-limits.daily", 3);
    }

    public int imagesUsedToday(UUID uuid) {
        long today = LocalDate.now(ZoneId.systemDefault()).toEpochDay();
        long[] rec = imageDaily.get(uuid);
        return (rec == null || rec[0] != today) ? 0 : (int) rec[1];
    }

    public boolean canSendImage(Player player) {
        if (isImageUnlimited(player)) return true;
        int limit = imageDailyLimit();
        return limit <= 0 || imagesUsedToday(player.getUniqueId()) < limit;
    }

    public void recordImageSent(Player player) {
        if (isImageUnlimited(player)) return;
        long today = LocalDate.now(ZoneId.systemDefault()).toEpochDay();
        imageDaily.compute(player.getUniqueId(), (k, rec) -> {
            if (rec == null || rec[0] != today) return new long[]{ today, 1 };
            rec[1]++;
            return rec;
        });
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
