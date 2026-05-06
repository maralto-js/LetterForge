package com.nemonicmail.gui;

import com.nemonicmail.model.Letter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CaixaCorreioGUI implements InventoryHolder {

    private static final int ROWS = 6;
    private static final int LETTER_SLOTS = 45; // slots 0-44
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final Player player;
    private final List<Letter> letters;
    private int page;
    private Inventory inventory;

    public CaixaCorreioGUI(Player player, List<Letter> letters, int page) {
        this.player = player;
        this.letters = new ArrayList<>(letters);
        this.page = page;
        build();
    }

    private void build() {
        int totalPages = Math.max(1, (int) Math.ceil((double) letters.size() / LETTER_SLOTS));
        this.page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = Component.text("✉ Caixa Postal", NamedTextColor.DARK_GRAY)
                .append(Component.text(" — Pág. " + (page + 1) + "/" + totalPages, NamedTextColor.GRAY));

        inventory = Bukkit.createInventory(this, ROWS * 9, title);

        // Letras na pagina atual
        int start = page * LETTER_SLOTS;
        int end = Math.min(start + LETTER_SLOTS, letters.size());
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, buildLetterItem(letters.get(i)));
        }

        // Barra inferior — decoracao
        ItemStack pane = buildPane();
        for (int i = 45; i < 54; i++) inventory.setItem(i, pane);

        // Botao anterior
        if (page > 0) inventory.setItem(45, buildNav("← Anterior", NamedTextColor.YELLOW));

        // Informacoes centrais
        inventory.setItem(49, buildInfo(letters));

        // Botao proximo
        if (page < totalPages - 1) inventory.setItem(53, buildNav("Próxima →", NamedTextColor.YELLOW));
    }

    public void open() {
        player.openInventory(inventory);
    }

    public void refresh() {
        build();
        player.openInventory(inventory);
    }

    /** Retorna a carta correspondente ao slot clicado, ou null. */
    public Letter getLetterAt(int slot) {
        if (slot < 0 || slot >= LETTER_SLOTS) return null;
        int index = page * LETTER_SLOTS + slot;
        if (index >= letters.size()) return null;
        return letters.get(index);
    }

    public boolean isNavPrev(int slot) { return slot == 45 && page > 0; }
    public boolean isNavNext(int slot) {
        int totalPages = Math.max(1, (int) Math.ceil((double) letters.size() / LETTER_SLOTS));
        return slot == 53 && page < totalPages - 1;
    }

    public void goNext() { page++; refresh(); }
    public void goPrev() { page--; refresh(); }

    public void markLocalRead(UUID letterId) {
        letters.replaceAll(l -> l.id().equals(letterId) ? l.withRead(true) : l);
    }

    @Override
    public Inventory getInventory() { return inventory; }

    // --- Builders de item ---

    private ItemStack buildLetterItem(Letter letter) {
        Material mat = getMaterial(letter);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        // Nome
        NamedTextColor nameColor = letter.read() ? NamedTextColor.GRAY : NamedTextColor.WHITE;
        Component typeTag = switch (letter.type()) {
            case URGENT    -> Component.text("[URGENTE] ", NamedTextColor.RED);
            case OFFICIAL  -> Component.text("[OFICIAL] ", NamedTextColor.GOLD);
            case BROADCAST -> Component.text("[AVISO] ",   NamedTextColor.YELLOW);
            case ANONYMOUS -> Component.text("[ANÔNIMO] ", NamedTextColor.GRAY);
            default        -> Component.empty();
        };

        meta.displayName(typeTag
                .append(Component.text("De: " + letter.displaySender(), nameColor))
                .decoration(TextDecoration.ITALIC, false));

        // Lore
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Data: " + DATE_FMT.format(Instant.ofEpochMilli(letter.sentAt())),
                NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Páginas: " + letter.pages().size(), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        if (!letter.read()) {
            lore.add(Component.text("● Não lida", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("✓ Lida", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Clique para ler", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Material getMaterial(Letter letter) {
        if (letter.read()) return Material.MAP;
        return switch (letter.type()) {
            case URGENT, OFFICIAL -> Material.WRITTEN_BOOK;
            case BROADCAST        -> Material.BOOK;
            case ANONYMOUS        -> Material.GRAY_DYE;
            default               -> Material.PAPER;
        };
    }

    private ItemStack buildPane() {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        pane.setItemMeta(meta);
        return pane;
    }

    private ItemStack buildNav(String label, NamedTextColor color) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, color, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildInfo(List<Letter> all) {
        long unread = all.stream().filter(l -> !l.read()).count();
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Caixa Postal", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Total: " + all.size(), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Não lidas: " + unread, NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }
}
