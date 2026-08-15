package dev.muggel.wake.core.module;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where modules publish their {@code api/} services for others to find. <br>
 * Cross-module only <br>
 * Modules publish through {@code registerService} and the withdrawal comes with disable. <br>
 * Consumers resolve on every use and handle absence. <br>
 * Never cache the returned reference.
 */
public final class ServiceRegistry {
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    <T> void register(@NonNull Class<T> type, @NonNull T service) {
        if (services.putIfAbsent(type, service) != null) {
            throw new IllegalStateException("Service for " + type.getName() + " is already registered");
        }
    }

    void unregister(@NonNull Class<?> type, @NonNull Object service) {
        services.remove(type, service);
    }

    public <T> @Nullable T get(@NonNull Class<T> type) {
        return type.cast(services.get(type));
    }
}