package dev.muggel.wake.core.database;

import co.aikar.idb.DB;
import co.aikar.idb.DbRow;
import co.aikar.idb.DbStatement;
import dev.muggel.wake.Wake;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The base class for a module's data access. <br>
 * 1. Declare tables in {@code getTableSchemas()} <br>
 * 2. Mirror one with {@code mirror(table, loader)} and read/write to it only through {@link CachedStore} (it caches, persists and announces) <br>
 * 3. Unmirrored tables go through {@code asyncUpdate(...)}, or {@code asyncUpdateLocal(...)} if no other server cares <br>
 * 4. Keep SQL parameterized and portable across SQLite and MariaDB <br>
 * 5. Register instances via the module's {@code registerDao(...)} so database reset covers them
 */
public abstract class WakeDao {
    private static final int KEY_CHUNK = 500;
    protected final Wake plugin;
    private final List<CachedStore<?>> mirrors = new ArrayList<>();
    protected WakeDao(Wake plugin) {
        this.plugin = plugin;
    }

    protected abstract Map<String, String> getTableSchemas();

    protected abstract String syncScope();

    /** Bump it whenever {@code getTableSchemas()} changes shape and provide the upgrade in {@link #migrationSteps} ({@code getTableSchemas()} describes current shape, steps transform older shape into current shape)*/
    protected int targetSchemaVersion() {
        return 1;
    }

    /** Ordered SQL transforming schema version {@code fromVersion} into {@code fromVersion + 1} */
    @SuppressWarnings("unused")
    protected List<String> migrationSteps(int fromVersion, Dialect dialect) {
        throw new IllegalStateException(getClass().getSimpleName() + " declares schema v" + targetSchemaVersion() + " but has no migration path from v" + fromVersion);
    }

    /** Identity in {@code wake_schema_version}. Override when a module has more than one DAO */
    protected String schemaId() {
        return syncScope();
    }

    public void initTables() {
        SchemaMigrator migrator = plugin.getDatabaseManager().getSchemaMigrator();
        String schemaId = schemaId();
        int target = targetSchemaVersion();
        Integer current = migrator.storedVersion(schemaId);
        if (current != null && current == target) {
            createTables();
            return;
        }
        migrator.underSchemaLock(schemaId, () -> {
            Integer stored = migrator.storedVersion(schemaId);
            if (stored != null && stored > target) {
                throw new IllegalStateException("Database schema '" + schemaId + "' is v" + stored + " but this build supports v" + target + ": update the Wake jar on this server");
            }
            if (stored != null && stored < target) {
                migrator.migrate(this, schemaId, stored, target);
            }
            createTables();
            if (stored == null) {
                migrator.stamp(schemaId, target);
            }
        });
    }

    private void createTables() {
        for (Map.Entry<String, String> entry : getTableSchemas().entrySet()) {
            try {
                DB.executeUpdate(entry.getValue());
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialize table: " + entry.getKey(), e);
            }
        }
    }

    @SuppressWarnings("SqlWithoutWhere")
    public void resetTables() {
        plugin.getDatabaseManager().awaitWrites();
        try (DbStatement stm = new DbStatement()) {
            stm.startTransaction();
            for (String table : getTableSchemas().keySet()) {
                try {
                    stm.executeUpdateQuery("DELETE FROM " + table);
                } catch (SQLException e) {
                    throw new IllegalStateException("Failed to reset table: " + table, e);
                }
            }
            stm.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset tables for " + schemaId(), e);
        }
        for (CachedStore<?> store : mirrors) {
            store.clearLocal();
            store.announceWholeScope("Failed to announce a reset of " + schemaId(), List.of());
        }
    }

    protected void asyncUpdate(String errorMessage, String query, Object... params) {
        DatabaseManager db = plugin.getDatabaseManager();
        db.queueWrite(errorMessage, syncScope(), db.currentActor(), List.of(new SqlStatement(query, params)));
    }

    protected void asyncUpdateFor(UUID subject, String errorMessage, String query, Object... params) {
        plugin.getDatabaseManager().queueWrite(errorMessage, null, subject, query, params);
    }

    @SuppressWarnings("SameParameterValue")
    protected void asyncUpdateLocal(String errorMessage, String query, Object... params) {
        DatabaseManager db = plugin.getDatabaseManager();
        db.queueWrite(errorMessage, null, db.currentActor(), query, params);
    }

    /** The in-memory mirror of one of this DAO's tables */
    protected <V> @NonNull CachedStore<V> mirror(@NonNull String table, CachedStore.@NonNull Loader<V> loader) {
        CachedStore<V> store = new CachedStore<>(plugin, syncScope(), table, loader);
        mirrors.add(store);
        return store;
    }

    public final void releaseMirrors() {
        for (CachedStore<?> store : mirrors) {
            plugin.getDatabaseManager().releaseMirror(store);
        }
        mirrors.clear();
    }

    protected final <T> @Nullable T read(@NonNull String subject, @NonNull SqlRead<T> body) {
        DatabaseManager database = plugin.getDatabaseManager();
        try {
            T result = body.run();
            database.readSucceeded(subject);
            return result;
        } catch (Exception e) {
            database.readFailed(subject, e);
            return null;
        }
    }

    @FunctionalInterface
    protected interface SqlRead<T> {
        @NonNull T run() throws SQLException;
    }

    protected static void selectByKeys(@NonNull String query, @NonNull String keyColumn, @Nullable Set<String> keys, @NonNull RowConsumer consumer) throws SQLException {
        if (keys == null) {
            for (DbRow row : DB.getResults(query)) {
                consumer.accept(row);
            }
            return;
        }
        List<String> all = List.copyOf(keys);
        for (int from = 0; from < all.size(); from += KEY_CHUNK) {
            List<String> chunk = all.subList(from, Math.min(from + KEY_CHUNK, all.size()));
            String filter = " WHERE " + keyColumn + " IN (" + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")";
            for (DbRow row : DB.getResults(query + filter, chunk.toArray())) {
                consumer.accept(row);
            }
        }
    }

    @FunctionalInterface
    protected interface RowConsumer {
        void accept(@NonNull DbRow row) throws SQLException;
    }
}