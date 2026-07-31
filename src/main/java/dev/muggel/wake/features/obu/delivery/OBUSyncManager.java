package dev.muggel.wake.features.obu.delivery;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.protocol.PacketSender;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class OBUSyncManager {
    private final Wake plugin;
    private final PacketSender packetSender;
    private final OBUContextManager contextManager;
    private final ContextDelivery delivery;
    private final Map<UUID, Map<String, OBUSetting>> localOverrides = new ConcurrentHashMap<>();
    private final Set<UUID> knownBoatContexts = ConcurrentHashMap.newKeySet();
    public OBUSyncManager(Wake plugin, PacketSender packetSender, OBUContextManager contextManager, ContextDelivery delivery) {
        this.plugin = plugin;
        this.packetSender = packetSender;
        this.contextManager = contextManager;
        this.delivery = delivery;
    }

    public void cleanup(UUID uuid) {
        localOverrides.remove(uuid);
        knownBoatContexts.remove(uuid);
    }

    public void addLocalOverride(UUID uuid, OBUSetting setting) {
        localOverrides.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .put(setting.getUniqueKey(), setting);
    }

    public void removeLocalOverride(UUID uuid, String uniqueKey) {
        Map<String, OBUSetting> map = localOverrides.get(uuid);
        if (map != null) {
            map.remove(uniqueKey);
        }
    }

    public void clearLocalOverrides(UUID uuid) {
        localOverrides.remove(uuid);
    }
    
    public Map<String, OBUSetting> getLocalOverrides(UUID uuid) {
        Map<String, OBUSetting> overrides = localOverrides.get(uuid);
        if (overrides == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(overrides);
    }

    private @NonNull List<OBUSetting> calculateAbsoluteTruth(@NonNull UUID uuid) {
        boolean blankSlate = false;
        String contextName = delivery.getPlayerActiveSandbox(uuid);
        if (contextName != null) {
            blankSlate = true;
        } else {
            contextName = delivery.getActiveContextName(uuid);
        }
        Entity entity = Bukkit.getEntity(uuid);
        if (entity instanceof Boat boat) {
            contextName = delivery.getBoatContextName(boat);
            blankSlate = false;
        }
        Map<String, OBUSetting> absoluteTruth = new HashMap<>();
        if (contextName != null) {
            OBUContext context = contextManager.getContext(contextName);
            if (context != null) {
                if (!blankSlate && OBUContextManager.inheritsDefault(context)) {
                    OBUContext defaults = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
                    if (defaults != null) {
                        for (OBUSetting setting : defaults.settings()) {
                            absoluteTruth.put(setting.getUniqueKey(), setting);
                        }
                    }
                }
                for (OBUSetting setting : context.settings()) {
                    absoluteTruth.put(setting.getUniqueKey(), setting);
                }
            }
        }
        if (entity instanceof Boat boat && !boat.getPassengers().isEmpty() && boat.getPassengers().getFirst() instanceof Player driver) {
            List<OBUSetting> driverTruth = calculateAbsoluteTruth(driver.getUniqueId());
            for (OBUSetting setting : driverTruth) {
                absoluteTruth.put(setting.getUniqueKey(), setting);
            }
        }
        Map<String, OBUSetting> overrides = localOverrides.get(uuid);
        if (overrides != null) {
            absoluteTruth.putAll(overrides);
        }
        return new ArrayList<>(absoluteTruth.values());
    }

    public void syncPlayer(@NonNull Player player) {
        List<OBUSetting> truth = calculateAbsoluteTruth(player.getUniqueId());
        delivery.updateVehicleScaleCache(player.getUniqueId(), truth);
        String personalContextName = OBUDefinition.CONTEXT_PERSONAL;
        try {
            if (truth.isEmpty()) {
                packetSender.sendWipePlayer(player, personalContextName);
            } else {
                packetSender.sendStoreContext(player, personalContextName, truth);
                packetSender.sendSwitchContext(player, personalContextName);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to sync personal context to " + player.getName(), e);
        }
        if (player.getVehicle() instanceof Boat boat) {
            broadcastSync(boat);
        }
    }

    public void syncToViewer(@NonNull Boat boat, @NonNull Player viewer) {
        List<OBUSetting> settings = calculateAbsoluteTruth(boat.getUniqueId());
        delivery.updateVehicleScaleCache(boat.getUniqueId(), settings);
        knownBoatContexts.add(boat.getUniqueId());
        if (!delivery.clients().isDriven(viewer.getUniqueId())) {
            return;
        }
        try {
            var packet = packetSender.createEntityContextPacket(boat.getUniqueId(), settings);
            packetSender.sendPrecompiledPacket(viewer, packet);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to sync boat state to viewer", e);
        }
    }

    public void syncTrackedBoats(@NonNull Player viewer) {
        for (Boat boat : viewer.getWorld().getEntitiesByClass(Boat.class)) {
            if (boat.isTrackedBy(viewer)) {
                syncToViewer(boat, viewer);
            }
        }
    }

    public void broadcastSync(@NonNull Boat boat) {
        List<OBUSetting> settings = calculateAbsoluteTruth(boat.getUniqueId());
        delivery.updateVehicleScaleCache(boat.getUniqueId(), settings);
        knownBoatContexts.add(boat.getUniqueId());
        Set<Player> viewers = new HashSet<>(boat.getTrackedBy());
        if (!boat.getPassengers().isEmpty() && boat.getPassengers().getFirst() instanceof Player p) {
            viewers.add(p);
        }
        viewers.removeIf(viewer -> !delivery.clients().isDriven(viewer.getUniqueId()));
        if (viewers.isEmpty()) {
            return;
        }
        try {
            var packet = packetSender.createEntityContextPacket(boat.getUniqueId(), settings);
            for (Player viewer : viewers) {
                packetSender.sendPrecompiledPacket(viewer, packet);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to broadcast boat state", e);
        }
    }

    public void wipeAllBoatContexts() {
        for (UUID boatId : knownBoatContexts) {
            try {
                var emptyPacket = packetSender.createEntityContextPacket(boatId, Collections.emptyList());
                for (Player player : Bukkit.getOnlinePlayers()) {
                    packetSender.sendPrecompiledPacket(player, emptyPacket);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to wipe context for boat " + boatId, e);
            }
        }
    }
}