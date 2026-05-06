package com.nemonicmail.listener;

import com.nemonicmail.NemonicMail;
import com.nemonicmail.gui.CaixaCorreioGUI;
import com.nemonicmail.image.MapImageManager;
import com.nemonicmail.manager.LetterManager;
import com.nemonicmail.model.Letter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GUIListener implements Listener {

    private final NemonicMail      plugin;
    private final LetterManager    letterManager;
    private final MapImageManager  mapImageManager;

    public GUIListener(NemonicMail plugin, LetterManager letterManager, MapImageManager mapImageManager) {
        this.plugin          = plugin;
        this.letterManager   = letterManager;
        this.mapImageManager = mapImageManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof CaixaCorreioGUI gui)) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();

        if (gui.isNavPrev(slot)) { gui.goPrev(); return; }
        if (gui.isNavNext(slot)) { gui.goNext(); return; }

        Letter letter = gui.getLetterAt(slot);
        if (letter == null) return;

        openLetterBook(player, letter, gui);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CaixaCorreioGUI) {
            event.setCancelled(true);
        }
    }

    private void openLetterBook(Player player, Letter letter, CaixaCorreioGUI gui) {
        boolean wasUnread = !letter.read();

        if (wasUnread) {
            letterManager.markRead(player.getUniqueId(), letter.id(), letter.type());
            gui.markLocalRead(letter.id());
        }

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Carta");
        meta.setAuthor(letter.displaySender());

        String typeTag = switch (letter.type()) {
            case URGENT    -> plugin.getMessages().raw("tags.urgent") + "\n";
            case OFFICIAL  -> plugin.getMessages().raw("tags.official") + "\n";
            case BROADCAST -> plugin.getMessages().raw("tags.broadcast") + "\n";
            case ANONYMOUS -> plugin.getMessages().raw("tags.anonymous") + "\n";
            default        -> "";
        };

        List<Component> pages = new ArrayList<>();
        List<String> raw = letter.pages();
        if (!raw.isEmpty()) {
            String header = "De: " + letter.displaySender() + "\n" + typeTag + "---\n";
            pages.add(Component.text(header + raw.get(0)));
            for (int i = 1; i < raw.size(); i++) {
                pages.add(Component.text(raw.get(i)));
            }
        } else {
            pages.add(Component.text("(carta vazia)"));
        }

        meta.pages(pages);
        book.setItemMeta(meta);

        player.closeInventory();
        player.openBook(book);

        player.sendMessage(plugin.getMessages().prefixed("gui.letter-opened",
                Map.of("sender", letter.displaySender())));

        if (wasUnread && mapImageManager != null) {
            mapImageManager.giveAttachmentToPlayer(player, letter.id());
        }
    }
}
