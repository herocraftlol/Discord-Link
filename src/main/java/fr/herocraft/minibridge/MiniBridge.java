package fr.herocraft.minibridge;

import fr.herocraft.minibridge.discord.DiscordBot;
import fr.herocraft.minibridge.discord.WebhookSender;
import fr.herocraft.minibridge.listeners.MinecraftListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

        if (!validateConfig()) {
            getLogger().severe("Configuration invalide ! Verifiez config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

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

        getServer().getPluginManager().registerEvents(new MinecraftListener(this), this);
        sendToDiscord(getConfig().getString("messages.server-start", "Serveur demarre !"));
        getLogger().info("MiniBridge active avec succes !");
    }

    @Override
    public void onDisable() {
        sendToDiscord(getConfig().getString("messages.server-stop", "Serveur arrete."));
        if (discordBot != null) discordBot.stop();
        if (webhookSender != null) webhookSender.shutdown();
        getLogger().info("MiniBridge desactive.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("minibridge")) return false;

        if (!sender.hasPermission("minibridge.admin")) {
            sender.sendMessage("Vous n'avez pas la permission.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("MiniBridge recharge !");
        } else {
            sender.sendMessage("Usage: /minibridge reload");
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
     * Envoie un message formate vers les joueurs Minecraft via Adventure API (Paper 1.21)
     * Appele uniquement depuis le thread principal Bukkit.
     */
    public void sendToMinecraft(String discordUser, String message) {
        String format = getConfig().getString(
                "discord-to-minecraft.format", "&9[Discord] &f{user}&7: &f{message}");
        String formatted = format
                .replace("{user}", discordUser)
                .replace("{message}", message);

        // Adventure API — compatible Paper 1.21, remplace broadcastMessage() deprecie
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);
        getServer().broadcast(component);
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

    public static MiniBridge getInstance() { return instance; }
    public boolean isDebug() { return getConfig().getBoolean("debug", false); }
}
