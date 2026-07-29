package dev.muggel.wake.features.obu.api;

import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public interface OBUService {
    double getVehicleScale(@NonNull UUID uuid);

    void applyRelativeImpulse(@NonNull Player player, double x, double y, double z);
}