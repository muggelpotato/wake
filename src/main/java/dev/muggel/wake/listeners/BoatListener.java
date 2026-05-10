package dev.muggel.wake.listeners;

import dev.muggel.wake.Wake;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleExitEvent;

public class BoatListener implements Listener {
    private final Wake plugin;

    public BoatListener(Wake plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getVehicle() instanceof Boat boat) {
            if (plugin.isKillBoatOnExit()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (boat.isValid() && boat.getPassengers().isEmpty()) {
                        boat.remove();
                    }
                });
            }
        }
    }
}