package dev.muggel.wake.features.obu.api;

import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public interface OBUService {
    double getVehicleScale(@NonNull Boat boat);

    void applyRelativeImpulse(@NonNull Player player, double x, double y, double z);
}