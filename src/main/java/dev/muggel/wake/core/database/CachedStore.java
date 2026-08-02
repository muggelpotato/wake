package dev.muggel.wake.core.database;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.Scheduling;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A database table held in memory. <br>
 * A DAO owns one per mirrored table and every read and write of that table goes through it. <br>
 * A write -> updates cache -> queues the statement -> announces the key once queue drains. <br>
 *
 * Three rules keep a reload from corrupting the cache: <br>
 * 1. a loader returns {@code null} for a failed read, never an empty result. The keys stay dirty and are retried <br>
 * 2. a result older than one already merged is dropped whole <br>
 * 3. a key written while the read was in flight keeps its local value, and only that key <br>
 * A crash between the commit and the announcement leaves peers stale until they reload, restart, or reconnect.
 */
public final class CachedStore<V> {
    @FunctionalInterface
    public interface Loader<V> {
        @Nullable Map<String, V> load(@Nullable Set<String> keys);
    }

    private static final int MAX_KEYED_READ = 500;
    private static final long[] TRANSIENT_RETRY_TICKS = {20, 100, 600};
    private final Wake plugin;
    private final String scope;
    private final String table;
    private final Loader<V> loader;
    private final Map<String, V> values = new ConcurrentHashMap<>();
    private final Map<String, Long> localWrites = new ConcurrentHashMap<>();
    private final Set<String> staleKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong wholeTableRequests = new AtomicLong();
    private final AtomicLong writeClock = new AtomicLong();
    private final AtomicLong reads = new AtomicLong();
    private final List<Consumer<Set<String>>> waiting = new ArrayList<>();
    private long wholeTableServed;
    private long appliedRead;
    private int failedReads;
    private boolean reading;
    private volatile boolean loaded;
    CachedStore(@NonNull Wake plugin, @NonNull String scope, @NonNull String table, @NonNull Loader<V> loader) {
        this.plugin = plugin;
        this.scope = scope;
        this.table = table;
        this.loader = loader;
        plugin.getDatabaseManager().registerMirror(this);
    }

    @NonNull String scope() {
        return scope;
    }

    @NonNull String table() {
        return table;
    }

    public @Nullable V get(@NonNull String key) {
        return values.get(key);
    }

    public boolean containsKey(@NonNull String key) {
        return values.containsKey(key);
    }

    public @NonNull Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public @NonNull Map<String, V> view() {
        return Collections.unmodifiableMap(values);
    }

    /** Whether a read has ever reached the store. An empty cache that never loaded is not an empty table */
    public boolean isLoaded() {
        return loaded;
    }

    /** Caches {@code value} under {@code key}, queues the statements that persist it and announces the key */
    public void save(@NonNull String key, @NonNull V value, @NonNull String errorMessage, @NonNull List<SqlStatement> statements) {
        recordWrite(key);
        values.put(key, value);
        queue(List.of(key), errorMessage, statements);
    }

    /** Drops {@code key} and queues the statements that remove it. Another server re-reads it and finds no row, which is the deletion */
    public void delete(@NonNull String key, @NonNull String errorMessage, @NonNull List<SqlStatement> statements) {
        recordWrite(key);
        values.remove(key);
        queue(List.of(key), errorMessage, statements);
    }

    public void moveKey(@NonNull String fromKey, @NonNull String toKey, @NonNull V value, @NonNull String errorMessage, @NonNull List<SqlStatement> statements) {
        recordWrite(fromKey);
        values.remove(fromKey);
        recordWrite(toKey);
        values.put(toKey, value);
        queue(List.of(fromKey, toKey), errorMessage, statements);
    }

    /** Caches {@code value} and announces {@code key} for a row the caller already wrote itself, for import */
    public void announce(@NonNull String key, @NonNull V value) {
        save(key, value, "Failed to announce an imported row", List.of());
    }

    /** Drops {@code key} from the cache without a write of its own, for a row the caller removes some other way */
    public void forget(@NonNull String key) {
        recordWrite(key);
        values.remove(key);
    }

    /** Queues statements that change this scope in a way no key names, so every other server reads the table again */
    public void announceWholeScope(@NonNull String errorMessage, @NonNull List<SqlStatement> statements) {
        queue(List.of(), errorMessage, statements);
    }

    /** Empties the cache without a write and has it read the table again, for rows the caller removed itself */
    public void clearLocal() {
        for (String key : Set.copyOf(values.keySet())) {
            recordWrite(key);
        }
        values.clear();
        markStale(null);
    }

    /** Another server moved these keys, or the whole table when {@code keys} is {@code null}. Read back on the next reload */
    void markStale(@Nullable Collection<String> keys) {
        if (keys == null) {
            wholeTableRequests.incrementAndGet();
        } else {
            staleKeys.addAll(keys);
        }
    }

