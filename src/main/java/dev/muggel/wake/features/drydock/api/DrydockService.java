package dev.muggel.wake.features.drydock.api;

import org.bukkit.entity.Player;

public interface DrydockService {
    void giveDrydockBoat(Player player, String boatType, int variant);
}
