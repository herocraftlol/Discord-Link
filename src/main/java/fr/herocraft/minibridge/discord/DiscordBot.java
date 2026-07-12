package fr.herocraft.minibridge.discord;

import fr.herocraft.minibridge.MiniBridge;
import org.bukkit.Bukkit;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * Bot Discord utilisant l'API HTTP et WebSocket natifs de Java 21.
 * Aucune dépendance externe (pas de JDA) — léger et sans fuite mémoire Netty.
 */
public class DiscordBot implements WebSocket.Listener {

    private static final String API_BASE = "https://discord.com/api/v10";

    private final MiniBridge plugin;
    private final String token;
    private final String channelId;
    private final boolean commandsEnabled;
    private final String adminRoleId;
    private final List<String> allowedCommands;
    private final String commandPrefix;

    private WebSocket webSocket;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    // Gestion du heartbeat Gateway
    private int heartbeatInterval = 45000;
    private ScheduledFuture<?> heartbeatTask;
    private int lastSequence = -1;
    private boolean identified = false;

    // Queue d'envoi pour respecter le rate limit Discord (payloads JSON complets : contenu ou embed)
    private final BlockingQueue<JSONObject> sendQueue = new LinkedBlockingQueue<>();
    // Queue séparée pour le channel console (log serveur), pour ne pas polluer le channel principal
    private final BlockingQueue<JSONObject> consoleSendQueue = new LinkedBlockingQueue<>();
    private final String consoleChannelId;
    private final ScheduledExecutorService senderThread;

