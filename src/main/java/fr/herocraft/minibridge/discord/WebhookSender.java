package fr.herocraft.minibridge.discord;

import fr.herocraft.minibridge.MiniBridge;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Envoi de messages via Webhook Discord.
 * Plus simple que le bot complet, mais ne peut pas lire les messages entrants.
 */
public class WebhookSender {

    private final MiniBridge plugin;
    private final String webhookUrl;
    private final String username;
    private final ExecutorService executor;

    public WebhookSender(MiniBridge plugin) {
        this(plugin, plugin.getConfig().getString("webhook-url"), plugin.getConfig().getString("webhook-username", "MiniBridge"));
    }

    public WebhookSender(MiniBridge plugin, String webhookUrl, String username) {
        this.plugin = plugin;
        this.webhookUrl = webhookUrl;
        this.username = username;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MiniBridge-Webhook");
            t.setDaemon(true);
            return t;
        });
    }

    public void send(String content) {
        JSONObject payload = new JSONObject();
        payload.put("username", username);
        payload.put("content", content);
        sendPayload(payload);
    }

    /**
     * Envoie un message texte "en tant que" le joueur : le webhook prend son pseudo
     * et sa tête de skin comme avatar. C'est le rendu le plus naturel pour le chat.
     *
     * @param playerName nom du joueur, utilisé comme pseudo du message
     * @param avatarUrl  URL de la tête du skin du joueur
     * @param content    contenu du message
     */
    @SuppressWarnings("unchecked")
    public void sendAsPlayer(String playerName, String avatarUrl, String content) {
        JSONObject payload = new JSONObject();
        payload.put("username", playerName);
        if (avatarUrl != null && !avatarUrl.isEmpty()) payload.put("avatar_url", avatarUrl);
        payload.put("content", content);
        sendPayload(payload);
    }

    /**
     * Envoie un embed via le webhook (utilisé pour afficher le skin d'un joueur : connexion, mort...).
     *
     * @param authorName    nom affiché en tête de l'embed (avec petite icône), peut être null
     * @param authorIconUrl URL de l'icône affichée à côté de authorName, peut être null
     * @param description   texte de l'embed (supporte le markdown Discord, ex: **gras**)
     * @param thumbnailUrl  URL de l'image affichée en grande vignette (ex: tête du skin du joueur), peut être null
     * @param color         couleur de la barre latérale de l'embed (format décimal, ex: 0x57F287)
     */
    @SuppressWarnings("unchecked")
    public void sendEmbed(String authorName, String authorIconUrl, String description, String thumbnailUrl, int color) {
        JSONObject embed = new JSONObject();
        if (description != null && !description.isEmpty()) embed.put("description", description);
        embed.put("color", color);

        if (authorName != null && !authorName.isEmpty()) {
            JSONObject author = new JSONObject();
            author.put("name", authorName);
            if (authorIconUrl != null && !authorIconUrl.isEmpty()) author.put("icon_url", authorIconUrl);
            embed.put("author", author);
        }

        if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
            JSONObject thumbnail = new JSONObject();
            thumbnail.put("url", thumbnailUrl);
            embed.put("thumbnail", thumbnail);
        }

        JSONArray embeds = new JSONArray();
        embeds.add(embed);

        JSONObject payload = new JSONObject();
        payload.put("username", username);
        payload.put("embeds", embeds);
        sendPayload(payload);
    }

    private void sendPayload(JSONObject payload) {
        executor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "MiniBridge/1.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                String body = payload.toJSONString();
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code >= 400) {
                    String response = "";
                    InputStream errStream = conn.getErrorStream();
                    if (errStream != null) response = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                    plugin.getLogger().warning("Webhook Discord erreur " + code + " : " + response
                            + " (vérifie webhook-url dans config.yml, et que le webhook n'a pas été supprimé sur Discord)");
                }
                conn.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("Impossible de contacter Discord (webhook) : " + e);
                if (plugin.isDebug()) plugin.getLogger().log(Level.WARNING, "Erreur webhook (détail)", e);
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
