package dev.muggel.wake.features.obu.delivery;

import dev.muggel.wake.features.obu.clients.ClientRegistry;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OBUSyncManager {
    private final PacketSender packetSender;
    private final OBUContextManager contextManager;
    private final ActiveContexts active;
    private final ClientRegistry clients;
    private final Map<UUID, Map<String, OBUSetting>> localOverrides = new ConcurrentHashMap<>();
    private final Set<UUID> knownBoatContexts = ConcurrentHashMap.newKeySet();
    public OBUSyncManager(PacketSender packetSender, OBUContextManager contextManager, ActiveContexts active, ClientRegistry clients) {
        this.packetSender = packetSender;
        this.contextManager = contextManager;
        this.active = active;
        this.clients = clients;
    }

    static @Nullable Player driverOf(@NonNull Entity target) {
        if (target instanceof Player player) {
            return player;
        }
        return target instanceof Boat boat && !boat.getPassengers().isEmpty() && boat.getPassengers().getFirst() instanceof Player driver ? driver : null;
    }

    public void cleanup(UUID uuid) {
        localOverrides.remove(uuid);
        knownBoatContexts.remove(uuid);
    }

    public void addLocalOverride(UUID uuid, OBUSetting setting) {
        localOverrides.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .put(setting.uniqueKey(), setting);
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
        String contextName = active.sandboxOf(uuid);
        if (contextName != null) {
            blankSlate = true;
        } else {
            contextName = active.contextOf(uuid);
        }
        Entity entity = Bukkit.getEntity(uuid);
        if (entity instanceof Boat boat) {
            contextName = active.pinnedOn(boat);
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
                            absoluteTruth.put(setting.uniqueKey(), setting);
                        }
                    }
                }
                for (OBUSetting setting : context.settings()) {
                    absoluteTruth.put(setting.uniqueKey(), setting);
                }
            }
        }
        if (entity instanceof Boat) {
            Player driver = driverOf(entity);
            if (driver != null) {
                for (OBUSetting setting : calculateAbsoluteTruth(driver.getUniqueId())) {
                    absoluteTruth.put(setting.uniqueKey(), setting);
                }
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
        active.updateScale(player.getUniqueId(), truth);
        String personalContextName = OBUDefinition.CONTEXT_PERSONAL;
        if (truth.isEmpty()) {
            packetSender.sendWipePlayer(player, personalContextName);
        } else {
            packetSender.sendStoreContext(player, personalContextName, truth);
            packetSender.sendSwitchContext(player, personalContextName);
        }
        if (player.getVehicle() instanceof Boat boat) {
            broadcastSync(boat);
        }
    }

    public void syncToViewer(@NonNull Boat boat, @NonNull Player viewer) {
        List<OBUSetting> settings = calculateAbsoluteTruth(boat.getUniqueId());
        active.updateScale(boat.getUniqueId(), settings);
        knownBoatContexts.add(boat.getUniqueId());
        if (!clients.isDriven(viewer.getUniqueId())) {
            return;
        }
        packetSender.sendPrecompiledPacket(viewer, packetSender.createEntityContextPacket(boat.getUniqueId(), settings));
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
        active.updateScale(boat.getUniqueId(), settings);
        knownBoatContexts.add(boat.getUniqueId());
        Set<Player> viewers = new HashSet<>(boat.getTrackedBy());
        Player driver = driverOf(boat);
        if (driver != null) {
            viewers.add(driver);
        }
        viewers.removeIf(viewer -> !clients.isDriven(viewer.getUniqueId()));
        if (viewers.isEmpty()) {
            return;
        }
        var packet = packetSender.createEntityContextPacket(boat.getUniqueId(), settings);
        for (Player viewer : viewers) {
            packetSender.sendPrecompiledPacket(viewer, packet);
        }
    }

    public void wipeAllBoatContexts() {
        for (UUID boatId : knownBoatContexts) {
            var emptyPacket = packetSender.createEntityContextPacket(boatId, Collections.emptyList());
            for (Player player : Bukkit.getOnlinePlayers()) {
                packetSender.sendPrecompiledPacket(player, emptyPacket);
            }
        }
    }
}