package dev.muggel.wake.core.database;

import co.aikar.idb.DB;
import dev.muggel.wake.Wake;

import java.sql.SQLException;
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

    public void initTables() {
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
        for (String table : getTableSchemas().keySet()) {
            try {
                DB.executeUpdate("DELETE FROM " + table);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to reset table: " + table, e);
            }
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