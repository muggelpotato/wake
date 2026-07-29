package dev.muggel.wake.core.database;

import co.aikar.idb.DB;
import com.zaxxer.hikari.HikariDataSource;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.Scheduling;
import dev.muggel.wake.core.sync.SyncService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Everything that happens when the database stops answering. <br>
 * A write that fails on a transient error is journaled to disk rather than lost. <br>
 * The acting player is told once, while a probe keeps trying until the database returns. <br>
 * The journal is replayed on reconnect and other servers are told to resync. <br>
 *
 * A queue that stops moving without any write failing is caught by the watchdog instead. <br>
 * It decides "backlogged but alive" or "outage" on an independent connection rather than waiting out a hung write.
 */
class OutageMonitor {
    private static final long PROBE_INTERVAL_TICKS = 100;
    private static final long WATCHDOG_DELAY_TICKS = 20;
    private final Wake plugin;
    private final OutageJournal journal;
    private final IntSupplier pendingWrites;
    private final LongSupplier completedWrites;
    private final Consumer<Runnable> onWriterThread;
    private final Set<UUID> notifiedActors = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean watchdogActive = new AtomicBoolean();
    private volatile boolean degraded = false;
    private volatile @Nullable UUID lastPendingActor;
    private volatile @Nullable HikariDataSource probeSource;
    OutageMonitor(@NonNull Wake plugin, @NonNull IntSupplier pendingWrites, @NonNull LongSupplier completedWrites, @NonNull Consumer<Runnable> onWriterThread) {
        this.plugin = plugin;
        this.journal = new OutageJournal(plugin);
        this.pendingWrites = pendingWrites;
        this.completedWrites = completedWrites;
        this.onWriterThread = onWriterThread;
    }

    void probeVia(@Nullable HikariDataSource dataSource) {
        this.probeSource = dataSource;
    }

    /** Replays whatever the last run left behind. A replay that cannot get through means the database is still down */
    void replayOnBoot() {
        if (journal.isEmpty()) {
            return;
        }
        int replayed = journal.replay();
        if (replayed >= 0) {
            plugin.getLogger().info("Replayed " + replayed + " journaled writes from the last outage");
        } else {
            enterDegraded(null);
        }
    }

    boolean isDegraded() {
        return degraded;
    }

    void journal(@NonNull List<SqlStatement> statements) {
        for (SqlStatement statement : statements) {
            journal.append(statement.sql(), statement.params());
        }
    }

    void closeJournal() {
        journal.closeWriter();
    }

    /** Told as a write is queued rather than as it runs, so a player hears about the outage without waiting on it */
    void writeQueued(@Nullable UUID actor) {
        notifyIfDegraded(actor);
        if (actor != null) {
            lastPendingActor = actor;
        }
    }

    void notifyIfDegraded(@Nullable UUID actor) {
        if (degraded && actor != null && notifiedActors.add(actor)) {
            sendLater(actor, "database.degraded");
        }
    }

    void notifyOnJoin(@NonNull UUID actor) {
        notifiedActors.remove(actor);
        notifyIfDegraded(actor);
    }

    void forgetActor(@NonNull UUID actor) {
        notifiedActors.remove(actor);
    }

    void enterDegraded(@Nullable UUID actor) {
        if (!degraded) {
            degraded = true;
            notifiedActors.clear();
            scheduleProbe();
        }
        if (actor != null && notifiedActors.add(actor)) {
            sendLater(actor, "database.degraded");
        }
    }

    /** No write completes for a second = an independent connection decides whether it is backlogged or gone */
    void armWatchdog() {
        if (degraded || !watchdogActive.compareAndSet(false, true)) {
            return;
        }
        scheduleWatchdog(completedWrites.getAsLong());
    }

    private void scheduleWatchdog(long lastCompleted) {
        if (Scheduling.laterAsync(plugin, () -> watchdogCheck(lastCompleted), WATCHDOG_DELAY_TICKS) == null) {
            watchdogActive.set(false);
        }
    }

    private void watchdogCheck(long lastCompleted) {
        if (degraded || pendingWrites.getAsInt() == 0) {
            watchdogActive.set(false);
            return;
        }
        long completed = completedWrites.getAsLong();
        if (completed != lastCompleted || probeConnectionAlive()) {
            scheduleWatchdog(completed);
            return;
        }
        watchdogActive.set(false);
        plugin.getLogger().warning("Database unresponsive: entering degraded mode before the hung write times out");
        enterDegraded(lastPendingActor);
    }

    private boolean probeConnectionAlive() {
        HikariDataSource dataSource = this.probeSource;
        if (dataSource == null) {
            try {
                DB.getFirstColumn("SELECT 1");
                return true;
            } catch (Exception unreachable) {
                return false;
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (Exception unreachable) {
            return false;
        }
    }

    private void scheduleProbe() {
        Scheduling.laterAsync(plugin, () -> {
            try {
                DB.getFirstColumn("SELECT 1");
            } catch (Exception stillDown) {
                scheduleProbe();
                return;
            }
            onWriterThread.accept(this::replayAndRecover);
        }, PROBE_INTERVAL_TICKS);
    }

    private void replayAndRecover() {
        int replayed = journal.replay();
        if (replayed < 0) {
            scheduleProbe();
            return;
        }
        degraded = false;
        plugin.getLogger().info("Database recovered: replayed " + replayed + " journaled writes");
        SyncService sync = plugin.getSyncService();
        if (sync != null) {
            sync.resyncAfterRecovery();
        }
        List<UUID> warned = List.copyOf(notifiedActors);
        notifiedActors.clear();
        for (UUID actor : warned) {
            sendLater(actor, "database.recovered");
            if (replayed > 0) {
                sendLater(actor, "database.replayed", Placeholder.unparsed("count", String.valueOf(replayed)));
            }
        }
        if (replayed > 0 && sync != null) {
            sync.publish(SyncService.SCOPE_FULL);
        }
    }

    private void sendLater(UUID actor, String messageKey, TagResolver... resolvers) {
        Scheduling.onMain(plugin, () -> {
            Player player = Bukkit.getPlayer(actor);
            if (player != null) {
                plugin.getMessageManager().send(player, messageKey, resolvers);
            }
        });
    }

    static boolean isRetryableFailure(Throwable failure) {
        int depth = 0;
        for (Throwable current = failure; current != null && depth++ < 16; current = current.getCause()) {
            if (current instanceof SQLTransientException) {
                return true;
            }
            if (!(current instanceof SQLException sql)) {
                continue;
            }
            String state = sql.getSQLState();
            if (state != null && (state.startsWith("08") || state.startsWith("40"))) {
                return true;
            }
            if (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205) {
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
}