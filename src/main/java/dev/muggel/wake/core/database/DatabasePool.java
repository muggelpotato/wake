package dev.muggel.wake.core.database;

import co.aikar.idb.BaseDatabase;
import co.aikar.idb.DB;
import co.aikar.idb.DatabaseOptions;
import co.aikar.idb.HikariPooledDatabase;
import co.aikar.idb.PooledDatabaseOptions;
import com.zaxxer.hikari.HikariDataSource;
import dev.muggel.wake.Wake;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.lang.reflect.Field;

/** Opens the connection pool everything else in the layer runs on at boot */
final class DatabasePool {
    static final String SQLITE_FILE = "wake.db";
    static final String PROBE_QUERY = "SELECT 1";
    private static final int DEFAULT_MAX_CONNECTIONS = 5;
    private static final int DEFAULT_MIN_IDLE = 1;
    private DatabasePool() {}

    record Handle(@NonNull Dialect dialect, @NonNull HikariDataSource dataSource) {}

    static @NonNull Handle open(@NonNull Wake plugin) {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("database");
        Dialect dialect = Dialect.SQLITE;
        if (config == null) {
            plugin.getLogger().warning("Database configuration missing, defaulting to SQLite");
            openSQLite(plugin);
        } else if ("mariadb".equalsIgnoreCase(config.getString("type", "sqlite"))) {
            dialect = Dialect.MARIADB;
            openMariaDB(plugin, config);
        } else {
            openSQLite(plugin);
        }
        HikariDataSource dataSource = tightenTimeouts();
        try {
            DB.getFirstColumn(PROBE_QUERY);
        } catch (Exception e) {
            throw new IllegalStateException("Database connection test failed", e);
        }
        return new Handle(dialect, dataSource);
    }

    private static void openSQLite(@NonNull Wake plugin) {
        DatabaseOptions options = DatabaseOptions.builder()
                .poolName(plugin.getName() + "-DB")
                .logger(plugin.getLogger())
                .sqlite(new File(plugin.getDataFolder(), SQLITE_FILE).getPath())
                .build();
        openPool(plugin, options);
        plugin.getLogger().info("Database ready (SQLite)");
    }

    private static void openMariaDB(@NonNull Wake plugin, @NonNull ConfigurationSection config) {
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
                .dsn("mariadb://" + host + ":" + port + "/" + database + "?socketTimeout=3000")
                .build();
        openPool(plugin, options);
        plugin.getLogger().info("Database ready (MariaDB)");
    }

    private static void openPool(@NonNull Wake plugin, @NonNull DatabaseOptions options) {
        FileConfiguration config = plugin.getConfig();
        DB.setGlobalDatabase(new HikariPooledDatabase(PooledDatabaseOptions.builder()
                .options(options)
                .maxConnections(Math.max(1, config.getInt("database.pool.maximum_connections", DEFAULT_MAX_CONNECTIONS)))
                .minIdleConnections(config.getInt("database.pool.minimum_idle", DEFAULT_MIN_IDLE))
                .build()));
    }

    /** Hikari's defaults are too long for quick ingame feedback */
    private static @NonNull HikariDataSource tightenTimeouts() {
        try {
            Field field = BaseDatabase.class.getDeclaredField("dataSource");
            field.setAccessible(true);
            if (!(field.get(DB.getGlobalDatabase()) instanceof HikariDataSource dataSource)) {
                throw new IllegalStateException("IDB is not pooling through HikariCP (its timeouts cannot be bounded)");
            }
            dataSource.setConnectionTimeout(5000);
            dataSource.setValidationTimeout(2500);
            return dataSource;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("IDB's data source is unreachable (its timeouts cannot be bounded)", e);
        }
    }
}