package com.nemonicmail.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Carrega mensagens de messages.yml com suporte a:
 * - Codes de cor legado (&a, &c, &l, etc.)
 * - Placeholders {chave} substituídos por valores em tempo de execução
 *
 * Uso: plugin.getMessages().prefixed("write.book-ready", Map.of("player", "Steve"))
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private FileConfiguration cfg;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        // Fallback para os padrões embutidos no jar: saveResource(false) nunca sobrescreve o
        // arquivo do servidor, então chaves novas de updates não existem no messages.yml antigo
        // — sem isto o chat mostraria "[msg:chave nao encontrada]" após upgrade do plugin.
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {}
    }

    /** Retorna a string bruta (com &) da chave, sem conversão. */
    public String raw(String key) {
        return cfg.getString(key, "&c[msg:" + key + " nao encontrada]");
    }

    /** Retorna a string bruta com placeholders aplicados. */
    public String raw(String key, Map<String, String> placeholders) {
        String value = raw(key);
        for (var entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return value;
    }

    /** Converte a mensagem para Component (suporta & e placeholders). */
    public Component component(String key) {
        return LEGACY.deserialize(raw(key));
    }

    /** Converte a mensagem para Component com placeholders substituídos. */
    public Component component(String key, Map<String, String> placeholders) {
        return LEGACY.deserialize(raw(key, placeholders));
    }

    /** Retorna o Component do prefixo configurado. */
    public Component prefix() {
        return component("prefix");
    }

    /**
     * Retorna prefix + mensagem convertida.
     * Equivalente a: [Correio] &aMensagem...
     */
    public Component prefixed(String key) {
        return prefix().append(component(key));
    }

    public Component prefixed(String key, Map<String, String> placeholders) {
        return prefix().append(component(key, placeholders));
    }
}
