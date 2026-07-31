package dev.muggel.wake.core.module;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where modules publish their {@code api/} services for others to find. <br>
 * Cross-module only <br>
 * Register on enable, unregister on disable. <br>
 * Consumers resolve on every use and handle absence. <br>
 * Never cache the returned reference.
 */
public final class ServiceRegistry {
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> void register(Class<T> clazz, T service) {
        if (clazz == null) {
            throw new IllegalArgumentException("Service class cannot be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("Service implementation cannot be null");
        }
        if (services.putIfAbsent(clazz, service) != null) {
            throw new IllegalStateException("Service for " + clazz.getName() + " is already registered");
        }
    }

    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(Class<T> clazz) {
        if (clazz == null) {
            return null;
        }
        Object service = services.get(clazz);
        return service != null ? (T) service : null;
    }

    public void unregister(Class<?> clazz) {
        if (clazz != null) {
            services.remove(clazz);
        }
    }

    public void unregisterAll() {
        services.clear();
    }
}