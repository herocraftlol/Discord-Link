package fr.herocraft.minibridge.discord;

import fr.herocraft.minibridge.MiniBridge;

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
        this.plugin = plugin;
        this.webhookUrl = plugin.getConfig().getString("webhook-url");
        this.username = plugin.getConfig().getString("webhook-username", "MiniBridge");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MiniBridge-Webhook");
            t.setDaemon(true);
            return t;
        });
    }

    public void send(String content) {
        executor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "MiniBridge/1.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                String escaped = content
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n");

                String body = "{\"username\":\"" + username + "\",\"content\":\"" + escaped + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code >= 400 && plugin.isDebug()) {
                    plugin.getLogger().warning("Webhook erreur " + code);
                }
                conn.disconnect();
            } catch (Exception e) {
                if (plugin.isDebug()) plugin.getLogger().log(Level.WARNING, "Erreur webhook", e);
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
