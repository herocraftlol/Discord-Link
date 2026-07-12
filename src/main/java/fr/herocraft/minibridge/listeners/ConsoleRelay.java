package fr.herocraft.minibridge.listeners;

import fr.herocraft.minibridge.MiniBridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Capte les logs de la console serveur (via le logger racine Java) et les relaie
 * périodiquement vers un channel Discord dédié, groupés dans un même message pour
 * respecter le rate limit Discord et éviter le spam.
 */
public class ConsoleRelay extends Handler {

    private final MiniBridge plugin;
    private final List<String> buffer = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final Set<String> ignoreContains;

    public ConsoleRelay(MiniBridge plugin) {
        this.plugin = plugin;
        this.ignoreContains = new HashSet<>(plugin.getConfig().getStringList("console.ignore-contains"));

        Level minLevel;
        try {
            minLevel = Level.parse(plugin.getConfig().getString("console.level", "INFO"));
        } catch (IllegalArgumentException e) {
            minLevel = Level.INFO;
        }
        setLevel(minLevel);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MiniBridge-ConsoleRelay");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = plugin.getConfig().getLong("console.flush-interval-ms", 3000);
        scheduler.scheduleWithFixedDelay(this::flushBuffer, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel().intValue() < getLevel().intValue()) return;

        String message = record.getMessage();
        if (message == null || message.isEmpty()) return;

        // Évite de relayer les propres logs du plugin (boucle infinie) et le spam configuré
        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.contains("MiniBridge")) return;
        for (String ignore : ignoreContains) {
            if (ignore != null && !ignore.isEmpty() && message.contains(ignore)) return;
        }

        buffer.add("[" + record.getLevel().getName() + "] " + message);
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

    @Override
    public void flush() {
        // Le flush réel est géré par le scheduler périodique (évite de spammer Discord
        // à chaque ligne de log ; requis pour implémenter Handler).
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
