package dev.muggel.wake.core.listeners;

import dev.muggel.wake.core.WakeModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleExitEvent;

public class BoatListener implements Listener {
    private final WakeModule module;
    public BoatListener(WakeModule module) {
        this.module = module;
    }
    @EventHandler(ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getVehicle() instanceof Boat boat) {
            if (module.isKillBoatOnExit()) {
                Bukkit.getScheduler().runTask(module.getPlugin(), () -> {
                    if (boat.isValid() && boat.getPassengers().isEmpty()) {
                        boat.remove();
                    }
                });
            }
        }
    }
}
