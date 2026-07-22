package dev.muggel.wake.core.sync;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.module.WakeModule;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Cross-server cache invalidation via Valkey/Redis pub-sub. <br>
 * After database writes, the dirty scope (usually a module id) is published and other servers reload just that scope. <br>
 * Only active when configured with a shared MariaDB database. Without it, publishing is a no-op.
 */
public class SyncService {
    private static final String CHANNEL = "wake:sync";
    private static final int TIMEOUT_MILLIS = 2000;
    public static final String SCOPE_STATE = "state";
    public static final String SCOPE_FULL = "full";
    private final Wake plugin;
    private final String serverId = UUID.randomUUID().toString();
    private final boolean enabled;
    private volatile boolean running = false;
    private volatile boolean publishFailed = false;
    private volatile long lastFailedPublishMillis = 0;
    private volatile boolean missedWhileDegraded = false;
    private @Nullable JedisPooled publisher;
    private volatile @Nullable Jedis subscriberConnection;
    private @Nullable Thread subscriberThread;
    public SyncService(@NonNull Wake plugin) {
        this.plugin = plugin;
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("sync");
        boolean wanted = config != null && config.getBoolean("enabled", false);
        if (wanted && !isSharedDatabase()) {
            plugin.getLogger().info("Cross-server sync inactive (needs a shared mariadb database)");
            wanted = false;
        }
        this.enabled = wanted;
        if (!enabled) {
            return;
        }
        String host = config.getString("redis.host", "localhost");
        int port = config.getInt("redis.port", 6379);
        String password = config.getString("redis.password", "");
        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(TIMEOUT_MILLIS)
                .socketTimeoutMillis(TIMEOUT_MILLIS);
        if (!password.isEmpty()) {
            clientConfig.password(password);
        }
        this.publisher = new JedisPooled(new HostAndPort(host, port), clientConfig.build());
        this.running = true;
        this.subscriberThread = new Thread(() -> subscribeLoop(host, port, clientConfig.build()), plugin.getName() + "-Sync-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        plugin.getLogger().info("Cross-server sync enabled (redis " + host + ":" + port + ")");
    }

    private boolean isSharedDatabase() {
        String type = plugin.getConfig().getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
        return "mariadb".equals(type) || "mysql".equals(type);
    }

    public void publish(String scope) {
        if (!enabled || publisher == null) {
            return;
        }
        if (publishFailed && System.currentTimeMillis() - lastFailedPublishMillis < 5000) {
            return;
        }
        try {
            String effectiveScope = publishFailed ? SCOPE_FULL : scope;
            publisher.publish(CHANNEL, serverId + "|" + effectiveScope);
            publishFailed = false;
        } catch (Exception e) {
            lastFailedPublishMillis = System.currentTimeMillis();
            if (!publishFailed) {
                publishFailed = true;
                plugin.getLogger().log(Level.WARNING, "Sync publish failed: other servers resync when it recovers", e);
            }
        }
    }

    @SuppressWarnings("BusyWait")
    private void subscribeLoop(String host, int port, JedisClientConfig clientConfig) {
        boolean needResync = false;
        int failures = 0;
        long backoffMillis = 2000;
        while (running) {
            JedisPubSub subscription = new JedisPubSub() {
                @Override
                public void onMessage(String channel, @NonNull String message) {
                    int separator = message.indexOf('|');
                    if (separator < 0 || message.substring(0, separator).equals(serverId)) {
                        return;
                    }
                    dispatch(message.substring(separator + 1));
                }
            };
            try (Jedis jedis = new Jedis(new HostAndPort(host, port), clientConfig)) {
                subscriberConnection = jedis;
                if (!running) {
                    return;
                }
                jedis.ping();
                if (needResync) {
                    dispatch(SCOPE_FULL);
                    needResync = false;
                }
                failures = 0;
                backoffMillis = 2000;
                jedis.subscribe(subscription, CHANNEL);
            } catch (Exception e) {
                needResync = true;
                if (running && (backoffMillis < 30000 || ++failures % 10 == 0)) {
                    plugin.getLogger().warning("Sync subscriber disconnected (retrying): " + e.getMessage());
                }
            } finally {
                subscriberConnection = null;
            }
            if (running) {
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMillis = Math.min(backoffMillis * 2, 30000);
            }
        }
    }

    private void dispatch(String scope) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.getDatabaseManager().isDegraded()) {
                    missedWhileDegraded = true;
                    return;
                }
                if (SCOPE_STATE.equals(scope) || SCOPE_FULL.equals(scope)) {
                    plugin.getStateDao().reloadAsync(() -> {
                        for (WakeModule module : plugin.getLoadedModules()) {
                            reloadQuietly(module);
                        }
                    });
                    return;
                }
                for (WakeModule module : plugin.getLoadedModules()) {
                    if (module.getId().equals(scope)) {
                        reloadQuietly(module);
                        return;
                    }
                }
            });
        } catch (IllegalPluginAccessException e) {
            // nothing to reload anymore
        }
    }

    /** Called after database recovery: replays any invalidation dropped while degraded as a full local resync */
    public void resyncAfterRecovery() {
        if (missedWhileDegraded) {
            missedWhileDegraded = false;
            dispatch(SCOPE_FULL);
        }
    }

    private void reloadQuietly(WakeModule module) {
        try {
            module.reload();
            if (plugin.getConfig().getBoolean("sync.verbose_logging", false)) {
                plugin.getLogger().info("Synced module '" + module.getId() + "' from another server");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to sync module '" + module.getId() + "'", e);
        }
    }

    public void shutdown() {
        running = false;
        closeSubscriberConnection();
        Thread thread = this.subscriberThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closeSubscriberConnection();
        }
        if (publisher != null) {
            publisher.close();
            publisher = null;
        }
    }

    private void closeSubscriberConnection() {
        Jedis connection = subscriberConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
    }
}