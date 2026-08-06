package dev.muggel.wake.features.obu.delivery;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Boat;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ActiveContexts {
    private final Map<UUID, String> sandboxes = new ConcurrentHashMap<>();
    private final Map<UUID, String> contexts = new ConcurrentHashMap<>();
    private final NamespacedKey boatContextKey;
    public ActiveContexts(@NonNull Wake plugin) {
        this.boatContextKey = new NamespacedKey(plugin, "obu_context");
    }

    public @Nullable String sandboxOf(@NonNull UUID uuid) {
        return sandboxes.get(uuid);
    }

    public @NonNull String contextOf(@NonNull UUID uuid) {
        return contexts.getOrDefault(uuid, OBUContextManager.DEFAULT_CONTEXT);
    }

    public void selectSandbox(@NonNull UUID uuid, @Nullable String sandboxName) {
        if (sandboxName == null) {
            sandboxes.remove(uuid);
        } else {
            sandboxes.put(uuid, canonical(sandboxName));
        }
    }

    public void selectContext(@NonNull UUID uuid, @NonNull String contextName) {
        contexts.put(uuid, canonical(contextName));
    }

    public boolean hasSelection(@NonNull UUID uuid) {
        return contexts.containsKey(uuid) || sandboxes.containsKey(uuid);
    }

    public @NonNull Set<UUID> clearSandbox(@NonNull String sandboxName) {
        String lower = canonical(sandboxName);
        Set<UUID> cleared = new HashSet<>();
        for (Map.Entry<UUID, String> entry : sandboxes.entrySet()) {
            if (lower.equals(entry.getValue()) && sandboxes.remove(entry.getKey(), lower)) {
                cleared.add(entry.getKey());
            }
        }
        return cleared;
    }

    public @Nullable String pinnedOn(@NonNull Boat boat) {
        String pinned = boat.getPersistentDataContainer().get(boatContextKey, PersistentDataType.STRING);
        return pinned == null ? null : canonical(pinned);
    }

    public void pin(@NonNull Boat boat, @Nullable String contextName) {
        if (contextName == null) {
            boat.getPersistentDataContainer().remove(boatContextKey);
        } else {
            boat.getPersistentDataContainer().set(boatContextKey, PersistentDataType.STRING, canonical(contextName));
        }
    }

    public void forgetPlayer(@NonNull UUID uuid) {
        sandboxes.remove(uuid);
        contexts.remove(uuid);
    }

    static @NonNull String canonical(@NonNull String contextName) {
        return contextName.toLowerCase(Locale.ROOT);
    }
}