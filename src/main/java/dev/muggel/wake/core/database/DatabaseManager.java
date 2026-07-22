package dev.muggel.wake.core.database;

import co.aikar.idb.BaseDatabase;
import co.aikar.idb.BukkitDB;
import co.aikar.idb.DB;
import co.aikar.idb.DatabaseOptions;
import co.aikar.idb.PooledDatabaseOptions;
import com.zaxxer.hikari.HikariDataSource;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.sync.SyncService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Owns the database connection (SQLite or MariaDB) and the single writer thread all async writes run on. <br>
 * When the database is unreachable it degrades gracefully: <br>
 * &rarr; Writes are journaled to disk and replayed on recovery, and the acting player is told in-game. <br>
 * Also tracks which command sender caused a write (the "actor") and publishes cross-server sync scopes once the write queue drains. <br>
 * Feature code never calls this directly &rarr; it writes through a {@link WakeDao}.
 */
public class DatabaseManager {
    private static final long PROBE_INTERVAL_TICKS = 100;
    private final Wake plugin;
    private final ExecutorService writeExecutor;
    private final OutageJournal journal;
    private volatile UUID currentActor;
    private volatile boolean degraded = false;
    private final Set<UUID> notifiedActors = ConcurrentHashMap.newKeySet();
    private final Set<String> dirtyScopes = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingWrites = new AtomicInteger();
    public DatabaseManager(Wake plugin) {
        this.plugin = plugin;
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, plugin.getName() + "-DB-Writer"));
        this.journal = new OutageJournal(plugin);
    }

    public void init() {
        ConfigurationSection dbConfig = plugin.getConfig().getConfigurationSection("database");
        if (dbConfig == null) {
            plugin.getLogger().warning("Database configuration missing, defaulting to SQLite");
            initSQLite();
        } else {
            String type = dbConfig.getString("type", "sqlite").toLowerCase(Locale.ROOT);
            if ("mariadb".equals(type) || "mysql".equals(type)) {
                initMariaDB(dbConfig);
            } else {
                initSQLite();
            }
        }
        tightenPoolTimeouts();
        try {
            DB.getFirstColumn("SELECT 1");
        } catch (Exception e) {
            throw new IllegalStateException("Database connection test failed", e);
        }
        if (journal.hasEntries()) {
            int replayed = journal.replay();
            if (replayed >= 0) {
                plugin.getLogger().info("Replayed " + replayed + " journaled writes from the last outage");
            } else {
                enterDegraded(null);
            }
        }
    }

    // Hikari's default timeout too long for quick ingame feedback
    private void tightenPoolTimeouts() {
        try {
            Field field = BaseDatabase.class.getDeclaredField("dataSource");
            field.setAccessible(true);
            if (field.get(DB.getGlobalDatabase()) instanceof HikariDataSource hikari) {
                hikari.setConnectionTimeout(5000);
                hikari.setValidationTimeout(2500);
            }
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "Could not tighten pool timeouts", e);
        }
    }

    private void initSQLite() {
        DatabaseOptions options = DatabaseOptions.builder()
                .poolName(plugin.getName() + "-DB")
                .logger(plugin.getLogger())
                .sqlite(new File(plugin.getDataFolder(), "wake.db").getPath())
                .build();
        PooledDatabaseOptions poolOptions = PooledDatabaseOptions.builder()
                .options(options)
                .build();
        BukkitDB.createHikariDatabase(plugin, poolOptions);
        plugin.getLogger().info("Database ready (SQLite)");
    }

    private void initMariaDB(@NonNull ConfigurationSection config) {
        String host = config.getString("host", "localhost");
        int port = config.getInt("port", 3306);
        String database = config.getString("database", "wake");
        DatabaseOptions options = DatabaseOptions.builder()
                .poolName(plugin.getName() + "-DB")
                .logger(plugin.getLogger())
                .mysql(
                        config.getString("username", "root"),
                        config.getString("password", ""),
                        database,
                        host + ":" + port
                )
                .dsn("mariadb://" + host + ":" + port + "/" + database + "?socketTimeout=10000")
                .build();
        PooledDatabaseOptions poolOptions = PooledDatabaseOptions.builder()
                .options(options)
                .build();
        BukkitDB.createHikariDatabase(plugin, poolOptions);
        plugin.getLogger().info("Database ready (MariaDB)");
    }

    public void queueWrite(String errorMessage, @Nullable String syncScope, @Nullable UUID actor, String query, Object... params) {
        notifyIfDegraded(actor);
        pendingWrites.incrementAndGet();
        writeExecutor.execute(() -> {
            try {
                if (degraded) {
                    journal.append(query, params);
                    return;
                }
                try {
                    DB.executeUpdate(query, params);
                    if (syncScope != null) {
                        dirtyScopes.add(syncScope);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, errorMessage, e);
                    if (isRetryableFailure(e)) {
                        journal.append(query, params);
                        enterDegraded(actor);
                    }
                }
            } finally {
                if (pendingWrites.decrementAndGet() == 0) {
                    publishDirtyScopes();
                }
            }
        });
    }

    public void setActor(CommandSender sender) {
        currentActor = sender instanceof Player player ? player.getUniqueId() : null;
    }

    public void restoreActor(@Nullable UUID actor) {
        currentActor = actor;
    }

    public @Nullable UUID currentActor() {
        return currentActor;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void publishScope(String scope) {
        writeExecutor.execute(() -> {
            SyncService sync = plugin.getSyncService();
            if (sync != null) {
                sync.publish(scope);
            }
        });
    }

    private void notifyIfDegraded(@Nullable UUID actor) {
        if (degraded && actor != null && notifiedActors.add(actor)) {
            sendLater(actor, "database.degraded");
        }
    }

    private void enterDegraded(@Nullable UUID actor) {
        if (!degraded) {
            degraded = true;
            notifiedActors.clear();
            scheduleProbe();
        }
        if (actor != null && notifiedActors.add(actor)) {
            sendLater(actor, "database.degraded");
        }
    }

    private void scheduleProbe() {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                try {
                    DB.getFirstColumn("SELECT 1");
                } catch (Exception e) {
                    scheduleProbe();
                    return;
                }
                writeExecutor.execute(this::replayAndRecover);
            }, PROBE_INTERVAL_TICKS);
        } catch (IllegalPluginAccessException e) {
            // leftover journal entries replay on next boot
        }
    }

    /**
     * The standard shape for a cache reload:
     * Drain queued writes and run the database read on an async thread, then apply the result on the main thread.
     * Reloads are remotely triggerable through cross-server sync, so they should never block the main thread on I/O.
     */
    public <T> void readAsync(@NonNull Supplier<T> read, @NonNull Consumer<T> applyOnMain) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            awaitWrites();
            T result = read.get();
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> applyOnMain.accept(result));
            } catch (IllegalPluginAccessException ignored) {
                // reload doesn't matter anymore
            }
        });
    }

    private void replayAndRecover() {
        int replayed = journal.replay();
        if (replayed < 0) {
            scheduleProbe();
            return;
        }
        degraded = false;
        plugin.getLogger().info("Database recovered: replayed " + replayed + " journaled writes");
        SyncService syncService = plugin.getSyncService();
        if (syncService != null) {
            // invalidations from other servers were dropped while degraded. reload local caches
            syncService.resyncAfterRecovery();
        }
        List<UUID> warned = List.copyOf(notifiedActors);
        notifiedActors.clear();
        for (UUID actor : warned) {
            sendLater(actor, "database.recovered");
            if (replayed > 0) {
                sendLater(actor, "database.replayed", Placeholder.unparsed("count", String.valueOf(replayed)));
            }
        }
        if (replayed > 0) {
            SyncService sync = plugin.getSyncService();
            if (sync != null) {
                sync.publish(SyncService.SCOPE_FULL);
            }
        }
    }

    private void publishDirtyScopes() {
        if (dirtyScopes.isEmpty()) {
            return;
        }
        List<String> scopes = List.copyOf(dirtyScopes);
        dirtyScopes.clear();
        SyncService sync = plugin.getSyncService();
        if (sync != null) {
            for (String scope : scopes) {
                sync.publish(scope);
            }
        }
    }

    private void sendLater(UUID actor, String messageKey, TagResolver... resolvers) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(actor);
                if (player != null) {
                    plugin.getMessageManager().send(player, messageKey, resolvers);
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // nobody left to notify
        }
    }

    static boolean isRetryableFailure(Throwable failure) {
        int depth = 0;
        for (Throwable current = failure; current != null && depth++ < 16; current = current.getCause()) {
            if (!(current instanceof SQLException sql)) {
                continue;
            }
            if (sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
            if (sql.getClass().getName().startsWith("org.sqlite.")) {
                int code = sql.getErrorCode() & 0xff;
                if (code == 5 || code == 8 || code == 10 || code == 13 || code == 14) {
                    return true;
                }
            }
        }
        return false;
    }

    public void awaitWrites() {
        try {
            writeExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending database writes", e);
        }
    }

    public void shutdown() {
        writeExecutor.shutdown();
        try {
            // outlasts the 10s socket timeout
            // a write hung on a dead connection needs to fail to journal itself, and flip degraded to prevent writes from being dropped before shutdownNow
            if (!writeExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out flushing pending database writes");
                writeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            writeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        journal.closeWriter();
        try {
            DB.close();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to close database pool", e);
        }
    }
}