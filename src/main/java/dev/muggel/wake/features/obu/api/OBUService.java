package dev.muggel.wake.features.obu.api;

import org.bukkit.entity.Player;

import java.util.UUID;

public interface OBUService {
    double getVehicleScale(UUID uuid);

    void applyRelativeImpulse(Player player, double x, double y, double z);
}