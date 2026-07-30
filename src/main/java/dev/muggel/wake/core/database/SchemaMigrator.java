package dev.muggel.wake.core.database;

import co.aikar.idb.DB;
import co.aikar.idb.DbStatement;
import dev.muggel.wake.Wake;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.List;

/**
 * Runs versioned schema upgrades at boot, before any cache loads. <br>
 * Each {@link WakeDao} declares its target schema version and the SQL steps to upgrade one version to the next. <br>
 * Failed migrations abort module loading.
 */
public class SchemaMigrator {
    private final Wake plugin;
    private final Dialect dialect;
    private boolean backedUp = false;
    public SchemaMigrator(@NonNull Wake plugin, @NonNull Dialect dialect) {
        this.plugin = plugin;
        this.dialect = dialect;
        try {
            DB.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS wake_schema_version (
                        module VARCHAR(64) PRIMARY KEY,
                        version INT NOT NULL
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize schema version table", e);
        }
    }

    public @Nullable Integer storedVersion(@NonNull String schemaId) {
        try {
            Object value = DB.getFirstColumn("SELECT version FROM wake_schema_version WHERE module = ?", schemaId);
            return value == null ? null : ((Number) value).intValue();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read schema version for " + schemaId, e);
        }
    }

    public void stamp(@NonNull String schemaId, int version) {
        try {
            DB.executeUpdate("REPLACE INTO wake_schema_version (module, version) VALUES (?, ?)", schemaId, version);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to stamp schema version for " + schemaId, e);
        }
    }

    public void migrate(@NonNull WakeDao dao, @NonNull String schemaId, int fromVersion, int targetVersion) {
        backupOnce();
        for (int version = fromVersion; version < targetVersion; version++) {
            List<String> steps = dao.migrationSteps(version, dialect);
            plugin.getLogger().info("Migrating schema '" + schemaId + "' v" + version + " -> v" + (version + 1) + " (" + steps.size() + " steps)");
            try (DbStatement stm = new DbStatement()) {
                stm.startTransaction();
                for (String sql : steps) {
                    try {
                        stm.executeUpdateQuery(sql);
                    } catch (SQLException e) {
                        throw new IllegalStateException("Migration step failed for '" + schemaId + "' v" + version + " -> v" + (version + 1) + ": " + sql, e);
                    }
                }
                stm.executeUpdateQuery("REPLACE INTO wake_schema_version (module, version) VALUES (?, ?)", schemaId, version + 1);
                stm.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("Migration failed for '" + schemaId + "' v" + version + " -> v" + (version + 1), e);
            }
        }
    }

    private void backupOnce() {
        if (backedUp) return;
        backedUp = true;
        if (dialect == Dialect.MARIADB) {
            plugin.getLogger().warning("Schema migrations pending: DDL cannot roll back, dump the database first if you need a restore path next time");
            return;
        }
        File dbFile = new File(plugin.getDataFolder(), DatabasePool.SQLITE_FILE);
        if (!dbFile.exists()) return;
        File backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            plugin.getLogger().warning("Could not create backups directory, migrating without a backup");
            return;
        }
        String baseName = "wake-pre-migration-" + System.currentTimeMillis();
        try {
            Files.copy(dbFile.toPath(), new File(backupDir, baseName + ".db").toPath(), StandardCopyOption.REPLACE_EXISTING);
            for (String suffix : new String[]{"-wal", "-shm"}) {
                File extra = new File(plugin.getDataFolder(), DatabasePool.SQLITE_FILE + suffix);
                if (extra.exists()) {
                    Files.copy(extra.toPath(), new File(backupDir, baseName + ".db" + suffix).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            plugin.getLogger().info("Backed up " + DatabasePool.SQLITE_FILE + " to backups/" + baseName + ".db before migrating");
        } catch (IOException e) {
            throw new IllegalStateException("Could not back up " + DatabasePool.SQLITE_FILE + " before migrating", e);
        }
    }
}