package fr.herocraft.minibridge.discord;

import fr.herocraft.minibridge.MiniBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;

public class DiscordBot extends WebSocketListener {

    private static final String API_BASE = "https://discord.com/api/v10";

    private final MiniBridge plugin;
    private final String token;
    private final String channelId;
    private final boolean commandsEnabled;
    private final String adminRoleId;
    private final List<String> allowedCommands;
    private final String commandPrefix;

    private OkHttpClient httpClient;
    private WebSocket webSocket;

    private int heartbeatInterval = 45000;
    private ScheduledFuture<?> heartbeatTask;
    private int lastSequence = -1;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MiniBridge-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService senderExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MiniBridge-Sender");
        t.setDaemon(true);
        return t;
    });

    public DiscordBot(MiniBridge plugin) {
        this.plugin = plugin;
        this.token = plugin.getConfig().getString("bot-token");
        this.channelId = plugin.getConfig().getString("channel-id");
        this.commandsEnabled = plugin.getConfig().getBoolean("discord-to-minecraft.commands-enabled", true);
        this.adminRoleId = plugin.getConfig().getString("discord-to-minecraft.admin-role-id", "");
        this.allowedCommands = plugin.getConfig().getStringList("discord-to-minecraft.allowed-commands");
        this.commandPrefix = plugin.getConfig().getString("command-prefix", "!");
    }

    public boolean start() {
        try {
            httpClient = new OkHttpClient.Builder()
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build();

            String gatewayUrl = getGatewayUrl();
            if (gatewayUrl == null) {
                plugin.getLogger().severe("[MiniBridge] Impossible d'obtenir l'URL Gateway. Token invalide ?");
                return false;
            }

            plugin.getLogger().info("[MiniBridge] Connexion au Gateway: " + gatewayUrl);

            Request request = new Request.Builder()
                    .url(gatewayUrl + "/?v=10&encoding=json")
                    .build();

            webSocket = httpClient.newWebSocket(request, this);

            // Demarrage de la queue d'envoi REST
            long delayMs = plugin.getConfig().getLong("send-delay-ms", 200);
            senderExecutor.scheduleWithFixedDelay(() -> {
                String msg = sendQueue.poll();
                if (msg != null) sendMessageRest(msg);
            }, 0, delayMs, TimeUnit.MILLISECONDS);

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[MiniBridge] Erreur demarrage bot", e);
            return false;
        }
    }

    public void stop() {
        if (heartbeatTask != null) heartbeatTask.cancel(true);
        scheduler.shutdownNow();
        senderExecutor.shutdownNow();
        if (webSocket != null) webSocket.close(1000, "Server stopping");
        if (httpClient != null) httpClient.dispatcher().executorService().shutdown();
    }

    // ========================
    //   OkHttp WebSocket callbacks
    // ========================

    @Override
    public void onOpen(WebSocket ws, Response response) {
        plugin.getLogger().info("[MiniBridge] WebSocket ouvert (HTTP " + response.code() + ")");
    }

    @Override
    public void onMessage(WebSocket ws, String text) {
        try {
            JSONParser parser = new JSONParser();
            JSONObject payload = (JSONObject) parser.parse(text);

            int op = ((Number) payload.get("op")).intValue();
            Object seq = payload.get("s");
            if (seq instanceof Number) lastSequence = ((Number) seq).intValue();

            plugin.getLogger().info("[MiniBridge DEBUG] op=" + op
                    + (payload.get("t") != null ? " t=" + payload.get("t") : ""));

            switch (op) {
                case 10 -> onHello(payload);
                case 11 -> {} // Heartbeat ACK — silencieux
                case 0  -> onDispatch(payload);
                case 7  -> reconnect();
                case 9  -> {
                    plugin.getLogger().severe("[MiniBridge] Session invalide (op=9) — token ou intents incorrects !");
                    scheduler.schedule(this::identify, 5, TimeUnit.SECONDS);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[MiniBridge] Erreur parsing Gateway", e);
        }
    }

    @Override
    public void onClosing(WebSocket ws, int code, String reason) {
        plugin.getLogger().warning("[MiniBridge] Gateway fermeture (code=" + code + "): " + reason);
        ws.close(1000, null);
    }

    @Override
    public void onClosed(WebSocket ws, int code, String reason) {
        plugin.getLogger().warning("[MiniBridge] Gateway ferme. Reconnexion dans 5s...");
        scheduler.schedule(this::reconnect, 5, TimeUnit.SECONDS);
    }

    @Override
    public void onFailure(WebSocket ws, Throwable t, Response response) {
        plugin.getLogger().warning("[MiniBridge] Erreur WebSocket: " + t.getMessage() + ". Reconnexion dans 10s...");
        scheduler.schedule(this::reconnect, 10, TimeUnit.SECONDS);
    }

    // ========================
    //   Gateway Protocol
    // ========================

    private void onHello(JSONObject payload) {
        JSONObject d = (JSONObject) payload.get("d");
        heartbeatInterval = ((Number) d.get("heartbeat_interval")).intValue();
        plugin.getLogger().info("[MiniBridge] Hello recu, heartbeat=" + heartbeatInterval + "ms");

        // Identify immediat
        identify();

        // Heartbeat regulier (premiere fois a mi-intervalle pour eviter timeout)
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeat,
                heartbeatInterval / 2,
                heartbeatInterval,
                TimeUnit.MILLISECONDS
        );
    }

    private void onDispatch(JSONObject payload) {
        String eventName = (String) payload.get("t");
        JSONObject d = (JSONObject) payload.get("d");
        if (eventName == null || d == null) return;

        switch (eventName) {
            case "READY" -> plugin.getLogger().info("[MiniBridge] Bot pret et authentifie !");
            case "GUILD_CREATE" -> plugin.getLogger().info("[MiniBridge] Serveur Discord charge: "
                    + d.get("name"));
            case "MESSAGE_CREATE" -> {
                plugin.getLogger().info("[MiniBridge DEBUG] MESSAGE_CREATE recu !");
                onMessageCreate(d);
            }
            default -> plugin.getLogger().info("[MiniBridge DEBUG] Evenement: " + eventName);
        }
    }

    @SuppressWarnings("unchecked")
    private void onMessageCreate(JSONObject message) {
        JSONObject author = (JSONObject) message.get("author");
        if (author == null) return;
        if (Boolean.TRUE.equals(author.get("bot"))) return;

        String msgChannelId = (String) message.get("channel_id");
        String content = (String) message.get("content");
        String username = (String) author.get("username");

        plugin.getLogger().info("[MiniBridge DEBUG] Message de " + username
                + " channel=" + msgChannelId + " (config=" + channelId + ")"
                + " contenu=" + content);

        if (!channelId.equals(msgChannelId)) {
            plugin.getLogger().info("[MiniBridge DEBUG] Channel ID ne correspond pas, message ignore.");
            return;
        }
        if (content == null || content.isEmpty()) return;

        // Commandes
        if (commandsEnabled && content.startsWith(commandPrefix)) {
            String cmd = content.substring(commandPrefix.length()).trim().toLowerCase();

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
                    sendMessage("Permission refusee.");
                    return;
                }
            }

            if (allowedCommands.contains(cmd)) {
                executeCommand(cmd, username);
            } else {
                sendMessage("Commandes disponibles : " + String.join(", ", allowedCommands));
            }
            return;
        }

        // Message normal -> Minecraft
        if (plugin.getConfig().getBoolean("discord-to-minecraft.enabled", true)) {
            String format = plugin.getConfig().getString(
                    "discord-to-minecraft.format", "&9[Discord] &f{user}&7: &f{message}");
            String formatted = format
                    .replace("{user}", username)
                    .replace("{message}", content);
            Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getServer().sendMessage(component));
        }
    }

    private void executeCommand(String cmd, String discordUser) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("[MiniBridge] Commande Discord par " + discordUser + ": " + cmd);
            switch (cmd) {
                case "list" -> {
                    int online = Bukkit.getOnlinePlayers().size();
                    int max = Bukkit.getMaxPlayers();
                    String names = Bukkit.getOnlinePlayers().stream()
                            .map(p -> p.getName())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("aucun");
                    sendMessage("Joueurs (" + online + "/" + max + "): " + names);
                }
                case "tps" -> {
                    double[] tps = Bukkit.getServer().getTPS();
                    sendMessage(String.format("TPS: 1m=%.1f 5m=%.1f 15m=%.1f", tps[0], tps[1], tps[2]));
                }
                case "time" -> {
                    long time = Bukkit.getWorlds().get(0).getTime();
                    String period = time < 12300 ? "Jour" : time < 13800 ? "Coucher" : time < 22200 ? "Nuit" : "Lever";
                    sendMessage("Temps: " + time + " ticks (" + period + ")");
                }
            }
        });
    }

    // ========================
    //   Gateway send
    // ========================

    @SuppressWarnings("unchecked")
    private void identify() {
        plugin.getLogger().info("[MiniBridge DEBUG] Envoi Identify...");
        JSONObject payload = new JSONObject();
        payload.put("op", 2);
        JSONObject d = new JSONObject();
        d.put("token", token);
        d.put("intents", 33793); // GUILDS(1) + GUILD_MESSAGES(512) + MESSAGE_CONTENT(32768)
        JSONObject props = new JSONObject();
        props.put("os", "linux");
        props.put("browser", "minibridge");
        props.put("device", "minibridge");
        d.put("properties", props);
        payload.put("d", d);
        boolean sent = webSocket.send(payload.toJSONString());
        plugin.getLogger().info("[MiniBridge DEBUG] Identify envoye: " + sent);
    }

    @SuppressWarnings("unchecked")
    private void sendHeartbeat() {
        JSONObject payload = new JSONObject();
        payload.put("op", 1);
        payload.put("d", lastSequence == -1 ? null : lastSequence);
        webSocket.send(payload.toJSONString());
    }

    private void reconnect() {
        plugin.getLogger().info("[MiniBridge] Reconnexion...");
        try {
            if (webSocket != null) webSocket.cancel();
            String gatewayUrl = getGatewayUrl();
            if (gatewayUrl != null) {
                Request request = new Request.Builder()
                        .url(gatewayUrl + "/?v=10&encoding=json")
                        .build();
                webSocket = httpClient.newWebSocket(request, this);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[MiniBridge] Reconnexion echouee", e);
            scheduler.schedule(this::reconnect, 30, TimeUnit.SECONDS);
        }
    }

    // ========================
    //   REST API
    // ========================

    public void sendMessage(String content) {
        sendQueue.offer(content);
    }

    private void sendMessageRest(String content) {
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

            String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(("{\"content\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 429) sendQueue.offer(content);
            else if (code >= 400) plugin.getLogger().warning("[MiniBridge] REST erreur " + code);
            conn.disconnect();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[MiniBridge] Erreur envoi REST", e);
        }
    }

    private String getGatewayUrl() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new java.net.URL(API_BASE + "/gateway/bot").openConnection();
            conn.setRequestProperty("Authorization", "Bot " + token);
            conn.setRequestProperty("User-Agent", "MiniBridge/1.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) {
                plugin.getLogger().severe("[MiniBridge] Gateway HTTP " + conn.getResponseCode()
                        + " — token invalide ?");
                return null;
            }
            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            JSONObject json = (JSONObject) new JSONParser().parse(response);
            String url = (String) json.get("url");
            plugin.getLogger().info("[MiniBridge] Gateway URL: " + url);
            return url;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[MiniBridge] Erreur Gateway URL", e);
            return null;
        }
    }
}
