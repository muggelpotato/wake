package dev.muggel.wake.core.sync;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.database.Dialect;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Cross-server cache invalidation via Valkey/Redis pub-sub. <br>
 * Database writes -> moved rows are published -> other servers read them back <br>
 * Only active when configured with a shared MariaDB database. <br>
 * 1. {@link SyncMessage} spells an announcement <br>
 * 2. {@link SyncBus} carries it <br>
 * 3. {@link SyncDispatcher} acts on the ones that arrive.
 */
public class SyncService {
    public static final String SCOPE_STATE = "state";
    public static final String SCOPE_FULL = "full";
    private final SyncDispatcher dispatcher;
    private final @Nullable SyncBus bus;
    public SyncService(@NonNull Wake plugin) {
        this.dispatcher = new SyncDispatcher(plugin);
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("sync");
        boolean wanted = config != null && config.getBoolean("enabled", false);
        if (wanted && plugin.getDatabaseManager().dialect() != Dialect.MARIADB) {
            plugin.getLogger().info("Cross-server sync inactive (needs a shared mariadb database)");
            wanted = false;
        }
        if (!wanted) {
            this.bus = null;
            return;
        }
        String host = config.getString("redis.host", "localhost");
        int port = config.getInt("redis.port", 6379);
        this.bus = new SyncBus(plugin, host, port, config.getString("redis.password", ""),
                SCOPE_FULL, dispatcher::accept, () -> {
                    plugin.getLogger().info("Sync subscriber reconnected: running a full resync");
                    dispatcher.accept(SCOPE_FULL);
                });
        plugin.getLogger().info("Cross-server sync enabled (redis " + host + ":" + port + ")");
    }

    public void publishKeys(String scope, String table, @NonNull Set<String> keys) {
        if (bus == null) {
            return;
        }
        bus.publish(SyncMessage.encode(scope, table, keys));
    }

    public void publish(String scope) {
        if (bus != null) {
            bus.publish(scope);
        }
    }

    // post database recovery
    public void resyncAfterRecovery() {
        dispatcher.accept(SCOPE_FULL);
    }

    public void shutdown() {
        if (bus != null) {
            bus.shutdown();
        }
    }
}