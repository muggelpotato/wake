package dev.muggel.wake.features.drydock.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.DrydockModule;
import dev.muggel.wake.features.drydock.boostpads.BoostpadRegistry;
import org.jspecify.annotations.NonNull;

public final class DrydockCommandHelper {
    private DrydockCommandHelper() {}

    public static @NonNull BoostpadRegistry boostpads(@NonNull Wake plugin) {
        DrydockModule module = plugin.getModule(DrydockModule.class);
        BoostpadRegistry registry = module != null ? module.getBoostpads() : null;
        if (registry == null) {
            throw new IllegalStateException("Drydock module is not loaded");
        }
        return registry;
    }
}