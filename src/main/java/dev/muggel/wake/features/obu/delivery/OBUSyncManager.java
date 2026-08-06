package dev.muggel.wake.features.obu.delivery;

import dev.muggel.wake.features.obu.clients.ClientRegistry;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import org.bukkit.Bukkit;
import org.bukkit.World;
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

public final class OBUSyncManager {
    private final PacketSender packetSender;
    private final OBUContextManager contextManager;
    private final ActiveContexts active;
    private final ClientRegistry clients;
    private final VehicleScaleCache scales = new VehicleScaleCache();
    private final Map<UUID, Map<String, OBUSetting>> localOverrides = new ConcurrentHashMap<>();
    private final Set<UUID> knownBoatContexts = ConcurrentHashMap.newKeySet();
    public OBUSyncManager(@NonNull PacketSender packetSender, @NonNull OBUContextManager contextManager, @NonNull ActiveContexts active, @NonNull ClientRegistry clients) {
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

    public double scaleOf(@NonNull UUID uuid) {
        return scales.scaleOf(uuid);
    }

    public void cleanup(@NonNull UUID uuid) {
        localOverrides.remove(uuid);
        knownBoatContexts.remove(uuid);
        scales.forget(uuid);
    }

    public void addLocalOverride(@NonNull UUID uuid, @NonNull OBUSetting setting) {
        localOverrides.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .put(setting.uniqueKey(), setting);
    }

    public void removeLocalOverride(@NonNull UUID uuid, @NonNull String uniqueKey) {
        Map<String, OBUSetting> map = localOverrides.get(uuid);
        if (map != null) {
            map.remove(uniqueKey);
        }
    }

    public void clearLocalOverrides(@NonNull UUID uuid) {
        localOverrides.remove(uuid);
    }

    public @NonNull Map<String, OBUSetting> getLocalOverrides(@NonNull UUID uuid) {
        Map<String, OBUSetting> overrides = localOverrides.get(uuid);
        return overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    private @NonNull Map<String, OBUSetting> playerTruth(@NonNull UUID uuid) {
        Map<String, OBUSetting> truth = new HashMap<>();
        String sandbox = active.sandboxOf(uuid);
        if (sandbox != null) {
            merge(truth, contextManager.getContext(sandbox));
        } else {
            OBUContext context = contextManager.getContext(active.contextOf(uuid));
            if (context != null && OBUContextManager.inheritsDefault(context)) {
                merge(truth, contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT));
            }
            merge(truth, context);
        }
        mergeOverrides(truth, uuid);
        return truth;
    }

    private @NonNull Map<String, OBUSetting> boatTruth(@NonNull Boat boat) {
        Map<String, OBUSetting> truth = new HashMap<>();
        String pinned = active.pinnedOn(boat);
        if (pinned != null) {
            OBUContext context = contextManager.getContext(pinned);
            if (context != null && OBUContextManager.inheritsDefault(context)) {
                merge(truth, contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT));
            }
            merge(truth, context);
        }
        Player driver = driverOf(boat);
        if (driver != null) {
            truth.putAll(playerTruth(driver.getUniqueId()));
        }
        mergeOverrides(truth, boat.getUniqueId());
        return truth;
    }

    private static void merge(@NonNull Map<String, OBUSetting> truth, @Nullable OBUContext context) {
        if (context == null) {
            return;
        }
        for (OBUSetting setting : context.settings()) {
            truth.put(setting.uniqueKey(), setting);
        }
    }

    private void mergeOverrides(@NonNull Map<String, OBUSetting> truth, @NonNull UUID uuid) {
        Map<String, OBUSetting> overrides = localOverrides.get(uuid);
        if (overrides != null) {
            truth.putAll(overrides);
        }
    }

    private @NonNull List<OBUSetting> settingsOn(@NonNull Boat boat) {
        List<OBUSetting> settings = new ArrayList<>(boatTruth(boat).values());
        scales.update(boat.getUniqueId(), settings);
        return settings;
    }

    private boolean nothingToSend(@NonNull UUID boatId, @NonNull List<OBUSetting> settings) {
        return settings.isEmpty() && !knownBoatContexts.contains(boatId);
    }

    public void syncPlayer(@NonNull Player player) {
        List<OBUSetting> truth = new ArrayList<>(playerTruth(player.getUniqueId()).values());
        if (truth.isEmpty()) {
            packetSender.sendWipePlayer(player);
        } else {
            packetSender.sendStoreContext(player, OBUDefinition.CONTEXT_PERSONAL, truth);
            packetSender.sendSwitchContext(player, OBUDefinition.CONTEXT_PERSONAL);
        }
        if (player.getVehicle() instanceof Boat boat) {
            broadcastSync(boat);
        }
    }

    public void syncToViewer(@NonNull Boat boat, @NonNull Player viewer) {
        if (!clients.isDriven(viewer.getUniqueId())) {
            return;
        }
        List<OBUSetting> settings = settingsOn(boat);
        if (nothingToSend(boat.getUniqueId(), settings)) {
            return;
        }
        knownBoatContexts.add(boat.getUniqueId());
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
        List<OBUSetting> settings = settingsOn(boat);
        Set<Player> viewers = new HashSet<>(boat.getTrackedBy());
        Player driver = driverOf(boat);
        if (driver != null) {
            viewers.add(driver);
        }
        viewers.removeIf(viewer -> !clients.isDriven(viewer.getUniqueId()));
        if (viewers.isEmpty() || nothingToSend(boat.getUniqueId(), settings)) {
            return;
        }
        knownBoatContexts.add(boat.getUniqueId());
        var packet = packetSender.createEntityContextPacket(boat.getUniqueId(), settings);
        for (Player viewer : viewers) {
            packetSender.sendPrecompiledPacket(viewer, packet);
        }
    }

    public void resyncPinnedBoats() {
        for (World world : Bukkit.getWorlds()) {
            for (Boat boat : world.getEntitiesByClass(Boat.class)) {
                if (active.pinnedOn(boat) != null) {
                    broadcastSync(boat);
                }
            }
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