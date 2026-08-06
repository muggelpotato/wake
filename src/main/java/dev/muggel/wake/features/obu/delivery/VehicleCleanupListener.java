package dev.muggel.wake.features.obu.delivery;

import org.bukkit.entity.Boat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.jspecify.annotations.NonNull;

public final class VehicleCleanupListener implements Listener {
    private final OBUSyncManager syncManager;
    public VehicleCleanupListener(@NonNull OBUSyncManager syncManager) {
        this.syncManager = syncManager;
    }

    @EventHandler
    public void onEntityRemove(@NonNull EntityRemoveEvent event) {
        if (event.getEntity() instanceof Boat) {
            syncManager.cleanup(event.getEntity().getUniqueId());
        }
    }
}