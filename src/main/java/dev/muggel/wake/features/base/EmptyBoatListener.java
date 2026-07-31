package dev.muggel.wake.features.base;

import org.bukkit.entity.Boat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.jspecify.annotations.NonNull;

public class EmptyBoatListener implements Listener {
    private final BaseModule module;
    public EmptyBoatListener(BaseModule module) {
        this.module = module;
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleExit(@NonNull VehicleExitEvent event) {
        if (event.getVehicle() instanceof Boat boat) {
            if (module.isKillBoatOnExit()) {
                if (boat.getPassengers().size() <= 1 && boat.getPassengers().contains(event.getExited())) {
                    boat.remove();
                }
            }
        }
    }
}