    public DiscordBot(MiniBridge plugin) {
        this.plugin = plugin;
        this.token = plugin.getConfig().getString("bot-token");
        this.channelId = plugin.getConfig().getString("channel-id");
        this.commandsEnabled = plugin.getConfig().getBoolean("discord-to-minecraft.commands-enabled", true);
        this.adminRoleId = plugin.getConfig().getString("discord-to-minecraft.admin-role-id", "");
        this.allowedCommands = plugin.getConfig().getStringList("discord-to-minecraft.allowed-commands");
        this.commandPrefix = plugin.getConfig().getString("command-prefix", "!");
        this.consoleChannelId = plugin.getConfig().getString("console.channel-id", "");

        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MiniBridge-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.senderThread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MiniBridge-Sender");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean start() {
        try {
            // Récupération de l'URL Gateway
            String gatewayUrl = getGatewayUrl();
            if (gatewayUrl == null) {
                plugin.getLogger().severe("Impossible de récupérer l'URL Gateway Discord. Token invalide ?");
                return false;
            }

            // Démarrage de la queue d'envoi
            startSendQueue();

            // Connexion WebSocket
            webSocket = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(gatewayUrl + "/?v=10&encoding=json"), this)
                    .get(10, TimeUnit.SECONDS);

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Erreur connexion Gateway Discord", e);
            return false;
        }
    }

    public void stop() {
        if (heartbeatTask != null) heartbeatTask.cancel(true);
        scheduler.shutdownNow();
        senderThread.shutdownNow();
        if (webSocket != null) webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Server stopping");
    }

    // ========================
    //   WebSocket Listener
    // ========================

    private final StringBuilder messageBuffer = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            handleGatewayMessage(messageBuffer.toString());
            messageBuffer.setLength(0);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        plugin.getLogger().warning("Gateway Discord fermé (code " + statusCode + "): " + reason);
        identified = false;
        // Reconnexion automatique après 5s
        scheduler.schedule(this::reconnect, 5, TimeUnit.SECONDS);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        plugin.getLogger().log(Level.WARNING, "Erreur WebSocket Discord", error);
    }

    // ========================
    //   Gateway Protocol
    // ========================

    @SuppressWarnings("unchecked")
    private void handleGatewayMessage(String raw) {
        try {
            JSONParser parser = new JSONParser();
            JSONObject payload = (JSONObject) parser.parse(raw);

            int op = ((Number) payload.get("op")).intValue();
            Object seq = payload.get("s");
            if (seq instanceof Number) lastSequence = ((Number) seq).intValue();

            switch (op) {
                case 10 -> handleHello(payload); // Hello
                case 0  -> handleDispatch(payload); // Dispatch
                case 7  -> reconnect(); // Reconnect
                case 9  -> { // Invalid session
                    identified = false;
                    scheduler.schedule(this::identify, 3, TimeUnit.SECONDS);
                }
            }
        } catch (Exception e) {
            if (plugin.isDebug()) plugin.getLogger().log(Level.WARNING, "Erreur parsing Gateway", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleHello(JSONObject payload) {
        JSONObject d = (JSONObject) payload.get("d");
        heartbeatInterval = ((Number) d.get("heartbeat_interval")).intValue();

        // Démarrage du heartbeat
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeat, 0, heartbeatInterval, TimeUnit.MILLISECONDS
        );

        // Identification
        identify();
    }

    @SuppressWarnings("unchecked")
    private void handleDispatch(JSONObject payload) {
        String eventName = (String) payload.get("t");
        JSONObject d = (JSONObject) payload.get("d");
        if (eventName == null || d == null) return;

        if ("READY".equals(eventName)) {
            identified = true;
            plugin.getLogger().info("Bot Discord identifié et prêt !");
        } else if ("MESSAGE_CREATE".equals(eventName)) {
            handleMessageCreate(d);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessageCreate(JSONObject message) {
        // Ignorer les messages du bot lui-même
        JSONObject author = (JSONObject) message.get("author");
        if (author == null) return;
        Boolean isBot = (Boolean) author.get("bot");
        if (Boolean.TRUE.equals(isBot)) return;

        // Vérifier que c'est le bon channel
        String msgChannelId = (String) message.get("channel_id");
        if (!channelId.equals(msgChannelId)) return;

        String content = (String) message.get("content");
        String username = (String) author.get("username");
        if (content == null || content.isEmpty()) return;

        // Commandes Discord -> serveur
        if (commandsEnabled && content.startsWith(commandPrefix)) {
            String cmd = content.substring(commandPrefix.length()).trim().toLowerCase();

            // Vérification du rôle admin
            if (!adminRoleId.isEmpty()) {
                JSONArray roles = (JSONArray) message.get("member") != null
                        ? (JSONArray) ((JSONObject) message.get("member")).get("roles")
                        : new JSONArray();
                if (roles == null || !roles.contains(adminRoleId)) {
                    sendMessage("❌ Vous n'avez pas la permission d'utiliser les commandes.");
                    return;
                }
            }

            if (allowedCommands.contains(cmd)) {
                executeCommand(cmd, username);
            } else {
                sendMessage("❌ Commande non autorisée. Commandes disponibles : `"
                        + String.join("`, `", allowedCommands) + "`");
            }
            return;
        }

        // Message normal Discord -> Minecraft
        if (plugin.getConfig().getBoolean("discord-to-minecraft.enabled", true)) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.sendToMinecraft(username, content)
            );
        }
    }

    private void executeCommand(String cmd, String discordUser) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("[MiniBridge] Commande exécutée par Discord (" + discordUser + "): " + cmd);
            switch (cmd) {
                case "list" -> {
                    int online = Bukkit.getOnlinePlayers().size();
                    int max = Bukkit.getMaxPlayers();
                    String names = Bukkit.getOnlinePlayers().stream()
                            .map(p -> p.getName())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("aucun");
                    sendMessage("👥 **Joueurs en ligne (" + online + "/" + max + "):** " + names);
                }
                case "tps" -> {
                    double[] tps = Bukkit.getServer().getTPS();
                    sendMessage(String.format("⚡ **TPS:** 1m: %.1f | 5m: %.1f | 15m: %.1f",
                            tps[0], tps[1], tps[2]));
                }
                case "time" -> {
                    long time = Bukkit.getWorlds().get(0).getTime();
                    String period = (time >= 0 && time < 12300) ? "☀️ Jour"
                            : (time < 13800) ? "🌅 Coucher"
                            : (time < 22200) ? "🌙 Nuit"
                            : "🌄 Lever";
                    sendMessage("🕐 **Temps:** " + time + " ticks — " + period);
                }
            }
        });
    }

    // ========================
    //   Gateway Senders
    // ========================

    @SuppressWarnings("unchecked")
    private void identify() {
        JSONObject payload = new JSONObject();
        payload.put("op", 2);

        JSONObject d = new JSONObject();
        d.put("token", token);
        d.put("intents", 33280); // GUILD_MESSAGES (512) + MESSAGE_CONTENT (32768)

        JSONObject properties = new JSONObject();
        properties.put("os", "linux");
        properties.put("browser", "minibridge");
        properties.put("device", "minibridge");
        d.put("properties", properties);

        payload.put("d", d);
        sendRaw(payload.toJSONString());
    }

    @SuppressWarnings("unchecked")
    private void sendHeartbeat() {
        JSONObject payload = new JSONObject();
        payload.put("op", 1);
        payload.put("d", lastSequence == -1 ? null : lastSequence);
        sendRaw(payload.toJSONString());
    }

    private void sendRaw(String json) {
        if (webSocket != null) {
            webSocket.sendText(json, true);
        }
    }

    private void reconnect() {
        plugin.getLogger().info("Reconnexion au Gateway Discord...");
        try {
            if (webSocket != null) webSocket.abort();
            String gatewayUrl = getGatewayUrl();
            if (gatewayUrl != null) {
                webSocket = httpClient.newWebSocketBuilder()
                        .buildAsync(URI.create(gatewayUrl + "/?v=10&encoding=json"), this)
                        .get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Reconnexion échouée, nouvel essai dans 30s", e);
            scheduler.schedule(this::reconnect, 30, TimeUnit.SECONDS);
        }
    }

    // ========================
    //   REST API
    // ========================

    public void sendMessage(String content) {
        JSONObject payload = new JSONObject();
        payload.put("content", content);
        sendQueue.offer(payload);
    }

    /**
     * Envoie un message vers le channel console dédié (log serveur).
     * Ignoré silencieusement si console.channel-id n'est pas configuré.
     */
    public void sendConsoleMessage(String content) {
        if (consoleChannelId == null || consoleChannelId.isEmpty()) return;
        JSONObject payload = new JSONObject();
        payload.put("content", content);
        consoleSendQueue.offer(payload);
    }

    /**
     * Envoie un embed Discord (utilisé pour afficher le skin d'un joueur : connexion, chat, mort...).
     *
     * @param authorName   nom affiché en tête de l'embed (avec petite icône), peut être null (ex: pour un join)
     * @param authorIconUrl URL de l'icône affichée à côté de authorName, peut être null
     * @param description  texte de l'embed (supporte le markdown Discord, ex: **gras**)
     * @param thumbnailUrl URL de l'image affichée en grande vignette (ex: tête du skin du joueur), peut être null
     * @param color        couleur de la barre latérale de l'embed (format décimal, ex: 0x57F287)
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
        payload.put("embeds", embeds);
        sendQueue.offer(payload);
    }

    private void startSendQueue() {
        long delayMs = plugin.getConfig().getLong("send-delay-ms", 100);
        senderThread.scheduleWithFixedDelay(() -> {
            JSONObject msg = sendQueue.poll();
            if (msg != null) sendMessageNow(channelId, msg, sendQueue);
        }, 0, delayMs, TimeUnit.MILLISECONDS);

        // Deuxième file dédiée au channel console (indépendante du rate-limit du channel principal)
        senderThread.scheduleWithFixedDelay(() -> {
            JSONObject msg = consoleSendQueue.poll();
            if (msg != null) sendMessageNow(consoleChannelId, msg, consoleSendQueue);
        }, 0, delayMs, TimeUnit.MILLISECONDS);
    }

    private void sendMessageNow(String targetChannelId, JSONObject payload, BlockingQueue<JSONObject> requeueTarget) {
        try {
            String url = API_BASE + "/channels/" + targetChannelId + "/messages";
            HttpURLConnection conn = (HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bot " + token);
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
            if (code == 429) { // Rate limited
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) {
                    String response = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                    if (plugin.isDebug()) plugin.getLogger().warning("Rate limited par Discord: " + response);
                }
                // Remettre dans la queue d'origine
                requeueTarget.offer(payload);
            } else if (code >= 400) {
                String response = "";
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) response = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                plugin.getLogger().warning("Discord API erreur " + code + " sur channel " + targetChannelId + " : " + response);
            }
            conn.disconnect();
        } catch (Exception e) {
            plugin.getLogger().warning("Impossible de contacter Discord (bot) : " + e);
            if (plugin.isDebug()) plugin.getLogger().log(Level.WARNING, "Erreur envoi Discord (détail)", e);
        }
    }

    private String getGatewayUrl() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new java.net.URL(API_BASE + "/gateway").openConnection();
            conn.setRequestProperty("Authorization", "Bot " + token);
            conn.setRequestProperty("User-Agent", "MiniBridge/1.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) return null;

            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(response);
            return (String) json.get("url");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Erreur récupération Gateway URL", e);
            return null;
        }
    }
}
