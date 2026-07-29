package dev.muggel.wake.core.sync;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.Scheduling;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The pub-sub channel itself. <br>
 * It carries strings, never reads them. <br>
 * Lettuce owns reconnection and re-subscribes by itself. <br>
 * Publish fails are retried until resync payload gets through.
 */
class SyncBus {
    private static final String CHANNEL = "wake:sync";
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int COMMAND_TIMEOUT_MILLIS = 5000;
    private static final long CONNECT_RETRY_TICKS = 100;
    private static final long PUBLISH_RETRY_TICKS = 100;
    private static final long PUBLISH_DRAIN_MILLIS = 2000;
    private final Wake plugin;
    private final String serverId = UUID.randomUUID().toString();
    private final String resyncPayload;
    private final Consumer<String> inbound;
    private final Runnable onResubscribe;
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private volatile boolean running;
    private volatile boolean publishFailed = false;
    private volatile @Nullable ClientResources resources;
    private volatile @Nullable RedisClient client;
    private volatile @Nullable StatefulRedisConnection<String, String> pubConnection;
    private volatile @Nullable RedisFuture<Long> lastPublish;
    private volatile boolean subscribedOnce = false;
    private volatile boolean everFailedConnect = false;
    private volatile @Nullable BukkitTask connectRetryTask;
    SyncBus(@NonNull Wake plugin, @NonNull String host, int port, @NonNull String password, @NonNull String resyncPayload, @NonNull Consumer<String> inbound, @NonNull Runnable onResubscribe) {
        this.plugin = plugin;
        this.resyncPayload = resyncPayload;
        this.inbound = inbound;
        this.onResubscribe = onResubscribe;
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
        Scheduling.async(plugin, this::tryConnect);
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
                    String payload = SyncMessage.payloadFor(message, serverId);
                    if (payload != null) {
                        inbound.accept(payload);
                    }
                }

                @Override
                public void subscribed(String channel, long count) {
                    if (subscribedOnce) {
                        onResubscribe.run();
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
            if (running) {
                connectRetryTask = Scheduling.laterAsync(plugin, this::tryConnect, CONNECT_RETRY_TICKS);
            }
        }
    }

    void publish(@NonNull String payload) {
        if (publishFailed) {
            return;
        }
        StatefulRedisConnection<String, String> connection = pubConnection;
        if (connection == null) {
            publishLost(new IllegalStateException("sync bus connection not established yet"));
            return;
        }
        try {
            RedisFuture<Long> sent = connection.async().publish(CHANNEL, SyncMessage.addressed(serverId, payload));
            lastPublish = sent;
            sent.whenComplete((ignored, error) -> {
                if (error != null) {
                    publishLost(error);
                }
            });
        } catch (Exception e) {
            publishLost(e);
        }
    }

    private void publishLost(@NonNull Throwable error) {
        if (!publishFailed) {
            publishFailed = true;
            plugin.getLogger().log(Level.WARNING, "Sync publish failed: other servers resync when it recovers", error);
        }
        if (!retryScheduled.compareAndSet(false, true)) {
            return;
        }
        Scheduling.laterAsync(plugin, () -> {
            retryScheduled.set(false);
            publishFailed = false;
            publish(resyncPayload);
        }, PUBLISH_RETRY_TICKS);
    }

    void shutdown() {
        running = false;
        BukkitTask retryTask = this.connectRetryTask;
        if (retryTask != null) {
            retryTask.cancel();
            this.connectRetryTask = null;
        }
        RedisFuture<Long> pending = this.lastPublish;
        if (pending != null) {
            try {
                pending.await(PUBLISH_DRAIN_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.lastPublish = null;
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