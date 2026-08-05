package dev.muggel.wake.features.core;

import dev.muggel.wake.Wake;
import org.bukkit.entity.Boat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.jspecify.annotations.NonNull;

public class EmptyBoatListener implements Listener {
    public static final String STATE_KEY_KILL_BOAT_ON_EXIT = "core.killboatonexit";
    public static final boolean DEFAULT_KILL_BOAT_ON_EXIT = false;
    private final Wake plugin;
    public EmptyBoatListener(@NonNull Wake plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(@NonNull VehicleExitEvent event) {
        if (!(event.getVehicle() instanceof Boat boat)) return;
        if (!plugin.getStateDao().get(STATE_KEY_KILL_BOAT_ON_EXIT, DEFAULT_KILL_BOAT_ON_EXIT)) return;
        if (boat.getPassengers().stream().allMatch(event.getExited()::equals)) {
            boat.remove();
        }
    }
}