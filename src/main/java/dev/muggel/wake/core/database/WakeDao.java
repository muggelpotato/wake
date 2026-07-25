package dev.muggel.wake.core.database;

import co.aikar.idb.DB;
import co.aikar.idb.DbStatement;
import dev.muggel.wake.Wake;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The base class for a module's data access. <br>
 * 1. Declare tables in {@code getTableSchemas()}, keep an in-memory cache in your fields, and write through {@code asyncUpdate(...)} <br>
 * 2. Update the cache first, the SQL runs later on the writer thread <br>
 * 3. Keep SQL parameterized and portable across SQLite and MariaDB <br>
 * 4. Register instances via the module's {@code registerDao(...)} so database reset covers them
 */
public abstract class WakeDao {
    protected final Wake plugin;
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
    }

    protected void asyncUpdate(String errorMessage, String query, Object... params) {
        DatabaseManager db = plugin.getDatabaseManager();
        db.queueWrite(errorMessage, syncScope(), db.currentActor(), query, params);
    }

    protected void asyncUpdateFor(UUID subject, String errorMessage, String query, Object... params) {
        plugin.getDatabaseManager().queueWrite(errorMessage, null, subject, query, params);
    }

    @SuppressWarnings("SameParameterValue")
    protected void asyncUpdateLocal(String errorMessage, String query, Object... params) {
        DatabaseManager db = plugin.getDatabaseManager();
        db.queueWrite(errorMessage, null, db.currentActor(), query, params);
    }
}