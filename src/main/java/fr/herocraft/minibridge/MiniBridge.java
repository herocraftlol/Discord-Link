package fr.herocraft.minibridge;

import fr.herocraft.minibridge.discord.DiscordBot;
import fr.herocraft.minibridge.discord.WebhookSender;
import fr.herocraft.minibridge.listeners.MinecraftListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class MiniBridge extends JavaPlugin {

    private static MiniBridge instance;
    private DiscordBot discordBot;
    private WebhookSender webhookSender;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Validation de la config
        if (!validateConfig()) {
            getLogger().severe("Configuration invalide ! Verifiez config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Demarrage du mode choisi (bot ou webhook)
        if (getConfig().getBoolean("use-webhook")) {
            webhookSender = new WebhookSender(this);
            getLogger().info("Mode Webhook active.");
        } else {
            discordBot = new DiscordBot(this);
            if (!discordBot.start()) {
                getLogger().severe("Impossible de demarrer le bot Discord !");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("Bot Discord connecte.");
        }

        // Enregistrement des listeners Minecraft
        getServer().getPluginManager().registerEvents(new MinecraftListener(this), this);

        // Message de demarrage vers Discord
        sendToDiscord(getConfig().getString("messages.server-start", "🟢 Serveur demarre !"));

        getLogger().info("MiniBridge active avec succes !");
    }

    @Override
    public void onDisable() {
        sendToDiscord(getConfig().getString("messages.server-stop", "🔴 Serveur arrete."));

        if (discordBot != null) {
            discordBot.stop();
        }

        getLogger().info("MiniBridge desactive.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("minibridge")) return false;

        if (!sender.hasPermission("minibridge.admin")) {
            sender.sendMessage("§cVous n'avez pas la permission.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§aMiniBridge recharge !");
        } else {
            sender.sendMessage("§eUsage: /minibridge reload");
        }
        return true;
    }

    /**
     * Envoie un message vers Discord (bot ou webhook selon la config)
     */
    public void sendToDiscord(String message) {
        if (message == null || message.isEmpty()) return;

        if (getConfig().getBoolean("use-webhook")) {
            if (webhookSender != null) webhookSender.send(message);
        } else {
            if (discordBot != null) discordBot.sendMessage(message);
        }
    }

    /**
     * Envoie un message vers Discord avec l'avatar du joueur
     * @param player Le joueur Minecraft (pour l'avatar)
     * @param message Le message a envoyer
     */
    public void sendToDiscord(Player player, String message) {
        if (message == null || message.isEmpty()) return;

        String avatarUrl = getPlayerAvatarUrl(player);
        String displayName = player.getName();

        if (getConfig().getBoolean("use-webhook")) {
            if (webhookSender != null) webhookSender.send(displayName, avatarUrl, message);
        } else {
            if (discordBot != null) discordBot.sendMessage(displayName, avatarUrl, message);
        }
    }

    /**
     * Retourne l'URL de l'avatar du joueur pour Discord
     * Utilise mc-heads.net pour obtenir une image du skin
     */
    public String getPlayerAvatarUrl(Player player) {
        // URL de l'avatar du joueur (8-bit style)
        // Options: mc-heads.net, mineskin.eu, crafatar.com
        String skinService = getConfig().getString("avatar-service", "mc-heads");
        
        return switch (skinService.toLowerCase()) {
            case "mineskin" -> "https://mineskin.eu/avatar/" + player.getUniqueId() + "/100.png";
            case "crafatar" -> "https://crafatar.com/avatars/" + player.getUniqueId() + "?size=128";
            case "mc-heads" -> "https://mc-heads.net/avatar/" + player.getName() + "/128.png";
            default -> "https://mc-heads.net/avatar/" + player.getName() + "/128.png";
        };
    }

    /**
     * Envoie un message formate vers les joueurs Minecraft
     */
    public void sendToMinecraft(String discordUser, String message) {
        String format = getConfig().getString("discord-to-minecraft.format", "&9[Discord] &f{user}&7: &f{message}");
        String formatted = format
                .replace("{user}", discordUser)
                .replace("{message}", message);

        // Conversion des codes couleur Bukkit
        String colored = formatted.replace("&", "§");
        getServer().broadcastMessage(colored);
    }

    private boolean validateConfig() {
        boolean useWebhook = getConfig().getBoolean("use-webhook");

        if (useWebhook) {
            String url = getConfig().getString("webhook-url", "");
            if (url.equals("VOTRE_WEBHOOK_URL_ICI") || url.isEmpty()) {
                getLogger().severe("webhook-url non configure dans config.yml !");
                return false;
            }
        } else {
            String token = getConfig().getString("bot-token", "");
            String channelId = getConfig().getString("channel-id", "");
            if (token.equals("VOTRE_TOKEN_ICI") || token.isEmpty()) {
                getLogger().severe("bot-token non configure dans config.yml !");
                return false;
            }
            if (channelId.equals("VOTRE_CHANNEL_ID_ICI") || channelId.isEmpty()) {
                getLogger().severe("channel-id non configure dans config.yml !");
                return false;
            }
        }
        return true;
    }

    public static MiniBridge getInstance() {
        return instance;
    }

    public boolean isDebug() {
        return getConfig().getBoolean("debug", false);
    }
}
