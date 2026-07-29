package dev.muggel.wake.features.obu.service;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PlayerSelections {
    private final Map<UUID, String> sandboxes = new ConcurrentHashMap<>();
    private final Map<UUID, String> contexts = new ConcurrentHashMap<>();

    @Nullable String sandbox(@NonNull UUID uuid) {
        return sandboxes.get(uuid);
    }

    @NonNull String context(@NonNull UUID uuid) {
        return contexts.getOrDefault(uuid, OBUContextManager.DEFAULT_CONTEXT);
    }

    void setSandbox(@NonNull UUID uuid, @Nullable String sandboxName) {
        if (sandboxName == null) {
            sandboxes.remove(uuid);
        } else {
            sandboxes.put(uuid, sandboxName.toLowerCase(Locale.ROOT));
        }
    }

    void setContext(@NonNull UUID uuid, @NonNull String contextName) {
        contexts.put(uuid, contextName);
    }

    boolean hasSelection(@NonNull UUID uuid) {
        return contexts.containsKey(uuid) || sandboxes.containsKey(uuid);
    }

    @NonNull Set<UUID> clearSandbox(@NonNull String sandboxName) {
        String lower = sandboxName.toLowerCase(Locale.ROOT);
        Set<UUID> cleared = new HashSet<>();
        for (Map.Entry<UUID, String> entry : Map.copyOf(sandboxes).entrySet()) {
            if (lower.equals(entry.getValue()) && sandboxes.remove(entry.getKey(), lower)) {
                cleared.add(entry.getKey());
            }
        }
        return cleared;
    }

    void forget(@NonNull UUID uuid) {
        sandboxes.remove(uuid);
        contexts.remove(uuid);
    }
}