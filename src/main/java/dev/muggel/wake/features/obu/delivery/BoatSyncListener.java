package dev.muggel.wake.features.obu.delivery;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.Scheduling;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.jspecify.annotations.NonNull;

public final class BoatSyncListener implements Listener {
    private final Wake plugin;
    private final OBUSyncManager syncManager;
    public BoatSyncListener(@NonNull Wake plugin, @NonNull OBUSyncManager syncManager) {
        this.plugin = plugin;
        this.syncManager = syncManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTrack(@NonNull PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof Boat boat) {
            syncManager.syncToViewer(boat, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(@NonNull VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Boat boat && event.getEntered() instanceof Player player) {
            syncManager.broadcastSync(boat, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(@NonNull VehicleExitEvent event) {
        if (event.getVehicle() instanceof Boat boat && event.getExited() instanceof Player) {
            Scheduling.onMain(plugin, () -> syncManager.broadcastSync(boat));
        }
    }

    @EventHandler
    public void onEntityRemove(@NonNull EntityRemoveEvent event) {
        if (event.getEntity() instanceof Boat) {
            syncManager.cleanup(event.getEntity().getUniqueId());
        }
    }
}