package dev.muggel.wake.features.drydock.listeners;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.api.events.PlayerHitBoostpadEvent;
import dev.muggel.wake.features.drydock.commands.DrydockBoostpadCommand;
import dev.muggel.wake.features.drydock.integration.obu.OBUBoostpadIntegration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BoostpadDetectorListener implements Listener {
    private static final Set<BoostpadDetectorListener> INSTANCES = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public record BoostpadConfig(
            @NonNull String blockKey,
            boolean enabled,
            double forceX,
            double forceY,
            double forceZ,
            long delayMs,
            int hitboxPercent,
            double offsetMultiplier
    ) {
        public BoostpadConfig(
                @NonNull String blockKey,
                boolean enabled,
                double forceX,
                double forceY,
                double forceZ,
                long delayMs,
                int hitboxPercent
        ) {
            this(blockKey, enabled, forceX, forceY, forceZ, delayMs, hitboxPercent, (hitboxPercent / 100.0) - 1.0);
        }
    }

    private final Wake plugin;
    private volatile boolean enabled;
    private volatile double cachedMaxOffsetMultiplier = 0.0;
    private final Map<Material, BoostpadConfig> materialConfigs = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> lastBoostTimes = new ConcurrentHashMap<>();

    public BoostpadDetectorListener(@NonNull Wake plugin) {
        this.plugin = plugin;
        reloadCache();
        INSTANCES.add(this);
    }

    public static void reloadAllCaches() {
        for (BoostpadDetectorListener listener : INSTANCES) {
            listener.reloadCache();
        }
    }

    public void reloadCache() {
        this.enabled = plugin.getStateManager().get(DrydockBoostpadCommand.STATE_KEY_ENABLED, true);
        Map<String, BoostpadConfig> configs = DrydockBoostpadCommand.getConfiguredBoostpads(plugin);
        materialConfigs.clear();
        int maxPct = 100;
        for (Map.Entry<String, BoostpadConfig> entry : configs.entrySet()) {
            BoostpadConfig cfg = entry.getValue();
            if (cfg.enabled()) {
                Material mat = Material.matchMaterial(entry.getKey());
                if (mat != null) {
                    materialConfigs.put(mat, cfg);
                }
                if (cfg.hitboxPercent() > maxPct) {
                    maxPct = cfg.hitboxPercent();
                }
            }
        }
        this.cachedMaxOffsetMultiplier = (maxPct / 100.0) - 1.0;
    }

    public void unregister() {
        INSTANCES.remove(this);
        lastBoostTimes.clear();
        materialConfigs.clear();
    }

    @EventHandler
    public void onVehicleDestroy(@NonNull VehicleDestroyEvent event) {
        lastBoostTimes.remove(event.getVehicle().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(@NonNull PlayerQuitEvent event) {
        lastBoostTimes.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(@NonNull VehicleMoveEvent event) {
        if (!enabled || materialConfigs.isEmpty()) {
            return;
        }

        if (!(event.getVehicle() instanceof Boat boat)) {
            return;
        }

        var passengers = boat.getPassengers();
        if (passengers.isEmpty() || !(passengers.getFirst() instanceof Player player)) {
            return;
        }

        // prevents impulse stacking
        if (!boat.isOnGround() && boat.getVelocity().getY() > 0.05) {
            return;
        }

        BoundingBox box = boat.getBoundingBox();
        double halfWidth = (box.getMaxX() - box.getMinX()) / 2.0;
        double halfLength = (box.getMaxZ() - box.getMinZ()) / 2.0;

        UUID boatId = boat.getUniqueId();
        double scale = OBUBoostpadIntegration.getVehicleScale(boatId);
        if (scale != 1.0 && scale > 0) {
            double centerX = (box.getMinX() + box.getMaxX()) / 2.0;
            double centerY = box.getMinY();
            double centerZ = (box.getMinZ() + box.getMaxZ()) / 2.0;
            halfWidth *= scale;
            halfLength *= scale;
            double height = (box.getMaxY() - box.getMinY()) * scale;
            box = new BoundingBox(centerX - halfWidth, centerY, centerZ - halfLength, centerX + halfWidth, centerY + height, centerZ + halfLength);
        }

        World world = boat.getWorld();
        BoostpadConfig matchedConfig = null;

        double maxOffsetX = Math.max(0.0, halfWidth * cachedMaxOffsetMultiplier);
        double maxOffsetZ = Math.max(0.0, halfLength * cachedMaxOffsetMultiplier);

        int minX = (int) Math.floor(box.getMinX() - maxOffsetX);
        int maxX = (int) Math.floor(box.getMaxX() + maxOffsetX);
        int minZ = (int) Math.floor(box.getMinZ() - maxOffsetZ);
        int maxZ = (int) Math.floor(box.getMaxZ() + maxOffsetZ);

        int targetX = -1, targetZ = -1;
        int y = (int) Math.floor(box.getMinY() - 0.85);
        double relativeY = box.getMinY() - (y + 1.0);
        if (relativeY >= -0.15 && relativeY <= 0.15) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Material mat = world.getType(x, y, z);
                    BoostpadConfig config = materialConfigs.get(mat);
                    if (config != null) {
                        double offsetX = halfWidth * config.offsetMultiplier();
                        double offsetZ = halfLength * config.offsetMultiplier();

                        double bMinX = box.getMinX() - offsetX;
                        double bMaxX = box.getMaxX() + offsetX;
                        double bMinZ = box.getMinZ() - offsetZ;
                        double bMaxZ = box.getMaxZ() + offsetZ;

                        if (bMaxX >= x && bMinX <= x + 1 && bMaxZ >= z && bMinZ <= z + 1) {
                            targetX = x;
                            targetZ = z;
                            matchedConfig = config;
                            break;
                        }
                    }
                }
                if (matchedConfig != null) break;
            }
        }

        if (matchedConfig == null) {
            return;
        }

        // prevent impulse swallowing on land
        if (matchedConfig.forceY() > 0 && (!boat.isOnGround() || boat.getVelocity().getY() < -0.1)) {
            return;
        }

        Block hitBlock = world.getBlockAt(targetX, y, targetZ);

        long now = System.currentTimeMillis();
        Map<String, Long> boatCooldowns = lastBoostTimes.computeIfAbsent(boatId, k -> new HashMap<>());

        // had issues with impulse stacking
        long debounceBuffer = Math.min(matchedConfig.delayMs(), 50L);
        Long globalLast = boatCooldowns.get("__global__");
        if (globalLast != null && (now - globalLast) < debounceBuffer) {
            return;
        }

        Long lastBoost = boatCooldowns.get(matchedConfig.blockKey());
        if (lastBoost != null && (now - lastBoost) < matchedConfig.delayMs()) {
            return;
        }

        boatCooldowns.put(matchedConfig.blockKey(), now);
        boatCooldowns.put("__global__", now);

        PlayerHitBoostpadEvent boostpadEvent = new PlayerHitBoostpadEvent(
                player, boat, hitBlock, matchedConfig.forceX(), matchedConfig.forceY(), matchedConfig.forceZ()
        );
        Bukkit.getPluginManager().callEvent(boostpadEvent);
    }
}
