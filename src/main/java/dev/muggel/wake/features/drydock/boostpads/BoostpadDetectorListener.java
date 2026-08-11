package dev.muggel.wake.features.drydock.boostpads;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.CollisionGeometry;
import dev.muggel.wake.core.CollisionGeometry.BlockSweep;
import dev.muggel.wake.core.VehiclePath.Legs;
import dev.muggel.wake.features.drydock.api.PlayerHitBoostpadEvent;
import dev.muggel.wake.features.drydock.integration.OBUBoostpadIntegration;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BoostpadDetectorListener extends PacketListenerAbstract implements Listener {
    public static final String STATE_KEY_ENABLED = "drydock.boostpads_enabled";
    public static final boolean DEFAULT_ENABLED = false;
    public static final String STATE_KEY_EARLY_OUT_X = "drydock.boostpads_early_out_x";
    public static final String STATE_KEY_EARLY_OUT_Y = "drydock.boostpads_early_out_y";
    public static final String STATE_KEY_EARLY_OUT_Z = "drydock.boostpads_early_out_z";
    public static final boolean DEFAULT_EARLY_OUT_X = false;
    public static final boolean DEFAULT_EARLY_OUT_Y = true;
    public static final boolean DEFAULT_EARLY_OUT_Z = false;
    public static final String STATE_KEY_GLOBAL_COOLDOWN_MS = "drydock.boostpads_global_cooldown_ms";
    public static final long DEFAULT_GLOBAL_COOLDOWN_MS = 0L;
    private static final double SURFACE_BAND = 0.15;
    private static final double SURFACE_DROP = 0.5;
    private static final double HULL_HALF = 0.6875;
    private static final double GRAZE_MARGIN = 0.01; // boats clip slightly into walls, an issue when colliding with a wall made of boostpads
    private final Wake plugin;
    private final BoostpadRegistry boostpads;
    private final Map<UUID, Map<String, Long>> lastBoostTimes = new HashMap<>();
    private final Set<UUID> jumpPresses = ConcurrentHashMap.newKeySet();
    private final Set<UUID> jumpHeld = ConcurrentHashMap.newKeySet();
    private boolean isRegistered = false;
    public BoostpadDetectorListener(@NonNull Wake plugin, BoostpadRegistry boostpads) {
        this.plugin = plugin;
        this.boostpads = boostpads;
    }

    public static long globalCooldownMs(@NonNull Wake plugin) {
        return Math.clamp(plugin.getStateDao().get(STATE_KEY_GLOBAL_COOLDOWN_MS, DEFAULT_GLOBAL_COOLDOWN_MS), 0L, BoostpadConfig.MAX_DELAY_MS);
    }

    public void updateRegistration() {
        boolean enabled = plugin.getStateDao().get(STATE_KEY_ENABLED, DEFAULT_ENABLED);
        boolean shouldBeRegistered = enabled && !boostpads.getBoostpadConfigs().isEmpty();
        if (shouldBeRegistered && !isRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            PacketEvents.getAPI().getEventManager().registerListener(this);
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
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        plugin.getVehiclePath().release();
        lastBoostTimes.clear();
        jumpPresses.clear();
        jumpHeld.clear();
        isRegistered = false;
    }

    @Override
    public void onPacketReceive(@NonNull PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        boolean jump;
        if (type == PacketType.Play.Client.PLAYER_INPUT) {
            jump = new WrapperPlayClientPlayerInput(event).isJump();
        } else if (type == PacketType.Play.Client.STEER_VEHICLE) {
            jump = new WrapperPlayClientSteerVehicle(event).isJump();
        } else {
            return;
        }
        UUID uuid = event.getUser().getUUID();
        if (jump) {
            jumpPresses.add(uuid);
            jumpHeld.add(uuid);
        } else {
            jumpHeld.remove(uuid);
        }
    }

    @EventHandler
    public void onEntityRemove(@NonNull EntityRemoveEvent event) {
        if (event.getEntity() instanceof Boat) {
            lastBoostTimes.remove(event.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(@NonNull PlayerQuitEvent event) {
        jumpHeld.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTickEnd(@NonNull ServerTickEndEvent event) {
        jumpPresses.clear();
    }

    @EventHandler
    public void onVehicleMove(@NonNull VehicleMoveEvent event) {
        Map<Material, BoostpadConfig> materialConfigs = boostpads.getBoostpadConfigs();
        if (materialConfigs.isEmpty() || !(event.getVehicle() instanceof Boat boat) || boat.isEmpty()) {
            return;
        }
        if (!(boat.getPassengers().getFirst() instanceof Player player)) {
            return;
        }
        List<PadHit> hits = findHits(boat, plugin.getVehiclePath().legs(event), materialConfigs);
        if (!hits.isEmpty()) {
            UUID uuid = player.getUniqueId();
            fireBoosts(boat, player, hits, jumpPresses.contains(uuid) || jumpHeld.contains(uuid));
        }
    }

    private @NonNull List<PadHit> findHits(@NonNull Boat boat, @NonNull Legs legs, @NonNull Map<Material, BoostpadConfig> materialConfigs) {
        double hull = HULL_HALF * OBUBoostpadIntegration.getVehicleScale(plugin, boat) - GRAZE_MARGIN;
        double reach = extent(boostpads.getMaxPadding(), hull);
        World world = boat.getWorld();
        int legCount = legs.count();
        Set<Long> seen = legCount > 1 ? new HashSet<>() : null;
        List<PadHit> hits = null;
        for (int leg = 0; leg < legCount; leg++) {
            Vector legEnd = legs.at(leg + 1);
            BlockSweep sweep = CollisionGeometry.sweep(legs.at(leg), legEnd, reach, -SURFACE_DROP);
            Vector scanned = sweep.from();
            for (int x = sweep.minX(); x <= sweep.maxX(); x++) {
                for (int z = sweep.minZ(); z <= sweep.maxZ(); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    for (int y = sweep.minY(); y <= sweep.maxY(); y++) {
                        BoostpadConfig config = materialConfigs.get(world.getType(x, y, z));
                        if (config == null) {
                            continue;
                        }
                        double padding = extent(config.padding(), hull);
                        double fraction = CollisionGeometry.intersectionFraction(scanned, legEnd,
                                x - padding, y + 1 - SURFACE_BAND, z - padding,
                                x + 1 + padding, y + 1 + SURFACE_BAND, z + 1 + padding);
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
        long globalCooldownNanos = globalCooldownMs(plugin) * 1_000_000L;
        Long lastAnyNanos = globalCooldownNanos > 0 && !boatCooldowns.isEmpty() ? Collections.max(boatCooldowns.values()) : null;
        World world = boat.getWorld();
        boolean grounded = boat.isOnGround();
        boolean cancelX = jumping && plugin.getStateDao().get(STATE_KEY_EARLY_OUT_X, DEFAULT_EARLY_OUT_X);
        boolean cancelY = jumping && plugin.getStateDao().get(STATE_KEY_EARLY_OUT_Y, DEFAULT_EARLY_OUT_Y);
        boolean cancelZ = jumping && plugin.getStateDao().get(STATE_KEY_EARLY_OUT_Z, DEFAULT_EARLY_OUT_Z);
        boolean firedBoostPad = false;
        for (PadHit hit : hits) {
            BoostpadConfig config = hit.config();
            boolean boostPad = config.forceY() > 0;
            if (boostPad && (firedBoostPad || !grounded)) {
                continue;
            }
            double forceX = cancelX ? 0.0 : config.forceX();
            double forceY = cancelY ? 0.0 : config.forceY();
            double forceZ = cancelZ ? 0.0 : config.forceZ();
            if (forceX == 0.0 && forceY == 0.0 && forceZ == 0.0) {
                continue;
            }
            long crossedNanos = plugin.getTickClock().at(hit.progress());
            Long lastBoostNanos = boatCooldowns.get(config.blockKey());
            if (lastBoostNanos != null && crossedNanos - lastBoostNanos < config.delayMs() * 1_000_000L) {
                continue;
            }
            if (lastAnyNanos != null && crossedNanos - lastAnyNanos < globalCooldownNanos) {
                continue;
            }
            boatCooldowns.put(config.blockKey(), crossedNanos);
            lastAnyNanos = crossedNanos;
            if (boostPad) {
                firedBoostPad = true;
            }
            Block hitBlock = world.getBlockAt(hit.x(), hit.y(), hit.z());
            PlayerHitBoostpadEvent hitEvent = new PlayerHitBoostpadEvent(player, boat, hitBlock, forceX, forceY, forceZ);
            Bukkit.getPluginManager().callEvent(hitEvent);
        }
    }

    private static double extent(double padding, double hull) {
        return Math.max(0.0, padding - 1.0 + hull);
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFFL);
    }

    private record PadHit(double progress, BoostpadConfig config, int x, int y, int z) {}
}