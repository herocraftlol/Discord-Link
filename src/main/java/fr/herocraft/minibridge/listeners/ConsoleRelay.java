package fr.herocraft.minibridge.listeners;

import fr.herocraft.minibridge.MiniBridge;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Capte les logs de la console serveur via Log4j2 (et non java.util.logging) : c'est
 * le système que Paper/Spigot utilise réellement pour les logs "vanilla" (connexions,
 * déconnexions, commandes exécutées, erreurs, etc.), contrairement aux appels
 * getLogger() de certains plugins qui eux passent par java.util.logging.
 * Les logs captés sont regroupés et relayés périodiquement vers un channel Discord dédié.
 */
public class ConsoleRelay extends AbstractAppender {

    private final MiniBridge plugin;
    private final List<String> buffer = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final Set<String> ignoreContains;
    private final Level minLevel;

    public ConsoleRelay(MiniBridge plugin) {
        super("MiniBridgeConsoleRelay", null, null, true, Property.EMPTY_ARRAY);
        this.plugin = plugin;
        this.ignoreContains = new HashSet<>(plugin.getConfig().getStringList("console.ignore-contains"));

        Level lvl;
        try {
            lvl = Level.toLevel(plugin.getConfig().getString("console.level", "INFO"));
        } catch (Exception e) {
            lvl = Level.INFO;
        }
        this.minLevel = lvl;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MiniBridge-ConsoleRelay");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = plugin.getConfig().getLong("console.flush-interval-ms", 3000);
        scheduler.scheduleWithFixedDelay(this::flushBuffer, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        start();
    }

    @Override
    public void append(LogEvent event) {
        // En Log4j2, un intLevel plus petit = plus sévère (ERROR=200 < WARN=300 < INFO=400).
        // On ignore tout ce qui est moins important que le seuil configuré.
        if (event.getLevel().intLevel() > minLevel.intLevel()) return;

        String loggerName = event.getLoggerName();
        if (loggerName != null && loggerName.contains("MiniBridge")) return;

        String message = event.getMessage() != null ? event.getMessage().getFormattedMessage() : null;
        if (message == null || message.isEmpty()) return;

        for (String ignore : ignoreContains) {
            if (ignore != null && !ignore.isEmpty() && message.contains(ignore)) return;
        }

        buffer.add("[" + event.getLevel().name() + "] " + message);
    }

    private void flushBuffer() {
        if (buffer.isEmpty()) return;

        List<String> lines = new ArrayList<>(buffer);
        buffer.clear();

        StringBuilder sb = new StringBuilder("```\n");
        for (String line : lines) {
            // Limite Discord ~2000 caractères par message : on tronque proprement si trop long
            if (sb.length() + line.length() > 1900) {
                sb.append("… (troncature)\n");
                break;
            }
            sb.append(line).append("\n");
        }
        sb.append("```");

        plugin.sendConsoleToDiscord(sb.toString());
    }

    /** Attache ce relais au logger racine Log4j2 (celui qui reçoit vraiment tous les logs serveur). */
    public void register() {
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        rootLogger.addAppender(this);
    }

    /** Détache proprement le relais (à appeler dans onDisable). */
    public void unregister() {
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        rootLogger.removeAppender(this);
    }

    @Override
    public void stop() {
        scheduler.shutdownNow();
        super.stop();
    }
}
