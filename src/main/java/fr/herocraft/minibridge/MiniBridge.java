package fr.herocraft.minibridge;

import fr.herocraft.minibridge.discord.DiscordBot;
import fr.herocraft.minibridge.discord.WebhookSender;
import fr.herocraft.minibridge.listeners.MinecraftListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
            getLogger().severe("Configuration invalide ! Vérifiez config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Démarrage du mode choisi (bot ou webhook)
        if (getConfig().getBoolean("use-webhook")) {
            webhookSender = new WebhookSender(this);
            getLogger().info("Mode Webhook activé.");
        } else {
            discordBot = new DiscordBot(this);
            if (!discordBot.start()) {
                getLogger().severe("Impossible de démarrer le bot Discord !");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("Bot Discord connecté.");
        }

        // Enregistrement des listeners Minecraft
        getServer().getPluginManager().registerEvents(new MinecraftListener(this), this);

        // Message de démarrage vers Discord
        sendToDiscord(getConfig().getString("messages.server-start", "🟢 Serveur démarré !"));

        getLogger().info("MiniBridge activé avec succès !");
    }

    @Override
    public void onDisable() {
        sendToDiscord(getConfig().getString("messages.server-stop", "🔴 Serveur arrêté."));

        if (discordBot != null) {
            discordBot.stop();
        }

        getLogger().info("MiniBridge désactivé.");
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
            sender.sendMessage("§aMiniBridge rechargé !");
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
     * Envoie un message formaté vers les joueurs Minecraft
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
                getLogger().severe("webhook-url non configuré dans config.yml !");
                return false;
            }
        } else {
            String token = getConfig().getString("bot-token", "");
            String channelId = getConfig().getString("channel-id", "");
            if (token.equals("VOTRE_TOKEN_ICI") || token.isEmpty()) {
                getLogger().severe("bot-token non configuré dans config.yml !");
                return false;
            }
            if (channelId.equals("VOTRE_CHANNEL_ID_ICI") || channelId.isEmpty()) {
                getLogger().severe("channel-id non configuré dans config.yml !");
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