    private void queue(@NonNull List<String> keys, @NonNull String errorMessage, @NonNull List<SqlStatement> statements) {
        DatabaseManager database = plugin.getDatabaseManager();
        database.queueMirrorWrite(errorMessage, this, keys, database.currentActor(), statements,
                () -> Scheduling.onMain(plugin, () -> repair(keys)));
    }

    /** Marks a key as locally written so an in-flight reload cannot overwrite it. Always call before touching the cache */
    private void recordWrite(@NonNull String key) {
        if (!reading) {
            localWrites.clear();
        }
        localWrites.put(key, writeClock.incrementAndGet());
    }

    /** Blocking whole-table load. {@code false} means the read failed and the cache was left untouched */
    public boolean load() {
        long wholeTableAt = wholeTableRequests.get();
        Set<String> covered = Set.copyOf(staleKeys);
        long readFrom = writeClock.get();
        Map<String, V> rows;
        try {
            rows = loader.load(null);
        } catch (RuntimeException e) {
            plugin.getDatabaseManager().readFailed(table, e);
            rows = null;
        }
        if (rows == null) {
            Scheduling.onMain(plugin, () -> reloadAsync(null));
            return false;
        }
        failedReads = 0;
        apply(rows, null, covered, readFrom, wholeTableAt, reads.incrementAndGet());
        spendGuards(readFrom);
        return true;
    }

    public void reloadAsync(@Nullable Consumer<Set<String>> afterApply) {
        if (afterApply != null) {
            waiting.add(afterApply);
        }
        failedReads = 0;
        startRead();
    }

    private void startRead() {
        if (reading) {
            return;
        }
        long wholeTableAt = wholeTableRequests.get();
        Set<String> covered = Set.copyOf(staleKeys);
        boolean whole = !loaded || wholeTableAt > wholeTableServed || covered.size() > MAX_KEYED_READ;
        List<Consumer<Set<String>>> due = List.copyOf(waiting);
        waiting.clear();
        if (!whole && covered.isEmpty()) {
            runAll(due, Set.of());
            return;
        }
        Set<String> keys = whole ? null : covered;
        long readFrom = writeClock.get();
        long ticket = reads.incrementAndGet();
        reading = true;
        plugin.getDatabaseManager().readAsync(() -> loader.load(keys), rows -> {
            reading = false;
            if (rows == null) {
                spendGuards(readFrom);
                if (retryIfTransient()) {
                    waiting.addAll(due);
                } else {
                    runAll(due, Set.of());
                }
                return;
            }
            failedReads = 0;
            Set<String> changed = ticket > appliedRead
                    ? apply(rows, keys, covered, readFrom, wholeTableAt, ticket)
                    : Set.of();
            spendGuards(readFrom);
            startRead();
            runAll(due, changed);
        });
    }

    /** Gives a failed read a few scheduled attempts to self-heal */
    private boolean retryIfTransient() {
        if (failedReads >= TRANSIENT_RETRY_TICKS.length || !plugin.getDatabaseManager().readFailureWasTransient(table)) {
            return false;
        }
        Scheduling.later(plugin, this::startRead, TRANSIENT_RETRY_TICKS[failedReads++]);
        return true;
    }

    private void runAll(@NonNull List<Consumer<Set<String>>> due, @NonNull Set<String> changed) {
        for (Consumer<Set<String>> callback : due) {
            try {
                callback.accept(changed);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Reload callback failed for " + table, e);
            }
        }
    }

    private void spendGuards(long readFrom) {
        localWrites.values().removeIf(clock -> clock <= readFrom);
    }

    /** Re-reads keys whose write was lost, so the cache stops serving what the database never took */
    private void repair(@NonNull List<String> keys) {
        markStale(keys.isEmpty() ? null : keys);
        reloadAsync(null);
    }

    /** Merges a settled read into the cache and answers with the keys it moved */
    private @NonNull Set<String> apply(@NonNull Map<String, V> rows, @Nullable Set<String> keys, @NonNull Set<String> covered, long readFrom, long wholeTableAt, long ticket) {
        Set<String> changed = new HashSet<>();
        if (keys == null) {
            values.keySet().removeIf(key -> {
                if (rows.containsKey(key) || !readWins(key, readFrom)) {
                    return false;
                }
                changed.add(key);
                return true;
            });
            rows.forEach((key, value) -> {
                if (readWins(key, readFrom) && !value.equals(values.put(key, value))) {
                    changed.add(key);
                }
            });
            wholeTableServed = wholeTableAt;
            loaded = true;
        } else {
            for (String key : keys) {
                if (!readWins(key, readFrom)) {
                    continue;
                }
                V value = rows.get(key);
                if (value == null) {
                    if (values.remove(key) != null) {
                        changed.add(key);
                    }
                } else if (!value.equals(values.put(key, value))) {
                    changed.add(key);
                }
            }
        }
        appliedRead = ticket;
        staleKeys.removeAll(covered);
        return changed;
    }

    private boolean readWins(@NonNull String key, long readFrom) {
        Long written = localWrites.get(key);
        return written == null || written <= readFrom;
    }
}