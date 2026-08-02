package dev.muggel.wake.features.drydock.boostpads;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.CollisionGeometry;
import dev.muggel.wake.core.CollisionGeometry.BlockSweep;
import dev.muggel.wake.core.VehiclePath.Legs;
import dev.muggel.wake.features.drydock.api.PlayerHitBoostpadEvent;
import dev.muggel.wake.features.drydock.commands.boostpad.BoostpadCommand;
import dev.muggel.wake.features.drydock.integration.OBUBoostpadIntegration;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BoostpadDetectorListener implements Listener {
    private static final double SURFACE_BAND = 0.15;
    private static final double SURFACE_DROP = 0.5;
    private final Wake plugin;
    private final BoostpadRegistry boostpads;
    private final Map<UUID, Map<String, Long>> lastBoostTimes = new HashMap<>();
    private final Set<UUID> jumpPresses = new HashSet<>();
    private volatile boolean isRegistered = false;
    public BoostpadDetectorListener(@NonNull Wake plugin, BoostpadRegistry boostpads) {
        this.plugin = plugin;
        this.boostpads = boostpads;
        updateRegistration();
    }

    public void updateRegistration() {
        boolean enabled = plugin.getStateDao().get(BoostpadCommand.STATE_KEY_ENABLED, BoostpadCommand.DEFAULT_ENABLED);
        boolean shouldBeRegistered = enabled && !boostpads.getBoostpadConfigs().isEmpty();
        if (shouldBeRegistered && !isRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            plugin.getVehiclePath().claim();
            isRegistered = true;
        } else if (!shouldBeRegistered) {
            unregister();
        }
    }

    public void unregister() {
        if (!isRegistered) {
            return;
        }
        HandlerList.unregisterAll(this);
        plugin.getVehiclePath().release();
        lastBoostTimes.clear();
        jumpPresses.clear();
        isRegistered = false;
    }

    @EventHandler
    public void onEntityRemove(@NonNull EntityRemoveEvent event) {
        if (event.getEntity() instanceof Boat) {
            lastBoostTimes.remove(event.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerInput(@NonNull PlayerInputEvent event) {
        if (event.getInput().isJump() && event.getPlayer().getVehicle() instanceof Boat) {
            jumpPresses.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onTickEnd(@NonNull ServerTickEndEvent event) {
        jumpPresses.clear();
    }

    @EventHandler
    public void onVehicleMove(@NonNull VehicleMoveEvent event) {
        Map<Material, BoostpadConfig> materialConfigs = boostpads.getBoostpadConfigs();
        if (materialConfigs.isEmpty() || !(event.getVehicle() instanceof Boat boat)) {
            return;
        }
        var passengers = boat.getPassengers();
        if (passengers.isEmpty() || !(passengers.getFirst() instanceof Player player)) {
            return;
        }
        List<PadHit> hits = findHits(boat, plugin.getVehiclePath().legs(event), materialConfigs);
        if (!hits.isEmpty()) {
            fireBoosts(boat, player, hits, jumpPresses.contains(player.getUniqueId()) || player.getCurrentInput().isJump());
        }
    }

    private @NonNull List<PadHit> findHits(@NonNull Boat boat, @NonNull Legs legs, @NonNull Map<Material, BoostpadConfig> materialConfigs) {
        BoundingBox box = boat.getBoundingBox();
        double scale = OBUBoostpadIntegration.getVehicleScale(plugin, boat.getUniqueId());
        double sizing = scale > 0 ? scale : 1.0;
        double halfWidth = (box.getMaxX() - box.getMinX()) / 2.0 * sizing;
        double halfLength = (box.getMaxZ() - box.getMinZ()) / 2.0 * sizing;
        double reach = 1.0 + Math.max(0.0, boostpads.getMaxOffsetMultiplier());
        World world = boat.getWorld();
        int legCount = legs.count();
        Set<Long> seen = legCount > 1 ? new HashSet<>() : null;
        List<PadHit> hits = null;
        for (int leg = 0; leg < legCount; leg++) {
            Vector legEnd = legs.at(leg + 1);
            BlockSweep sweep = CollisionGeometry.sweep(legs.at(leg), legEnd, halfWidth * reach, halfLength * reach, -SURFACE_DROP);
            Vector scanned = sweep.from();
            for (int y = sweep.minY(); y <= sweep.maxY(); y++) {
                for (int x = sweep.minX(); x <= sweep.maxX(); x++) {
                    for (int z = sweep.minZ(); z <= sweep.maxZ(); z++) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                            continue;
                        }
                        BoostpadConfig config = materialConfigs.get(world.getType(x, y, z));
                        if (config == null) {
                            continue;
                        }
                        double halfX = Math.max(0.0, halfWidth * (1.0 + config.offsetMultiplier()));
                        double halfZ = Math.max(0.0, halfLength * (1.0 + config.offsetMultiplier()));
                        double fraction = CollisionGeometry.intersectionFraction(
                                scanned.getX(), scanned.getY(), scanned.getZ(),
                                legEnd.getX(), legEnd.getY(), legEnd.getZ(),
                                x - halfX, y + 1 - SURFACE_BAND, z - halfZ,
                                x + 1 + halfX, y + 1 + SURFACE_BAND, z + 1 + halfZ);
                        if (fraction < 0 || (seen != null && !seen.add(blockKey(x, y, z)))) {
                            continue;
                        }
                        if (hits == null) {
                            hits = new ArrayList<>();
                        }
                        hits.add(new PadHit(legs.progress(leg, fraction), config, x, y, z));
                    }
                }
            }
        }
        return hits == null ? List.of() : hits;
    }

    private void fireBoosts(@NonNull Boat boat, @NonNull Player player, @NonNull List<PadHit> hits, boolean jumping) {
        hits.sort(Comparator.comparingDouble(PadHit::progress));
        Map<String, Long> boatCooldowns = lastBoostTimes.computeIfAbsent(boat.getUniqueId(), key -> new HashMap<>(4));
        World world = boat.getWorld();
        boolean firedJumpPad = false;
        for (PadHit hit : hits) {
            BoostpadConfig config = hit.config();
            if (config.forceY() > 0 && (firedJumpPad || jumping || !boat.isOnGround())) {
                continue;
            }
            long crossedNanos = plugin.getTickClock().at(hit.progress());
            Long lastBoostNanos = boatCooldowns.get(config.blockKey());
            if (lastBoostNanos != null && (crossedNanos - lastBoostNanos) / 1_000_000L < config.delayMs()) {
                continue;
            }
            boatCooldowns.put(config.blockKey(), crossedNanos);
            if (config.forceY() > 0) {
                firedJumpPad = true;
            }
            Block hitBlock = world.getBlockAt(hit.x(), hit.y(), hit.z());
            PlayerHitBoostpadEvent hitEvent = new PlayerHitBoostpadEvent(player, boat, hitBlock, config.forceX(), config.forceY(), config.forceZ());
            Bukkit.getPluginManager().callEvent(hitEvent);
        }
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFFL);
    }

    private record PadHit(double progress, BoostpadConfig config, int x, int y, int z) {}
}