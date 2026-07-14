package fr.herocraft.minibridge;

import fr.herocraft.minibridge.discord.DiscordBot;
import fr.herocraft.minibridge.discord.WebhookSender;
import fr.herocraft.minibridge.listeners.ConsoleRelay;
import fr.herocraft.minibridge.listeners.MinecraftListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class MiniBridge extends JavaPlugin {

    private static MiniBridge instance;
    private DiscordBot discordBot;
    private WebhookSender webhookSender;
    private WebhookSender consoleWebhookSender;
    private ConsoleRelay consoleRelay;

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

        // Channel console (optionnel) : relaie les logs du serveur vers Discord
        if (getConfig().getBoolean("console.enabled", false)) {
            if (getConfig().getBoolean("use-webhook")) {
                String consoleWebhookUrl = getConfig().getString("console.webhook-url", "");
                if (consoleWebhookUrl.isEmpty()) {
                    getLogger().warning("console.enabled est activé mais console.webhook-url est vide : le channel console est ignoré.");
                } else {
                    consoleWebhookSender = new WebhookSender(this, consoleWebhookUrl,
                            getConfig().getString("webhook-username", "MiniBridge") + " Console");
                }
            } else if (getConfig().getString("console.channel-id", "").isEmpty()) {
                getLogger().warning("console.enabled est activé mais console.channel-id est vide : le channel console est ignoré.");
            }

            if (consoleWebhookSender != null || !getConfig().getString("console.channel-id", "").isEmpty()) {
                consoleRelay = new ConsoleRelay(this);
                consoleRelay.register();
                getLogger().info("Relais console Discord activé.");
            }
        }

        // Message de démarrage vers Discord
        sendToDiscord(getConfig().getString("messages.server-start", "🟢 Serveur démarré !"));

        getLogger().info("MiniBridge activé avec succès !");
    }

    @Override
    public void onDisable() {
        sendToDiscord(getConfig().getString("messages.server-stop", "🔴 Serveur arrêté."));

        if (consoleRelay != null) {
            consoleRelay.unregister();
            consoleRelay.stop();
        }
        if (consoleWebhookSender != null) {
            consoleWebhookSender.shutdown();
        }
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
     * Envoie un embed Discord affichant le skin (tête) d'un joueur, par exemple à la connexion.
     * Fonctionne aussi bien en mode bot qu'en mode webhook.
     *
     * @param description texte de l'embed (markdown Discord supporté, ex: **{player}**)
     * @param playerName  nom du joueur, utilisé pour générer l'URL de l'avatar
     */
    public void sendPlayerAvatarEmbed(String description, String playerName) {
        String avatarUrl = getPlayerAvatarUrl(playerName);
        int color = getEmbedColor();

        if (getConfig().getBoolean("use-webhook")) {
            if (webhookSender != null) webhookSender.sendEmbed(null, null, description, avatarUrl, color);
        } else {
            if (discordBot != null) discordBot.sendEmbed(null, null, description, avatarUrl, color);
        }
    }

    /**
     * Envoie un message de chat vers Discord en affichant le skin du joueur.
     * En mode webhook : le message apparaît directement avec le pseudo et la tête du
     * joueur comme avatar (rendu le plus naturel). En mode bot : envoyé en embed avec
     * une petite icône (skin) à côté du pseudo, car un bot ne peut pas changer d'avatar
     * par message.
     *
     * @param playerName nom du joueur
     * @param message    contenu du message de chat
     */
    public void sendPlayerChatMessage(String playerName, String message) {
        String avatarUrl = getPlayerAvatarUrl(playerName);

        if (getConfig().getBoolean("use-webhook")) {
            if (webhookSender != null) webhookSender.sendAsPlayer(playerName, avatarUrl, message);
        } else {
            if (discordBot != null) discordBot.sendEmbed(playerName, avatarUrl, message, null, getEmbedColor());
        }
    }

    private String getPlayerAvatarUrl(String playerName) {
        String avatarTemplate = getConfig().getString("skins.avatar-url", "https://mc-heads.net/avatar/{player}/100");
        return avatarTemplate.replace("{player}", playerName);
    }

    private int getEmbedColor() {
        try {
            return Integer.parseInt(getConfig().getString("skins.embed-color", "57F287"), 16);
        } catch (NumberFormatException e) {
            return 0x57F287;
        }
    }

    /**
     * Envoie un message vers le channel console dédié (bot ou webhook selon la config).
     * Ne fait rien si le channel console n'est pas configuré/activé.
     */
    public void sendConsoleToDiscord(String content) {
        if (content == null || content.isEmpty()) return;

        if (getConfig().getBoolean("use-webhook")) {
            if (consoleWebhookSender != null) consoleWebhookSender.send(content);
        } else {
            if (discordBot != null) discordBot.sendConsoleMessage(content);
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
