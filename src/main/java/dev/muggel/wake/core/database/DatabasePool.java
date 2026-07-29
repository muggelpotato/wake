package dev.muggel.wake.core.database;

import co.aikar.idb.BaseDatabase;
import co.aikar.idb.BukkitDB;
import co.aikar.idb.DB;
import co.aikar.idb.DatabaseOptions;
import co.aikar.idb.PooledDatabaseOptions;
import com.zaxxer.hikari.HikariDataSource;
import dev.muggel.wake.Wake;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.logging.Level;

/** Opens the connection pool everything else in the layer runs on at boot */
final class DatabasePool {
    private DatabasePool() {}

    record Handle(@NonNull Dialect dialect, @Nullable HikariDataSource dataSource) {}

    static @NonNull Handle open(@NonNull Wake plugin) {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("database");
        Dialect dialect = Dialect.SQLITE;
        if (config == null) {
            plugin.getLogger().warning("Database configuration missing, defaulting to SQLite");
            openSQLite(plugin);
        } else if (isMySQLFamily(config.getString("type", "sqlite"))) {
            dialect = Dialect.MARIADB;
            openMariaDB(plugin, config);
        } else {
            openSQLite(plugin);
        }
        HikariDataSource dataSource = tightenTimeouts(plugin);
        try {
            DB.getFirstColumn("SELECT 1");
        } catch (Exception e) {
            throw new IllegalStateException("Database connection test failed", e);
        }
        return new Handle(dialect, dataSource);
    }

    private static boolean isMySQLFamily(@NonNull String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        return "mariadb".equals(lower) || "mysql".equals(lower);
    }

    private static void openSQLite(@NonNull Wake plugin) {
        DatabaseOptions options = DatabaseOptions.builder()
                .poolName(plugin.getName() + "-DB")
                .logger(plugin.getLogger())
                .sqlite(new File(plugin.getDataFolder(), "wake.db").getPath())
                .build();
        BukkitDB.createHikariDatabase(plugin, PooledDatabaseOptions.builder().options(options).build());
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
        BukkitDB.createHikariDatabase(plugin, PooledDatabaseOptions.builder().options(options).build());
        plugin.getLogger().info("Database ready (MariaDB)");
    }

    /** Hikari's defaults are too long for quick ingame feedback */
    private static @Nullable HikariDataSource tightenTimeouts(@NonNull Wake plugin) {
        try {
            Field field = BaseDatabase.class.getDeclaredField("dataSource");
            field.setAccessible(true);
            if (field.get(DB.getGlobalDatabase()) instanceof HikariDataSource dataSource) {
                dataSource.setConnectionTimeout(5000);
                dataSource.setValidationTimeout(2500);
                return dataSource;
            }
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "Could not tighten pool timeouts", e);
        }
        return null;
    }
}