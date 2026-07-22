package dev.muggel.wake.features.drydock.listeners;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.util.VehicleCollisionUtils;
import dev.muggel.wake.features.drydock.api.BoostpadConfig;
import dev.muggel.wake.features.drydock.api.DrydockService;
import dev.muggel.wake.features.drydock.api.events.PlayerHitBoostpadEvent;
import dev.muggel.wake.features.drydock.commands.boostpad.BoostpadCommand;
import dev.muggel.wake.features.drydock.integration.obu.OBUBoostpadIntegration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BoostpadDetectorListener implements Listener {
    private static final double SURFACE_BAND = 0.15;
    private static final int MAX_SCAN_BLOCKS = 4096;
    private final Wake plugin;
    private final DrydockService drydockService;
    private final Map<UUID, Map<String, Long>> lastBoostTimes = new ConcurrentHashMap<>();
    private volatile boolean isRegistered = false;
    public BoostpadDetectorListener(@NonNull Wake plugin, DrydockService drydockService) {
        this.plugin = plugin;
        this.drydockService = drydockService;
        updateRegistration();
    }

    public void updateRegistration() {
        boolean enabled = plugin.getStateDao().get(BoostpadCommand.STATE_KEY_ENABLED, true);
        boolean shouldBeRegistered = enabled && !drydockService.getBoostpadConfigs().isEmpty();
        if (shouldBeRegistered && !isRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            isRegistered = true;
        } else if (!shouldBeRegistered && isRegistered) {
            HandlerList.unregisterAll(this);
            lastBoostTimes.clear();
            isRegistered = false;
        }
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        lastBoostTimes.clear();
        isRegistered = false;
    }

    @EventHandler
    public void onEntityRemove(@NonNull EntityRemoveEvent event) {
        if (event.getEntity() instanceof Boat) {
            lastBoostTimes.remove(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onVehicleMove(@NonNull VehicleMoveEvent event) {
        Map<Material, BoostpadConfig> materialConfigs = drydockService.getBoostpadConfigs();
        if (materialConfigs.isEmpty()) {
            return;
        }
        if (!(event.getVehicle() instanceof Boat boat)) {
            return;
        }
        var passengers = boat.getPassengers();
        if (passengers.isEmpty() || !(passengers.getFirst() instanceof Player player)) {
            return;
        }
        BoundingBox box = boat.getBoundingBox();
        double halfWidth = (box.getMaxX() - box.getMinX()) / 2.0;
        double halfLength = (box.getMaxZ() - box.getMinZ()) / 2.0;
        UUID boatId = boat.getUniqueId();
        double scale = OBUBoostpadIntegration.getVehicleScale(boatId);
        if (scale != 1.0 && scale > 0) {
            halfWidth *= scale;
            halfLength *= scale;
        }
        World world = boat.getWorld();
        Location to = event.getTo();
        Location from = event.getFrom();
        Vector toVec = to.toVector();
        Vector fromVec = from.getWorld() == world ? from.toVector() : toVec;
        double maxOffsetMultiplier = Math.max(0.0, drydockService.getMaxOffsetMultiplier());
        double reachX = halfWidth * (1.0 + maxOffsetMultiplier);
        double reachZ = halfLength * (1.0 + maxOffsetMultiplier);
        int minX = (int) Math.floor(Math.min(fromVec.getX(), toVec.getX()) - reachX);
        int maxX = (int) Math.floor(Math.max(fromVec.getX(), toVec.getX()) + reachX);
        int minZ = (int) Math.floor(Math.min(fromVec.getZ(), toVec.getZ()) - reachZ);
        int maxZ = (int) Math.floor(Math.max(fromVec.getZ(), toVec.getZ()) + reachZ);
        int yMin = (int) Math.floor(Math.min(fromVec.getY(), toVec.getY()) - 0.85);
        int yMax = (int) Math.floor(Math.max(fromVec.getY(), toVec.getY()) - 0.85);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) * (yMax - yMin + 1) > MAX_SCAN_BLOCKS) {
            fromVec = toVec;
            minX = (int) Math.floor(toVec.getX() - reachX);
            maxX = (int) Math.floor(toVec.getX() + reachX);
            minZ = (int) Math.floor(toVec.getZ() - reachZ);
            maxZ = (int) Math.floor(toVec.getZ() + reachZ);
            yMin = (int) Math.floor(toVec.getY() - 0.85);
            yMax = yMin;
        }
        List<PadHit> hits = null;
        for (int y = yMin; y <= yMax; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    Material mat = world.getType(x, y, z);
                    BoostpadConfig config = materialConfigs.get(mat);
                    if (config == null) {
                        continue;
                    }
                    double halfX = Math.max(0.0, halfWidth * (1.0 + config.offsetMultiplier()));
                    double halfZ = Math.max(0.0, halfLength * (1.0 + config.offsetMultiplier()));
                    double fraction = VehicleCollisionUtils.intersectionFraction(
                            fromVec.getX(), fromVec.getY(), fromVec.getZ(),
                            toVec.getX(), toVec.getY(), toVec.getZ(),
                            x - halfX, y + 1 - SURFACE_BAND, z - halfZ,
                            x + 1 + halfX, y + 1 + SURFACE_BAND, z + 1 + halfZ);
                    if (fraction >= 0) {
                        if (hits == null) {
                            hits = new ArrayList<>();
                        }
                        hits.add(new PadHit(fraction, config, x, y, z));
                    }
                }
            }
        }
        if (hits == null) {
            return;
        }
        hits.sort(Comparator.comparingDouble(PadHit::fraction));
        long now = System.currentTimeMillis();
        Map<String, Long> boatCooldowns = lastBoostTimes.computeIfAbsent(boatId, k -> new HashMap<>());
        boolean firedJumpPad = false;
        for (PadHit hit : hits) {
            BoostpadConfig config = hit.config();
            if (config.forceY() > 0) {
                if (firedJumpPad || !boat.isOnGround() || boat.getVelocity().getY() < -0.1) {
                    continue;
                }
            }
            Long lastBoost = boatCooldowns.get(config.blockKey());
            if (lastBoost != null && (now - lastBoost) < config.delayMs()) {
                continue;
            }
            boatCooldowns.put(config.blockKey(), now);
            if (config.forceY() > 0) {
                firedJumpPad = true;
            }
            Block hitBlock = world.getBlockAt(hit.x(), hit.y(), hit.z());
            PlayerHitBoostpadEvent hitEvent = new PlayerHitBoostpadEvent(player, boat, hitBlock, config.forceX(), config.forceY(), config.forceZ());
            Bukkit.getPluginManager().callEvent(hitEvent);
        }
    }

    private record PadHit(double fraction, BoostpadConfig config, int x, int y, int z) {}
}