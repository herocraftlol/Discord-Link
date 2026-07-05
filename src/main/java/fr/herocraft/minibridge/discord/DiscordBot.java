package fr.herocraft.minibridge.discord;

import fr.herocraft.minibridge.MiniBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
 * Aucune dependance externe (pas de JDA) — leger et sans fuite memoire Netty.
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

    private int heartbeatInterval = 45000;
    private ScheduledFuture<?> heartbeatTask;
    private int lastSequence = -1;
    private boolean identified = false;

    private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService senderThread;

    public DiscordBot(MiniBridge plugin) {
        this.plugin = plugin;
        this.token = plugin.getConfig().getString("bot-token");
        this.channelId = plugin.getConfig().getString("channel-id");
        this.commandsEnabled = plugin.getConfig().getBoolean("discord-to-minecraft.commands-enabled", true);
        this.adminRoleId = plugin.getConfig().getString("discord-to-minecraft.admin-role-id", "");
        this.allowedCommands = plugin.getConfig().getStringList("discord-to-minecraft.allowed-commands");
        this.commandPrefix = plugin.getConfig().getString("command-prefix", "!");

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
            String gatewayUrl = getGatewayUrl();
            if (gatewayUrl == null) {
                plugin.getLogger().severe("Impossible de recuperer l'URL Gateway Discord. Token invalide ?");
                return false;
            }
            startSendQueue();
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
        plugin.getLogger().warning("Gateway Discord ferme (code " + statusCode + "): " + reason);
        identified = false;
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

            // Log tous les opcodes recus pour diagnostiquer
            plugin.getLogger().info("[MiniBridge DEBUG] Gateway op=" + op
                    + (payload.get("t") != null ? " t=" + payload.get("t") : ""));

            switch (op) {
                case 10 -> handleHello(payload);
                case 0  -> handleDispatch(payload);
                case 7  -> reconnect();
                case 9  -> {
                    // Op 9 = Invalid Session — Discord a rejete notre identification
                    JSONObject d9 = (JSONObject) payload.get("d");
                    plugin.getLogger().severe("[MiniBridge] Session invalide (op=9)! "
                            + "Verifiez votre bot-token et les intents sur le portail Discord. "
                            + "Resumable=" + d9);
                    identified = false;
                    scheduler.schedule(this::identify, 5, TimeUnit.SECONDS);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[MiniBridge] Erreur parsing Gateway: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleHello(JSONObject payload) {
        JSONObject d = (JSONObject) payload.get("d");
        heartbeatInterval = ((Number) d.get("heartbeat_interval")).intValue();

        if (heartbeatTask != null) heartbeatTask.cancel(false);
        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeat, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS
        );
        // Delai de 500ms pour laisser le WebSocket etre completement pret
        scheduler.schedule(this::identify, 500, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private void handleDispatch(JSONObject payload) {
        String eventName = (String) payload.get("t");
        JSONObject d = (JSONObject) payload.get("d");
        if (eventName == null || d == null) return;

        if ("READY".equals(eventName)) {
            identified = true;
            plugin.getLogger().info("Bot Discord identifie et pret !");
        } else if ("MESSAGE_CREATE".equals(eventName)) {
            handleMessageCreate(d);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessageCreate(JSONObject message) {
        // Ignorer les messages du bot lui-meme
        JSONObject author = (JSONObject) message.get("author");
        if (author == null) return;
        Boolean isBot = (Boolean) author.get("bot");
        if (Boolean.TRUE.equals(isBot)) return;

        String msgChannelId = (String) message.get("channel_id");
        String content = (String) message.get("content");
        String username = (String) author.get("username");

        // Log avant tout filtre pour diagnostiquer
        if (plugin.isDebug()) {
            plugin.getLogger().info("[MiniBridge DEBUG] MESSAGE_CREATE de=" + username
                    + " channel=" + msgChannelId + " (attendu=" + channelId + ")"
                    + " contenu=" + content);
        }

        // Verifier que c'est le bon channel
        if (!channelId.equals(msgChannelId)) return;

        if (content == null || content.isEmpty()) return;

        // Commandes Discord -> serveur
        if (commandsEnabled && content.startsWith(commandPrefix)) {
            String cmd = content.substring(commandPrefix.length()).trim().toLowerCase();

            // Verification du role admin — FIX: iteration manuelle au lieu de .contains()
            if (!adminRoleId.isEmpty()) {
                JSONObject member = (JSONObject) message.get("member");
                JSONArray roles = (member != null) ? (JSONArray) member.get("roles") : null;

                boolean hasRole = false;
                if (roles != null) {
                    for (Object roleObj : roles) {
                        if (adminRoleId.equals(String.valueOf(roleObj))) {
                            hasRole = true;
                            break;
                        }
                    }
                }

                if (!hasRole) {
                    sendMessage("Vous n'avez pas la permission d'utiliser les commandes.");
                    return;
                }
            }

            if (allowedCommands.contains(cmd)) {
                executeCommand(cmd, username);
            } else {
                sendMessage("Commande non autorisee. Commandes disponibles : "
                        + String.join(", ", allowedCommands));
            }
            return;
        }

        // Message normal Discord -> Minecraft — FIX: utilisation de l'API Adventure Paper
        if (plugin.getConfig().getBoolean("discord-to-minecraft.enabled", true)) {
            String format = plugin.getConfig().getString(
                    "discord-to-minecraft.format", "&9[Discord] &f{user}&7: &f{message}");
            String formatted = format
                    .replace("{user}", username)
                    .replace("{message}", content);

            // Conversion Adventure (Paper 1.21)
            Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);

            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.getServer().sendMessage(component)
            );
        }
    }

    private void executeCommand(String cmd, String discordUser) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("[MiniBridge] Commande Discord de " + discordUser + ": " + cmd);
            switch (cmd) {
                case "list" -> {
                    int online = Bukkit.getOnlinePlayers().size();
                    int max = Bukkit.getMaxPlayers();
                    String names = Bukkit.getOnlinePlayers().stream()
                            .map(p -> p.getName())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("aucun");
                    sendMessage("Joueurs en ligne (" + online + "/" + max + "): " + names);
                }
                case "tps" -> {
                    double[] tps = Bukkit.getServer().getTPS();
                    sendMessage(String.format("TPS: 1m: %.1f | 5m: %.1f | 15m: %.1f",
                            tps[0], tps[1], tps[2]));
                }
                case "time" -> {
                    long time = Bukkit.getWorlds().get(0).getTime();
                    String period = (time >= 0 && time < 12300) ? "Jour"
                            : (time < 13800) ? "Coucher de soleil"
                            : (time < 22200) ? "Nuit"
                            : "Lever de soleil";
                    sendMessage("Temps: " + time + " ticks - " + period);
                }
            }
        });
    }

    // ========================
    //   Gateway Senders
    // ========================

    @SuppressWarnings("unchecked")
    private void identify() {
        plugin.getLogger().info("[MiniBridge DEBUG] Envoi Identify (op=2)...");
        JSONObject payload = new JSONObject();
        payload.put("op", 2);

        JSONObject d = new JSONObject();
        d.put("token", token);
        d.put("intents", 33793); // GUILDS (1) + GUILD_MESSAGES (512) + MESSAGE_CONTENT (32768)

        JSONObject properties = new JSONObject();
        properties.put("os", "linux");
        properties.put("browser", "minibridge");
        properties.put("device", "minibridge");
        d.put("properties", properties);

        payload.put("d", d);

        try {
            webSocket.sendText(payload.toJSONString(), true).get(5, TimeUnit.SECONDS);
            plugin.getLogger().info("[MiniBridge DEBUG] Identify envoye avec succes.");
        } catch (Exception e) {
            plugin.getLogger().severe("[MiniBridge] Echec envoi Identify: " + e.getMessage());
        }
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
            plugin.getLogger().log(Level.WARNING, "Reconnexion echouee, nouvel essai dans 30s", e);
            scheduler.schedule(this::reconnect, 30, TimeUnit.SECONDS);
        }
    }

    // ========================
    //   REST API
    // ========================

    public void sendMessage(String content) {
        sendQueue.offer(content);
    }

    private void startSendQueue() {
        long delayMs = plugin.getConfig().getLong("send-delay-ms", 100);
        senderThread.scheduleWithFixedDelay(() -> {
            String msg = sendQueue.poll();
            if (msg != null) sendMessageNow(msg);
        }, 0, delayMs, TimeUnit.MILLISECONDS);
    }

    private void sendMessageNow(String content) {
        try {
            String url = API_BASE + "/channels/" + channelId + "/messages";
            HttpURLConnection conn = (HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bot " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "MiniBridge/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String escaped = content
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n");

            String body = "{\"content\":\"" + escaped + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 429) {
                sendQueue.offer(content); // Remettre en queue si rate limited
                if (plugin.isDebug()) plugin.getLogger().warning("Rate limited par Discord");
            } else if (code >= 400 && plugin.isDebug()) {
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) {
                    String err = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                    plugin.getLogger().warning("Discord API erreur " + code + ": " + err);
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            if (plugin.isDebug()) plugin.getLogger().log(Level.WARNING, "Erreur envoi Discord", e);
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
            plugin.getLogger().log(Level.WARNING, "Erreur recuperation Gateway URL", e);
            return null;
        }
    }
}
