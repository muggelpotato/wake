package dev.muggel.wake.features.obu.delivery;

import dev.muggel.wake.features.obu.clients.ClientRegistry;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.protocol.SettingMerge;
import dev.muggel.wake.features.obu.protocol.SettingMerge.Removal;
import dev.muggel.wake.features.obu.protocol.SettingSelector;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        localOverrides.compute(uuid, (k, held) -> byKey(SettingMerge.fold(held == null ? List.of() : held.values(), List.of(setting))));
    }

    public @NonNull Removal removeLocalOverrides(@NonNull UUID uuid, @NonNull SettingSelector selector) {
        Removal[] result = {Removal.NOTHING};
        localOverrides.computeIfPresent(uuid, (k, held) -> {
            result[0] = SettingMerge.subtract(held.values(), selector);
            return result[0].kept().isEmpty() ? null : byKey(result[0].kept());
        });
        return result[0];
    }

    public void clearLocalOverrides(@NonNull UUID uuid) {
        localOverrides.remove(uuid);
    }

    public @NonNull Map<String, OBUSetting> getLocalOverrides(@NonNull UUID uuid) {
        Map<String, OBUSetting> overrides = localOverrides.get(uuid);
        return overrides == null ? Map.of() : overrides;
    }

    private static @NonNull Map<String, OBUSetting> byKey(@NonNull List<OBUSetting> settings) {
        Map<String, OBUSetting> byKey = new LinkedHashMap<>();
        for (OBUSetting setting : settings) {
            byKey.put(setting.uniqueKey(), setting);
        }
        return Collections.unmodifiableMap(byKey);
    }

    private @NonNull List<OBUSetting> playerTruth(@NonNull UUID uuid) {
        List<OBUSetting> truth = List.of();
        String sandbox = active.sandboxOf(uuid);
        if (sandbox != null) {
            truth = merge(truth, contextManager.getContext(sandbox));
        } else {
            OBUContext context = contextManager.getContext(active.contextOf(uuid));
            if (context != null && OBUContextManager.inheritsDefault(context)) {
                truth = merge(truth, contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT));
            }
            truth = merge(truth, context);
        }
        return SettingMerge.fold(truth, getLocalOverrides(uuid).values());
    }

    private @NonNull List<OBUSetting> boatTruth(@NonNull Boat boat, @Nullable Player driver) {
        List<OBUSetting> truth = List.of();
        String pinned = active.pinnedOn(boat);
        if (pinned != null) {
            OBUContext context = contextManager.getContext(pinned);
            if (context != null && OBUContextManager.inheritsDefault(context)) {
                truth = merge(truth, contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT));
            }
            truth = merge(truth, context);
        }
        if (driver != null) {
            truth = SettingMerge.fold(truth, playerTruth(driver.getUniqueId()));
        }
        return SettingMerge.fold(truth, getLocalOverrides(boat.getUniqueId()).values());
    }

    private static @NonNull List<OBUSetting> merge(@NonNull List<OBUSetting> truth, @Nullable OBUContext context) {
        return context == null ? truth : SettingMerge.fold(truth, context.settings());
    }

    private @NonNull List<OBUSetting> settingsOn(@NonNull Boat boat, @Nullable Player driver) {
        List<OBUSetting> settings = boatTruth(boat, driver);
        scales.update(boat.getUniqueId(), settings);
        return settings;
    }

    private boolean nothingToSend(@NonNull UUID boatId, @NonNull List<OBUSetting> settings, boolean toDriver) {
        return !toDriver && settings.isEmpty() && !knownBoatContexts.contains(boatId);
    }

    public void syncPlayer(@NonNull Player player) {
        List<OBUSetting> truth = playerTruth(player.getUniqueId());
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
        Player driver = driverOf(boat);
        List<OBUSetting> settings = settingsOn(boat, driver);
        if (nothingToSend(boat.getUniqueId(), settings, viewer.equals(driver))) {
            return;
        }
        knownBoatContexts.add(boat.getUniqueId());
        packetSender.sendEntityContext(List.of(viewer), boat.getUniqueId(), settings);
    }

    public void syncTrackedBoats(@NonNull Player viewer) {
        for (Boat boat : viewer.getWorld().getEntitiesByClass(Boat.class)) {
            if (boat.getTrackedBy().contains(viewer)) {
                syncToViewer(boat, viewer);
            }
        }
    }

    public void broadcastSync(@NonNull Boat boat) {
        broadcastSync(boat, driverOf(boat));
    }

    public void broadcastSync(@NonNull Boat boat, @Nullable Player driver) {
        if (!boat.isValid()) {
            return;
        }
        List<OBUSetting> settings = settingsOn(boat, driver);
        Set<Player> viewers = new HashSet<>(boat.getTrackedBy());
        if (driver != null) {
            viewers.add(driver);
        }
        viewers.removeIf(viewer -> !clients.isDriven(viewer.getUniqueId()));
        if (viewers.isEmpty() || nothingToSend(boat.getUniqueId(), settings, viewers.contains(driver))) {
            return;
        }
        knownBoatContexts.add(boat.getUniqueId());
        packetSender.sendEntityContext(viewers, boat.getUniqueId(), settings);
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
            packetSender.sendEntityContext(Bukkit.getOnlinePlayers(), boatId, List.of());
        }
    }
}