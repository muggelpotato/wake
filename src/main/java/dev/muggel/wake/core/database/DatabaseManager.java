package dev.muggel.wake.core.database;

import co.aikar.idb.DB;
import co.aikar.idb.DbStatement;
import dev.muggel.wake.core.Scheduling;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.sync.SyncService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The single writer thread every async write runs on. <br>
 * It owns {@link DatabasePool} (opens the connection), {@link OutageMonitor} (decides what happens when it stops answering) and {@link MirrorRegistry} (routes invalidations) <br>
 * This class is what puts a write through all three. <br>
 * Feature code never calls it directly, it writes through a {@link WakeDao}.
 */
public class DatabaseManager {
    private static final long PUBLISH_INTERVAL_MILLIS = 200;
    private final Wake plugin;
    private final ExecutorService writeExecutor;
    private final OutageMonitor outage;
    private final MirrorRegistry mirrors;
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final AtomicLong completedWrites = new AtomicLong();
    private final Set<String> failingReads = ConcurrentHashMap.newKeySet();
    private final Set<String> transientReads = ConcurrentHashMap.newKeySet();
    private long lastPublishMillis;
    private volatile @Nullable UUID currentActor;
    private @Nullable DegradedNoticeListener noticeListener;
    private SchemaMigrator schemaMigrator;
    private Dialect dialect = Dialect.SQLITE;
    public DatabaseManager(Wake plugin) {
        this.plugin = plugin;
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, plugin.getName() + "-DB-Writer"));
        this.mirrors = new MirrorRegistry(plugin);
        this.outage = new OutageMonitor(plugin, pendingWrites::get, completedWrites::get, writeExecutor::execute);
    }

    public void init() {
        DatabasePool.Handle pool = DatabasePool.open(plugin);
        this.dialect = pool.dialect();
        this.schemaMigrator = new SchemaMigrator(plugin, dialect);
        outage.probeVia(pool.dataSource());
        this.noticeListener = new DegradedNoticeListener(this);
        Bukkit.getPluginManager().registerEvents(noticeListener, plugin);
        outage.replayOnBoot();
    }

    public void queueWrite(String errorMessage, @Nullable String syncScope, @Nullable UUID actor, String query, Object... params) {
        queueWrite(errorMessage, syncScope, actor, List.of(new SqlStatement(query, params)));
    }

    public void queueWrite(String errorMessage, @Nullable String syncScope, @Nullable UUID actor, @NonNull List<SqlStatement> statements) {
        queueWrite(errorMessage, syncScope, null, List.of(), actor, statements, null);
    }

    /** Queues a write to a mirrored table, remembering which rows it moved */
    void queueMirrorWrite(String errorMessage, @NonNull CachedStore<?> mirror, @NonNull List<String> rowKeys, @Nullable UUID actor, @NonNull List<SqlStatement> statements, @Nullable Runnable onLost) {
        queueWrite(errorMessage, mirror.scope(), mirror, rowKeys, actor, statements, onLost);
    }

    /** Runs statements as one transaction */
    private void queueWrite(String errorMessage, @Nullable String syncScope, @Nullable CachedStore<?> mirror, @NonNull List<String> rowKeys, @Nullable UUID actor, @NonNull List<SqlStatement> statements, @Nullable Runnable onLost) {
        outage.writeQueued(actor);
        pendingWrites.incrementAndGet();
        outage.armWatchdog();
        try {
            submitWrite(errorMessage, syncScope, mirror, rowKeys, actor, statements, onLost);
        } catch (RejectedExecutionException afterShutdown) {
            completedWrites.incrementAndGet();
            pendingWrites.decrementAndGet();
            plugin.getLogger().log(Level.SEVERE, errorMessage + " (queued after the database was shut down)", afterShutdown);
            if (onLost != null) {
                onLost.run();
            }
        }
    }

    private void submitWrite(String errorMessage, @Nullable String syncScope, @Nullable CachedStore<?> mirror, @NonNull List<String> rowKeys, @Nullable UUID actor, @NonNull List<SqlStatement> statements, @Nullable Runnable onLost) {
        writeExecutor.execute(() -> {
            try {
                if (outage.isDegraded()) {
                    outage.journal(statements);
                    return;
                }
                try {
                    execute(statements);
                    mirrors.recordLocalChange(mirror, rowKeys, syncScope);
                } catch (Exception e) {
                    if (OutageMonitor.isRetryableFailure(e)) {
                        if (!outage.isDegraded()) {
                            plugin.getLogger().log(Level.WARNING,
                                    errorMessage + " (database unreachable, journaling until it returns)", e);
                        }
                        outage.journal(statements);
                        outage.enterDegraded(actor);
                    } else {
                        plugin.getLogger().log(Level.SEVERE, errorMessage, e);
                        if (onLost != null) {
                            onLost.run();
                        }
                    }
                }
            } finally {
                completedWrites.incrementAndGet();
                if (pendingWrites.decrementAndGet() == 0 || System.currentTimeMillis() - lastPublishMillis >= PUBLISH_INTERVAL_MILLIS) {
                    lastPublishMillis = System.currentTimeMillis();
                    mirrors.publishPending();
                }
            }
        });
    }

    private static void execute(@NonNull List<SqlStatement> statements) throws SQLException {
        if (statements.isEmpty()) {
            return;
        }
        if (statements.size() == 1) {
            SqlStatement only = statements.getFirst();
            DB.executeUpdate(only.sql(), only.params());
            return;
        }
        try (DbStatement stm = new DbStatement()) {
            stm.startTransaction();
            for (SqlStatement statement : statements) {
                stm.executeUpdateQuery(statement.sql(), statement.params());
            }
            stm.commit();
        }
    }

    void readFailed(@NonNull String subject, @NonNull Throwable failure) {
        if (OutageMonitor.isRetryableFailure(failure)) {
            transientReads.add(subject);
        } else {
            transientReads.remove(subject);
        }
        if (failingReads.add(subject)) {
            plugin.getLogger().log(Level.SEVERE, "Failed to read " + subject + " (repeats stay silent until it succeeds)", failure);
        }
    }

    void readSucceeded(@NonNull String subject) {
        transientReads.remove(subject);
        if (failingReads.remove(subject)) {
            plugin.getLogger().info("Reading " + subject + " works again");
        }
    }

    boolean readFailureWasTransient(@NonNull String subject) {
        return transientReads.contains(subject);
    }

    /** The standard shape for a cache reload (Drain queued writes -> read on async thread -> apply results on main thread) */
    public <T> void readAsync(@NonNull Supplier<@Nullable T> read, @NonNull Consumer<@Nullable T> applyOnMain) {
        Scheduling.async(plugin, () -> {
            T result;
            try {
                awaitWrites();
                result = read.get();
            } catch (RuntimeException e) {
                readFailed("the database", e);
                result = null;
            }
            T settled = result;
            Scheduling.onMain(plugin, () -> applyOnMain.accept(settled));
        });
    }

    public void runWithDrainedQueue(@NonNull Runnable body) {
        readAsync(() -> null, ignored -> body.run());
    }

    public void awaitWrites() {
        try {
            writeExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending database writes", e);
        }
    }

    /** Queued behind the writes it belongs to, so a scope is never announced ahead of the rows that moved in it */
    public void publishScope(String scope) {
        writeExecutor.execute(() -> {
            SyncService sync = plugin.getSyncService();
            if (sync != null) {
                sync.publish(scope);
            }
        });
    }

    public void markRemoteChange(@NonNull String scope, @Nullable String table, @Nullable Collection<String> keys) {
        mirrors.markRemoteChange(scope, table, keys);
    }

    public void invalidateAllMirrors() {
        mirrors.markRemoteChange(SyncService.SCOPE_FULL, null, null);
    }

    void registerMirror(@NonNull CachedStore<?> mirror) {
        mirrors.register(mirror);
    }

    void releaseMirror(@NonNull CachedStore<?> mirror) {
        mirrors.release(mirror);
    }

    public boolean isDegraded() {
        return outage.isDegraded();
    }

    void notifyOnJoin(@NonNull UUID actor) {
        outage.notifyOnJoin(actor);
    }

    void forgetActor(@NonNull UUID actor) {
        outage.forgetActor(actor);
    }

    /** Which command sender caused the writes being queued right now, so an outage notice reaches the right player */
    public void setActor(CommandSender sender) {
        currentActor = sender instanceof Player player ? player.getUniqueId() : null;
    }

    public void restoreActor(@Nullable UUID actor) {
        currentActor = actor;
    }

    public @Nullable UUID currentActor() {
        return currentActor;
    }

    public SchemaMigrator getSchemaMigrator() {
        return schemaMigrator;
    }

    public @NonNull Dialect dialect() {
        return dialect;
    }

    public void shutdown() {
        if (noticeListener != null) {
            HandlerList.unregisterAll(noticeListener);
            noticeListener = null;
        }
        writeExecutor.shutdown();
        try {
            // outlasts the 3s socket timeout with margin
            // a write hung on a dead connection needs to fail to journal itself, and flip degraded to prevent writes from being dropped before shutdownNow
            if (!writeExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out flushing pending database writes");
                writeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            writeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        outage.closeJournal();
        try {
            DB.close();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to close database pool", e);
        }
    }
}