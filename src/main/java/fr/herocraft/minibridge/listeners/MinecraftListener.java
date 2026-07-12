package fr.herocraft.minibridge.listeners;

import fr.herocraft.minibridge.MiniBridge;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
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

        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Filtrage basique anti-spam/injection
        message = sanitize(message);

        String format = plugin.getConfig().getString("messages.format-chat", "💬 **{player}** : {message}");
        String content = format
                .replace("{player}", player.getName())
                .replace("{message}", message);
        
        // Envoi avec avatar du joueur
        plugin.sendToDiscord(player, content);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("messages.join", true)) return;

        Player player = event.getPlayer();
        String format = plugin.getConfig().getString("messages.format-join", "✅ **{player}** a rejoint le serveur");
        String content = format.replace("{player}", player.getName());
        
        plugin.sendToDiscord(player, content);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("messages.quit", true)) return;

        Player player = event.getPlayer();
        String format = plugin.getConfig().getString("messages.format-quit", "❌ **{player}** a quitté le serveur");
        String content = format.replace("{player}", player.getName());
        
        plugin.sendToDiscord(player, content);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("messages.death", true)) return;

        Player player = event.getEntity();
        // Paper 1.21 - getDeathMessage() may return String or Component depending on context
        String deathMsgRaw = event.getDeathMessage();
        String deathMsg;
        if (deathMsgRaw != null) {
            // Check if it's already a String
            deathMsg = deathMsgRaw;
        } else {
            deathMsg = player.getName() + " est mort";
        }

        String format = plugin.getConfig().getString("messages.format-death", "[player] est mort : {message}");
        String content = format
                .replace("{player}", player.getName())
                .replace("{message}", sanitize(deathMsg));
        
        plugin.sendToDiscord(player, content);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.getConfig().getBoolean("messages.advancement", true)) return;

        // Ignorer les advancements de recettes
        String key = event.getAdvancement().getKey().getKey();
        if (key.startsWith("recipes/")) return;

        Player player = event.getPlayer();
        String advancement = event.getAdvancement().getKey().getKey()
                .replace("/", " › ")
                .replace("_", " ");

        String format = plugin.getConfig().getString("messages.format-advancement",
                "🏆 **{player}** a obtenu : **{advancement}**");
        String content = format
                .replace("{player}", player.getName())
                .replace("{advancement}", advancement);
        
        plugin.sendToDiscord(player, content);
    }

    /**
     * Nettoyage basique : supprime les balises Discord et les caractères de contrôle
     */
    private String sanitize(String input) {
        if (input == null) return "";
        return input
                .replace("@everyone", "@\u200beveryone")
                .replace("@here", "@\u200bhere")
                .replaceAll("[\\p{Cntrl}&&[^\n]]", "")
                .trim();
    }
}
