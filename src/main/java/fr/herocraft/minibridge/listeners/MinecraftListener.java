package fr.herocraft.minibridge.listeners;

import fr.herocraft.minibridge.MiniBridge;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class MinecraftListener implements Listener {

    private final MiniBridge plugin;

    public MinecraftListener(MiniBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("messages.chat", true)) return;

        String player = event.getPlayer().getName();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Filtrage basique anti-spam/injection
        message = sanitize(message);

        if (plugin.getConfig().getBoolean("messages.chat-avatar", true)) {
            // Affiche le message avec le skin du joueur (pseudo+avatar en mode webhook,
            // ou icône dans un embed en mode bot)
            plugin.sendPlayerChatMessage(player, message);
        } else {
            String format = plugin.getConfig().getString("messages.format-chat", "💬 **{player}** : {message}");
            plugin.sendToDiscord(format
                    .replace("{player}", player)
                    .replace("{message}", message));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("messages.join", true)) return;

        String player = event.getPlayer().getName();
        String format = plugin.getConfig().getString("messages.format-join", "✅ **{player}** a rejoint le serveur");
        String message = format.replace("{player}", player);

        if (plugin.getConfig().getBoolean("messages.join-avatar", true)) {
            // Envoie un embed avec la tête du skin du joueur en vignette
            plugin.sendPlayerAvatarEmbed(message, player);
        } else {
            plugin.sendToDiscord(message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("messages.quit", true)) return;

        String format = plugin.getConfig().getString("messages.format-quit", "❌ **{player}** a quitté le serveur");
        plugin.sendToDiscord(format.replace("{player}", event.getPlayer().getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("messages.death", true)) return;

        String player = event.getEntity().getName();
        String deathMsg = event.getDeathMessage() != null
                ? PlainTextComponentSerializer.plainText().serialize(event.deathMessage())
                : player + " est mort";

        String format = plugin.getConfig().getString("messages.format-death", "💀 **{player}** est mort : {message}");
        plugin.sendToDiscord(format
                .replace("{player}", player)
                .replace("{message}", sanitize(deathMsg)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.getConfig().getBoolean("messages.advancement", true)) return;

        // Ignorer les advancements de recettes
        String key = event.getAdvancement().getKey().getKey();
        if (key.startsWith("recipes/")) return;

        String player = event.getPlayer().getName();
        String advancement = event.getAdvancement().getKey().getKey()
                .replace("/", " › ")
                .replace("_", " ");

        String format = plugin.getConfig().getString("messages.format-advancement",
                "🏆 **{player}** a obtenu : **{advancement}**");
        plugin.sendToDiscord(format
                .replace("{player}", player)
                .replace("{advancement}", advancement));
    }

    /**
     * Nettoyage basique : supprime les balises Discord et les caractères de contrôle
     */
    private String sanitize(String input) {
        if (input == null) return "";
        return input
                .replace("@everyone", "@\u200beveryone")
                .replace("@here", "@\u200bhere")
                .replaceAll("[\\p{Cntrl}&&[^\n]]", "") // supprime les caractères de contrôle sauf newline
                .trim();
    }
}
