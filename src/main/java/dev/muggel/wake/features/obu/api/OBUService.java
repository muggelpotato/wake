package dev.muggel.wake.features.obu.api;

import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.OBUSyncManager;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

public interface OBUService {
    void resetPlayer(Player player);
    
    void cleanupPlayer(Player player);

    void cleanupBoat(Boat boat);

    void applyDefaultContext(Player player);

    void applyContext(Player player, OBUContext context);

    boolean applySetting(Entity target, OBUSetting setting);

    void applyEntityContext(Boat boat, String contextName);

    void createSandbox(String name);

    void setPlayerActiveSandbox(Player player, String sandboxName);

    String getPlayerActiveSandbox(Player player);

    Set<String> getSandboxNames();

    String getActiveContextName(Player player);

    void broadcastBoatContext(Boat boat);

    void sendBoatContext(Boat boat, Player viewer);

    OBUSyncManager getSyncManager();

    double getVehicleScale(UUID uuid);

    void applyRelativeImpulse(Player player, double x, double y, double z);
}
