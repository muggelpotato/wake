package dev.muggel.wake.features.drydock.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.arguments.ArgumentHelper;
import dev.muggel.wake.core.commands.arguments.KeyArgumentType;
import dev.muggel.wake.features.drydock.DrydockModule;
import dev.muggel.wake.features.drydock.boostpads.BoostpadRegistry;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

public final class DrydockCommandHelper {
    private DrydockCommandHelper() {}

    public static @NonNull BoostpadRegistry boostpads(@NonNull Wake plugin) {
        BoostpadRegistry registry = boostpadsOrNull(plugin);
        if (registry == null) {
            throw new IllegalStateException("Drydock module is not loaded");
        }
        return registry;
    }

    @Contract(value = "_ -> new", pure = true)
    public static @NonNull KeyArgumentType boostpadKey(@NonNull Wake plugin) {
        return KeyArgumentType.of(() -> configuredPads(plugin), "commands.drydock.boostpad.block_not_found");
    }

    private static @Nullable BoostpadRegistry boostpadsOrNull(@NonNull Wake plugin) {
        DrydockModule module = plugin.getModule(DrydockModule.class);
        return module != null ? module.getBoostpads() : null;
    }

    private static @NonNull Set<NamespacedKey> configuredPads(@NonNull Wake plugin) {
        BoostpadRegistry registry = boostpadsOrNull(plugin);
        if (registry == null) {
            return Set.of();
        }
        Set<NamespacedKey> pads = new HashSet<>();
        for (String stored : registry.cachedBoostpads().keySet()) {
            NamespacedKey key = ArgumentHelper.resolveKey(stored);
            if (key != null && key.toString().equals(stored)) {
                pads.add(key);
            }
        }
        return pads;
    }
}