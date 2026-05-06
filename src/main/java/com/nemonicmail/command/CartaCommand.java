package com.nemonicmail.command;

import com.nemonicmail.Keys;
import com.nemonicmail.NemonicMail;
import com.nemonicmail.gui.CaixaCorreioGUI;
import com.nemonicmail.manager.LetterManager;
import com.nemonicmail.manager.SpamGuard;
import com.nemonicmail.model.AuditEntry;
import com.nemonicmail.model.Letter;
import com.nemonicmail.model.LetterPriority;
import com.nemonicmail.model.LetterType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.*;

public class CartaCommand implements CommandExecutor, TabCompleter {

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("dd/MM/yy HH:mm");

    private final NemonicMail plugin;
    private final LetterManager letterManager;
    private final SpamGuard spamGuard;

    public CartaCommand(NemonicMail plugin, LetterManager letterManager, SpamGuard spamGuard) {
        this.plugin = plugin;
        this.letterManager = letterManager;
        this.spamGuard = spamGuard;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().raw("general.only-players"));
            return true;
        }

        if (!player.hasPermission("nemonicmail.use")) {
            player.sendMessage(plugin.getMessages().prefixed("general.no-permission"));
            return true;
        }

        if (args.length == 0) { sendHelp(player); return true; }

        return switch (args[0].toLowerCase()) {
            case "escrever", "escreve", "write"  -> handleWrite(player, args);
            case "caixa", "inbox", "ver"         -> handleInbox(player, args);
            case "todos", "all", "broadcast"     -> handleBroadcast(player, args);
            case "imagem", "img", "image"        -> handleImage(player, args);
            case "admin"                         -> handleAdmin(player, args);
            case "moderar"                       -> handleModerate(player);
            case "reload"                        -> handleReload(player);
            case "ajuda", "help"                 -> { sendHelp(player); yield true; }
            default                              -> { sendHelp(player); yield true; }
        };
    }

    // -----------------------------------------------------------------------
    // /carta reload
    // -----------------------------------------------------------------------

    private boolean handleReload(Player player) {
        if (!player.hasPermission("nemonicmail.reload")) {
            player.sendMessage(plugin.getMessages().prefixed("general.reload-no-perm"));
            return true;
        }
        plugin.reload();
        player.sendMessage(plugin.getMessages().prefixed("general.reload-success"));
        return true;
    }

    // -----------------------------------------------------------------------
    // /carta escrever
    // -----------------------------------------------------------------------

    private boolean handleWrite(Player player, String[] args) {
        var msgs = plugin.getMessages();
        if (args.length < 2) { player.sendMessage(msgs.prefixed("write.usage")); return true; }

        String targetName = args[1];
        String modifier   = args.length >= 3 ? args[2].toLowerCase() : "";

        LetterType type = switch (modifier) {
            case "anon", "anonimo", "anonymous" -> LetterType.ANONYMOUS;
            case "urgente", "urgent"            -> LetterType.URGENT;
            case "oficial", "official"          -> LetterType.OFFICIAL;
            default                             -> LetterType.DIRECT;
        };
        LetterPriority priority = switch (type) {
            case URGENT, OFFICIAL -> LetterPriority.HIGH;
            default               -> LetterPriority.NORMAL;
        };

        if ((type == LetterType.URGENT || type == LetterType.OFFICIAL)
                && !player.hasPermission("nemonicmail.priority.high")) {
            player.sendMessage(msgs.prefixed("write.no-perm-type"));
            return true;
        }

        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        if (onlineTarget != null) {
            giveWritingBook(player, onlineTarget.getUniqueId().toString(),
                    onlineTarget.getName(), type, priority);
            return true;
        }

        player.sendMessage(msgs.prefixed("general.player-searching"));
        final LetterType ft = type; final LetterPriority fp = priority;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!offline.hasPlayedBefore()) {
                    player.sendMessage(msgs.prefixed("general.player-not-found",
                            Map.of("player", targetName)));
                    return;
                }
                String recipUUID = offline.getUniqueId().toString();
                String recipName = offline.getName() != null ? offline.getName() : targetName;
                giveWritingBook(player, recipUUID, recipName, ft, fp);
            });
        });
        return true;
    }

    private void giveWritingBook(Player player, String recipUUID, String recipName,
                                  LetterType type, LetterPriority priority) {
        var msgs = plugin.getMessages();
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getType() == Material.WRITABLE_BOOK && inHand.hasItemMeta()
                && inHand.getItemMeta().getPersistentDataContainer()
                         .has(Keys.IS_DRAFT, PersistentDataType.BOOLEAN)) {
            player.sendMessage(msgs.prefixed("write.already-has-book"));
            return;
        }

        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(Component.text("Carta para " + recipName, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Escreva e assine para enviar", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));

        var pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.IS_DRAFT,        PersistentDataType.BOOLEAN, true);
        pdc.set(Keys.RECIPIENT_UUID,  PersistentDataType.STRING,  recipUUID);
        pdc.set(Keys.RECIPIENT_NAME,  PersistentDataType.STRING,  recipName);
        pdc.set(Keys.LETTER_TYPE,     PersistentDataType.STRING,  type.name());
        pdc.set(Keys.LETTER_PRIORITY, PersistentDataType.STRING,  priority.name());
        book.setItemMeta(meta);
        player.getInventory().addItem(book);

        var mim = plugin.getMapImageManager();
        boolean hasImage = mim != null && mim.hasPending(player.getUniqueId());
        player.sendMessage(msgs.prefixed(hasImage ? "write.book-ready-img" : "write.book-ready",
                Map.of("player", recipName)));
    }

    // -----------------------------------------------------------------------
    // /carta caixa
    // -----------------------------------------------------------------------

    private boolean handleInbox(Player player, String[] args) {
        int page = 0;
        if (args.length >= 2) {
            try { page = Math.max(0, Integer.parseInt(args[1]) - 1); }
            catch (NumberFormatException ignored) {}
        }
        var inbox = letterManager.getInbox(player.getUniqueId());
        if (inbox.isEmpty()) { player.sendMessage(plugin.getMessages().prefixed("inbox.empty")); return true; }
        new CaixaCorreioGUI(player, inbox, page).open();
        return true;
    }

    // -----------------------------------------------------------------------
    // /carta todos
    // -----------------------------------------------------------------------

    private boolean handleBroadcast(Player player, String[] args) {
        var msgs = plugin.getMessages();
        if (!player.hasPermission("nemonicmail.broadcast")) {
            player.sendMessage(msgs.prefixed("broadcast.no-permission"));
            return true;
        }

        String modifier = args.length >= 2 ? args[1].toLowerCase() : "";
        LetterType type = switch (modifier) {
            case "urgente", "urgent"   -> LetterType.URGENT;
            case "oficial", "official" -> LetterType.OFFICIAL;
            default                    -> LetterType.BROADCAST;
        };
        LetterPriority priority = (type == LetterType.URGENT || type == LetterType.OFFICIAL)
                ? LetterPriority.HIGH : LetterPriority.NORMAL;

        if (priority == LetterPriority.HIGH && !player.hasPermission("nemonicmail.priority.high")) {
            player.sendMessage(msgs.prefixed("broadcast.no-perm-type"));
            return true;
        }

        SpamGuard.CheckResult result = spamGuard.checkBroadcast(player);
        if (result instanceof SpamGuard.CheckResult.OnCooldown cd) {
            player.sendMessage(msgs.prefixed("cooldown.broadcast",
                    Map.of("seconds", String.valueOf(cd.secondsLeft()))));
            return true;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getType() == Material.WRITABLE_BOOK && inHand.hasItemMeta()
                && inHand.getItemMeta().getPersistentDataContainer().has(Keys.IS_DRAFT, PersistentDataType.BOOLEAN)) {
            player.sendMessage(msgs.prefixed("broadcast.already-has-book"));
            return true;
        }

        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(Component.text("Anúncio para TODOS", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Escreva e assine para enviar a todos", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));

        var pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.IS_DRAFT,        PersistentDataType.BOOLEAN, true);
        pdc.set(Keys.RECIPIENT_NAME,  PersistentDataType.STRING,  "TODOS");
        pdc.set(Keys.LETTER_TYPE,     PersistentDataType.STRING,  LetterType.BROADCAST.name());
        pdc.set(Keys.LETTER_PRIORITY, PersistentDataType.STRING,  priority.name());
        book.setItemMeta(meta);
        player.getInventory().addItem(book);
        player.sendMessage(msgs.prefixed("broadcast.book-ready"));
        return true;
    }

    // -----------------------------------------------------------------------
    // /carta imagem
    // -----------------------------------------------------------------------

    private boolean handleImage(Player player, String[] args) {
        var msgs = plugin.getMessages();
        if (plugin.getMapImageManager() == null) {
            player.sendMessage(msgs.prefixed("image.disabled")); return true;
        }
        if (args.length < 2) { sendImageHelp(player); return true; }
        return switch (args[1].toLowerCase()) {
            case "url"                  -> handleImageUrl(player, args);
            case "upload"               -> handleImageUpload(player);
            case "confirmar", "confirm" -> handleImageConfirm(player, args);
            case "cancelar", "cancel"   -> handleImageCancel(player);
            case "status"               -> handleImageStatus(player);
            default                     -> { sendImageHelp(player); yield true; }
        };
    }

    private boolean handleImageUrl(Player player, String[] args) {
        var msgs = plugin.getMessages();
        if (!player.hasPermission("nemonicmail.image.url")) {
            player.sendMessage(msgs.prefixed("image.no-perm-url")); return true;
        }
        if (args.length < 3) { player.sendMessage(msgs.prefixed("image.url-usage")); return true; }

        // Image URL rate limit (separate from letter cooldown)
        SpamGuard.CheckResult result = spamGuard.checkImageUrl(player);
        if (result instanceof SpamGuard.CheckResult.OnCooldown cd) {
            player.sendMessage(msgs.prefixed("cooldown.image-url",
                    Map.of("seconds", String.valueOf(cd.secondsLeft()))));
            return true;
        }

        String url  = args[2];
        int[]  grid = parseGrid(args.length >= 4 ? args[3] : "1x1");
        spamGuard.recordImageUrl(player.getUniqueId());

        player.sendMessage(msgs.prefixed("image.processing"));
        plugin.getMapImageManager()
            .processUrl(player.getUniqueId(), url, grid[0], grid[1])
            .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.sendMessage(msgs.prefixed("image.ready"));
            }))
            .exceptionally(err -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline())
                        player.sendMessage(msgs.prefixed("image.error",
                                Map.of("error", rootMessage(err))));
                });
                return null;
            });
        return true;
    }

    private boolean handleImageUpload(Player player) {
        var msgs = plugin.getMessages();
        if (!player.hasPermission("nemonicmail.image.upload")) {
            player.sendMessage(msgs.prefixed("image.no-perm-upload")); return true;
        }
        var ups = plugin.getImageUploadServer();
        if (ups == null) { player.sendMessage(msgs.prefixed("image.upload-disabled")); return true; }

        // Upload rate limit (independent from URL rate limit)
        SpamGuard.CheckResult result = spamGuard.checkImageUpload(player);
        if (result instanceof SpamGuard.CheckResult.OnCooldown cd) {
            player.sendMessage(msgs.prefixed("cooldown.image-upload",
                    Map.of("seconds", String.valueOf(cd.secondsLeft()))));
            return true;
        }

        String serverIp  = plugin.getConfig().getString("image-upload.server-ip", "localhost");
        String uploadUrl = ups.generateToken(player.getUniqueId(), serverIp);
        spamGuard.recordImageUpload(player.getUniqueId());

        player.sendMessage(msgs.prefixed("image.upload-link-title"));
        player.sendMessage(Component.text("[Correio] ", NamedTextColor.DARK_GRAY)
            .append(Component.text("[Abrir página de upload]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(uploadUrl))
                .hoverEvent(HoverEvent.showText(Component.text(uploadUrl, NamedTextColor.GRAY)))));
        return true;
    }

    private boolean handleImageConfirm(Player player, String[] args) {
        var msgs = plugin.getMessages();
        var ups = plugin.getImageUploadServer();
        if (ups == null) { player.sendMessage(msgs.prefixed("image.upload-disabled")); return true; }
        if (args.length < 3) { player.sendMessage(msgs.prefixed("image.confirm-usage")); return true; }
        String token = args[2];
        ups.consumeReady(token, player.getUniqueId()).ifPresentOrElse(
            tiles -> {
                plugin.getMapImageManager().storePending(player.getUniqueId(), tiles, 1, 1);
                player.sendMessage(msgs.prefixed("image.confirmed"));
            },
            () -> player.sendMessage(msgs.prefixed("image.token-invalid"))
        );
        return true;
    }

    private boolean handleImageCancel(Player player) {
        plugin.getMapImageManager().cancelPending(player.getUniqueId());
        player.sendMessage(plugin.getMessages().prefixed("image.cancelled"));
        return true;
    }

    private boolean handleImageStatus(Player player) {
        var mim = plugin.getMapImageManager();
        String key = (mim != null && mim.hasPending(player.getUniqueId()))
                ? "image.status-has" : "image.status-none";
        player.sendMessage(plugin.getMessages().prefixed(key));
        return true;
    }

    // -----------------------------------------------------------------------
    // /carta admin
    // -----------------------------------------------------------------------

    private boolean handleAdmin(Player player, String[] args) {
        var msgs = plugin.getMessages();
        if (!player.hasPermission("nemonicmail.admin")) {
            player.sendMessage(msgs.prefixed("general.no-permission"));
            return true;
        }
        if (args.length < 2) { sendAdminHelp(player); return true; }
        return switch (args[1].toLowerCase()) {
            case "debug"   -> handleAdminDebug(player, args);
            case "ver"     -> handleAdminVer(player, args);
            case "revogar" -> handleAdminRevogar(player, args);
            case "quem"    -> handleAdminQuem(player, args);
            default        -> { sendAdminHelp(player); yield true; }
        };
    }

    private boolean handleAdminDebug(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getMessages().prefixed("admin.debug-usage"));
            return true;
        }
        boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        plugin.setDebugMode(on);
        String key = on ? "admin.debug-on" : "admin.debug-off";
        player.sendMessage(plugin.getMessages().prefixed(key));
        plugin.getLogger().info("[NemonicMail] Debug mode " + (on ? "ATIVADO" : "DESATIVADO")
                + " por " + player.getName());
        return true;
    }

    private boolean handleAdminVer(Player player, String[] args) {
        var msgs = plugin.getMessages();
        if (args.length < 3) { player.sendMessage(msgs.prefixed("admin.ver-usage")); return true; }
        String targetName = args[2];

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            if (!offline.hasPlayedBefore()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage(msgs.prefixed("general.player-not-found",
                            Map.of("player", targetName))));
                return;
            }

            letterManager.getPlayerLettersAdmin(offline.getUniqueId())
                .thenAccept(letters -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(Component.text("═══ ", NamedTextColor.DARK_GRAY)
                        .append(Component.text("Admin: Cartas de " + targetName, NamedTextColor.GOLD, TextDecoration.BOLD))
                        .append(Component.text(" ═══", NamedTextColor.DARK_GRAY)));

                    if (letters.isEmpty()) {
                        player.sendMessage(Component.text("  Nenhuma carta encontrada.", NamedTextColor.GRAY));
                        return;
                    }
                    letters.stream().limit(15).forEach(l -> {
                        String date = DATE_FMT.format(new Date(l.sentAt()));
                        String type = l.type().name();
                        String read = l.read() ? "✓" : "✗";
                        Component line = Component.text("  [" + type + "] ", NamedTextColor.YELLOW)
                            .append(Component.text("De: " + l.senderName() + " | " + date
                                + " | Lida:" + read, NamedTextColor.GRAY))
                            .append(Component.text(" [ID]", NamedTextColor.AQUA)
                                .hoverEvent(HoverEvent.showText(
                                    Component.text(l.id().toString(), NamedTextColor.WHITE))));
                        player.sendMessage(line);
                    });
                    if (letters.size() > 15)
                        player.sendMessage(Component.text("  ... e mais " + (letters.size() - 15) + " carta(s).",
                                NamedTextColor.GRAY));
                }));
        });
        return true;
    }

    private boolean handleAdminRevogar(Player player, String[] args) {
        var msgs = plugin.getMessages();
        if (args.length < 3) { player.sendMessage(msgs.prefixed("admin.revogar-usage")); return true; }
        UUID letterId;
        try {
            letterId = UUID.fromString(args[2]);
        } catch (IllegalArgumentException e) {
            player.sendMessage(msgs.prefixed("admin.invalid-id"));
            return true;
        }

        letterManager.revokeLetter(letterId, player.getUniqueId().toString(), player.getName())
            .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline())
                    player.sendMessage(msgs.prefixed("admin.revoked", Map.of("id", args[2])));
            }));
        return true;
    }

    private boolean handleAdminQuem(Player player, String[] args) {
        var msgs = plugin.getMessages();
        if (args.length < 3) { player.sendMessage(msgs.prefixed("admin.quem-usage")); return true; }
        UUID letterId;
        try {
            letterId = UUID.fromString(args[2]);
        } catch (IllegalArgumentException e) {
            player.sendMessage(msgs.prefixed("admin.invalid-id"));
            return true;
        }

        letterManager.getLetter(letterId)
            .thenAccept(opt -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (opt.isEmpty()) {
                    player.sendMessage(msgs.prefixed("admin.letter-not-found"));
                    return;
                }
                Letter l = opt.get();
                player.sendMessage(Component.text("═══ ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("Admin: Carta " + args[2].substring(0, 8) + "...",
                            NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text(" ═══", NamedTextColor.DARK_GRAY)));
                player.sendMessage(Component.text("  Remetente real: ", NamedTextColor.YELLOW)
                    .append(Component.text(l.senderName() + " (" + l.senderUUID() + ")",
                            NamedTextColor.WHITE)));
                player.sendMessage(Component.text("  Destinatario: ", NamedTextColor.YELLOW)
                    .append(Component.text(l.recipientName(), NamedTextColor.WHITE)));
                player.sendMessage(Component.text("  Tipo: ", NamedTextColor.YELLOW)
                    .append(Component.text(l.type().name(), NamedTextColor.WHITE)));
                player.sendMessage(Component.text("  Enviada: ", NamedTextColor.YELLOW)
                    .append(Component.text(DATE_FMT.format(new Date(l.sentAt())), NamedTextColor.WHITE)));
            }));
        return true;
    }

    // -----------------------------------------------------------------------
    // /carta moderar  (Phase 1: exibe últimas entradas do audit_log flagradas)
    // -----------------------------------------------------------------------

    private boolean handleModerate(Player player) {
        var msgs = plugin.getMessages();
        if (!player.hasPermission("nemonicmail.admin")) {
            player.sendMessage(msgs.prefixed("general.no-permission"));
            return true;
        }

        letterManager.getRecentAuditLog(10)
            .thenAccept(entries -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(Component.text("═══ ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("Log de Moderação (últimas 10)", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text(" ═══", NamedTextColor.DARK_GRAY)));

                if (entries.isEmpty()) {
                    player.sendMessage(Component.text("  Nenhum evento registrado.", NamedTextColor.GRAY));
                    return;
                }
                for (AuditEntry e : entries) {
                    String date = DATE_FMT.format(new Date(e.eventTime()));
                    String actor = e.actorName() != null ? e.actorName() : "sistema";
                    player.sendMessage(Component.text("  [" + date + "] ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(e.eventType(), NamedTextColor.RED))
                        .append(Component.text(" | " + actor, NamedTextColor.GRAY))
                        .append(Component.text(" → " + (e.targetId() != null ? e.targetId() : "-"),
                                NamedTextColor.YELLOW)));
                    if (e.detail() != null && !e.detail().isBlank())
                        player.sendMessage(Component.text("    " + e.detail(), NamedTextColor.GRAY));
                }
            }));
        return true;
    }

    // -----------------------------------------------------------------------
    // Help
    // -----------------------------------------------------------------------

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("═══ ", NamedTextColor.DARK_GRAY)
            .append(Component.text("NemonicMail ", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text("═══", NamedTextColor.DARK_GRAY)));
        player.sendMessage(Component.text("/carta escrever <jogador> [anon|urgente|oficial]", NamedTextColor.YELLOW)
            .append(Component.text(" — Escrever uma carta", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/carta caixa", NamedTextColor.YELLOW)
            .append(Component.text(" — Ver sua caixa postal", NamedTextColor.GRAY)));
        if (player.hasPermission("nemonicmail.broadcast"))
            player.sendMessage(Component.text("/carta todos [urgente|oficial]", NamedTextColor.YELLOW)
                .append(Component.text(" — Enviar anúncio para todos", NamedTextColor.GRAY)));
        if (plugin.getMapImageManager() != null) {
            if (player.hasPermission("nemonicmail.image.url") || player.hasPermission("nemonicmail.image.upload"))
                player.sendMessage(Component.text("/carta imagem url|upload", NamedTextColor.YELLOW)
                    .append(Component.text(" — Anexar imagem a uma carta", NamedTextColor.GRAY)));
            player.sendMessage(Component.text("/carta imagem status|cancelar|confirmar", NamedTextColor.YELLOW)
                .append(Component.text(" — Gerenciar imagem pendente", NamedTextColor.GRAY)));
        }
        if (player.hasPermission("nemonicmail.admin"))
            player.sendMessage(Component.text("/carta admin | /carta moderar", NamedTextColor.YELLOW)
                .append(Component.text(" — Administração", NamedTextColor.GRAY)));
        if (player.hasPermission("nemonicmail.reload"))
            player.sendMessage(Component.text("/carta reload", NamedTextColor.YELLOW)
                .append(Component.text(" — Recarregar configurações", NamedTextColor.GRAY)));
    }

    private void sendImageHelp(Player player) {
        player.sendMessage(Component.text("═══ ", NamedTextColor.DARK_GRAY)
            .append(Component.text("Imagens em Cartas", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text(" ═══", NamedTextColor.DARK_GRAY)));
        if (player.hasPermission("nemonicmail.image.url"))
            player.sendMessage(Component.text("/carta imagem url <url> [1x1|2x1|2x2]", NamedTextColor.YELLOW)
                .append(Component.text(" — Processar imagem via URL HTTPS", NamedTextColor.GRAY)));
        if (player.hasPermission("nemonicmail.image.upload"))
            player.sendMessage(Component.text("/carta imagem upload", NamedTextColor.YELLOW)
                .append(Component.text(" — Fazer upload pelo navegador", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/carta imagem confirmar <token>", NamedTextColor.YELLOW)
            .append(Component.text(" — Confirmar imagem de upload", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/carta imagem cancelar", NamedTextColor.YELLOW)
            .append(Component.text(" — Remover imagem pendente", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/carta imagem status", NamedTextColor.YELLOW)
            .append(Component.text(" — Ver se há imagem pendente", NamedTextColor.GRAY)));
    }

    private void sendAdminHelp(Player player) {
        player.sendMessage(Component.text("═══ ", NamedTextColor.DARK_GRAY)
            .append(Component.text("Admin — NemonicMail", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text(" ═══", NamedTextColor.DARK_GRAY)));
        player.sendMessage(Component.text("/carta admin debug on|off", NamedTextColor.YELLOW)
            .append(Component.text(" — Ativar/desativar modo debug", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/carta admin ver <jogador>", NamedTextColor.YELLOW)
            .append(Component.text(" — Ver cartas de um jogador", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/carta admin quem <id>", NamedTextColor.YELLOW)
            .append(Component.text(" — Revelar remetente real (incluindo anônimos)", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/carta admin revogar <id>", NamedTextColor.YELLOW)
            .append(Component.text(" — Revogar/ocultar uma carta", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/carta moderar", NamedTextColor.YELLOW)
            .append(Component.text(" — Ver log de moderação", NamedTextColor.GRAY)));
    }

    // -----------------------------------------------------------------------
    // Tab completion
    // -----------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();

        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("escrever", "caixa", "ajuda"));
            if (player.hasPermission("nemonicmail.broadcast")) base.add("todos");
            if (plugin.getMapImageManager() != null) base.add("imagem");
            if (player.hasPermission("nemonicmail.admin")) { base.add("admin"); base.add("moderar"); }
            if (player.hasPermission("nemonicmail.reload")) base.add("reload");
            return base.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }

        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "escrever", "escreve", "write" ->
                    Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
                case "todos", "all", "broadcast" ->
                    List.of("urgente", "oficial").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
                case "imagem", "img", "image" -> {
                    List<String> subs = new ArrayList<>();
                    if (player.hasPermission("nemonicmail.image.url"))    subs.add("url");
                    if (player.hasPermission("nemonicmail.image.upload")) subs.add("upload");
                    subs.addAll(List.of("confirmar", "cancelar", "status"));
                    yield subs.stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
                }
                case "admin" ->
                    List.of("debug", "ver", "quem", "revogar").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
                default -> List.of();
            };
        }

        if (args.length == 3) {
            return switch (args[0].toLowerCase()) {
                case "escrever", "escreve", "write" ->
                    List.of("anon", "urgente", "oficial").stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase())).toList();
                case "admin" -> switch (args[1].toLowerCase()) {
                    case "debug" -> List.of("on", "off").stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase())).toList();
                    case "ver" -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                        .toList();
                    default -> List.of();
                };
                default -> List.of();
            };
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("imagem") && args[1].equalsIgnoreCase("url"))
            return List.of("1x1", "2x1", "1x2", "2x2").stream()
                .filter(s -> s.startsWith(args[3].toLowerCase())).toList();

        return List.of();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int[] parseGrid(String spec) {
        String[] parts = spec.toLowerCase().split("x");
        if (parts.length == 2) {
            try {
                int maxW = plugin.getConfig().getInt("image-rendering.max-grid-w", 2);
                int maxH = plugin.getConfig().getInt("image-rendering.max-grid-h", 2);
                int w = Math.max(1, Math.min(Integer.parseInt(parts[0]), maxW));
                int h = Math.max(1, Math.min(Integer.parseInt(parts[1]), maxH));
                return new int[]{ w, h };
            } catch (NumberFormatException ignored) {}
        }
        return new int[]{ 1, 1 };
    }

    private String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
