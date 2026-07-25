package dev.muggel.wake.core.sync;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.module.WakeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Cross-server cache invalidation via Valkey/Redis pub-sub. <br>
 * After database writes, the dirty scope (usually a module id) is published and other servers reload just that scope. <br>
 * Only active when configured with a shared MariaDB database. <br>
 * Lettuce owns reconnection and re-subscribes the channel by itself. <br>
 * Every re-subscribe triggers a full local resync because invalidations may have been missed while disconnected.
 */
public class SyncService {
    private static final String CHANNEL = "wake:sync";
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int COMMAND_TIMEOUT_MILLIS = 5000;
    private static final long CONNECT_RETRY_TICKS = 100;
    public static final String SCOPE_STATE = "state";
    public static final String SCOPE_FULL = "full";
    private final Wake plugin;
    private final String serverId = UUID.randomUUID().toString();
    private final boolean enabled;
    private volatile boolean running = false;
    private volatile boolean publishFailed = false;
    private volatile long lastFailedPublishMillis = 0;
    private volatile @Nullable ClientResources resources;
    private volatile @Nullable RedisClient client;
    private volatile @Nullable StatefulRedisConnection<String, String> pubConnection;
    private volatile boolean subscribedOnce = false;
    private volatile boolean everFailedConnect = false;
    private volatile @Nullable BukkitTask connectRetryTask;
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
        RedisURI.Builder uri = RedisURI.Builder.redis(host, port).withTimeout(Duration.ofMillis(COMMAND_TIMEOUT_MILLIS));
        if (!password.isEmpty()) {
            uri.withPassword(password.toCharArray());
        }
        ClientResources clientResources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        this.resources = clientResources;
        RedisClient redisClient = RedisClient.create(clientResources, uri.build());
        redisClient.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS))
                        .build())
                .build());
        this.client = redisClient;
        this.running = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::tryConnect);
        plugin.getLogger().info("Cross-server sync enabled (redis " + host + ":" + port + ")");
    }

    private boolean isSharedDatabase() {
        String type = plugin.getConfig().getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
        return "mariadb".equals(type) || "mysql".equals(type);
    }

    private void tryConnect() {
        RedisClient redisClient = this.client;
        if (!running || redisClient == null) {
            return;
        }
        StatefulRedisConnection<String, String> pub = null;
        StatefulRedisPubSubConnection<String, String> sub = null;
        try {
            pub = redisClient.connect();
            sub = redisClient.connectPubSub();
            sub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    handleMessage(message);
                }

                @Override
                public void subscribed(String channel, long count) {
                    if (subscribedOnce) {
                        plugin.getLogger().info("Sync subscriber reconnected: running a full resync");
                        dispatch(SCOPE_FULL);
                    }
                    subscribedOnce = true;
                }
            });
            if (everFailedConnect) {
                subscribedOnce = true;
            }
            sub.async().subscribe(CHANNEL);
            this.pubConnection = pub;
        } catch (Exception e) {
            if (sub != null) {
                sub.close();
            }
            if (pub != null) {
                pub.close();
            }
            if (!everFailedConnect) {
                everFailedConnect = true;
                plugin.getLogger().log(Level.WARNING, "Sync bus unreachable, retrying every 5s: " + e.getMessage());
            }
            scheduleConnectRetry();
        }
    }

    private void scheduleConnectRetry() {
        if (!running || !plugin.isEnabled()) {
            return;
        }
        try {
            connectRetryTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::tryConnect, CONNECT_RETRY_TICKS);
        } catch (IllegalPluginAccessException e) {
            // nothing left to sync
        }
    }

    private void handleMessage(@NonNull String message) {
        int separator = message.indexOf('|');
        if (separator < 0 || message.substring(0, separator).equals(serverId)) {
            return;
        }
        dispatch(message.substring(separator + 1));
    }

    public void publish(String scope) {
        if (!enabled) {
            return;
        }
        if (publishFailed && System.currentTimeMillis() - lastFailedPublishMillis < 5000) {
            return;
        }
        try {
            StatefulRedisConnection<String, String> connection = pubConnection;
            if (connection == null) {
                throw new IllegalStateException("sync bus connection not established yet");
            }
            String effectiveScope = publishFailed ? SCOPE_FULL : scope;
            connection.sync().publish(CHANNEL, serverId + "|" + effectiveScope);
            publishFailed = false;
        } catch (Exception e) {
            lastFailedPublishMillis = System.currentTimeMillis();
            if (!publishFailed) {
                publishFailed = true;
                plugin.getLogger().log(Level.WARNING, "Sync publish failed: other servers resync when it recovers", e);
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

    /** Called after database recovery: local caches may predate the replayed journal and invalidations from other servers were dropped -> full local resync */
    public void resyncAfterRecovery() {
        dispatch(SCOPE_FULL);
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
        BukkitTask retryTask = this.connectRetryTask;
        if (retryTask != null) {
            retryTask.cancel();
            this.connectRetryTask = null;
        }
        this.pubConnection = null;
        RedisClient redisClient = this.client;
        if (redisClient != null) {
            redisClient.shutdown(0, 2, TimeUnit.SECONDS);
            this.client = null;
        }
        ClientResources clientResources = this.resources;
        if (clientResources != null) {
            try {
                clientResources.shutdown(0, 1, TimeUnit.SECONDS).await(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.resources = null;
        }
    }
}